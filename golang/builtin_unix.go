//go:build !windows

// POSIX half of the builtin host boundary (ADR 0034). Two things live here and
// nowhere else: which interpreters are candidates when the host names none, and
// how a command is started and stopped as a whole job rather than as one child.

package toolnexus

import (
	"os/exec"
	"path/filepath"
	"syscall"
)

// shellCandidates is what `bash` runs when the host sets no Shell. `sh -c` is
// what every port has always meant on POSIX, so detection changes nothing here
// — it exists so the Windows list has a peer rather than a special case.
func shellCandidates() [][]string {
	// `/bin/sh` first, then a PATH lookup. Measured (SPIKE ports/js-python): the
	// `shell:true` / `shell=True` the js and python ports used to pass means
	// literally `/bin/sh`, while spawning the bare name `sh` is a PATH lookup —
	// two different binaries on a machine that has both. Naming the absolute
	// path first is what makes this change byte-identical for those two ports.
	return [][]string{{"/bin/sh", "-c"}, {"sh", "-c"}}
}

// startJob starts cmd in its own process group, so a later signal can reach the
// command AND everything it started. Without Setpgid, a kill reaches the shell
// only and the real work keeps running, reparented — measured in all seven
// ports (spikes/builtin-host-boundary/SPIKE.md §1).
func startJob(cmd *exec.Cmd) error {
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.Setpgid = true
	return cmd.Start()
}

// signalJob asks the whole process group to stop (graceful=true ⇒ SIGTERM) or
// kills it (SIGKILL). The negated pid is the group: the child is its own group
// leader because startJob made it one.
func signalJob(cmd *exec.Cmd, graceful bool) error {
	if cmd.Process == nil {
		return nil
	}
	sig := syscall.SIGKILL
	if graceful {
		sig = syscall.SIGTERM
	}
	return syscall.Kill(-cmd.Process.Pid, sig)
}

// realPath resolves symlinks in p. The Windows build needs a different call
// entirely (filepath.EvalSymlinks fails through a directory junction), which is
// the reason this is a platform function and not one line inline.
func realPath(p string) (string, error) {
	return filepath.EvalSymlinks(p)
}

// reservedDeviceName is Windows-only. On POSIX every name is a plain name.
func reservedDeviceName(string) bool { return false }
