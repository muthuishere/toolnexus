// W2 probe — the REAL golang port's builtins, cross-compiled for windows/amd64
// and run on a native Windows box over agentbus. No git, no Go module download
// on the target: one .exe is shipped and executed.
//
// It answers four questions with the shipped code, not with a reimplementation:
//   1. does `bash` run at all on native Windows (issue #100)
//   2. what do the file builtins resolve a relative path against (issue #101)
//   3. does a timeout kill the grandchild (issue #102)
//   4. do glob/grep emit `/`-separated relative paths on Windows (SPEC §4A)
package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

func call(tk *tn.Toolkit, name string, args map[string]any) (string, bool) {
	tool, ok := tk.Get(name)
	if !ok {
		return "<tool absent>", false
	}
	res, err := tool.Execute(args, &tn.ToolContext{Ctx: context.Background()})
	if err != nil {
		return fmt.Sprintf("<go error: %v>", err), true
	}
	return res.Output, res.IsError
}

func main() {
	fmt.Printf("GOOS=%s GOARCH=%s\n", runtime.GOOS, runtime.GOARCH)
	cwd, _ := os.Getwd()
	fmt.Printf("HOST_CWD=%s\n", cwd)

	scratchArg := "."
	if len(os.Args) > 1 {
		scratchArg = os.Args[1]
	}
	if shell, shErr := tn.BuiltinShell(nil); shErr != nil {
		fmt.Println("SHELL_DETECT_ERR=", shErr)
	} else {
		fmt.Printf("SHELL_DETECTED=%q\n", shell)
	}
	tk, err := tn.CreateToolkit(context.Background(), tn.Options{
		Builtins: tn.BuiltinsConfig{BaseDir: scratchArg},
	})
	if err != nil {
		fmt.Println("CREATE_TOOLKIT_ERR=", err)
		return
	}
	defer tk.Close()

	// 1 — bash on native Windows.
	out, isErr := call(tk, "bash", map[string]any{"command": "echo hello-from-bash-builtin"})
	fmt.Printf("BASH_ISERROR=%v BASH_OUTPUT=%q\n", isErr, out)

	// 2 — relative-path resolution, now with BaseDir set to the scratch dir.
	scratch := scratchArg
	_ = os.MkdirAll(filepath.Join(scratch, "elsewhere"), 0o755)
	rel := "relative-probe.txt"
	out, isErr = call(tk, "write", map[string]any{"path": rel, "content": "landed"})
	fmt.Printf("WRITE_ISERROR=%v WRITE_OUTPUT=%q\n", isErr, out)
	inBase := filepath.Join(scratch, rel)
	inCwd := filepath.Join(cwd, rel)
	switch {
	case fileThere(inBase):
		fmt.Printf("RELATIVE_LANDED_IN=baseDir (%s)\n", inBase)
		_ = os.Remove(inBase)
	case fileThere(inCwd):
		fmt.Printf("RELATIVE_LANDED_IN=host_cwd (%s)\n", inCwd)
		_ = os.Remove(inCwd)
	default:
		fmt.Println("RELATIVE_LANDED_IN=nowhere")
	}

	// 2b — confinement, which only exists when the host asks for it.
	tkC, cErr := tn.CreateToolkit(context.Background(), tn.Options{
		Builtins: tn.BuiltinsConfig{BaseDir: scratch, ConfineToBaseDir: true},
	})
	if cErr != nil {
		fmt.Println("CONFINE_TOOLKIT_ERR=", cErr)
	} else {
		out, isErr := call(tkC, "read", map[string]any{"path": "..\\..\\escape.txt"})
		fmt.Printf("CONFINE_DOTDOT_ISERROR=%v OUTPUT=%q\n", isErr, out)
		out, isErr = call(tkC, "write", map[string]any{"path": "CON", "content": "x"})
		fmt.Printf("CONFINE_DEVICE_ISERROR=%v OUTPUT=%q\n", isErr, out)
		tkC.Close()
	}

	// 3 — timeout and the grandchild. Only meaningful if bash works at all.
	marker := filepath.Join(scratch, "orphan.marker")
	_ = os.Remove(marker)
	// child.cmd detaches a grandchild and then waits — see win/orphan/child.cmd
	// for why this is plain cmd and not `start ""` or PowerShell.
	command := fmt.Sprintf(`child.cmd %s`, marker)
	out, isErr = call(tk, "bash", map[string]any{"command": command, "timeout": 700})
	fmt.Printf("TIMEOUT_ISERROR=%v TIMEOUT_OUTPUT=%q\n", isErr, out)
	time.Sleep(9 * time.Second)
	if _, err := os.Stat(marker); err == nil {
		fmt.Println("ORPHAN=SURVIVED")
		_ = os.Remove(marker)
	} else {
		fmt.Println("ORPHAN=none_or_bash_unavailable")
	}

	// 4 — separator in a listing, on a Windows filesystem.
	_ = os.MkdirAll(filepath.Join(scratch, "tree", "sub"), 0o755)
	_ = os.WriteFile(filepath.Join(scratch, "tree", "sub", "a.txt"), []byte("x\n"), 0o644)
	out, isErr = call(tk, "glob", map[string]any{"pattern": "**/*.txt", "path": filepath.Join(scratch, "tree")})
	fmt.Printf("GLOB_ISERROR=%v GLOB_OUTPUT=%q\n", isErr, out)
	out, isErr = call(tk, "grep", map[string]any{"pattern": "x", "path": filepath.Join(scratch, "tree")})
	fmt.Printf("GREP_ISERROR=%v GREP_OUTPUT=%q\n", isErr, out)
}

// fileThere is os.Stat with the error thrown away — the probe only asks whether
// the bytes landed, never why they did not.
func fileThere(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}
