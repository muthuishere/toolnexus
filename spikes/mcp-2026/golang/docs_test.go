package mcp2026_test

// Every code block in docs/*.md is preceded by <!-- from: <path> --> and must appear verbatim in
// that file (an example that examples_test.go runs). Edit the example, then re-copy the block.

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
)

var fromBlock = regexp.MustCompile("(?s)<!-- from: (\\S+) -->\n```[a-z]*\n(.*?)\n```")

func TestDocsSnippetsMatchExamples(t *testing.T) {
	pages, _ := filepath.Glob("docs/*.md")
	if len(pages) == 0 {
		t.Fatal("no docs pages")
	}
	total := 0
	for _, page := range pages {
		b, err := os.ReadFile(page)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(string(b), "Go reference spike — other ports pending") {
			t.Errorf("%s: missing the spike banner", page)
		}
		for _, m := range fromBlock.FindAllStringSubmatch(string(b), -1) {
			total++
			src, err := os.ReadFile(m[1])
			if err != nil {
				t.Errorf("%s: %v", page, err)
				continue
			}
			if !strings.Contains(string(src), m[2]) {
				t.Errorf("%s: block from %s no longer matches the example:\n%s", page, m[1], m[2])
			}
		}
	}
	t.Logf("%d code blocks checked across %d pages", total, len(pages))
}
