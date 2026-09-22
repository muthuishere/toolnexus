"""Stress harness for the builtin host boundary (ADR 0034) — python port.

The same five scenarios as the go and js harnesses, plus S6: python is the port
whose cancellation path had to reach into a worker thread, so it gets a scenario
of its own — 20 concurrent calls cancelled mid-flight.

Every scenario carries a CONTROL. An assertion that passes because the harness
never ran measures nothing, which has already happened twice here.

    python spikes/builtin-host-boundary/stress/python/stress.py
"""
from __future__ import annotations

import asyncio
import os
import shutil
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../../python/src"))
from toolnexus.builtin import create_builtin_tools  # noqa: E402

DIR = tempfile.mkdtemp(prefix="tn-stress-py-")
ALL_PASS = True


def verdict(ok: bool) -> str:
    return "PASS" if ok else "FAIL"


def report(line: str, ok: bool) -> None:
    global ALL_PASS
    ALL_PASS = ALL_PASS and ok
    print(f"{line} {verdict(ok)}", flush=True)


def tool(cfg, name):
    for t in create_builtin_tools(cfg):
        if t.name == name:
            return t
    raise AssertionError(name)


# The GRANDCHILD writes the marker, and `sleep 0.2` in front stops the shell
# exec-optimising the single command away.
def orphan_command(marker: str) -> str:
    return f"sleep 0.2; sh -c 'sleep 5; touch {marker}'"


def mkdir(d: str) -> str:
    os.makedirs(d, exist_ok=True)
    return d


def markers(d: str) -> int:
    return len([f for f in os.listdir(d)]) if os.path.isdir(d) else 0


def child_count() -> int:
    try:
        out = subprocess.run(["ps", "-eo", "pid=,ppid="], capture_output=True, text=True, timeout=10).stdout
        return sum(1 for line in out.splitlines() if line.split()[1:2] == [str(os.getpid())])
    except Exception:  # noqa: BLE001
        return -1


def fd_count() -> int:
    try:
        return len(os.listdir(f"/dev/fd"))
    except Exception:  # noqa: BLE001
        return -1


async def s1():
    bash = tool(None, "bash")
    n = 30
    md = mkdir(os.path.join(DIR, "s1"))
    results = await asyncio.gather(
        *[bash.execute({"command": orphan_command(os.path.join(md, f"t{i}.marker")), "timeout": 300}, None) for i in range(n)]
    )
    await asyncio.sleep(7)
    orphans = markers(md)
    timed_out = sum(1 for r in results if r.metadata.get("timedOut") is True)
    killed = sum(1 for r in results if r.metadata.get("killedTree") is True)

    cd = mkdir(os.path.join(DIR, "s1c"))
    await asyncio.gather(
        *[bash.execute({"command": orphan_command(os.path.join(cd, f"c{i}.marker")), "timeout": 20000}, None) for i in range(3)]
    )
    control = markers(cd)
    report(
        f"S1 concurrent_timeouts n={n} orphans={orphans} timedOut={timed_out} killedTree={killed} control_markers={control}/3",
        orphans == 0 and timed_out == n and killed == n and control == 3,
    )


async def s2():
    bash = tool(None, "bash")
    pairs = 20
    md = mkdir(os.path.join(DIR, "s2"))
    jobs = []
    for i in range(pairs):
        jobs.append(("ok", i, bash.execute({"command": f"echo ok-{i}"}, None)))
        jobs.append(("timeout", i, bash.execute({"command": orphan_command(os.path.join(md, f"m{i}.marker")), "timeout": 300}, None)))
    done = await asyncio.gather(*[j[2] for j in jobs])
    await asyncio.sleep(7)

    ok_correct = ok_wrong = to_correct = to_wrong = 0
    for (kind, i, _), r in zip(jobs, done):
        if kind == "ok":
            # Each success must carry ITS OWN output, not a concurrent call's.
            if not r.is_error and r.output.strip() == f"ok-{i}":
                ok_correct += 1
            else:
                ok_wrong += 1
        else:
            if r.is_error and r.metadata.get("timedOut") is True:
                to_correct += 1
            else:
                to_wrong += 1
    report(
        f"S2 mixed_load ok_correct={ok_correct}/{pairs} ok_wrong={ok_wrong} timeout_correct={to_correct}/{pairs} "
        f"timeout_wrong={to_wrong} orphans={markers(md)}",
        ok_correct == pairs and ok_wrong == 0 and to_correct == pairs and to_wrong == 0 and markers(md) == 0,
    )


async def s3():
    bash = tool(None, "bash")
    # 5 MB on stdout then a sleep past the timeout — the shape that deadlocks
    # when a killed child's pipes are held open by a grandchild.
    t0 = time.monotonic()
    res = await bash.execute({"command": "head -c 5000000 /dev/zero | tr '\\0' 'x'; sleep 10", "timeout": 1000}, None)
    elapsed = int((time.monotonic() - t0) * 1000)
    control = await bash.execute({"command": "head -c 5000000 /dev/zero | tr '\\0' 'x'", "timeout": 30000}, None)
    report(
        f"S3 big_output_timeout elapsed_ms={elapsed} is_error={res.is_error} bytes_on_timeout={len(res.output)} "
        f"control_ok={not control.is_error} control_bytes={len(control.output)}",
        res.is_error and elapsed < 6000 and not control.is_error and len(control.output) >= 5_000_000,
    )


