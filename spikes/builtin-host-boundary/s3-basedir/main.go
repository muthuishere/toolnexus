// S3 probe — can a `BaseDir` + containment check (issue #101) be implemented
// portably, and what defeats a naive one?
//
// The candidate implementation under test is deliberately small, because it is
// the shape that would land in seven ports:
//
//	resolve(p)  = p if absolute, else join(baseDir, p)
//	contained(p)= canonicalize(resolve(p)) is baseDir or under it
//
// canonicalize resolves symlinks on the deepest existing ancestor, then
// re-attaches the non-existent tail — a not-yet-created file has no realpath,
// and a check that only works for files that already exist is not a check for
// `write`.
//
// Run with no arguments. It prints one line per case: the verdict, and whether
// the bytes actually stayed inside.
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

func canonicalize(p string) (string, error) {
	abs, err := filepath.Abs(p)
	if err != nil {
		return "", err
	}
	// Walk up to the deepest ancestor that exists, resolve THAT, re-attach the tail.
	tail := ""
	cur := abs
	for {
		if resolved, err := filepath.EvalSymlinks(cur); err == nil {
			if tail == "" {
				return resolved, nil
			}
			return filepath.Join(resolved, tail), nil
		}
		parent := filepath.Dir(cur)
		if parent == cur {
			return abs, nil
		}
		tail = filepath.Join(filepath.Base(cur), tail)
		cur = parent
	}
}

func contained(baseDir, p string) (bool, string) {
	cb, err := canonicalize(baseDir)
	if err != nil {
		return false, "canonicalize(base): " + err.Error()
	}
	cp, err := canonicalize(p)
	if err != nil {
		return false, "canonicalize(path): " + err.Error()
	}
	rel, err := filepath.Rel(cb, cp)
	if err != nil {
		return false, "rel: " + err.Error()
	}
	if rel == "." {
		return true, cp
	}
	if rel == ".." || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return false, cp
	}
	return true, cp
}

func resolve(baseDir, p string) string {
	if filepath.IsAbs(p) {
		return p
	}
	return filepath.Join(baseDir, p)
}

func main() {
	fmt.Printf("GOOS=%s\n", runtime.GOOS)
	base, err := os.MkdirTemp("", "tn-base-*")
	if err != nil {
		panic(err)
	}
	outside, _ := os.MkdirTemp("", "tn-outside-*")
	defer os.RemoveAll(base)
	defer os.RemoveAll(outside)
	_ = os.MkdirAll(filepath.Join(base, "sub"), 0o755)
	_ = os.WriteFile(filepath.Join(outside, "secret.txt"), []byte("secret\n"), 0o600)

	// A symlink inside the base pointing out of it. On Windows this needs either
	// Developer Mode or admin; the probe reports which, rather than assuming.
	link := filepath.Join(base, "link")
	linkErr := os.Symlink(outside, link)

	type tc struct{ name, path string }
	cases := []tc{
		{"plain_relative", "sub/file.txt"},
		{"dotdot_relative", "../escape.txt"},
		{"deep_dotdot", "sub/../../escape.txt"},
		{"absolute_outside", filepath.Join(outside, "secret.txt")},
		{"through_symlink", "link/secret.txt"},
		{"base_itself", "."},
		{"empty", ""},
	}
	if runtime.GOOS == "windows" {
		cases = append(cases,
			tc{"win_backslash_dotdot", `..\escape.txt`},
			tc{"win_forward_slash", "sub/ok.txt"},
			tc{"win_drive_relative", "C:sub/file.txt"},
			tc{"win_device_CON", "CON"},
			tc{"win_device_nul_nested", `sub\NUL`},
			tc{"win_trailing_dot", "sub/file.txt."},
			tc{"win_alt_stream", "sub/file.txt:hidden"},
			tc{"win_extended_prefix", `\\?\` + filepath.Join(outside, "secret.txt")},
			tc{"win_case_variant_base", strings.ToUpper(base) + `\sub\file.txt`},
		)
	}
	if linkErr != nil {
		fmt.Printf("SYMLINK_UNAVAILABLE=%v\n", linkErr)
	}
	for _, c := range cases {
		r := resolve(base, c.path)
		ok, canon := contained(base, r)
		fmt.Printf("%-24s resolved=%-70q contained=%-5v canon=%q\n", c.name, r, ok, canon)
	}

	// The honest limit: `bash` leaves the base whatever the file tools do.
	fmt.Println("-- bash escapes, measured (workdir set to base):")
	for _, esc := range []string{
		`cd .. && ls | head -1`,
		`env -C / ls | head -1`,
		`cat ` + filepath.Join(outside, "secret.txt"),
	} {
		fmt.Printf("   %-34s -> %s\n", esc, runShell(base, esc))
	}
}

// runShell runs one command through the platform's default interpreter with
// cwd = dir, and returns a one-line summary. It exists only to measure what a
// `workdir`-pinned shell can still reach; it is not a proposed implementation.
func runShell(dir, command string) string {
	var argv []string
	if runtime.GOOS == "windows" {
		argv = []string{os.Getenv("COMSPEC"), "/d", "/s", "/c", command}
	} else {
		argv = []string{"/bin/sh", "-c", command}
	}
	cmd := exec.Command(argv[0], argv[1:]...)
	cmd.Dir = dir
	out, err := cmd.CombinedOutput()
	s := strings.TrimSpace(string(out))
	s = strings.ReplaceAll(s, "\n", " | ")
	if err != nil {
		return fmt.Sprintf("err=%v out=%q", err, s)
	}
	return fmt.Sprintf("ok out=%q", s)
}
