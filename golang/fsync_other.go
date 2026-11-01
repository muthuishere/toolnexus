//go:build !unix

package toolnexus

import "os"

// plainFsync falls back to os.File.Sync where there is no separate fsync(2).
func plainFsync(f *os.File) error { return f.Sync() }
