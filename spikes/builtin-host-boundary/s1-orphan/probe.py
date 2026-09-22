# S1 probe (python). naive = what python/src/toolnexus/builtin.py does today
# (subprocess.run(shell=True, timeout=...)). group = start_new_session + killpg.
import os, signal, subprocess, sys, time
mode, marker = sys.argv[1], sys.argv[2]
command = f"sleep 0.2; sh -c 'sleep 1; touch {marker}'"
if mode == "naive":
    try:
        subprocess.run(command, shell=True, timeout=0.3, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    except subprocess.TimeoutExpired:
        pass
else:
    p = subprocess.Popen(command, shell=True, start_new_session=True,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    try:
        p.communicate(timeout=0.3)
    except subprocess.TimeoutExpired:
        os.killpg(p.pid, signal.SIGTERM)
        time.sleep(0.2)
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        p.wait()
print("killed")
