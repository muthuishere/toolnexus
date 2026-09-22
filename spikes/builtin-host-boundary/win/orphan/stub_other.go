//go:build !windows

package main

import "os/exec"

func prepareJob(*exec.Cmd) (func(), error) { return func() {}, nil }
func assignJob(*exec.Cmd) error            { return nil }
