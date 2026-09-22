"""O3 / python — canonicalise BOTH sides and refuse escapes.

  (a) a symlink out of the base
  (b) a path that does not exist yet (the `write` case)
  (c) case normalisation — os.path.realpath does NOT normalise case (confirm)
  (d) what is raised when the path is missing entirely

Every "refused" row has a control: an INSIDE path allowed by the same function.
A check that refuses everything refuses escapes too, and measures nothing.

Run: python3 p3_confine.py
"""

import os
import tempfile

# --- the candidate ---------------------------------------------------------
def canonical(p):
    """realpath the deepest EXISTING ancestor, re-attach the tail."""
    cur = os.path.abspath(p)
    tail = []
    while True:
        if os.path.exists(cur) or os.path.islink(cur):
            return os.path.normcase(os.path.join(os.path.realpath(cur), *tail))
        parent = os.path.dirname(cur)
        if parent == cur:
            return os.path.normcase(os.path.join(cur, *tail))
        tail.insert(0, os.path.basename(cur))
        cur = parent


def contained(base, p):
    cb = canonical(base)
    cp = canonical(p if os.path.isabs(p) else os.path.join(base, p))
    # commonpath, not a string prefix: ".../base2" must not count as inside ".../base"
    try:
        return os.path.commonpath([cb, cp]) == cb
    except ValueError:  # different drives on Windows
        return False


# --- fixture ---------------------------------------------------------------
root = tempfile.mkdtemp(prefix="tn-o3-")
base = os.path.join(root, "base")
outside = os.path.join(root, "outside")
os.makedirs(os.path.join(base, "sub"))
os.makedirs(outside)
open(os.path.join(base, "sub", "in.txt"), "w").write("in")
open(os.path.join(outside, "secret.txt"), "w").write("secret")
os.symlink(outside, os.path.join(base, "link"))  # (a)
os.makedirs(root + "-sibling/base2", exist_ok=True)

print("== O3 python ==")
rows = [
    ("CONTROL inside file", "sub/in.txt", True),
    ("CONTROL the base itself", ".", True),
    ("CONTROL empty string", "", True),
    ("(b) nonexistent file (write)", "sub/brand/new.txt", True),
    ("(b) nonexistent ESCAPE", "../outside/brand/new.txt", False),
    ("relative escape", "../outside/secret.txt", False),
    ("absolute escape", os.path.join(outside, "secret.txt"), False),
    ("(a) via symlink out of base", "link/secret.txt", False),
    ("(a) CONTROL symlink dir itself", "link", False),
    ("sibling-prefix trap", base + "2", False),
]
wrong = 0
for label, p, want in rows:
    try:
        got = contained(base, p)
    except Exception as e:  # noqa: BLE001
        got = f"THREW:{type(e).__name__}"
    ok = got == want
    wrong += not ok
    print(f"{label:<32} contained={str(got):<5} want={want}  {'ok' if ok else 'MISMATCH'}")
print(f"ROWS_WRONG={wrong}")

# --- (c) case normalisation -------------------------------------------------
upper_tail = os.path.join(base, "SUB", "in.txt")
true_cased = os.path.realpath(os.path.join(base, "sub", "in.txt"))
print(f"FS_CASE_INSENSITIVE={os.path.exists(upper_tail)}")
print(f"REALPATH_OF_MIXED_CASE={os.path.realpath(upper_tail)!r}")
print(f"TRUE_CASED_REALPATH={true_cased!r}")
print(f"REALPATH_NORMALISES_CASE={os.path.realpath(upper_tail) == true_cased}")
print(
    "NORMCASE_MAKES_THEM_EQUAL="
    f"{os.path.normcase(os.path.realpath(upper_tail)) == os.path.normcase(true_cased)}"
)
print(f"OS_PATH_NORMCASE_IS_IDENTITY_ON_THIS_OS={os.path.normcase('AbC') == 'AbC'}")
print(f"MIXED_CASE_CONTAINED={contained(base, upper_tail)}")

# --- (d) what is raised when the path is missing entirely -------------------
missing = os.path.join(base, "nope", "deeper", "x.txt")
try:
    r = os.path.realpath(missing)
    print(f"RAW_REALPATH_ON_MISSING={r!r}  raised=NOTHING")
except Exception as e:  # noqa: BLE001
    print(f"RAW_REALPATH_ON_MISSING raised={type(e).__name__}")
try:
    print(f"REALPATH_STRICT_ON_MISSING={os.path.realpath(missing, strict=True)!r}")
except Exception as e:  # noqa: BLE001
    print(f"REALPATH_STRICT_ON_MISSING raised={type(e).__name__}: {e}")
print(f"CANONICAL_ON_MISSING={canonical(missing)!r}")

# --- (c) continued: the case that actually breaks ---------------------------
# normcase is IDENTITY on POSIX (ntpath lowercases; posixpath does not), so on a
# case-INSENSITIVE macOS/APFS volume python has no stdlib way to normalise case.
# Measure the shape that exposes it: the host hands in a differently-cased BASE
# naming the very same directory.
base_mixed = os.path.join(os.path.dirname(base), "BASE")
print(f"\nBASE_MIXED={base_mixed!r}")
print(f"BASE_MIXED_EXISTS={os.path.exists(base_mixed)}")
print(f"CANONICAL_BASE      ={canonical(base)!r}")
print(f"CANONICAL_BASE_MIXED={canonical(base_mixed)!r}")
print(f"CANONICAL_AGREE={canonical(base) == canonical(base_mixed)}")
# CONTROL: the same two strings differ, so an 'agree' above would be real.
print(f"CONTROL_STRINGS_DIFFER={base != base_mixed}")
print(f"INSIDE_FILE_UNDER_MIXED_BASE_CONTAINED={contained(base_mixed, 'sub/in.txt')}")
print(f"REAL_ESCAPE_UNDER_MIXED_BASE_REFUSED={not contained(base_mixed, '../outside/secret.txt')}")
