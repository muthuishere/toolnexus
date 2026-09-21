"""Dynamic agent-skill source. Mirrors the JS reference (``js/src/skill.ts``):
discover ``**/SKILL.md``, parse YAML frontmatter, and expose ONE ``skill`` tool
that loads a skill's instructions + sampled resources on demand (progressive
disclosure).

Beyond on-disk discovery the source also accepts skills supplied as data
(``SkillDef``) — SPEC.md §3. Directory-sourced skills keep the exact ``file://``
base + on-disk sibling sampling (byte-identical); data-sourced skills use a
logical ``skill://name/`` base + a supplied resource list and never touch disk.

Frontmatter parsing uses a real YAML parser (PyYAML ``safe_load``) over the
``---``-fenced header block, matching the JS reference exactly.
"""
from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable, Literal, Optional, Union

import yaml

from .types import Tool, ToolResult

SKILL_TOOL_DESCRIPTION = """Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.

Use this tool to inject the skill's instructions and resources into current conversation. The output may contain detailed workflow guidance as well as references to scripts, files, etc in the same directory as the skill.

The skill name must match one of the skills listed in your system prompt."""

# Instruction preamble prepended to prompt() when ≥1 described skill exists.
# Byte-identical across all four ports — do not reword. See SPEC.md §3.
SKILLS_PROMPT_PREAMBLE = (
    "Skills provide specialized instructions and workflows for specific tasks.\n"
    "Use the skill tool to load a skill when a task matches its description."
)


@dataclass
class SkillInfo:
    name: str
    location: str  # absolute path to SKILL.md (fs) or logical base (data)
    content: str  # body after frontmatter
    description: Optional[str] = None
    origin: Literal["fs", "logical"] = "fs"  # internal discriminator
    resources: Optional[list[str]] = None  # logical resources (data skills only)
    base: Optional[str] = None  # logical base (data skills only)


@dataclass
class SkillDef:
    """A skill supplied directly as data, bypassing the filesystem (SPEC.md §3, S1)."""

    name: str
    content: str
    description: Optional[str] = None
    resources: Optional[list[str]] = None  # logical names for the <skill_files> block
    base: Optional[str] = None  # defaults to skill://<name>/


SkillSkipReason = Literal[
    "missing-name", "malformed-frontmatter", "duplicate-name", "unreadable"
]


@dataclass
class SkillSkip:
    location: str
    #: The byte-identical, cross-port reason code. Never reworded.
    reason: SkillSkipReason
    #: The NATIVE parser's own message (D6, #93, ADR 0028) — what a host needs to
    #: fix the file. Empty when the skip has no underlying parser error. Port-specific
    #: by construction: PyYAML's wording is not Go's, so never assert on it across ports.
    detail: str = ""


@dataclass
class SkillInventory:
    skills: list[SkillInfo]
    skipped: list[SkillSkip]


# A lazy provider of data-supplied skills (sync or async), resolved at build time.
SkillProvider = Callable[[], Union[list[SkillDef], Awaitable[list[SkillDef]]]]


_FRONTMATTER_RE = re.compile(r"^---\r?\n(.*?)\r?\n---\r?\n?(.*)$", re.DOTALL)

# D6 (#93, ADR 0028) — the LENIENT fallback, INVERTED from the issue's proposal.
# A real YAML parser runs FIRST and keeps every byte of its semantics; the line-wise
# read below runs ONLY on a block YAML has already REFUSED, so by construction it can
# never see a file whose YAML semantics matter (a refused file has none). Line-wise
# FIRST is forbidden: it misparses `description: |`, `description: >`, a plain scalar
# continued on the next line and `name: !!str x`, all of which six ports read today.
#
# Ported faithfully from the measured-correct prototype in
# spikes/issues/93/prototype/lenient.py (which is written in this language); the
# guards below are its guards.

