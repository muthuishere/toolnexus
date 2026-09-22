@echo off
rem The direct child. It detaches ONE grandchild and then stays alive, so the
rem probe has a live child to kill and a grandchild to look for afterwards.
rem
rem Plain cmd.exe on purpose: PowerShell can be blocked by execution policy or
rem AppLocker on a managed box, so neither the probe nor the shipped default may
rem depend on it. `start /B cmd /c <file>` also keeps the first token unquoted —
rem `start "" /B ...` makes cmd read the quoted token as a program name and pops
rem a modal "Windows cannot find" dialog on a desktop session.
start /B cmd /c "%~dp0grandchild.cmd" %1
ping -n 13 127.0.0.1 >NUL
