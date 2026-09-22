//go:build windows

// Windows half of the builtin host boundary (ADR 0034).
//
// Three things are genuinely different here, each measured on a native Windows
// box rather than assumed (spikes/builtin-host-boundary/SPIKE.md):
//
//  1. There is no `sh`. `%COMSPEC%` is the one interpreter Windows guarantees;
//     PowerShell is often blocked by execution or application-control policy,
//     and `pwsh` is frequently absent.
//  2. There is no signalable process group. CREATE_NEW_PROCESS_GROUP only makes
//     Ctrl-Break deliverable, and a service-hosted process has no console to
//     break. A Job Object is the boundary a child cannot escape; `taskkill /T`
//     is the fallback when a job cannot be created.
//  3. `filepath.EvalSymlinks` FAILS on a path through a directory junction
//     ("The system cannot find the path specified"), and a junction needs no
//     privilege to create. GetFinalPathNameByHandle resolves junctions, 8.3
//     short names and case in one call.

package toolnexus

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"
)

// shellCandidates is the detection order when the host sets no Shell.
// %COMSPEC% first — see (1) above.
func shellCandidates() [][]string {
	var out [][]string
	if comspec := os.Getenv("COMSPEC"); comspec != "" {
		out = append(out, []string{comspec, "/d", "/s", "/c"})
	}
	out = append(out,
		[]string{"cmd.exe", "/d", "/s", "/c"},
		[]string{"pwsh", "-NoProfile", "-Command"},
		[]string{"powershell", "-NoProfile", "-Command"},
		[]string{"bash", "-lc"}, // Git for Windows / WSL, if the host has one
	)
	return out
}

var (
	kernel32                 = syscall.NewLazyDLL("kernel32.dll")
	procCreateJobObject      = kernel32.NewProc("CreateJobObjectW")
	procSetInformationJob    = kernel32.NewProc("SetInformationJobObject")
	procAssignProcessToJob   = kernel32.NewProc("AssignProcessToJobObject")
	procGetFinalPathByHandle = kernel32.NewProc("GetFinalPathNameByHandleW")
)

const (
	jobObjectExtendedLimitInformation        = 9
	jobObjectLimitKillOnJobClose      uint32 = 0x2000
	processSetQuota                          = 0x0100
	fileFlagBackupSemantics                  = 0x02000000
)

type jobIoCounters struct {
	ReadOperationCount, WriteOperationCount, OtherOperationCount uint64
	ReadTransferCount, WriteTransferCount, OtherTransferCount    uint64
}

type jobBasicLimitInformation struct {
	PerProcessUserTimeLimit int64
	PerJobUserTimeLimit     int64
	LimitFlags              uint32
	MinimumWorkingSetSize   uintptr
	MaximumWorkingSetSize   uintptr
	ActiveProcessLimit      uint32
	Affinity                uintptr
	PriorityClass           uint32
	SchedulingClass         uint32
}

type jobExtendedLimitInformation struct {
	BasicLimitInformation jobBasicLimitInformation
	IoInfo                jobIoCounters
	ProcessMemoryLimit    uintptr
	JobMemoryLimit        uintptr
	PeakProcessMemoryUsed uintptr
	PeakJobMemoryUsed     uintptr
}

// jobs tracks the Job Object created for each running command, keyed by the
// *exec.Cmd, so signalJob can close it. Closing the last handle kills every
// process in the job — including ones started after the assignment.
var jobs = map[*exec.Cmd]syscall.Handle{}

