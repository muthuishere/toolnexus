"""O4 / python — does the kill reach the grandchild, and does it still reach it
when the surrounding coroutine is CANCELLED rather than timing out?

The python port's `bash` is `async def run(...)` that does
`await asyncio.to_thread(do)` where `do()` calls the BLOCKING
`subprocess.run(..., timeout=...)` (python/src/toolnexus/builtin.py:183-206).
That is the dimension Go does not have, and it is the one likely to break:
asyncio.to_thread offers NO way to interrupt the worker thread.

Command shape is the one from spikes/.../s1-orphan/probe.py and it matters:
    sleep 0.2; sh -c 'sleep 1; touch MARKER'
Without the leading `sleep 0.2` the shell execs the single command, there is no
grandchild, and a naive kill looks correct.

Marker present 2 s later => the grandchild outlived the kill.

Run: python3 p4_kill.py
"""

import asyncio
import os
import signal
import subprocess
import tempfile
import threading
import time

DIR = tempfile.mkdtemp(prefix="tn-o4-")
TIMEOUT = 0.3
GRACE = 0.2


def command(marker):
    return f"sleep 0.2; sh -c 'sleep 1; touch {marker}'"


# --------------------------------------------------------------------------
# blocking bodies (what runs inside the worker thread)
# --------------------------------------------------------------------------
def do_naive(marker, timeout):
    """Exactly today's shape: subprocess.run(shell=True, timeout=...)."""
    try:
        subprocess.run(
            command(marker),
            shell=True,
            timeout=timeout,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
    except subprocess.TimeoutExpired:
        pass


def do_group(marker, timeout, register=None):
    """Popen + start_new_session, killpg TERM -> grace -> KILL."""
    p = subprocess.Popen(
        command(marker),
        shell=True,
        start_new_session=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    if register:
        register(p)
    try:
        p.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        kill_group(p)
        p.wait()


def kill_group(p):
    """TERM, grace, KILL.

    MEASURED CAVEAT (macOS): once the group leader has taken SIGTERM but has not
    been reaped, `os.killpg` on that group raises **PermissionError (EPERM)**,
    not ProcessLookupError. Catching only ProcessLookupError — the obvious
    spelling, and the one spikes/.../s1-orphan/probe.py uses — crashes the kill
    path. Both must be caught, on BOTH signals.
    """
    try:
        os.killpg(p.pid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        return
    time.sleep(GRACE)
    try:
        os.killpg(p.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


# --------------------------------------------------------------------------
# arms
# --------------------------------------------------------------------------
async def arm(name, coro_factory, wait_for_cancel=None):
    marker = os.path.join(DIR, f"{name}.marker")
    if os.path.exists(marker):
        os.remove(marker)
    note = ""
    t0 = time.time()
    task = asyncio.ensure_future(coro_factory(marker))
    if wait_for_cancel is not None:
        await asyncio.sleep(wait_for_cancel)
        task.cancel()
        try:
            await task
            note = "await returned normally after cancel()"
        except asyncio.CancelledError:
            note = "CancelledError raised to the caller"
    else:
        await task
    elapsed = time.time() - t0
    await asyncio.sleep(2.0)
    survived = os.path.exists(marker)
    alive = threading.active_count() - 1
    print(
        f"{name:<22} {'ORPHAN_SURVIVED ' if survived else 'killed_whole_job'}"
        f"  caller_unblocked_at={elapsed:.2f}s  live_worker_threads={alive}  {note}"
    )
    return survived


async def main():
    print(f"== O4 python (py {os.sys.version.split()[0]}, {os.uname().sysname}) ==")

    # CONTROL: no timeout, no cancel. The marker MUST appear.
    control = await arm(
        "control", lambda m: asyncio.to_thread(do_naive, m, None)
    )
    if not control:
        print("!! CONTROL DID NOT SURVIVE — the probe cannot write its marker; "
              "every row below is meaningless")

    # today's timeout path
    await arm("naive-timeout", lambda m: asyncio.to_thread(do_naive, m, TIMEOUT))
    # the candidate's timeout path
    await arm("group-timeout", lambda m: asyncio.to_thread(do_group, m, TIMEOUT))

    # ---- the cancellation dimension ------------------------------------
    # (i) today's shape, cancelled mid-await: to_thread cannot interrupt the
    #     worker, so the thread keeps running subprocess.run to completion.
    await arm(
        "naive-cancelled",
        lambda m: asyncio.to_thread(do_naive, m, None),
        wait_for_cancel=TIMEOUT,
    )

    # (ii) the candidate WITHOUT a cancel hook: same problem — the coroutine is
    #      cancelled, nothing in the thread learns of it, nothing is killed.
    await arm(
        "group-cancelled-nohook",
        lambda m: asyncio.to_thread(do_group, m, None),
        wait_for_cancel=TIMEOUT,
    )

    # (iii) the candidate WITH a handle published out of the thread: the
    #       coroutine's `finally` kills the group it registered. This is the
    #       shape that actually works, and it needs no third-party dependency.
    async def with_hook(m):
        holder = {}
        ready = threading.Event()

        def register(p):
            holder["p"] = p
            ready.set()

        task = asyncio.create_task(
            asyncio.to_thread(do_group, m, None, register)
        )
        try:
            await task
        except asyncio.CancelledError:
            await asyncio.to_thread(ready.wait, 1.0)
            p = holder.get("p")
            if p is not None:
                await asyncio.to_thread(kill_group, p)
            # MEASURED: the worker thread is NOT joined. Cancelling the outer
            # coroutine cancels this task's FUTURE, but nothing can interrupt the
            # thread blocked in communicate(); it unwinds on its own once the
            # group is dead. `live_worker_threads=2` in the output below is that
            # thread, still alive when the arm reports. The job IS killed; the
            # thread is not reclaimed synchronously.
            try:
                await task
            except asyncio.CancelledError:
                pass
            raise

    await arm("group-cancelled-hook", with_hook, wait_for_cancel=TIMEOUT)


asyncio.run(main())
