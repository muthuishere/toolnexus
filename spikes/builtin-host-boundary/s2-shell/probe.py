# S2 probe (python) — what does `subprocess.run(cmd, shell=True)` run? That is
# what python/src/toolnexus/builtin.py:189 does today. On POSIX it is
# `/bin/sh -c`; on Windows it is `%COMSPEC% /c`, i.e. cmd.exe.
import os
import subprocess
import sys

PROBES = [
    ("posix_var", "echo $HOME"),
    ("bashism", "[[ -d . ]] && echo bashism-ok"),
    ("cmd_var", "echo %USERPROFILE%"),
    ("which_self", "echo $0"),
]

print(f"platform={sys.platform} COMSPEC={os.environ.get('COMSPEC', '<unset>')}")
for name, cmd in PROBES:
    try:
        p = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
        print(f"{name}: exit={p.returncode} out={p.stdout.decode(errors='replace').strip()!r}")
    except Exception as e:  # noqa: BLE001 - a probe reports, it does not handle
        print(f"{name}: raised {type(e).__name__}: {e}")
