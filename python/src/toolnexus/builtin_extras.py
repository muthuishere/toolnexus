"""Opt-in builtins for coding-agent hosts (SPEC §4C, change add-go-host-asks):
background shells (``bash`` ``run_in_background`` + ``bash_output`` + ``kill_shell``),
``websearch`` over a host-supplied provider, and ``notebook_edit``. None of them is
on by default, so a host that configures nothing sees the same ten builtins.
Mirrors ``golang/builtin_extras.go``.
"""
from __future__ import annotations

import asyncio
import inspect
import json
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from typing import TYPE_CHECKING, Any, Awaitable, Callable, Optional, Union

from .types import Tool, ToolContext, ToolResult

if TYPE_CHECKING:  # pragma: no cover
    from .builtin import _BuiltinEnv


# --------------------------------------------------------------------------- #
# background shells
# --------------------------------------------------------------------------- #
@dataclass
class BackgroundShellInfo:
    """One background command, for a host UI."""

    id: str
    command: str
    started: float
    running: bool
    # Valid when not running; -1 if killed by a signal.
    exit_code: int
    killed: bool


class _BgJob:
    def __init__(self, job_id: str, command: str, proc: "subprocess.Popen[bytes]", grace_s: float) -> None:
        self.id = job_id
        self.command = command
        self.started = time.time()
        self.proc = proc
        self.grace_s = grace_s
        self.lock = threading.Lock()
        self.buf = bytearray()
        self.read = 0  # bytes of buf already returned by bash_output
        self.killed = False
        self.done = threading.Event()
        self.exit_code = 0
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self) -> None:
        out = self.proc.stdout
        assert out is not None
        while True:
            chunk = out.read1(65536) if hasattr(out, "read1") else out.read(65536)
            if not chunk:
                break
            with self.lock:
                self.buf.extend(chunk)
        code = self.proc.wait()
        with self.lock:
            self.exit_code = code if code >= 0 else -1
        self.done.set()

    def running(self) -> bool:
        return not self.done.is_set()

    def info(self) -> BackgroundShellInfo:
        with self.lock:
            return BackgroundShellInfo(
                id=self.id,
                command=self.command,
                started=self.started,
                running=self.running(),
                exit_code=self.exit_code,
                killed=self.killed,
            )

    def fresh_output(self) -> str:
        with self.lock:
            data = bytes(self.buf[self.read :])
            self.read = len(self.buf)
        return data.decode("utf-8", "replace")

    def kill(self) -> bool:
        """Stop the job's whole process group: SIGTERM, the grace window, SIGKILL.
        Returns whether it was still running."""
        from .builtin import _kill_job

        if not self.running():
            return False
        with self.lock:
            self.killed = True
        _kill_job(self.proc, True)
        if not self.done.wait(self.grace_s):
            _kill_job(self.proc, False)
            self.done.wait()
        return True


class BackgroundShells:
    """Owns the commands ``bash`` started with ``run_in_background``. Pass one as
    ``builtins={"background": BackgroundShells()}`` and call :meth:`close_all` when
    the host shuts down — a background command is NOT tied to the turn that
    started it, so nothing else stops it."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._next = 0
        self._jobs: dict[str, _BgJob] = {}

    def _start(self, argv: list[str], command: str, workdir: str, grace_s: float) -> _BgJob:
        proc = subprocess.Popen(
            [*argv, command],
            cwd=workdir,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
            start_new_session=sys.platform != "win32",
        )
        with self._lock:
            self._next += 1
            job = _BgJob(f"bg{self._next}", command, proc, grace_s)
            self._jobs[job.id] = job
        return job

    def _get(self, job_id: str) -> Optional[_BgJob]:
        with self._lock:
            return self._jobs.get(job_id)

    def list(self) -> list[BackgroundShellInfo]:  # noqa: A003 — mirrors Go List
        """Every background command, oldest first."""
        with self._lock:
            jobs = list(self._jobs.values())
        jobs.sort(key=lambda j: j.started)
        return [j.info() for j in jobs]

    def kill(self, job_id: str) -> None:
        """Stop one background command's process group. Unknown id ⇒ KeyError."""
        job = self._get(job_id)
        if job is None:
            raise KeyError(f'no background shell "{job_id}"')
        job.kill()

    def close_all(self) -> None:
        """Stop every background command still running, concurrently."""
        with self._lock:
            jobs = list(self._jobs.values())
        threads = [threading.Thread(target=j.kill) for j in jobs]
        for t in threads:
            t.start()
        for t in threads:
            t.join()


