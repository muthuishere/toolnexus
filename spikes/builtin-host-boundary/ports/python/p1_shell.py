"""O1 / python — does moving from shell=True to an explicit argv prefix preserve
today's behaviour, and does dropping shell=True change the TIMEOUT path?

ARM_TODAY   subprocess.run(command, shell=True)      <- builtin.py:189
ARM_ARGV    subprocess.run(["sh","-c",command])      <- the candidate
ARM_CONTROL subprocess.run(["echo", command])        <- MUST differ every time.

The control arm is there so a column of SAME means the comparator can tell
things apart at all.

Run: python3 p1_shell.py
"""

import os
import subprocess
import sys
import time

SHAPES = {
    "quotes": """echo "a  b" 'c$NOPE'""",
    "pipe": "printf 'x\\ny\\n' | grep y",
    "redirect": "echo hi > /dev/stderr",
    "andand": "true && echo second",
    "multiline": "echo one\necho two\nif true; then echo three; fi",
    "nonzero": "echo before; exit 7",
    "varexp": "V=1; echo $V$HOME_NOPE",
}


def run(cmd, shell):
    p = subprocess.run(
        cmd, shell=shell, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
    )
    return (p.stdout, p.returncode)


print("== O1 python: shell=True vs explicit argv ==")
same = 0
control_false_positives = 0
for name, command in SHAPES.items():
    today = run(command, True)
    argv = run(["sh", "-c", command], False)
    control = run(["echo", command], False)
    eq = today == argv
    ceq = today == control
    same += eq
    control_false_positives += ceq
    print(
        f"{name:<10} today_vs_argv={'SAME' if eq else 'DIFFER'}  "
        f"control_vs_today={'SAME(PROBE BROKEN)' if ceq else 'DIFFER(ok)'}"
    )
    if not eq:
        print(f"  today={today!r}\n  argv ={argv!r}")
print(
    f"SUMMARY shapes={len(SHAPES)} same={same} control_false_positives={control_false_positives}"
)

# Which interpreter does shell=True actually mean? The `:;` prefix stops the
# shell exec-ing the single command and reporting `ps` as its own name.
probe = ":; ps -o comm= -p $$"
print(f"SHELL_TRUE_INTERPRETER={run(probe, True)[0].strip()!r}")
print(f"ARGV_INTERPRETER={run(['sh','-c',probe], False)[0].strip()!r}")

# --------------------------------------------------------------------------
# The timeout path: does dropping shell=True change how it behaves?
# --------------------------------------------------------------------------
print("\n== O1 python: the timeout path, shell=True vs argv ==")
MARKER_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".p1tmp")
os.makedirs(MARKER_DIR, exist_ok=True)


def timeout_arm(label, shell, timeout):
    marker = os.path.join(MARKER_DIR, f"{label}.marker")
    if os.path.exists(marker):
        os.remove(marker)
    command = f"echo partial; sleep 0.2; sh -c 'sleep 1; touch {marker}'"
    full = command if shell else ["sh", "-c", command]
    out = None
    kind = "NO_TIMEOUT_RAISED"
    t0 = time.time()
    try:
        p = subprocess.run(
            full,
            shell=shell,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
        )
        out = p.stdout
    except subprocess.TimeoutExpired as e:
        kind = "TimeoutExpired"
        out = e.output
    elapsed = time.time() - t0
    time.sleep(2)
    survived = os.path.exists(marker)
    print(
        f"{label:<24} raised={kind:<16} e.output={out!r:<20} "
        f"blocked_for={elapsed:.2f}s  "
        f"{'ORPHAN_SURVIVED' if survived else 'killed_whole_job'}"
    )


# CONTROL: no timeout at all. The marker MUST appear, or the probe cannot write
# it and every "killed_whole_job" below would be an artefact.
timeout_arm("control-no-timeout", True, None)
timeout_arm("shellTrue-timeout", True, 0.3)
timeout_arm("argvList-timeout", False, 0.3)
