package mcp2026

// Skill URI safety (python-sdk #3685): a skill:// URI is percent-decoded ONCE, then rejected if it
// still contains '%' (double encoding), a backslash, NUL, an absolute/empty path, or a "."/".."
// segment. Both the server (before lookup) and the client (before writing files) use it.

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
)

// parseSkillURI splits skill://<name>/<path> into a validated name and slash-separated relative path.
func parseSkillURI(uri string) (name, rel string, err error) {
	rest, ok := strings.CutPrefix(uri, "skill://")
	if !ok {
		return "", "", errors.New("not a skill:// URI")
	}
	dec, err := url.PathUnescape(rest)
	if err != nil {
		return "", "", fmt.Errorf("bad percent-encoding: %w", err)
	}
	if strings.ContainsAny(dec, "%\\\x00") { // '%' left after one decode = double encoding
		return "", "", errors.New("skill URI has encoded, backslash or NUL characters")
	}
	segs := strings.Split(dec, "/")
	if len(segs) < 2 {
		return "", "", errors.New("skill URI needs skill://<name>/<path>")
	}
	for _, s := range segs {
		if s == "" || s == "." || s == ".." || strings.Contains(s, ":") {
			return "", "", errors.New("skill URI path escapes the skill (empty, '.', '..' or ':' segment)")
		}
	}
	return segs[0], strings.Join(segs[1:], "/"), nil
}

// DownloadSkill fetches a skill (skills/get + resources/read for every file) into dir/<name>/,
// verifying each file's sha256 digest. A file whose URI is not inside the skill is refused.
func (c *Client) DownloadSkill(uri, dir string) error {
	name, _, err := parseSkillURI(uri)
	if err != nil {
		return err
	}
	r, err := c.Call("skills/get", obj{"uri": uri})
	if err != nil {
		return err
	}
	sk, _ := r["skill"].(map[string]any)
	res, _ := sk["resources"].([]any)
	type file struct {
		path string
		data []byte
	}
	var files []file
	for _, x := range res { // validate everything before writing anything
		m, _ := x.(map[string]any)
		fu, _ := m["uri"].(string)
		n, rel, err := parseSkillURI(fu)
		if err != nil || n != name || !filepath.IsLocal(filepath.FromSlash(rel)) {
			return fmt.Errorf("refusing skill resource %q: outside skill %s", fu, name)
		}
		rr, err := c.Call("resources/read", obj{"uri": fu})
		if err != nil {
			return err
		}
		cs, _ := rr["contents"].([]any)
		if len(cs) == 0 {
			return fmt.Errorf("%s: empty resources/read", fu)
		}
		t, _ := cs[0].(map[string]any)["text"].(string)
		if d, _ := m["digest"].(string); d != "" && d != fmt.Sprintf("sha256:%x", sha256.Sum256([]byte(t))) {
			return fmt.Errorf("%s: digest mismatch", fu)
		}
		files = append(files, file{filepath.Join(dir, name, filepath.FromSlash(rel)), []byte(t)})
	}
	for _, f := range files {
		if err := os.MkdirAll(filepath.Dir(f.path), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(f.path, f.data, 0o644); err != nil {
			return err
		}
	}
	return nil
}