def _bg_status(i: BackgroundShellInfo) -> str:
    if i.running:
        return "running"
    if i.killed:
        return "killed"
    return f"exited with code {i.exit_code}"


def bash_background_start(env: "_BuiltinEnv", argv: list[str], command: str, workdir: str) -> ToolResult:
    """Start ``command`` in the host's background registry (bash run_in_background)."""
    bg: BackgroundShells = env.background
    try:
        job = bg._start(argv, command, workdir, env.kill_grace_s)
    except Exception as e:  # noqa: BLE001
        return ToolResult(output=f"bash: {e}", is_error=True, metadata={"shell": env.shell_label})
    return ToolResult(
        output=(
            f"Started background shell {job.id}. Read its output with bash_output, "
            "stop it with kill_shell."
        ),
        is_error=False,
        metadata={"shell": env.shell_label, "backgroundId": job.id},
    )


def _id_schema() -> dict[str, Any]:
    return {
        "type": "object",
        "properties": {"id": {"type": "string", "description": "The background shell id bash returned"}},
        "required": ["id"],
        "additionalProperties": False,
    }


def bash_output_tool(env: "_BuiltinEnv") -> Tool:
    from .builtin import _builtin, _err, _ok

    bg: BackgroundShells = env.background

    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        job_id = str(args.get("id") or "")
        job = bg._get(job_id)
        if job is None:
            return _err(f'bash_output: no background shell "{job_id}"')
        fresh = job.fresh_output()
        info = job.info()
        status = _bg_status(info)
        meta: dict[str, Any] = {"id": job_id, "status": status.split(" ", 1)[0]}
        if not info.running and not info.killed:
            meta["exitCode"] = info.exit_code
        return _ok(f"{fresh}\n[status: {status}]", meta)

    return _builtin(
        "bash_output",
        "Read the output a background bash command (started with run_in_background) produced "
        "since the last read, and whether it is still running.",
        _id_schema(),
        run,
    )


def kill_shell_tool(env: "_BuiltinEnv") -> Tool:
    from .builtin import _builtin, _err, _ok

    bg: BackgroundShells = env.background

    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        job_id = str(args.get("id") or "")
        job = bg._get(job_id)
        if job is None:
            return _err(f'kill_shell: no background shell "{job_id}"')
        if not await asyncio.to_thread(job.kill):
            return _ok(
                f"Background shell {job_id} had already {_bg_status(job.info())}",
                {"id": job_id, "killed": False},
            )
        return _ok(f"Killed background shell {job_id}", {"id": job_id, "killed": True})

    return _builtin(
        "kill_shell",
        "Stop a background bash command and everything it started.",
        _id_schema(),
        run,
    )


# --------------------------------------------------------------------------- #
# websearch
# --------------------------------------------------------------------------- #
@dataclass
class WebSearchResult:
    """One hit from a host search provider."""

    title: str
    url: str
    snippet: str = ""


# The host's search provider: (query, limit) -> results (sync or async; dicts with
# title/url/snippet are accepted too). The library ships none.
WebSearchFn = Callable[[str, int], Union[list[WebSearchResult], Awaitable[list[WebSearchResult]]]]


def _hit(r: Any) -> WebSearchResult:
    if isinstance(r, WebSearchResult):
        return r
    return WebSearchResult(title=str(r.get("title", "")), url=str(r.get("url", "")), snippet=str(r.get("snippet") or ""))


def websearch_tool(search: WebSearchFn) -> Tool:
    from .builtin import _builtin, _err, _num, _ok

    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        q = str(args.get("query") or "")
        if not q.strip():
            return _err("websearch: query is required")
        lim = _num(args.get("limit"))
        limit = int(lim) if lim is not None and lim >= 1 else 5
        try:
            out = search(q, limit)
            if inspect.isawaitable(out):
                out = await out
        except Exception as e:  # noqa: BLE001
            return _err(f"websearch: {e}")
        results = [_hit(r) for r in (out or [])][:limit]
        if not results:
            return _ok("No results.", {"count": 0})
        chunks = []
        for i, r in enumerate(results):
            s = f"{i + 1}. {r.title}\n   {r.url}"
            if r.snippet:
                s += f"\n   {r.snippet}"
            chunks.append(s)
        return _ok(
            "\n\n".join(chunks),
            {
                "count": len(results),
                "results": [{"title": r.title, "url": r.url, "snippet": r.snippet} for r in results],
            },
        )

    return _builtin(
        "websearch",
        "Search the web. Returns numbered results with title, URL and snippet.",
        {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "The search query"},
                "limit": {"type": "number", "description": "Maximum number of results (default 5)"},
            },
            "required": ["query"],
            "additionalProperties": False,
        },
        run,
    )


