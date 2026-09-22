"""O2 / python — an empty base_dir must stay byte-identical to today's
process-cwd behaviour, for read/write/edit/glob/grep and for the paths inside
apply_patch's patch text.

The byte-identity claim is about the STRING the port echoes back: `read` puts
the path in its media output line and glob/grep emit paths relative to `root`,
so a resolver that absolutises on an empty base moves a conformance golden even
though the bytes land in the same file.

Run: python3 p2_basedir.py
"""

import os
import re
import tempfile

# candidate resolver — the shape that would land in the port
def resolve_a(base, p):
    if not base:
        return p
    return p if os.path.isabs(p) else os.path.join(base, p)


# the tempting one-liner, measured so the difference is on the record
def resolve_b(base, p):
    return os.path.abspath(os.path.join(base or "", p))


print("== O2 python: empty base must be byte-identical ==")
for p in ["a.txt", "sub/a.txt", "./a.txt", "../a.txt", "/tmp/abs.txt", ".", ""]:
    a = resolve_a("", p)
    b = resolve_b("", p)
    print(
        f"{p!r:<14} join_guard={a!r:<16} identical={'YES' if a == p else 'NO'}  "
        f"abspath={b!r} identical={'YES' if b == p else 'NO'}"
    )

base = tempfile.mkdtemp(prefix="tn-o2-")
# CONTROL: with a base set the resolver must NOT be identity, or the column above
# would read YES for any input.
print(
    "CONTROL base_set_is_not_identity="
    + ("IDENTITY(PROBE BROKEN)" if resolve_a(base, "a.txt") == "a.txt" else "differs(ok)")
)

cwd = tempfile.mkdtemp(prefix="tn-o2-cwd-")
os.chdir(cwd)
os.makedirs(os.path.join(base, "sub"), exist_ok=True)
open(resolve_a(base, "sub/landed.txt"), "w").write("x")
open(resolve_a("", "landed.txt"), "w").write("x")
print(f"LANDED_UNDER_BASE={os.path.exists(os.path.join(base, 'sub/landed.txt'))}")
print(f"LANDED_UNDER_CWD={os.path.exists(os.path.join(cwd, 'landed.txt'))}")

# --- os.path.join's own trap, which the Go/JS joins do not share -------------
# os.path.join(base, "/etc/passwd") DISCARDS base. resolve_a guards with isabs
# first, so measure that the guard is what saves it, not the join.
print(f"JOIN_WITH_ABSOLUTE_DISCARDS_BASE={os.path.join(base, '/etc/passwd')!r}")
print(f"RESOLVE_A_WITH_ABSOLUTE={resolve_a(base, '/etc/passwd')!r}")

# --- apply_patch: the paths are CONTENT, not arguments -----------------------
PATCH = """*** Begin Patch
*** Add File: sub/new.txt
+hello
*** Update File: sub/landed.txt
@@
-x
+y
*** Delete File: sub/gone.txt
*** End Patch"""

# the regex the port SHIPS (python/src/toolnexus/builtin.py:625)
PORT_MARKER = re.compile(r"^\*\*\* (Add|Update|Delete) File: (.+)$")
extracted = [
    (m.group(1), m.group(2).strip())
    for m in (PORT_MARKER.match(l) for l in PATCH.split("\n"))
    if m
]
print(f"PORT_REGEX_EXTRACTED={extracted}")
print(
    "PORT_REGEX_IGNORES_BODY_DECOY="
    f"{PORT_MARKER.match('+*** Add File: decoy.txt') is None}"
)
# The port already parses into ops carrying `path`, so rebasing is one call at
# apply time on op.path — no patch-TEXT rewrite is needed in either port.
print(f"REBASED_OP_PATHS={[(k, resolve_a(base, p)) for k, p in extracted]}")
print(
    "EMPTY_BASE_OP_PATHS_BYTE_IDENTICAL="
    f"{[(k, resolve_a('', p)) for k, p in extracted] == extracted}"
)
