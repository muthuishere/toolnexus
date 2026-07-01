"""Built-in tool source (``source="builtin"``). The default toolset toolnexus
ships so an agent can act with zero custom wiring — opencode's built-ins, ported
with identical tool names + input schemas. See ../../SPEC.md §4A.

Every tool obeys the uniform ``Tool``/``ToolResult`` contract: a failure is a
``ToolResult(is_error=True)``, never a raised exception across the boundary. Paths
resolve relative to the process working directory unless absolute. Mirrors the JS
reference (``js/src/builtin.ts``); same behavior, idiomatic Python shape.
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import subprocess
import urllib.error
import urllib.request
from typing import Any, Awaitable, Callable, Optional, Union

from .types import JSONSchema, Tool, ToolContext, ToolResult

# A single global builtin toggle (mirrors MCP is_enabled precedence). The dict
# form also allows a ``tools`` name→bool map for per-tool enable/disable.
BuiltinsConfig = Union[bool, dict]


def builtins_enabled(cfg: Optional[BuiltinsConfig]) -> bool:
    """Whether the builtin source is on. Default ON. Same precedence as MCP:
    ``disabled=True`` wins, else ``enabled=False`` disables, otherwise enabled.
    """
    if cfg is None:
        return True
    if isinstance(cfg, bool):
        return cfg
    if cfg.get("disabled") is True:
        return False
    if cfg.get("enabled") is False:
        return False
    return True


def select_builtins(cfg: Optional[BuiltinsConfig]) -> list[Tool]:
    """Resolve the active builtin tools for a config. Whole-source-off wins and
    returns ``[]``. Otherwise all ten are on; a ``tools`` name→bool map drops
    any tool mapped to ``False`` (all-on baseline; ``True``/absent stay on;
    unknown names are ignored). SPEC §4A.
    """
    if not builtins_enabled(cfg):
        return []
    tools_map = cfg.get("tools") if isinstance(cfg, dict) else None
    all_tools = create_builtin_tools()
    if not tools_map:
        return all_tools
    return [t for t in all_tools if tools_map.get(t.name) is not False]


def _err(output: str, metadata: Optional[dict[str, Any]] = None) -> ToolResult:
    return ToolResult(output=output, is_error=True, metadata=metadata)


def _ok(output: str, metadata: Optional[dict[str, Any]] = None) -> ToolResult:
    return ToolResult(output=output, is_error=False, metadata=metadata)


IGNORE_DIRS = {"node_modules", ".git"}

# run(args, ctx) -> ToolResult
_RunFn = Callable[[dict[str, Any], Optional[ToolContext]], Awaitable[ToolResult]]


def _builtin(
    name: str, description: str, input_schema: JSONSchema, run: _RunFn
) -> Tool:
    async def execute(
        args: Optional[dict[str, Any]] = None, ctx: Optional[ToolContext] = None
    ) -> ToolResult:
        try:
            return await run(args or {}, ctx)
        except Exception as e:  # noqa: BLE001 — surfaced as a tool error
            return _err(f"{name}: {e}")

    return Tool(
        name=name,
        description=description,
        input_schema=input_schema,
        source="builtin",
        execute=execute,
    )


def _num(value: Any) -> Optional[float]:
    """Return value if it is a real number (not a bool), else None."""
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)):
        return float(value)
    return None


# --------------------------------------------------------------------------- #
# glob helpers (shared by grep + glob)
# --------------------------------------------------------------------------- #
def _glob_to_regexp(glob: str) -> "re.Pattern[str]":
    """Convert a glob (``*``, ``**``, ``?``) to an anchored regex."""
    out = ""
    i = 0
    n = len(glob)
    while i < n:
        c = glob[i]
        if c == "*":
            if i + 1 < n and glob[i + 1] == "*":
                out += ".*"
                i += 1
                if i + 1 < n and glob[i + 1] == "/":
                    i += 1
            else:
                out += "[^/]*"
        elif c == "?":
            out += "[^/]"
        elif c in "\\^$.|+()[]{}":
            out += "\\" + c
        else:
            out += c
        i += 1
    return re.compile("^" + out + "$")


def _match_glob(rel: str, glob: str) -> bool:
    """Match a relative path against a glob; slash-less globs test the basename."""
    regex = _glob_to_regexp(glob)
    if "/" not in glob:
        return regex.match(os.path.basename(rel)) is not None
    return regex.match(rel) is not None


def _walk_files(root: str) -> list[str]:
    """Recursively list files under ``root`` (skips node_modules/.git)."""
    out: list[str] = []
    stack = [root]
    while stack:
        d = stack.pop()
        try:
            entries = list(os.scandir(d))
        except OSError:
            continue
        for entry in entries:
            if entry.is_dir(follow_symlinks=False):
                if entry.name in IGNORE_DIRS:
                    continue
                stack.append(entry.path)
            elif entry.is_file(follow_symlinks=False):
                out.append(entry.path)
    return out


# --------------------------------------------------------------------------- #
# individual tools
# --------------------------------------------------------------------------- #
def _bash_tool() -> Tool:
    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        command = str(args.get("command") or "")
        if not command:
            return _err("bash: command is required")
        workdir = str(args["workdir"]) if args.get("workdir") else None
        timeout_ms = _num(args.get("timeout"))
        if timeout_ms is None:
            timeout_ms = 60_000

        def do() -> ToolResult:
            try:
                proc = subprocess.run(
                    command,
                    shell=True,
                    cwd=workdir,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    timeout=timeout_ms / 1000,
                )
            except subprocess.TimeoutExpired as e:
                out = e.output.decode("utf-8", "replace") if e.output else ""
                return _err(f"bash: command timed out after {int(timeout_ms)}ms\n{out}")
            out = proc.stdout.decode("utf-8", "replace")
            code = proc.returncode
            if code != 0:
                return _err(
                    f"{out}\nbash: command exited with code {code}",
                    {"exitCode": code},
                )
            return _ok(out, {"exitCode": code})

        return await asyncio.to_thread(do)

    return _builtin(
        "bash",
        "Run a shell command and return its combined stdout+stderr. Non-zero exit is an error.",
        {
            "type": "object",
            "properties": {
                "command": {"type": "string", "description": "The shell command to run"},
                "workdir": {"type": "string", "description": "Working directory (default: process cwd)"},
                "timeout": {"type": "number", "description": "Timeout in milliseconds (default 60000)"},
                "description": {"type": "string", "description": "Human-readable description of the command"},
            },
            "required": ["command"],
            "additionalProperties": False,
        },
        run,
    )


def _read_tool() -> Tool:
    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        p = str(args.get("path") or "")
        if not p:
            return _err("read: path is required")
        try:
            with open(p, "r", encoding="utf-8") as f:
                content = f.read()
        except OSError as e:
            return _err(f"read: {e}")
        if args.get("offset") is None and args.get("limit") is None:
            return _ok(content)
        lines = content.split("\n")
        off = _num(args.get("offset"))
        offset = max(1, int(off)) if off is not None else 1
        start = offset - 1
        lim = _num(args.get("limit"))
        limit = max(0, int(lim)) if lim is not None else len(lines) - start
        return _ok("\n".join(lines[start : start + limit]))

    return _builtin(
        "read",
        "Read a UTF-8 text file. With offset/limit, return only that line window.",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to the file to read"},
                "offset": {"type": "number", "description": "1-based line to start from"},
                "limit": {"type": "number", "description": "Maximum number of lines to read"},
            },
            "required": ["path"],
            "additionalProperties": False,
        },
        run,
    )


def _coerce_str(value: Any) -> str:
    if isinstance(value, str):
        return value
    return "" if value is None else str(value)


def _write_tool() -> Tool:
    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        p = str(args.get("path") or "")
        if not p:
            return _err("write: path is required")
        content = _coerce_str(args.get("content"))
        os.makedirs(os.path.dirname(os.path.abspath(p)), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            f.write(content)
        byte_len = len(content.encode("utf-8"))
        return _ok(f"Wrote {byte_len} bytes to {p}", {"bytes": byte_len})

    return _builtin(
        "write",
        "Write content to a file (create/overwrite), creating parent directories.",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to write to"},
                "content": {"type": "string", "description": "Content to write"},
            },
            "required": ["path", "content"],
            "additionalProperties": False,
        },
        run,
    )


def _edit_tool() -> Tool:
    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        p = str(args.get("path") or "")
        if not p:
            return _err("edit: path is required")
        old = args.get("oldString")
        if not isinstance(old, str) or len(old) == 0:
            return _err("edit: oldString is required")
        new = _coerce_str(args.get("newString"))
        try:
            with open(p, "r", encoding="utf-8") as f:
                content = f.read()
        except OSError as e:
            return _err(f"edit: {e}")
        count = content.count(old)
        if count == 0:
            return _err(f"edit: oldString not found in {p}")
        if args.get("replaceAll") is True:
            nxt = content.replace(old, new)
            n = count
        else:
            if count > 1:
                return _err(
                    f"edit: oldString is not unique in {p} ({count} occurrences); use replaceAll"
                )
            nxt = content.replace(old, new, 1)
            n = 1
        with open(p, "w", encoding="utf-8") as f:
            f.write(nxt)
        plural = "" if n == 1 else "s"
        return _ok(f"Edited {p} ({n} replacement{plural})", {"replacements": n})

    return _builtin(
        "edit",
        "Exact-string replace in a file. Default replaces a single unique occurrence; replaceAll replaces all.",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to the file to edit"},
                "oldString": {"type": "string", "description": "Exact string to replace"},
                "newString": {"type": "string", "description": "Replacement string"},
                "replaceAll": {"type": "boolean", "description": "Replace all occurrences"},
            },
            "required": ["path", "oldString", "newString"],
            "additionalProperties": False,
        },
        run,
    )


def _grep_tool() -> Tool:
    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        pattern = str(args.get("pattern") or "")
        if not pattern:
            return _err("grep: pattern is required")
        try:
            regex = re.compile(pattern)
        except re.error as e:
            return _err(f"grep: invalid regex: {e}")
        root = str(args["path"]) if args.get("path") else os.getcwd()
        include = str(args["include"]) if args.get("include") else None
        lim = _num(args.get("limit"))
        limit = int(lim) if lim is not None else 100
        matches: list[str] = []
        for file in _walk_files(root):
            if len(matches) >= limit:
                break
            rel = os.path.relpath(file, root)
            if include and not _match_glob(rel, include):
                continue
            try:
                with open(file, "r", encoding="utf-8") as f:
                    text = f.read()
            except OSError:
                continue
            lines = text.split("\n")
            for i, line in enumerate(lines):
                if len(matches) >= limit:
                    break
                if regex.search(line):
                    matches.append(f"{file}:{i + 1}:{line}")
        return _ok("\n".join(matches), {"count": len(matches)})

    return _builtin(
        "grep",
        "Search file contents by regex under a directory. Output is file:line:text matches.",
        {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Regular expression to search for"},
                "path": {"type": "string", "description": "Directory to search (default: process cwd)"},
                "include": {"type": "string", "description": "Glob filter for file names"},
                "limit": {"type": "number", "description": "Maximum number of matches (default 100)"},
            },
            "required": ["pattern"],
            "additionalProperties": False,
        },
        run,
    )


def _glob_tool() -> Tool:
    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        pattern = str(args.get("pattern") or "")
        if not pattern:
            return _err("glob: pattern is required")
        root = str(args["path"]) if args.get("path") else os.getcwd()
        lim = _num(args.get("limit"))
        limit = int(lim) if lim is not None else 100
        found: list[str] = []
        for file in _walk_files(root):
            if len(found) >= limit:
                break
            rel = os.path.relpath(file, root)
            if _match_glob(rel, pattern):
                found.append(rel)
        found.sort()
        return _ok("\n".join(found[:limit]), {"count": min(len(found), limit)})

    return _builtin(
        "glob",
        "List files matching a glob under a directory. Output is newline-joined relative paths.",
        {
            "type": "object",
            "properties": {
                "pattern": {"type": "string", "description": "Glob pattern to match"},
                "path": {"type": "string", "description": "Directory to search (default: process cwd)"},
                "limit": {"type": "number", "description": "Maximum number of results (default 100)"},
            },
            "required": ["pattern"],
            "additionalProperties": False,
        },
        run,
    )


def _strip_html(html: str) -> str:
    """Very light HTML → text: drop scripts/styles + tags, collapse whitespace."""
    html = re.sub(r"<script[\s\S]*?</script>", "", html, flags=re.IGNORECASE)
    html = re.sub(r"<style[\s\S]*?</style>", "", html, flags=re.IGNORECASE)
    html = re.sub(r"<[^>]+>", "", html)
    html = re.sub(r"[ \t]+\n", "\n", html)
    html = re.sub(r"\n{3,}", "\n\n", html)
    return html.strip()


def _webfetch_tool() -> Tool:
    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        url = str(args.get("url") or "")
        if not url:
            return _err("webfetch: url is required")
        fmt = args.get("format")
        fmt = fmt if fmt in ("text", "html") else "markdown"
        t = _num(args.get("timeout"))
        timeout_s = t if t is not None else 30

        def do() -> ToolResult:
            req = urllib.request.Request(url, method="GET")
            try:
                with urllib.request.urlopen(req, timeout=timeout_s) as resp:
                    status = resp.status
                    body = resp.read().decode("utf-8", "replace")
            except urllib.error.HTTPError as e:
                return _err(f"HTTP {e.code}", {"status": e.code})
            except Exception as e:  # noqa: BLE001 — surfaced as a tool error
                return _err(f"webfetch: {e}")
            output = body if fmt == "html" else _strip_html(body)
            return _ok(output, {"status": status, "format": fmt})

        return await asyncio.to_thread(do)

    return _builtin(
        "webfetch",
        "HTTP GET a URL and return its body as text, markdown, or html.",
        {
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "URL to fetch"},
                "format": {
                    "type": "string",
                    "enum": ["text", "markdown", "html"],
                    "description": "Response format (default markdown)",
                },
                "timeout": {"type": "number", "description": "Timeout in seconds (default 30)"},
            },
            "required": ["url"],
            "additionalProperties": False,
        },
        run,
    )


def _question_tool() -> Tool:
    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        questions = args.get("questions")
        questions = questions if isinstance(questions, list) else []
        return _ok(json.dumps(questions), {"questions": questions})

    return _builtin(
        "question",
        "Ask the host one or more questions. Returns the questions as structured output for the host to answer.",
        {
            "type": "object",
            "properties": {
                "questions": {
                    "type": "array",
                    "description": "Questions to ask",
                    "items": {
                        "type": "object",
                        "properties": {
                            "question": {"type": "string"},
                            "header": {"type": "string"},
                            "options": {"type": "array", "items": {"type": "string"}},
                            "multiple": {"type": "boolean"},
                        },
                        "required": ["question"],
                    },
                }
            },
            "required": ["questions"],
            "additionalProperties": False,
        },
        run,
    )


def _todowrite_tool() -> Tool:
    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        todos = args.get("todos")
        todos = todos if isinstance(todos, list) else []
        rendered = "\n".join(
            f"[{'x' if t.get('completed') else ' '}] {_coerce_str(t.get('text'))}"
            for t in todos
        )
        return _ok(rendered or "(no todos)", {"todos": todos})

    return _builtin(
        "todowrite",
        "Replace the session todo list. Returns the rendered list.",
        {
            "type": "object",
            "properties": {
                "todos": {
                    "type": "array",
                    "description": "The full todo list to store",
                    "items": {
                        "type": "object",
                        "properties": {
                            "id": {"type": "string"},
                            "text": {"type": "string"},
                            "completed": {"type": "boolean"},
                        },
                        "required": ["id", "text", "completed"],
                    },
                }
            },
            "required": ["todos"],
            "additionalProperties": False,
        },
        run,
    )


# --------------------------------------------------------------------------- #
# apply_patch (opencode Begin/End Patch grammar)
# --------------------------------------------------------------------------- #
_FILE_MARKER = re.compile(r"^\*\*\* (Add|Update|Delete) File: (.+)$")


def _parse_patch(patch_text: str) -> list[dict[str, Any]]:
    lines = patch_text.split("\n")
    i = 0
    while i < len(lines) and lines[i].strip() == "":
        i += 1
    if i >= len(lines) or lines[i].strip() != "*** Begin Patch":
        raise ValueError("missing '*** Begin Patch'")
    i += 1
    ops: list[dict[str, Any]] = []
    while i < len(lines):
        line = lines[i]
        if line.strip() == "*** End Patch":
            return ops
        if line.strip() == "":
            i += 1
            continue
        m = _FILE_MARKER.match(line)
        if not m:
            raise ValueError(f"unexpected line: {line}")
        kind = m.group(1)
        p = m.group(2).strip()
        i += 1
        body: list[str] = []
        while (
            i < len(lines)
            and lines[i].strip() != "*** End Patch"
            and not _FILE_MARKER.match(lines[i])
        ):
            body.append(lines[i])
            i += 1
        if kind == "Add":
            content = "\n".join(l[1:] if l.startswith("+") else l for l in body)
            ops.append({"type": "add", "path": p, "content": content})
        elif kind == "Delete":
            ops.append({"type": "delete", "path": p})
        else:
            ops.append({"type": "update", "path": p, "body": body})
    raise ValueError("missing '*** End Patch'")


def _apply_update(content: str, body: list[str]) -> str:
    """Apply an Update hunk-body to file content; raise on a non-match."""
    hunks: list[list[str]] = []
    cur: list[str] = []
    for l in body:
        if l.startswith("@@"):
            if cur:
                hunks.append(cur)
            cur = []
        else:
            cur.append(l)
    if cur:
        hunks.append(cur)

    result = content
    for hunk in hunks:
        old_lines: list[str] = []
        new_lines: list[str] = []
        for l in hunk:
            if l.startswith("-"):
                old_lines.append(l[1:])
            elif l.startswith("+"):
                new_lines.append(l[1:])
            elif l.startswith(" "):
                old_lines.append(l[1:])
                new_lines.append(l[1:])
            else:
                old_lines.append(l)
                new_lines.append(l)
        old_block = "\n".join(old_lines)
        new_block = "\n".join(new_lines)
        if len(old_block) > 0:
            if old_block not in result:
                raise ValueError("hunk does not match file contents")
            result = result.replace(old_block, new_block, 1)
        else:
            # pure insertion with no context — append.
            sep = "" if (result.endswith("\n") or result == "") else "\n"
            result = result + sep + new_block
    return result


def _apply_patch_tool() -> Tool:
    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        patch_text = str(args.get("patchText") or "")
        if not patch_text:
            return _err("apply_patch: patchText is required")
        try:
            ops = _parse_patch(patch_text)
        except Exception as e:  # noqa: BLE001
            return _err(f"apply_patch: {e}")

        # Stage every write/delete first; touch the filesystem only if all apply.
        writes: list[tuple[str, str]] = []
        deletes: list[str] = []
        try:
            for op in ops:
                if op["type"] == "add":
                    if os.path.exists(op["path"]):
                        raise ValueError(f"file already exists: {op['path']}")
                    writes.append((op["path"], op["content"]))
                elif op["type"] == "delete":
                    if not os.path.exists(op["path"]):
                        raise ValueError(f"file not found: {op['path']}")
                    deletes.append(op["path"])
                else:
                    with open(op["path"], "r", encoding="utf-8") as f:
                        current = f.read()
                    writes.append((op["path"], _apply_update(current, op["body"])))
        except Exception as e:  # noqa: BLE001
            return _err(f"apply_patch: {e}")

        for path_, content in writes:
            os.makedirs(os.path.dirname(os.path.abspath(path_)), exist_ok=True)
            with open(path_, "w", encoding="utf-8") as f:
                f.write(content)
        for d in deletes:
            try:
                os.remove(d)
            except FileNotFoundError:
                pass

        plural = "" if len(ops) == 1 else "s"
        return _ok(
            f"Applied patch: {len(ops)} file operation{plural}",
            {
                "added": sum(1 for o in ops if o["type"] == "add"),
                "updated": sum(1 for o in ops if o["type"] == "update"),
                "deleted": sum(1 for o in ops if o["type"] == "delete"),
            },
        )

    return _builtin(
        "apply_patch",
        "Apply a patch (Begin/End Patch grammar: Add/Update/Delete File). Atomic — a non-matching hunk aborts with no writes.",
        {
            "type": "object",
            "properties": {
                "patchText": {"type": "string", "description": "The patch text in Begin/End Patch format"}
            },
            "required": ["patchText"],
            "additionalProperties": False,
        },
        run,
    )


def create_builtin_tools() -> list[Tool]:
    """Build the ten built-in tools (each ``source="builtin"``). The order is
    fixed for parity: bash, read, write, edit, grep, glob, webfetch, question,
    apply_patch, todowrite.
    """
    return [
        _bash_tool(),
        _read_tool(),
        _write_tool(),
        _edit_tool(),
        _grep_tool(),
        _glob_tool(),
        _webfetch_tool(),
        _question_tool(),
        _apply_patch_tool(),
        _todowrite_tool(),
    ]