# --------------------------------------------------------------------------- #
# notebook_edit
# --------------------------------------------------------------------------- #
def _notebook_source(s: str) -> list[str]:
    """nbformat's list of lines, each keeping its newline."""
    if not s:
        return []
    return s.splitlines(keepends=True)


def notebook_edit_tool(env: "_BuiltinEnv") -> Tool:
    from .builtin import _builtin, _diff_metadata, _err, _file_change, _num, _ok

    async def run(args: dict[str, Any], ctx: Optional[ToolContext]) -> ToolResult:
        p = str(args.get("path") or "")
        if not p:
            return _err("notebook_edit: path is required")
        try:
            full = env.resolve_path(p)
        except Exception as e:  # noqa: BLE001
            return _err(f"notebook_edit: {e}")
        try:
            with open(full, "r", encoding="utf-8") as f:
                raw = f.read()
        except OSError as e:
            return _err(f"notebook_edit: {e}")
        try:
            nb = json.loads(raw)
            if not isinstance(nb, dict):
                raise ValueError("not an object")
        except ValueError as e:
            return _err(f"notebook_edit: {p} is not a notebook: {e}")
        cells = nb.get("cells") if isinstance(nb.get("cells"), list) else []
        mode = str(args.get("edit_mode") or "replace")
        idx = -1
        cell_id = args.get("cell_id")
        index = _num(args.get("cell_index"))
        if isinstance(cell_id, str) and cell_id:
            for i, c in enumerate(cells):
                if isinstance(c, dict) and c.get("id") == cell_id:
                    idx = i
                    break
            if idx < 0:
                return _err(f'notebook_edit: no cell with id "{cell_id}"')
        elif index is not None:
            idx = int(index)
            if idx < 0 or idx >= len(cells):
                return _err(f"notebook_edit: cell_index {idx} out of range (0-{len(cells) - 1})")
        elif mode != "insert":
            return _err("notebook_edit: cell_id or cell_index is required")
        src = str(args.get("new_source") or "")
        cell_type = str(args.get("cell_type") or "")
        if mode == "replace":
            cm = cells[idx]
            cm["source"] = _notebook_source(src)
            if cell_type:
                cm["cell_type"] = cell_type
            if cm.get("cell_type") == "code":
                cm["outputs"] = []
                cm["execution_count"] = None
            else:
                cm.pop("outputs", None)
                cm.pop("execution_count", None)
            summary = f"Replaced cell {idx} in {p}"
        elif mode == "insert":
            cell_type = cell_type or "code"
            cell: dict[str, Any] = {"cell_type": cell_type, "metadata": {}, "source": _notebook_source(src)}
            if cell_type == "code":
                cell["outputs"] = []
                cell["execution_count"] = None
            at = idx + 1
            cells.insert(at, cell)
            summary = f"Inserted {cell_type} cell at {at} in {p}"
        elif mode == "delete":
            del cells[idx]
            summary = f"Deleted cell {idx} from {p}"
        else:
            return _err(f'notebook_edit: unknown edit_mode "{mode}"')
        nb["cells"] = cells
        out = json.dumps(nb, indent=1, ensure_ascii=False) + "\n"
        try:
            with open(full, "w", encoding="utf-8") as f:
                f.write(out)
        except OSError as e:
            return _err(f"notebook_edit: {e}")
        meta: dict[str, Any] = {"cells": len(cells)}
        meta.update(_diff_metadata([_file_change(env, p, full, raw, out)]))
        return _ok(summary, meta)

    return _builtin(
        "notebook_edit",
        "Edit a Jupyter notebook (.ipynb) cell: replace its source, insert a new cell after it, "
        "or delete it. Replacing a code cell clears its outputs.",
        {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Path to the .ipynb file"},
                "cell_id": {"type": "string", "description": "The cell's id (or use cell_index)"},
                "cell_index": {"type": "number", "description": "0-based cell position (used when cell_id is absent)"},
                "new_source": {"type": "string", "description": "The new cell source (replace/insert)"},
                "cell_type": {
                    "type": "string",
                    "enum": ["code", "markdown"],
                    "description": "Cell type for insert (default code) or to change on replace",
                },
                "edit_mode": {
                    "type": "string",
                    "enum": ["replace", "insert", "delete"],
                    "description": "Default replace. insert adds the new cell AFTER the given cell, or first when none is given",
                },
            },
            "required": ["path"],
            "additionalProperties": False,
        },
        run,
    )
