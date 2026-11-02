"""Runs examples/prompt_cache_tool_tiers.py so CI catches it drifting from the API.
The example is hermetic (scripted transport, fake key)."""
from __future__ import annotations

import subprocess
import sys
from pathlib import Path

EXAMPLE = Path(__file__).resolve().parents[1] / "examples" / "prompt_cache_tool_tiers.py"


def test_prompt_cache_tool_tiers_example_runs():
    r = subprocess.run([sys.executable, str(EXAMPLE)], capture_output=True, text=True, timeout=60)
    assert r.returncode == 0, r.stdout + r.stderr
    assert r.stdout.rstrip().endswith("ok")