func startJob(cmd *exec.Cmd) error {
	h, _, err := procCreateJobObject.Call(0, 0)
	job := syscall.Handle(h)
	if job != 0 {
		info := jobExtendedLimitInformation{}
		info.BasicLimitInformation.LimitFlags = jobObjectLimitKillOnJobClose
		if r, _, _ := procSetInformationJob.Call(uintptr(job), jobObjectExtendedLimitInformation,
			uintptr(unsafe.Pointer(&info)), unsafe.Sizeof(info)); r == 0 {
			_ = syscall.CloseHandle(job)
			job = 0
		}
	} else {
		_ = err // a job is an optimisation over taskkill, never a hard requirement
	}
	if err := cmd.Start(); err != nil {
		if job != 0 {
			_ = syscall.CloseHandle(job)
		}
		return err
	}
	if job != 0 {
		if ph, oerr := syscall.OpenProcess(processSetQuota|syscall.PROCESS_TERMINATE, false,
			uint32(cmd.Process.Pid)); oerr == nil {
			r, _, _ := procAssignProcessToJob.Call(uintptr(job), uintptr(ph))
			_ = syscall.CloseHandle(ph)
			if r == 0 {
				_ = syscall.CloseHandle(job)
				job = 0
			}
		} else {
			_ = syscall.CloseHandle(job)
			job = 0
		}
	}
	if job != 0 {
		jobs[cmd] = job
	}
	return nil
}

// signalJob stops the whole tree. Windows has no SIGTERM, so `graceful` cannot
// mean "ask nicely" the way it does on POSIX: the first attempt is taskkill
// WITHOUT /F (which posts a close request to processes that have a window),
// and the forceful one closes the Job Object — or falls back to taskkill /T /F.
func signalJob(cmd *exec.Cmd, graceful bool) error {
	if cmd.Process == nil {
		return nil
	}
	pid := fmt.Sprint(cmd.Process.Pid)
	if graceful {
		return exec.Command("taskkill", "/T", "/PID", pid).Run()
	}
	if job, ok := jobs[cmd]; ok {
		delete(jobs, cmd)
		if err := syscall.CloseHandle(job); err == nil {
			return nil
		}
	}
	return exec.Command("taskkill", "/T", "/F", "/PID", pid).Run()
}

// realPath asks Windows for a handle's final path — the one call that resolves
// directory junctions, symlinks, substituted drives and 8.3 short names. The
// `\\?\` (or `\\?\UNC\`) prefix it returns is stripped, because every other
// path in the library is spelled without it and a containment comparison has to
// compare like with like.
func realPath(p string) (string, error) {
	u16, err := syscall.UTF16PtrFromString(p)
	if err != nil {
		return "", err
	}
	h, err := syscall.CreateFile(u16, 0,
		syscall.FILE_SHARE_READ|syscall.FILE_SHARE_WRITE|syscall.FILE_SHARE_DELETE,
		nil, syscall.OPEN_EXISTING, fileFlagBackupSemantics, 0)
	if err != nil {
		return "", err
	}
	defer syscall.CloseHandle(h)

	buf := make([]uint16, 1024)
	for {
		r, _, callErr := procGetFinalPathByHandle.Call(uintptr(h),
			uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)), 0)
		if r == 0 {
			return "", callErr
		}
		if int(r) < len(buf) {
			out := syscall.UTF16ToString(buf[:r])
			if strings.HasPrefix(out, `\\?\UNC\`) {
				return `\\` + out[len(`\\?\UNC\`):], nil
			}
			return strings.TrimPrefix(out, `\\?\`), nil
		}
		buf = make([]uint16, r+1)
	}
}

// winReservedNames are the device names every Windows directory implicitly
// contains. A write to one succeeds, creates no file, and passes any
// containment check built on path comparison — measured: `CON` inside a base
// directory reported WROTE_CON=ok with CON_EXISTS_AS_FILE=False.
var winReservedNames = map[string]bool{
	"CON": true, "PRN": true, "AUX": true, "NUL": true,
	"COM1": true, "COM2": true, "COM3": true, "COM4": true, "COM5": true,
	"COM6": true, "COM7": true, "COM8": true, "COM9": true,
	"LPT1": true, "LPT2": true, "LPT3": true, "LPT4": true, "LPT5": true,
	"LPT6": true, "LPT7": true, "LPT8": true, "LPT9": true,
}

// reservedDeviceName reports whether p's last segment names a device. The name
// counts with or without an extension: `NUL`, `NUL.txt` and `nul.log` are all
// the null device.
func reservedDeviceName(p string) bool {
	base := filepath.Base(p)
	if i := strings.IndexByte(base, '.'); i >= 0 {
		base = base[:i]
	}
	return winReservedNames[strings.ToUpper(strings.TrimSpace(base))]
}