#: Column 0, ordinary key characters, one ``:`` — the value is the rest of the line.
_LENIENT_LINE_RE = re.compile(r"^([A-Za-z0-9_][A-Za-z0-9_.-]*):[ \t]*(.*)$")
#: Only the two scalars the catalog needs. Nothing else is rescued.
_LENIENT_KEYS = ("name", "description")
#: A value opening one of these starts a YAML construct the fallback does not
#: implement (block scalar, anchor, alias, flow seq/map, tag). Take NOTHING rather
#: than garbage — a half-broken block scalar degrades to "no description", never to
#: an invented one.
_LENIENT_OPENERS = frozenset("|>&*[{!")


def _unquote(value: str) -> str:
    v = value.strip()
    if len(v) > 1 and v[0] == v[-1] and v[0] in "\"'":
        return v[1:-1]
    return v


def _line_wise(block: str) -> dict[str, str]:
    """Rescue ``name``/``description`` from a frontmatter block YAML refused."""
    data: dict[str, str] = {}
    for raw in block.splitlines():
        if raw[:1] in (" ", "\t", "#", ""):
            continue  # indented continuation, comment or blank — not a top-level key
        match = _LENIENT_LINE_RE.match(raw)
        if not match:
            continue
        key, value = match.group(1), match.group(2).strip()
        if key not in _LENIENT_KEYS or key in data:
            continue  # first wins, and only the two known scalars
        if not value or value[0] in _LENIENT_OPENERS:
            continue
        data[key] = _unquote(value)
    return data


def _parse_frontmatter(text: str) -> tuple[dict[str, str], str, bool, str]:
    """Parse the ``---``-fenced YAML frontmatter with a real YAML parser, falling
    back to the line-wise read only on a block YAML refused.

    Returns ``(data, content, malformed, detail)``. ``malformed`` is True only when
    fences are present, the YAML fails to parse AND the fallback could not recover a
    ``name`` — distinguishing a malformed header from a body with no frontmatter, so
    the inventory (S3) reports the right skip reason. ``detail`` carries PyYAML's own
    message for the refusal, whether or not the fallback rescued the file.
    """
    match = _FRONTMATTER_RE.match(text)
    if not match:
        return {}, text, False, ""
    data: dict[str, str] = {}
    detail = ""
    try:
        parsed = yaml.safe_load(match.group(1))
    except Exception as e:  # noqa: BLE001 — every YAML refusal, not just YAMLError
        parsed = None
        detail = str(e).replace("\n", " ").strip()
    structurally_wrong = False
    if isinstance(parsed, dict):
        for key, value in parsed.items():
            if isinstance(value, (str, int, float, bool)):
                data[str(key)] = str(value).strip()
        # Addendum A10: YAML libraries DISAGREE about half-broken scalars —
        # `description: [unterminated` throws in PyYAML but recovers into a sequence
        # in JS's yaml. So the rescue triggers on a throw, on a non-mapping, AND on a
        # mapping whose name/description is present but structurally wrong. The
        # invariant both ends serve: a file never gains an invented description and
        # never silently keeps a structurally-wrong one.
        structurally_wrong = any(
            key in parsed and key not in data for key in _LENIENT_KEYS
        )
    if detail or structurally_wrong or not isinstance(parsed, dict):
        for key, value in _line_wise(match.group(1)).items():
            data.setdefault(key, value)
    return data, match.group(2), (detail != "" and not data.get("name")), detail


