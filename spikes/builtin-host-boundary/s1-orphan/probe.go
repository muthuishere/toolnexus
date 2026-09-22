//go:build ignore

// S1 probe (go). Two modes:
//   naive — exactly what golang/builtin.go:338 does today (exec.CommandContext).
//   group — Setpgid + SIGTERM/SIGKILL to the negated pgid.
// The command is `sleep 1; touch <marker>`: if the marker exists 2s after the
// 300ms timeout fired, the grandchild outlived the kill.
package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"syscall"
	"time"
)

func main() {
	mode, marker := os.Args[1], os.Args[2]
	command := fmt.Sprintf("sleep 0.2; sh -c 'sleep 1; touch %s'", marker)
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()

	if mode == "naive" {
		cmd := exec.CommandContext(ctx, "sh", "-c", command)
		_, _ = cmd.CombinedOutput()
	} else {
		cmd := exec.Command("sh", "-c", command)
		cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
		if err := cmd.Start(); err != nil {
			fmt.Println("START_ERR", err)
			return
		}
		pgid := cmd.Process.Pid
		done := make(chan error, 1)
		go func() { done <- cmd.Wait() }()
		select {
		case <-done:
		case <-ctx.Done():
			_ = syscall.Kill(-pgid, syscall.SIGTERM)
			select {
			case <-done:
			case <-time.After(200 * time.Millisecond):
				_ = syscall.Kill(-pgid, syscall.SIGKILL)
				<-done
			}
		}
	}
	fmt.Println("killed")
}
