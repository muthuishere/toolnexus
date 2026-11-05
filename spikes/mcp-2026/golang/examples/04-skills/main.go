// Example 04 — list and load a remote agent skill (skills extension, SEP-2640).
//
// Run: go run ./examples/04-skills
//
// A server that advertises capabilities.extensions["io.modelcontextprotocol/skills"] serves
// skills/list (names + frontmatter), skills/get (one skill's files with sha256 digests), and the
// files themselves through resources/read on skill:// URIs.
package main

import (
	"fmt"
	"log"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"

	mcp "toolnexus/spikes/mcp2026"
)

func main() {
	ts := httptest.NewServer(mcp.FixtureServer()) // serves examples/skills/hello-world
	defer ts.Close()
	c := mcp.NewClient(ts.URL + "/mcp")

	// 1. Does the server speak the skills extension?
	d, err := c.Call("server/discover", nil)
	if err != nil {
		log.Fatal(err)
	}
	ext, _ := d["capabilities"].(map[string]any)["extensions"].(map[string]any)
	_, hasSkills := ext[mcp.SkillsExt]
	fmt.Println("skills extension:", hasSkills)

	// 2. List skills: cheap, frontmatter only (what a model sees before it loads anything).
	list, err := c.Call("skills/list", nil)
	if err != nil {
		log.Fatal(err)
	}
	for _, s := range list["skills"].([]any) {
		sk := s.(map[string]any)
		fm, _ := sk["frontmatter"].(map[string]any)
		fmt.Printf("skill %s: %s\n", sk["uri"], fm["name"])
	}

	// 3. Load one skill's instructions: resources/read on its SKILL.md.
	const uri = "skill://hello-world/SKILL.md"
	r, err := c.Call("resources/read", mcp.Obj{"uri": uri})
	if err != nil {
		log.Fatal(err)
	}
	body := r["contents"].([]any)[0].(map[string]any)["text"].(string)
	fmt.Println("first heading:", firstHeading(body))

	// 4. Or download the whole skill (every file, digest-checked, path-traversal-safe) to disk.
	dir, _ := os.MkdirTemp("", "skills")
	defer os.RemoveAll(dir)
	if err := c.DownloadSkill(uri, dir); err != nil {
		log.Fatal(err)
	}
	filepath.WalkDir(dir, func(p string, e os.DirEntry, _ error) error {
		if !e.IsDir() {
			rel, _ := filepath.Rel(dir, p)
			fmt.Println("downloaded:", filepath.ToSlash(rel))
		}
		return nil
	})
}

func firstHeading(md string) string {
	for _, l := range strings.Split(md, "\n") {
		if strings.HasPrefix(l, "# ") {
			return l
		}
	}
	return ""
}