def _walk_skill_files(root: str) -> list[str]:
    out: list[str] = []
    stack = [root]
    seen: set[str] = set()
    while stack:
        d = stack.pop()
        try:
            entries = list(os.scandir(d))
        except OSError:
            continue
        for entry in entries:
            try:
                is_dir = entry.is_dir(follow_symlinks=True)
                is_file = entry.is_file(follow_symlinks=True)
            except OSError:
                continue
            if is_dir:
                if entry.name in ("node_modules", ".git"):
                    continue
                real = os.path.realpath(entry.path)
                if real in seen:
                    continue
                seen.add(real)
                stack.append(entry.path)
            elif is_file and entry.name == "SKILL.md":
                out.append(entry.path)
    # Addendum A1/A15 — DISCOVERY ORDER is part of the contract, not the
    # filesystem's business: DEPTH (segment count) ASCENDING first, then Unicode
    # code point as the tie-break WITHIN a depth. Pure code-point order does not
    # deliver the rule: `docx` beats `synced/<uuid>/docx` only because `d` < `s`,
    # while `xlsx` would LOSE to `synced/<uuid>/xlsx`. Depth-first makes a top-level
    # skill beat a nested copy whatever its name — which is what first-wins means to
    # a host. No locale collation, no case folding, no segment-aware comparison;
    # symlinks sort at their discovered path, never their target.
    def _order(path: str) -> tuple[int, str]:
        rel = os.path.relpath(path, root)
        parts = rel.split(os.sep)
        return len(parts), rel

    out.sort(key=_order)
    return out


def _sample_sibling_files(directory: str, limit: int = 10) -> list[str]:
    """The ``<skill_files>`` SAMPLE LIST a loaded skill returns.

    ORDER IS PART OF SHIPPED OUTPUT (A22/A24/A25): this text goes to the model, so
    the listing is deterministic — collect every file, sort by path RELATIVE TO
    ``directory`` in plain Unicode code-point order (``sorted`` on ``str``, never a
    locale-aware key, and no depth rule), THEN cap.

    A25 ruled this shape over a per-directory sort during traversal: a per-directory
    sort is a function of the TRAVERSAL, so every port must reproduce the same stack
    discipline to agree, whereas a global sort over relative paths is a function of
    the FILE SET and the cap alone — nothing left to reproduce. A15's depth rule
    resolves duplicate NAMES in discovery and has no business ordering a flat listing.

    Sorting BEFORE the cap is the load-bearing half (ADR-0004 K1): the walk used to
    stop as soon as ``limit`` files had been seen in raw ``os.scandir`` order, so
    arbitrary filesystem order chose WHICH files were sampled, not merely how they
    were arranged — a different sample per machine, and on some filesystems between
    two runs of the same process.
    """
    out: list[str] = []
    stack = [directory]
    seen: set[str] = set()
    while stack:
        cur = stack.pop()
        try:
            entries = list(os.scandir(cur))
        except OSError:
            continue
        for entry in entries:
            try:
                is_dir = entry.is_dir(follow_symlinks=True)
                is_file = entry.is_file(follow_symlinks=True)
            except OSError:
                continue
            if is_dir:
                if entry.name in ("node_modules", ".git"):
                    continue
                real = os.path.realpath(entry.path)
                if real in seen:
                    continue
                seen.add(real)
                stack.append(entry.path)
            elif is_file and entry.name != "SKILL.md":
                out.append(entry.path)
    # A28: the emitted `<file>` string is the ABSOLUTE path (§3 pins that shape), and
    # every entry shares the `directory` prefix — so ordering by the relative path and
    # ordering by the emitted absolute path are the SAME order, and there is no
    # order-versus-display mismatch. `/` on every platform for the key itself.
    out.sort(key=lambda p: os.path.relpath(p, directory).replace(os.sep, "/"))
    return out[:limit] if limit > 0 else out


@dataclass
class _RawCandidate:
    info: Optional[SkillInfo] = None
    skip: Optional[SkillSkip] = None


