//go:build unix

package toolnexus

import (
	"os"
	"syscall"
)

// plainFsync is fsync(2) without macOS's F_FULLFSYNC (which os.File.Sync uses).
func plainFsync(f *os.File) error { return syscall.Fsync(int(f.Fd())) }
