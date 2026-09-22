//go:build windows

package main

import (
	"fmt"
	"os"
	"syscall"
	"unsafe"
)

// finalPath asks Windows for a handle's FINAL path — the one call that resolves
// junctions, symlinks, substituted drives and 8.3 short names in one go.
// Declared against kernel32 directly so the probe needs no module download on
// the target machine.
func finalPath(p string) (string, error) {
	u16, err := syscall.UTF16PtrFromString(p)
	if err != nil {
		return "", err
	}
	const FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
	h, err := syscall.CreateFile(u16, 0,
		syscall.FILE_SHARE_READ|syscall.FILE_SHARE_WRITE|syscall.FILE_SHARE_DELETE,
		nil, syscall.OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, 0)
	if err != nil {
		return "", err
	}
	defer syscall.CloseHandle(h)

	proc := syscall.NewLazyDLL("kernel32.dll").NewProc("GetFinalPathNameByHandleW")
	buf := make([]uint16, 32768)
	r, _, e := proc.Call(uintptr(h), uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)), 0)
	if r == 0 {
		return "", e
	}
	return syscall.UTF16ToString(buf[:r]), nil
}

func init() { finalPathProbe = finalPath }

var _ = os.Args
var _ = fmt.Sprint
