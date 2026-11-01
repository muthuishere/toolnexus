package toolnexus

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// Edit diffs (SPEC §4): edit / write / apply_patch results carry
// metadata.files — [{path, status, additions, deletions}], counts identical
// across ports — and metadata.diff, a unified diff (valid, not byte-identical
// across ports) with paths relative to baseDir when it is set.

// fileChange is one file's before/after, as a diff names it.
type fileChange struct {
	path, status  string // status: added | modified | deleted
	before, after string
}

// readIfExists reads a file; ok is false when it does not exist (or cannot be read).
func readIfExists(p string) (string, bool) {
	raw, err := os.ReadFile(p)
	if err != nil {
		return "", false
	}
	return string(raw), true
}

// diffPath is the path a diff shows: relative to baseDir when set (forward
// slashes), else the path as the caller gave it.
func (e *builtinEnv) diffPath(given, full string) string {
	if e != nil && e.baseDir != "" {
		base, bErr := filepath.Abs(e.baseDir)
		abs, aErr := filepath.Abs(full)
		if bErr == nil && aErr == nil {
			if rel, err := filepath.Rel(base, abs); err == nil {
				return filepath.ToSlash(rel)
			}
		}
	}
	return filepath.ToSlash(given)
}

// addDiffMeta sets metadata.files and metadata.diff for the changes.
func addDiffMeta(meta map[string]any, changes []fileChange) {
	files := make([]any, 0, len(changes))
	var diff strings.Builder
	for _, ch := range changes {
		a, b := diffSplitLines(ch.before), diffSplitLines(ch.after)
		ops := diffLines(a, b)
		adds, dels := 0, 0
		for _, o := range ops {
			switch o.kind {
			case '+':
				adds++
			case '-':
				dels++
			}
		}
		files = append(files, map[string]any{"path": ch.path, "status": ch.status, "additions": adds, "deletions": dels})
		writeUnified(&diff, ch, ops)
	}
	meta["files"] = files
	meta["diff"] = diff.String()
}

// diffSplitLines splits on "\n"; a trailing newline does not start a new line.
func diffSplitLines(s string) []string {
	if s == "" {
		return nil
	}
	s = strings.TrimSuffix(s, "\n")
	return strings.Split(s, "\n")
}

type diffOp struct {
	kind byte // ' ' | '-' | '+'
	text string
}

// diffLines is a minimal line diff: common prefix/suffix are trimmed, the
// middle is solved by LCS. A middle too large for the table degrades to
// delete-all + add-all, which is still a valid (if unminimal) diff.
func diffLines(a, b []string) []diffOp {
	pre := 0
	for pre < len(a) && pre < len(b) && a[pre] == b[pre] {
		pre++
	}
	suf := 0
	for suf < len(a)-pre && suf < len(b)-pre && a[len(a)-1-suf] == b[len(b)-1-suf] {
		suf++
	}
	var ops []diffOp
	for _, l := range a[:pre] {
		ops = append(ops, diffOp{' ', l})
	}
	ma, mb := a[pre:len(a)-suf], b[pre:len(b)-suf]
	n, m := len(ma), len(mb)
	if n*m > 4_000_000 {
		for _, l := range ma {
			ops = append(ops, diffOp{'-', l})
		}
		for _, l := range mb {
			ops = append(ops, diffOp{'+', l})
		}
	} else {
		// lcs[i][j] = LCS length of ma[i:], mb[j:]
		lcs := make([][]int, n+1)
		for i := range lcs {
			lcs[i] = make([]int, m+1)
		}
		for i := n - 1; i >= 0; i-- {
			for j := m - 1; j >= 0; j-- {
				if ma[i] == mb[j] {
					lcs[i][j] = lcs[i+1][j+1] + 1
				} else if lcs[i+1][j] >= lcs[i][j+1] {
					lcs[i][j] = lcs[i+1][j]
				} else {
					lcs[i][j] = lcs[i][j+1]
				}
			}
		}
		i, j := 0, 0
		for i < n && j < m {
			switch {
			case ma[i] == mb[j]:
				ops = append(ops, diffOp{' ', ma[i]})
				i++
				j++
			case lcs[i+1][j] >= lcs[i][j+1]:
				ops = append(ops, diffOp{'-', ma[i]})
				i++
			default:
				ops = append(ops, diffOp{'+', mb[j]})
				j++
			}
		}
		for ; i < n; i++ {
			ops = append(ops, diffOp{'-', ma[i]})
		}
		for ; j < m; j++ {
			ops = append(ops, diffOp{'+', mb[j]})
		}
	}
	for _, l := range a[len(a)-suf:] {
		ops = append(ops, diffOp{' ', l})
	}
	return ops
}

// writeUnified renders one file's ops as a unified diff with 3 lines of context.
func writeUnified(w *strings.Builder, ch fileChange, ops []diffOp) {
	from, to := "a/"+ch.path, "b/"+ch.path
	if ch.status == "added" {
		from = "/dev/null"
	}
	if ch.status == "deleted" {
		to = "/dev/null"
	}
	fmt.Fprintf(w, "--- %s\n+++ %s\n", from, to)
	const ctx = 3
	changed := func(k int) bool { return ops[k].kind != ' ' }
	k := 0
	for k < len(ops) {
		if !changed(k) {
			k++
			continue
		}
		// hunk spans [start, end): grow while another change is within 2*ctx.
		start := k - ctx
		if start < 0 {
			start = 0
		}
		end := k
		for end < len(ops) {
			if changed(end) {
				end++
				continue
			}
			next := end
			for next < len(ops) && !changed(next) && next-end < 2*ctx {
				next++
			}
			if next < len(ops) && changed(next) {
				end = next
				continue
			}
			end += ctx
			if end > len(ops) {
				end = len(ops)
			}
			break
		}
		// line numbers at start
		aLine, bLine := 1, 1
		for _, o := range ops[:start] {
			if o.kind != '+' {
				aLine++
			}
			if o.kind != '-' {
				bLine++
			}
		}
		aCount, bCount := 0, 0
		for _, o := range ops[start:end] {
			if o.kind != '+' {
				aCount++
			}
			if o.kind != '-' {
				bCount++
			}
		}
		if aCount == 0 {
			aLine--
		}
		if bCount == 0 {
			bLine--
		}
		fmt.Fprintf(w, "@@ -%d,%d +%d,%d @@\n", aLine, aCount, bLine, bCount)
		for _, o := range ops[start:end] {
			w.WriteByte(o.kind)
			w.WriteString(o.text)
			w.WriteByte('\n')
		}
		k = end
	}
}
