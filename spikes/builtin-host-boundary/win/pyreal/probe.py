# W6 — the REAL python port's builtins on native Windows. builtin.py and its two
# local imports are copied into a minimal package next to this script (they pull
# no third-party dependency), so nothing needs pip on target.
import asyncio
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tnmini.builtin import create_builtin_tools, builtin_shell  # noqa: E402


def tool(cfg, name):
    for t in create_builtin_tools(cfg):
        if t.name == name:
            return t
    raise AssertionError(name)


async def main():
    scratch = sys.argv[1] if len(sys.argv) > 1 else "."
    print(f"platform={sys.platform} HOST_CWD={os.getcwd()}")
    try:
        print(f"SHELL_DETECTED={builtin_shell()}")
    except Exception as e:  # noqa: BLE001
        print(f"SHELL_DETECT_ERR={e}")

    cfg = {"base_dir": scratch}

    res = await tool(cfg, "bash").execute({"command": "echo hello-from-bash-builtin"}, None)
    print(f"BASH_ISERROR={res.is_error} BASH_OUTPUT={res.output!r} SHELL={res.metadata.get('shell')!r}")

    res = await tool(cfg, "write").execute({"path": "relative-probe.txt", "content": "landed"}, None)
    print(f"WRITE_ISERROR={res.is_error} WRITE_OUTPUT={res.output!r}")
    in_base = os.path.join(scratch, "relative-probe.txt")
    in_cwd = os.path.join(os.getcwd(), "relative-probe.txt")
    where = "baseDir" if os.path.exists(in_base) else ("host_cwd" if os.path.exists(in_cwd) else "nowhere")
    print(f"RELATIVE_LANDED_IN={where}")

    confined = {"base_dir": scratch, "confine_to_base_dir": True}
    res = await tool(confined, "read").execute({"path": "..\\..\\escape.txt"}, None)
    print(f"CONFINE_DOTDOT_ISERROR={res.is_error} OUTPUT={res.output!r}")
    res = await tool(confined, "write").execute({"path": "CON", "content": "x"}, None)
    print(f"CONFINE_DEVICE_ISERROR={res.is_error} OUTPUT={res.output!r}")

    marker = os.path.join(scratch, "py-orphan.marker")
    if os.path.exists(marker):
        os.remove(marker)
    res = await tool(cfg, "bash").execute({"command": f"child.cmd {marker}", "timeout": 700}, None)
    print(f"TIMEOUT_ISERROR={res.is_error} TIMEDOUT={res.metadata.get('timedOut')} KILLEDTREE={res.metadata.get('killedTree')}")
    time.sleep(9)
    print(f"ORPHAN={'SURVIVED' if os.path.exists(marker) else 'none'}")

    os.makedirs(os.path.join(scratch, "tree", "sub"), exist_ok=True)
    with open(os.path.join(scratch, "tree", "sub", "a.txt"), "w") as f:
        f.write("x\n")
    res = await tool(cfg, "glob").execute({"pattern": "**/*.txt", "path": "tree"}, None)
    print(f"GLOB_ISERROR={res.is_error} GLOB_OUTPUT={res.output!r}")
    res = await tool(cfg, "grep").execute({"pattern": "x", "path": "tree"}, None)
    print(f"GREP_ISERROR={res.is_error} GREP_OUTPUT={res.output!r}")


asyncio.run(main())
