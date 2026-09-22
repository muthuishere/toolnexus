@echo off
rem The grandchild: sleeps, then writes the marker. If the marker exists after
rem the probe has killed its direct child, the kill did not reach the tree.
ping -n 7 127.0.0.1 >NUL
echo orphan> %1