async def s4():
    bash = tool(None, "bash")
    rounds = []
    for r in range(3):
        await asyncio.gather(
            *[bash.execute({"command": orphan_command(os.path.join(DIR, f"s4-{r}-{i}.marker")), "timeout": 300}, None) for i in range(10)]
        )
        await asyncio.sleep(0.5)
        import threading

        rounds.append((threading.active_count(), child_count(), fd_count()))
    # Monotonic growth across identical rounds is the leak signal.
    ok = rounds[2][2] <= rounds[0][2] + 8 and rounds[2][1] <= rounds[0][1] + 2 and rounds[2][0] <= rounds[0][0] + 8
    report(
        f"S4 leaks threads={'/'.join(str(x[0]) for x in rounds)} children={'/'.join(str(x[1]) for x in rounds)} "
        f"fds={'/'.join(str(x[2]) for x in rounds)}",
        ok,
    )


async def s5():
    base = mkdir(os.path.join(DIR, "confine"))
    outside = mkdir(os.path.join(DIR, "outside"))
    mkdir(os.path.join(base, "sub"))
    with open(os.path.join(outside, "secret.txt"), "w") as f:
        f.write("secret")
    try:
        os.symlink(outside, os.path.join(base, "link"))
    except OSError:
        pass
    for name in ["a.txt", "sub/b.txt", "c.txt"]:
        with open(os.path.join(base, name), "w") as f:
            f.write("x")

    write = tool({"base_dir": base, "confine_to_base_dir": True}, "write")
    legal = [
        "a.txt",
        "sub/b.txt",
        "./c.txt",
        "sub/new-file.txt",
        # `....` is a LITERAL directory name, not a parent reference — the
        # lookalike that catches a checker doing string surgery on dots.
        "....//x",
    ]
    escapes = [
        "../x",
        "sub/../../x",
        os.path.join(outside, "secret.txt"),
        "link/secret.txt",
        "sub/./../../outside/secret.txt",
        "../../../../../../../../tmp/tn-stress-escaped-py.txt",
    ]

    jobs, kinds = [], []
    for _ in range(50):
        for p in legal:
            jobs.append(write.execute({"path": p, "content": "x"}, None))
            kinds.append((p, True))
        for p in escapes:
            jobs.append(write.execute({"path": p, "content": "pwned"}, None))
            kinds.append((p, False))
    out = await asyncio.gather(*jobs)

    allowed_escapes = [p for (p, legal_p), r in zip(kinds, out) if not legal_p and not r.is_error]
    refused_legals = [(p, r.output) for (p, legal_p), r in zip(kinds, out) if legal_p and r.is_error]

    # CONTROL: the same escapes with confinement OFF must all be allowed.
    unconfined = tool({"base_dir": base}, "write")
    control_allowed = 0
    for p in escapes:
        r = await unconfined.execute({"path": p, "content": "x"}, None)
        if not r.is_error:
            control_allowed += 1
    for p in sorted(set(allowed_escapes)):
        print(f"   ALLOWED {p!r}")
    for p, msg in sorted(set(refused_legals)):
        print(f"   REFUSED_LEGAL {p!r} -> {msg!r}")
    report(
        f"S5 confinement_under_load attempts={len(out)} allowed_escapes={len(allowed_escapes)} "
        f"refused_legals={len(refused_legals)} control_allowed_without_confine={control_allowed}/{len(escapes)}",
        not allowed_escapes and not refused_legals and control_allowed == len(escapes),
    )


async def s6():
    """python-only: cancelling 20 concurrent calls mid-flight.

    This is the port whose kill had to reach a Popen created inside a worker
    thread — `asyncio.to_thread` cannot be interrupted, so a handle left in the
    worker frame means a cancelled run keeps its job alive (measured).
    """
    bash = tool(None, "bash")
    md = mkdir(os.path.join(DIR, "s6"))
    tasks = [
        asyncio.ensure_future(bash.execute({"command": orphan_command(os.path.join(md, f"x{i}.marker")), "timeout": 60000}, None))
        for i in range(20)
    ]
    await asyncio.sleep(0.4)
    for t in tasks:
        t.cancel()
    cancelled = 0
    for t in tasks:
        try:
            await t
        except asyncio.CancelledError:
            cancelled += 1
    await asyncio.sleep(8)
    orphans = markers(md)

    # CONTROL: the same command NOT cancelled must write its marker.
    cd = mkdir(os.path.join(DIR, "s6c"))
    await bash.execute({"command": orphan_command(os.path.join(cd, "c.marker")), "timeout": 20000}, None)
    report(
        f"S6 concurrent_cancels n=20 cancelled={cancelled} orphans={orphans} control_markers={markers(cd)}/1",
        cancelled == 20 and orphans == 0 and markers(cd) == 1,
    )


async def main():
    print(f"== python stress (pid {os.getpid()})", flush=True)
    await s1()
    await s2()
    await s3()
    await s4()
    await s5()
    await s6()
    shutil.rmtree(DIR, ignore_errors=True)
    if os.path.exists("/tmp/tn-stress-escaped-py.txt"):
        os.remove("/tmp/tn-stress-escaped-py.txt")
    print(f"== python stress {verdict(ALL_PASS)}")
    sys.exit(0 if ALL_PASS else 1)


asyncio.run(main())
