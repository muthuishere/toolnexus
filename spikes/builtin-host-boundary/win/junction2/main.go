// W3b — does each runtime's real-path call see through a Windows DIRECTORY
// JUNCTION? A junction needs no privilege (measured in junction.ps1), so if the
// containment check cannot see through one, an agent with `bash` can mint its
// own escape hatch and every file builtin will follow it.
//
// Go part. Node and Python equivalents sit beside this file; all three take the
// junction path as argv[1] and print one line.
package main

import (
	"fmt"
	"os"
	"path/filepath"
)

func main() {
	p := os.Args[1]
	abs, _ := filepath.Abs(p)
	ev, err := filepath.EvalSymlinks(p)
	fmt.Printf("go:     Abs=%q EvalSymlinks=%q err=%v\n", abs, ev, err)
	if extraProbe != nil {
		extraProbe(p)
	}
	if fi, err := os.Lstat(p); err == nil {
		fmt.Printf("go:     Lstat.mode=%v isSymlinkBit=%v\n", fi.Mode(), fi.Mode()&os.ModeSymlink != 0)
	}
}

// finalPathProbe is set on Windows by final_windows.go; nil elsewhere.
var finalPathProbe func(string) (string, error)

func init() {
	extraProbe = func(p string) {
		if finalPathProbe == nil {
			return
		}
		fp, err := finalPathProbe(p)
		fmt.Printf("go:     GetFinalPathNameByHandle=%q err=%v\n", fp, err)
	}
}

var extraProbe func(string)
