// W4 — the #102 defect on native Windows, and the fix that works there.
//
// There is no process group to signal on Windows the way there is on POSIX:
// CREATE_NEW_PROCESS_GROUP only makes Ctrl-Break deliverable, and a console
// app started by a service has no console to break. The portable answer is
// `taskkill /T /F /PID`, which walks the child tree; the durable one is a Job
// Object, which cannot be escaped at all. This probe measures all three.
//
//	mode=naive    Process.Kill equivalent — cmd.Process.Kill(), the direct child
//	mode=taskkill taskkill /T /F /PID <pid>
//	mode=job      assign the child to a Job Object with KILL_ON_JOB_CLOSE
package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

func main() {
	mode := os.Args[1]
	dir, _ := os.MkdirTemp("", "orphan-*")
	defer os.RemoveAll(dir)
	marker := filepath.Join(dir, "marker.txt")

	// The direct child is cmd.exe running child.cmd, which detaches one
	// grandchild and then waits. cmd.exe, not PowerShell: execution policy or
	// AppLocker can block PowerShell on a managed box, so neither the probe nor
	// the shipped default may depend on it.
	comspec := os.Getenv("COMSPEC")
	if comspec == "" {
		comspec = "cmd.exe"
	}
	script := "child.cmd"
	if len(os.Args) > 2 {
		script = os.Args[2]
	}
	cmd := exec.Command(comspec, "/d", "/s", "/c", script, marker)
	var closeJob func()
	if mode == "job" {
		var err error
		closeJob, err = prepareJob(cmd)
		if err != nil {
			fmt.Println("JOB_UNAVAILABLE=", err)
			return
		}
	}
	if err := cmd.Start(); err != nil {
		fmt.Println("START_ERR=", err)
		return
	}
	if mode == "job" {
		if err := assignJob(cmd); err != nil {
			fmt.Println("JOB_ASSIGN_ERR=", err)
		}
	}
	time.Sleep(2500 * time.Millisecond) // let child.ps1 detach the grandchild

	switch mode {
	case "control":
		// No kill at all: proves the command CAN write the marker, so a
		// "killed_whole_job" verdict means the kill worked, not that the probe
		// was broken. A spike without its control measures nothing.
	case "naive":
		_ = cmd.Process.Kill()
	case "taskkill":
		out, err := exec.Command("taskkill", "/T", "/F", "/PID", fmt.Sprint(cmd.Process.Pid)).CombinedOutput()
		fmt.Printf("TASKKILL err=%v out=%q\n", err, string(out))
	case "job":
		closeJob()
	}
	_ = cmd.Wait()

	time.Sleep(8 * time.Second) // outlast the grandchild's own sleep
	if _, err := os.Stat(marker); err == nil {
		fmt.Printf("%s: ORPHAN_SURVIVED\n", mode)
	} else {
		fmt.Printf("%s: killed_whole_job\n", mode)
	}
}