def _candidates_from_dir(root: str) -> list[_RawCandidate]:
    out: list[_RawCandidate] = []
    if not os.path.isdir(root):
        print(f"[toolnexus] skills dir not found: {root}", file=sys.stderr)
        return out
    for file in _walk_skill_files(root):
        try:
            with open(file, "r", encoding="utf-8") as f:
                text = f.read()
        except OSError:
            out.append(_RawCandidate(skip=SkillSkip(location=file, reason="unreadable")))
            continue
        data, content, malformed, detail = _parse_frontmatter(text)
        if malformed:
            out.append(
                _RawCandidate(
                    skip=SkillSkip(location=file, reason="malformed-frontmatter", detail=detail)
                )
            )
            continue
        name = data.get("name")
        if not name:
            out.append(
                _RawCandidate(skip=SkillSkip(location=file, reason="missing-name", detail=detail))
            )
            continue
        out.append(
            _RawCandidate(
                info=SkillInfo(
                    name=name,
                    description=data.get("description"),
                    location=file,
                    content=content,
                    origin="fs",
                )
            )
        )
    return out


def _candidates_from_defs(defs: list[SkillDef]) -> list[_RawCandidate]:
    out: list[_RawCandidate] = []
    for d in defs:
        if not d.name:
            out.append(
                _RawCandidate(skip=SkillSkip(location=d.base or "skill://", reason="missing-name"))
            )
            continue
        base = d.base if d.base else f"skill://{d.name}/"
        out.append(
            _RawCandidate(
                info=SkillInfo(
                    name=d.name,
                    description=d.description,
                    location=base,
                    content=d.content or "",
                    origin="logical",
                    resources=list(d.resources or []),
                    base=base,
                )
            )
        )
    return out


def _collect_candidates(
    dirs: Optional[str | list[str]], defs: Optional[list[SkillDef]]
) -> list[_RawCandidate]:
    roots = [] if dirs is None else ([dirs] if isinstance(dirs, str) else list(dirs))
    cands: list[_RawCandidate] = []
    for root in roots:
        cands.extend(_candidates_from_dir(root))
    if defs:
        cands.extend(_candidates_from_defs(defs))
    return cands


def _merge_candidates(
    cands: list[_RawCandidate],
) -> tuple[dict[str, SkillInfo], list[SkillSkip]]:
    skills: dict[str, SkillInfo] = {}
    skipped: list[SkillSkip] = []
    for c in cands:
        if c.skip is not None:
            skipped.append(c.skip)
            continue
        info = c.info
        assert info is not None
        if info.name in skills:
            print(
                f'[toolnexus] duplicate skill name "{info.name}" ({info.location}) — keeping first',
                file=sys.stderr,
            )
            skipped.append(SkillSkip(location=info.location, reason="duplicate-name"))
            continue
        skills[info.name] = info
    return skills, skipped


def _apply_filter(
    skills: dict[str, SkillInfo], filter: Optional[dict[str, bool]]
) -> dict[str, SkillInfo]:
    """Per-agent skill allowlist (S2): nil/empty ⇒ all; ≥1 True ⇒ allowlist;
    only-False ⇒ drop-list over all-on; unknown names ignored + warned once."""
    if not filter:
        return skills
    has_true = any(v is True for v in filter.values())
    for k in filter:
        if k not in skills:
            print(f'[toolnexus] skill filter name "{k}" matched no skill', file=sys.stderr)
    out: dict[str, SkillInfo] = {}
    for name, info in skills.items():
        keep = (filter.get(name) is True) if has_true else (filter.get(name) is not False)
        if keep:
            out[name] = info
    return out


