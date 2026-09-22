//go:build windows

package main

import (
	"fmt"
	"os/exec"
	"syscall"
	"unsafe"
)

// A Job Object with JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE: every process in the
// job dies when the last handle closes, and a child cannot leave the job
// (nested jobs are permitted but still bounded by the parent's limits). This is
// the Windows analogue of a process group that a child cannot escape — declared
// against kernel32 directly so the spike needs no module download on target.
var (
	kernel32               = syscall.NewLazyDLL("kernel32.dll")
	procCreateJobObject    = kernel32.NewProc("CreateJobObjectW")
	procSetInfoJobObject   = kernel32.NewProc("SetInformationJobObject")
	procAssignProcessToJob = kernel32.NewProc("AssignProcessToJobObject")
)

const (
	jobObjectExtendedLimitInformation        = 9
	jobObjectLimitKillOnJobClose      uint32 = 0x2000
)

type ioCounters struct {
	ReadOperationCount  uint64
	WriteOperationCount uint64
	OtherOperationCount uint64
	ReadTransferCount   uint64
	WriteTransferCount  uint64
	OtherTransferCount  uint64
}

type jobObjectBasicLimitInformation struct {
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

type jobObjectExtendedLimitInfo struct {
	BasicLimitInformation jobObjectBasicLimitInformation
	IoInfo                ioCounters
	ProcessMemoryLimit    uintptr
	JobMemoryLimit        uintptr
	PeakProcessMemoryUsed uintptr
	PeakJobMemoryUsed     uintptr
}

var jobHandle syscall.Handle

func prepareJob(cmd *exec.Cmd) (func(), error) {
	h, _, e := procCreateJobObject.Call(0, 0)
	if h == 0 {
		return nil, fmt.Errorf("CreateJobObject: %v", e)
	}
	jobHandle = syscall.Handle(h)
	info := jobObjectExtendedLimitInfo{}
	info.BasicLimitInformation.LimitFlags = jobObjectLimitKillOnJobClose
	r, _, e := procSetInfoJobObject.Call(h, jobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&info)), unsafe.Sizeof(info))
	if r == 0 {
		return nil, fmt.Errorf("SetInformationJobObject: %v", e)
	}
	// Start suspended-free: assignment happens right after Start, which is a
	// small race the real implementation closes with CREATE_SUSPENDED.
	return func() { _ = syscall.CloseHandle(jobHandle) }, nil
}

func assignJob(cmd *exec.Cmd) error {
	const processSetQuota = 0x0100 // not in syscall on this Go version
	ph, err := syscall.OpenProcess(processSetQuota|syscall.PROCESS_TERMINATE, false, uint32(cmd.Process.Pid))
	if err != nil {
		return err
	}
	defer syscall.CloseHandle(ph)
	r, _, e := procAssignProcessToJob.Call(uintptr(jobHandle), uintptr(ph))
	if r == 0 {
		return fmt.Errorf("AssignProcessToJobObject: %v", e)
	}
	return nil
}
