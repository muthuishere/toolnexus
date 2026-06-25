"""Dynamic agent-skill source. Mirrors the JS reference (``js/src/skill.ts``):
discover ``**/SKILL.md``, parse YAML frontmatter, and expose ONE ``skill`` tool
that loads a skill's instructions + sampled resources on demand (progressive
disclosure).

Frontmatter parsing uses a minimal flat ``key: value`` parser (no heavy dep),
matching the JS reference exactly.
"""
from __future__ import annotations

import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

from .types import Tool, ToolResult

SKILL_TOOL_DESCRIPTION = """Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.

Use this tool to inject the skill's instructions and resources into current conversation. The output may contain detailed workflow guidance as well as references to scripts, files, etc in the same directory as the skill.

The skill name must match one of the skills listed in your system prompt."""


@dataclass
class SkillInfo:
    name: str
    location: str  # absolute path to SKILL.md
    content: str  # body after frontmatter
    description: Optional[str] = None


_FRONTMATTER_RE = re.compile(r"^---\r?\n(.*?)\r?\n---\r?\n?(.*)$", re.DOTALL)


def _parse_frontmatter(text: str) -> tuple[dict[str, str], str]:
    """Minimal YAML frontmatter parser — only flat `key: value` pairs are needed."""
    match = _FRONTMATTER_RE.match(text)
    if not match:
        return {}, text
    data: dict[str, str] = {}
    for line in re.split(r"\r?\n", match.group(1)):
        idx = line.find(":")
        if idx == -1:
            continue
        key = line[:idx].strip()
        value = line[idx + 1 :].strip()
        if (value.startswith('"') and value.endswith('"')) or (
            value.startswith("'") and value.endswith("'")
        ):
            value = value[1:-1]
        if key:
            data[key] = value
    return data, match.group(2)


def _walk_skill_files(root: str) -> list[str]:
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
                if entry.name in ("node_modules", ".git"):
                    continue
                stack.append(entry.path)
            elif entry.is_file(follow_symlinks=False) and entry.name == "SKILL.md":
                out.append(entry.path)
    return out


def _sample_sibling_files(directory: str, limit: int = 10) -> list[str]:
    out: list[str] = []
    stack = [directory]
    while stack and len(out) < limit:
        cur = stack.pop()
        try:
            entries = list(os.scandir(cur))
        except OSError:
            continue
        for entry in entries:
            if len(out) >= limit:
                break
            if entry.is_dir(follow_symlinks=False):
                if entry.name in ("node_modules", ".git"):
                    continue
                stack.append(entry.path)
            elif entry.is_file(follow_symlinks=False) and entry.name != "SKILL.md":
                out.append(entry.path)
    return out


@dataclass
class SkillSource:
    skills: dict[str, SkillInfo]
    tool: Tool

    def prompt(self) -> str:
        """Markdown catalog for the system prompt (mirrors opencode Skill.fmt)."""
        described = sorted(
            (s for s in self.skills.values() if s.description is not None),
            key=lambda s: s.name,
        )
        if not described:
            return "No skills are currently available."
        lines = ["## Available Skills"]
        lines.extend(f"- **{s.name}**: {s.description}" for s in described)
        return "\n".join(lines)


def load_skills(dirs: str | list[str]) -> SkillSource:
    """Discover skills under one or more roots and build the `skill` loader tool."""
    roots = [dirs] if isinstance(dirs, str) else list(dirs)
    skills: dict[str, SkillInfo] = {}

    for root in roots:
        if not os.path.isdir(root):
            print(f"[toolnexus] skills dir not found: {root}", file=sys.stderr)
            continue
        for file in _walk_skill_files(root):
            try:
                with open(file, "r", encoding="utf-8") as f:
                    text = f.read()
            except OSError:
                continue
            data, content = _parse_frontmatter(text)
            name = data.get("name")
            if not name:
                continue
            if name in skills:
                print(
                    f'[toolnexus] duplicate skill name "{name}" ({file}) — keeping first',
                    file=sys.stderr,
                )
                continue
            skills[name] = SkillInfo(
                name=name,
                description=data.get("description"),
                location=file,
                content=content,
            )

    async def execute(
        args: Optional[dict[str, Any]] = None, ctx: Any = None
    ) -> ToolResult:
        name = str((args or {}).get("name", ""))
        info = skills.get(name)
        if info is None:
            available = ", ".join(sorted(skills.keys())) or "none"
            return ToolResult(
                output=f'Skill "{name}" not found. Available skills: {available}',
                is_error=True,
            )
        directory = os.path.dirname(info.location)
        base = Path(directory).as_uri()
        files = _sample_sibling_files(directory)
        output = "\n".join(
            [
                f'<skill_content name="{info.name}">',
                f"# Skill: {info.name}",
                "",
                info.content.strip(),
                "",
                f"Base directory for this skill: {base}",
                "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.",
                "Note: file list is sampled.",
                "",
                "<skill_files>",
                "\n".join(f"<file>{f}</file>" for f in files),
                "</skill_files>",
                "</skill_content>",
            ]
        )
        return ToolResult(
            output=output, is_error=False, metadata={"name": info.name, "dir": directory}
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

    return SkillSource(skills=skills, tool=tool)