@dataclass
class SkillSource:
    skills: dict[str, SkillInfo]
    tool: Tool
    #: Every file discovery refused, with its reason and native detail (D6, #93).
    #: A host must not need ``list_skills`` to learn that 6 of 87 skills vanished.
    #: Empty by default, so a caller that ignores it loads byte-identically.
    skipped: list[SkillSkip] = field(default_factory=list)

    def prompt(self) -> str:
        """Markdown catalog for the system prompt (mirrors opencode Skill.fmt).

        ORDER IS PART OF THE CONTRACT (A22): SPEC §0.10 pins this prompt
        byte-identical across ports, so the catalog is sorted by name in **Unicode
        code-point** order. Python's ``sorted`` on ``str`` IS code-point order by
        construction — do NOT "improve" this with ``locale.strxfrm``, a
        locale-aware key or any collation: that is machine-dependent and would make
        the same skills directory produce a different system prompt on a different
        host.
        """
        described = sorted(
            (s for s in self.skills.values() if s.description is not None),
            key=lambda s: s.name,
        )
        if not described:
            return "No skills are currently available."
        lines = [SKILLS_PROMPT_PREAMBLE, "", "## Available Skills"]
        lines.extend(f"- **{s.name}**: {s.description}" for s in described)
        return "\n".join(lines)


def list_skills(
    dirs: Optional[str | list[str]] = None,
    *,
    skills: Optional[list[SkillDef]] = None,
) -> SkillInventory:
    """Discover + validate skills from the same sources load_skills accepts,
    returning parsed skills plus typed skip reasons — no toolkit wired
    (SPEC.md §3, S3). The inventory is UNFILTERED (it authors the S2 allowlist)."""
    merged, skipped = _merge_candidates(_collect_candidates(dirs, skills))
    return SkillInventory(skills=list(merged.values()), skipped=skipped)


def load_skills(
    dirs: Optional[str | list[str]] = None,
    *,
    skills: Optional[list[SkillDef]] = None,
    filter: Optional[dict[str, bool]] = None,
    sample_limit: int = 0,
) -> SkillSource:
    """Discover skills (dirs and/or data) and build the `skill` loader tool."""
    merged, skipped = _merge_candidates(_collect_candidates(dirs, skills))
    resolved = _apply_filter(merged, filter)

    async def execute(
        args: Optional[dict[str, Any]] = None, ctx: Any = None
    ) -> ToolResult:
        name = str((args or {}).get("name", ""))
        info = resolved.get(name)
        if info is None:
            # Code-point order, same rule as prompt() — this string is shown to the
            # model, so it is user-visible data and must not vary by host locale.
            available = ", ".join(sorted(resolved.keys())) or "none"
            return ToolResult(
                output=f'Skill "{name}" not found. Available skills: {available}',
                is_error=True,
            )
        # eff_limit: 0 ⇒ default 10 (byte-identical), n>0 ⇒ cap, -1 ⇒ omit.
        eff_limit = 10 if sample_limit == 0 else sample_limit
        emit_files = eff_limit != -1
        if info.origin == "logical":
            base = info.base or f"skill://{info.name}/"
            res = info.resources or []
            if not res:
                emit_files = False
            files = res[:eff_limit] if eff_limit > 0 else res
            meta_dir = base
        else:
            directory = os.path.dirname(info.location)
            base = Path(directory).as_uri()
            files = [] if eff_limit == -1 else _sample_sibling_files(directory, eff_limit)
            meta_dir = directory
        lines = [
            f'<skill_content name="{info.name}">',
            f"# Skill: {info.name}",
            "",
            info.content.strip(),
            "",
            f"Base directory for this skill: {base}",
            "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
        ]
        if emit_files:
            lines.extend(
                [
                    "Note: file list is sampled.",
                    "",
                    "<skill_files>",
                    "\n".join(f"<file>{f}</file>" for f in files),
                    "</skill_files>",
                ]
            )
        lines.append("</skill_content>")
        return ToolResult(
            output="\n".join(lines),
            is_error=False,
            metadata={"name": info.name, "dir": meta_dir},
        )

    tool = Tool(
        name="skill",
        description=SKILL_TOOL_DESCRIPTION,
        input_schema={
            "type": "object",
            "properties": {
                "name": {"type": "string", "description": "The name of the skill to load"}
            },
            "required": ["name"],
            "additionalProperties": False,
        },
        source="skill",
        execute=execute,
    )

    return SkillSource(skills=resolved, tool=tool, skipped=skipped)
