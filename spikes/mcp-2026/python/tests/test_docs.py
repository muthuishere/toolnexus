"""Every code block in docs/*.md is preceded by <!-- from: <path> --> and must appear verbatim in that file
(an example that test_examples.py runs). Edit the example, then re-copy the block."""
import glob
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FROM_BLOCK = re.compile(r"<!-- from: (\S+) -->\n```[a-z]*\n(.*?)\n```", re.S)


def test_docs_snippets_match_examples():
    pages = sorted(glob.glob(os.path.join(ROOT, "docs", "*.md")))
    assert len(pages) == 6
    total = 0
    for page in pages:
        text = open(page, encoding="utf-8").read()
        assert "Python port of the MCP 2026-07-28 spike" in text, f"{page}: missing the spike banner"
        for src, block in FROM_BLOCK.findall(text):
            total += 1
            with open(os.path.join(ROOT, src), encoding="utf-8") as f:
                assert block in f.read(), f"{page}: block from {src} no longer matches the example:\n{block}"
    assert total >= 12
