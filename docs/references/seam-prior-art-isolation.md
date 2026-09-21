# Prior art: the isolation primitives themselves

Research input for the builtin-execution-seam ADR. Companion to
[`builtin-exec-seam-2026-09-22.md`](./builtin-exec-seam-2026-09-22.md), which is the
agreed design this document tests against.

- **Status:** RESEARCH. No code, no ADR, no OpenSpec change. Nothing here is authorised.
- **Date:** 2026-09-22
- **Scope:** the isolation primitives a seven-language library could actually reach —
  macOS Seatbelt, Landlock, seccomp-bpf, namespaces, cgroups, rootless podman/docker,
  gVisor, Firecracker, Deno's permission model, WASI, chroot/jail, and the Windows set.
- **Method:** web research plus **primary measurement on this machine** (macOS 26.4,
  arm64, Docker 29.4.0, Deno 2.8.3). Measured numbers are labelled *measured here*; they
  are single-machine, single-run figures, not benchmarks. Everything else is cited.

> **Headline.** The agreed design survives this review, and the one assertion it stakes
> itself on — that ordering must not be delegated across the seam — is now backed by a
> reproduction rather than an argument. Three corrections follow: the failure taxonomy
> cannot be built on exit codes, `podman run` per call is ~60× a fork+exec and the wrong
> default shape, and "sandboxed" cannot be a boolean.

---

## 0. What was measured here

All figures from macOS 26.4 (Darwin 25.4.0), arm64, Docker Desktop 29.4.0, `alpine`
warm in the local image store.

| operation | measured here |
|---|---|
| `fork+exec /bin/sh -c true` | **5.3 ms/call** |
| `sandbox-exec -f profile … /bin/sh -c true` | **12.4 ms/call** |
| `docker run --rm --network=none alpine true` | **307 ms/call** |
| `docker exec <running> true` | **50.8 ms/call** |

`docker run --rm` is **~58× a fork+exec**; a warm `docker exec` is **~6× cheaper than
`docker run --rm`**. macOS pays the Docker Desktop VM tax on all of these.

---

## 1. What can a LIBRARY portably assume?

**Answer: about confinement, essentially nothing.** There is at most one in-process,
unprivileged, daemon-free isolation primitive per OS family, and Windows does not have one.

| primitive | root? | daemon? | floor | platform | usable *in-process* from a library? |
|---|---|---|---|---|---|
| macOS Seatbelt (`sandbox_init`, `sandbox-exec`) | no | no | 10.5+ | macOS only | **yes** — but **deprecated since ~10.8** |
| Landlock | no | no | **kernel 5.13** (ABI 1); ABI 4 = 6.7; ABI 6 = 6.12 | Linux only | yes, but **thread-scoped before ABI 8** |
| seccomp-bpf | no (needs `NO_NEW_PRIVS`) | no | 3.5 / 3.17 | Linux only | yes (TSYNC covers all threads) |
| user namespaces | no | no | 3.8 | Linux only | **no** — `CLONE_NEWUSER` fails `EINVAL` in a multithreaded process |
| cgroups v2 limits | yes, unless delegated | **systemd** | 4.15 + systemd 244 | Linux only | no |
| rootless podman / docker | no, but **admin pre-setup** | podman no / docker **yes** | subuid + setuid `newuidmap` | Linux (macOS = a VM) | no — shell out only |
| gVisor / runsc | effectively | no | **Linux 5.6+** | Linux only | no |
| Firecracker | needs `/dev/kvm` | no | KVM | Linux only | no |
| WASI / wasmtime | no | no | none | **all three** | **yes — and it cannot spawn a process** |
| chroot | **yes** (`CAP_SYS_CHROOT`) | no | — | Linux | no |
| Windows job object | no | no | Win8 (nesting) | Windows | yes, but irreversible and hits the host |
| Windows restricted token / AppContainer / LPAC | no | no | XP / Win8 / 1703 | Windows | **no — spawn-time only** |
| Windows Sandbox | **yes** | Hyper-V | 1903+, **not Home** | Windows | no API |

Sources: [Landlock ABI table](https://docs.kernel.org/userspace-api/landlock.html) ·
[`seccomp(2)`](https://man7.org/linux/man-pages/man2/seccomp.2.html) ·
[systemd CGROUP_DELEGATION](https://systemd.io/CGROUP_DELEGATION/) ·
[podman rootless tutorial](https://github.com/containers/podman/blob/main/docs/tutorials/rootless_tutorial.md) ·
[Docker rootless](https://docs.docker.com/engine/security/rootless/) ·
[gVisor install](https://gvisor.dev/docs/user_guide/install/) ·
[Firecracker getting started](https://github.com/firecracker-microvm/firecracker/blob/main/docs/getting-started.md) ·
[Wasmtime security](https://docs.wasmtime.dev/security.html) ·
[`chroot(2)`](https://man7.org/linux/man-pages/man2/chroot.2.html) ·
[job objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects) ·
[`CreateRestrictedToken`](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-createrestrictedtoken) ·
[`CreateAppContainerProfile`](https://learn.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-createappcontainerprofile)

### The traps that matter for *us* specifically

- **seccomp cannot do paths.** BPF cannot dereference userspace pointers, so it can filter
  `openat` but never *which file*. Any pointer-argument check is inherently TOCTOU-racy
  ([kernel docs](https://www.kernel.org/doc/html/latest/userspace-api/seccomp_filter.html)).
  seccomp and Landlock are complements; neither alone is a filesystem boundary.
- **Landlock is not assumable even on a new kernel.** It is a stackable LSM and must be in
  `CONFIG_LSM` or the `lsm=` boot parameter. Debian enabled it from 5.18.16-1
  ([#999551](https://bugs.debian.org/999551), originally `wontfix`, reversed); Fedora and
  Arch are on; **Ubuntu's request expired unresolved in April 2026**
  ([LP #1950381](https://bugs.launchpad.net/ubuntu/+source/linux/+bug/1950381)). The
  probe — `landlock_create_ruleset(NULL, 0, …VERSION)` — distinguishes `-ENOSYS` (not
  built) from **`-EOPNOTSUPP` (built but not enabled at boot)**. Version-checking is wrong;
  probing is the only correct move.
- **Unprivileged userns is being closed.** Ubuntu 24.04 LTS restricts it by default via
  AppArmor ([Ubuntu blog](https://ubuntu.com/blog/ubuntu-23-10-restricted-unprivileged-user-namespaces)),
  and the DISA STIG for RHEL 9 *requires* `user.max_user_namespaces=0`
  ([V-257816](https://www.stigviewer.com/stigs/red_hat_enterprise_linux_9/2025-02-27/finding/V-257816)).
  Hardened enterprise hosts — exactly the ones that want a sandbox — are the ones where
  rootless podman will not start.
- **WASI is the only portable in-process sandbox, and it is useless to us.** Deny-by-default,
  capability-based, identical on all three OSes — and **no `fork`, no `exec`, no
  `posix_spawn`, at any phase**. There is no process-spawning proposal in
  [WASI Proposals.md](https://github.com/WebAssembly/WASI/blob/main/docs/Proposals.md).
  A `bash` builtin cannot exist inside WASI. Also fatal for us specifically: Wasmtime is
  tier-1 for Rust/C/C++ only; **Java has no official binding and JS/TS has none at all** —
  "one wasmtime everywhere" would mean integrating four or five different runtimes across
  our seven ports.

### Seatbelt is deprecated, present, and load-bearing — verified here

*Measured here*, macOS 26.4: `/usr/bin/sandbox-exec` exists, and `man sandbox-exec`
opens with `execute within a sandbox (DEPRECATED)`, man page dated **March 2017**. It works.

But SBPL is not API and has never been documented for third-party use
([Rowe, "Sandboxing on macOS"](https://bdash.net.nz/posts/sandboxing-on-macos/)), and it
drifts per release — a syntax error in my own test surfaced Apple's *internal* profile
prelude (`(defined? 'APFSIOC_GET_GRAFT_INFO)`), which is a fair picture of how much of this
is private implementation detail. Two failure modes a library would inherit:

- **Nesting fails.** A process already under a Seatbelt policy cannot apply another —
  `sandbox_apply: Operation not permitted`
  ([openai/codex#45657](https://github.com/openai/codex/issues/45657)). Our host may
  already be sandboxed (Claude Code, Codex, any App-Sandboxed app). That is not a degraded
  mode, it is a hard failure.
- **macOS 26 is actively breaking consumers** ([claude-code#55849](https://github.com/anthropics/claude-code/issues/55849)).

**The realistic floor.** A library, with no root, no daemon, and Windows in scope, can
guarantee only:

1. **Process-tree lifecycle control** — kill the whole group. Windows job object with
   `KILL_ON_JOB_CLOSE` and no breakaway; POSIX process group / `setsid`. This is the only
   confinement-adjacent thing that works everywhere with no privilege.
2. **Deny-by-default policy in our own layer** — argv not a shell string, a working
   directory, an env allowlist, a timeout, output caps. This is the Deno model, and like
   Deno's it is **not** an OS boundary.
3. **Determinism we own outright** — ordering, truncation, schema, error shape. See §4.
   This is the one thing on the list we can actually promise.

Prior art confirming the floor: [Birdcage](https://github.com/phylum-dev/birdcage), an
explicitly "cross-platform embeddable sandboxing" library, supports **Linux and macOS only —
Windows is absent** — and still warns it "is not a complete sandbox preventing all
side-effects or permanent damage."

---

## 2. Where is the boundary normally drawn?

**In the process launcher, owned by the host application — never inside the library.**
Three independent lines of evidence:

- **Chromium**: "anything that needs to be sandboxed needs to live on a separate process"
  ([sandbox design](https://chromium.googlesource.com/chromium/src/+/HEAD/docs/design/sandbox.md)).
  Restricted token + job object + alternate desktop + integrity level + AppContainer,
  applied at `CreateProcess` time, with a several-thousand-line broker.
- **Windows makes it structural.** Restricted tokens, integrity levels, AppContainer and
  LPAC are all *spawn-time* attributes. There is no "confine me now" call. The single
  in-process API, `SetProcessMitigationPolicy`, is one-way and would break the host —
  `DynamicCode` kills every JIT, which is .NET, Node and the JVM, i.e. three of our ports.
- **Deno**, the closest thing to a library-level capability seam in wide use, draws it at
  the runtime and then documents that the boundary ends at the process edge (§7).

The reason is not convention, it is arithmetic: confinement primitives are **irreversible
and process-wide**. A library that applied one would mutate its host permanently, and a
library cannot know what else the host process needs to do. That is why the agreed design's
shape — a host-supplied `BuiltinExec`, nil by default — is the correct one and matches how
everyone else has solved this.

---

## 3. Cost of crossing

| | latency | vs in-process |
|---|---|---|
| in-process syscall (`os.ReadFile`) | ~1–10 µs; raw syscall 100–800 ns ([gms.tf](https://gms.tf/on-the-costs-of-syscalls.html)) | 1× |
| seccomp filter overhead | **~20–30 ns/syscall** ([LWN 834056](https://lwn.net/Articles/834056/)) | free |
| `fork+exec` bare (Linux) | ~0.37 ms; `/bin/sh -c` ~1.57 ms ([lmbench](https://lmbench.sourceforge.net/man/lmbench.8.html)) | ~10²× |
| `fork+exec /bin/sh -c` (macOS) | **5.3 ms — measured here** (macOS is ~10× Linux, [bitsnbites](https://www.bitsnbites.eu/benchmarking-os-primitives/)) | ~10³× |
| `sandbox-exec` + sh (macOS) | **12.4 ms — measured here** (~2.3× plain exec) | ~10³× |
| `docker exec` into a warm container (macOS) | **50.8 ms — measured here** | ~10⁴× |
| `docker run --rm` (macOS, Docker Desktop) | **307 ms — measured here**; 568 ms Linux/SSD, 1528 ms macOS ([arXiv 2602.15214](https://arxiv.org/html/2602.15214)) | ~10⁵× |
| `podman run --rm` (Linux) | rootless **407 ms**, rootful **993 ms**; docker 556 ms ([nerdctl#761](https://github.com/containerd/nerdctl/discussions/761)) | ~10⁵× |
| gVisor syscall tax | ptrace ~20× native; KVM 0.39× ([gVisor perf](https://gvisor.dev/docs/architecture_guide/performance/)) | — |
| Firecracker boot | **125 ms** claimed ([NSDI '20](https://www.usenix.org/system/files/nsdi20-paper-agache.pdf)) — but **~350 ms end-to-end, slowest hypervisor measured** ([arXiv 2110.11462](https://arxiv.org/pdf/2110.11462)) | — |

### Where the time goes — and it is not the hardening

The decomposition study ([arXiv 2602.15214](https://arxiv.org/html/2602.15214)) instruments
a warm `docker run`: **namespace creation 7.94 ms (<1.5% of total)**, **bridge-vs-host
networking 0.04–0.06 ms (negligible)**. The ~500 ms is client→daemon→runc plumbing plus
rootfs prep and teardown; going through the Docker daemon rather than calling the OCI
runtime directly costs **~250 ms on its own** ([arXiv 2110.11462](https://arxiv.org/pdf/2110.11462)).

**So `--network=none --read-only --cap-drop=ALL` are essentially free.** They buy security,
not latency. Keep every one of them. The cost is the *container lifecycle*, not the
hardening.

### ⚠️ This contradicts the agreed design's target

The design names `podman run --rm …` **per call**. At the measured 307 ms (macOS) to
~400–1000 ms (Linux, depending on rootless/rootful and storage driver), a 30-call agent run
pays **9–30 seconds of pure overhead**. A warm `docker exec` is 50.8 ms — *measured here*,
and notably the one number the published record does not contain.

Every published design that cares about this converges on the same answer: **boot one
sandbox per agent session, `exec` per tool call.** AWS Lambda pre-boots a Firecracker pool
rather than boot per invocation, *despite* the 125 ms figure being theirs
([NSDI '20](https://www.usenix.org/system/files/nsdi20-paper-agache.pdf)). Quark measures
cold 563 ms vs keep-warm 1.4 ms ([arXiv 2309.12624](https://arxiv.org/pdf/2309.12624)).
Blaxel states the budget plainly: "Five tool calls at 50 ms each complete in 250 ms, but
the same steps in a 1.5-second sandbox take over seven seconds"
([Blaxel](https://blaxel.ai/blog/sandboxes-for-coding-agents-comparison)).

**This is not a reason to change the seam — it is a reason the seam is right.** A
`BuiltinExec` function is agnostic to whether the host implements it as `run --rm` or as
`exec` into a warm container. The correction is to the *documentation*: `podman run --rm`
should be shown as the simplest illustration, and a persistent-container `exec` as the
recommended production shape. The ADR should not hard-code a per-call `run`.

One cliff worth naming in the docs: podman on the **`vfs`** storage driver measured
**2 m 15 s vs docker's 0.636 s** for the same trivial container
([podman#13226](https://github.com/containers/podman/issues/13226)). That is a 200× cliff,
not a 2× one, and a host can hit it by accident.

---

## 4. Does the filesystem view change ordering and enumeration?

**Yes. This is the crux, and it reproduces.**

### The reproduction (measured here)

300 files, `f001.txt … f300.txt`, created in **identical order** on four backing stores.
Raw `readdir` order (via `find -maxdepth 1`, which does not sort), first 10:

| backing store | first 10 entries |
|---|---|
| host **APFS** (macOS 26.4) | `f277 f263 f288 f049 f075 f061 f129 f101 f115 f114` |
| same dir **via virtiofs bind mount** into the Linux VM | `f277 f263 f288 f049 f075 f061 f129 f101 f115 f114` |
| container **overlayfs** (image layer) | `f001 f002 f003 f004 f005 f006 f007 f008 f009 f010` |
| container **tmpfs** | `f300 f299 f298 f297 f296 f295 f294 f293 f292 f291` |

**Three mutually disjoint answers to "the first 10 files", from the same 300 names.** A cap
of 10 shows the model a completely different file set depending on where the bytes happen
to live. On a 10-name tree with mixed case and non-ASCII, tmpfs returned the **exact
reverse** of the host order.

Note what this does and does not say:

- **The transport is not the variable.** virtiofs relayed APFS's order byte-for-byte. A
  bind mount preserves order because it is the same superblock and the same inodes
  ([mount(8)](https://man7.org/linux/man-pages/man8/mount.8.html),
  [shared subtrees](https://docs.kernel.org/filesystems/sharedsubtree.html)).
- **The backing store is the variable.** And crossing into a sandbox is *precisely* the act
  of changing the backing store, because everything not bind-mounted is the image's
  overlayfs or a tmpfs.

### Why this is structural, not incidental

- **POSIX never specified it.** `readdir()` is defined only as "an ordered sequence", with
  no constraint on which order ([POSIX](https://pubs.opengroup.org/onlinepubs/9699919799/functions/readdir.html)).
- **ext4 hashes, with a per-filesystem salt.** With `dir_index` (the default), traversal is
  hash order ([ext4 directory docs](https://docs.kernel.org/filesystems/ext4/directory.html)),
  and the hash is salted by the superblock's `s_hash_seed`, randomized by `mke2fs`. So the
  same names in two ext4 filesystems enumerate differently. This caused a documented
  production outage — a classloader picked the wrong Bouncy Castle jar — proven by
  hex-editing the seed until the orders matched ([thewisenerd](https://thewisenerd.com/blog/ext4-readdir/)).
  A container image is a different filesystem from the host *by definition*. OpenZFS has
  the identical per-object-salted property ([zap.c](https://github.com/openzfs/zfs/blob/master/module/zfs/zap.c)).
- **overlayfs re-orders by construction.** The kernel emits from a per-layer append list,
  and `ovl_dir_read_merged` deliberately hoists lowest-layer entries first — in-tree
  comment: *"Insert lowest layer entries before upper ones, this allows offsets to be
  reasonably constant."* Offsets are synthetic and "assigned sequentially when the
  directories are read"
  ([overlayfs docs](https://www.kernel.org/doc/html/latest/filesystems/overlayfs.html),
  [readdir.c](https://github.com/torvalds/linux/blob/master/fs/overlayfs/readdir.c)).
  Merged order is therefore not any underlying directory's order — **no VM required**.
  `fuse-overlayfs` (rootless podman) is an independent reimplementation with its own
  merge, so it is not obliged to match the kernel driver either.
- **gVisor is worse than "different" — it is non-deterministic.** `pkg/sentry/fsimpl/gofer/directory.go`
  emits remote children, then *synthetic* children by ranging **a Go map**, whose iteration
  order Go deliberately randomizes. No sort is applied, and dirents are cached per-FD
  ([source](https://github.com/google/gvisor/blob/master/pkg/sentry/fsimpl/gofer/directory.go)).
  Under gVisor the same directory can enumerate differently **run to run on one machine**.
- **APFS deliberately stopped being sorted.** HFS+ returned names lexicographically after
  normalization; APFS keys its B+tree on a *hash* of the normalized name. Apple bug
  rdar://32799008 is titled "`readdir` on APFS is not sorted"
  ([openradar](http://openradar.appspot.com/32799008),
  [Oakley](https://eclecticlight.co/2024/03/25/apfs-directories-and-names/)).
- **NTFS looks alphabetical and Microsoft disclaims it**: "The order in which the search
  returns the files, such as alphabetical order, is not guaranteed, and is dependent on the
  file system" ([FindNextFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-findnextfilew)).

### Enumeration, not just ordering

Two ways the candidate *set* changes, which sorting cannot fix and which belong in the
ADR's non-goals:

- **macOS case-insensitivity.** Docker's own docs: "If a file system on macOS is
  case-insensitive, that behavior is shared by any bind mount from macOS into a container"
  ([osxfs](https://docker-docs.uclv.cu/docker-for-mac/osxfs/),
  [for-mac#320](https://github.com/docker/for-mac/issues/320)). `xt_CONNMARK.h` and
  `xt_connmark.h` **collide and lose an entry**. APFS also normalizes Unicode, so
  names distinct on ext4 are one entry on APFS.
- **9p/virtiofs completeness.** 9p's offset-cookie scheme can emit **duplicate** entries
  across a split read and drop entries on concurrent mutation; `multidevs` leaks entries
  from other devices ([qemu-devel](https://lists.gnu.org/archive/html/qemu-devel/2019-09/msg00923.html),
  [kata#378](https://github.com/kata-containers/kata-containers/issues/378)); 9p can drop
  `d_type` ([Debian #755738](https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=755738));
  virtiofs `cache=auto|always` can serve stale listings
  ([for-mac#7501](https://github.com/docker/for-mac/issues/7501)).

### The parity hazard is already inside our own ports

*Measured here*, same APFS directory, 300 files, first 5 by each language's **native**
listing call:

| call | first 5 | sorts? |
|---|---|---|
| Python `os.listdir` / `os.scandir` | `f277 f263 f288 f049 f075` | **no** — "arbitrary order" ([docs](https://docs.python.org/3/library/os.html#os.listdir)) |
| Go `f.Readdirnames` | `f277 f263 f288 f049 f075` | **no** — "in directory order" |
| Go `os.ReadDir` | `f001 f002 f003 f004 f005` | **yes, by contract** ([pkg.go.dev/os#ReadDir](https://pkg.go.dev/os#ReadDir)) |
| Node `fs.readdirSync` | `f001 f002 f003 f004 f005` | **yes — but by accident** |
| Java `File.list()` | — | **no** — "not guaranteed to appear in alphabetical order" ([javadoc](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/io/File.html)) |

**Correction to a note in our own memory index.** "Node's `readdirSync` IS sorted" is
mechanically true on POSIX and dangerous to rely on. libuv routes it through `scandir(3)`
with `uv__fs_scandir_sort`, a plain `strcmp` comparator
([src/unix/fs.c](https://github.com/libuv/libuv/blob/v1.x/src/unix/fs.c)). But it is
**undocumented**, it is **false on Windows** — the Windows path uses `NtQueryDirectoryFile`
([nodejs/node#3232](https://github.com/nodejs/node/issues/3232)) — and it is **false for
`fs.opendir`/`dir.read()`**, so a mechanical refactor to the streaming API silently changes
ordering. It should be restated as an accident we do not depend on.

### Verdict: the agreed design is right, and the spike will pass

Spike 1 ("ordering survives the boundary") is the correct spike and the design's
justification for it holds. The one refinement: the design says *"if a container has to
implement our ordering rule to pass, the seam is in the wrong place."* The evidence says
something stronger — **no container could implement it reliably even if asked**, because
under gVisor the enumeration order is not stable run-to-run on a single machine. Sorting in
the library is not merely the cleaner allocation of responsibility; it is the only
allocation that can work.

Make the spike's hostile-order case concrete: **have the fake seam return entries in
reverse**. That is not a contrived adversary — it is literally what tmpfs did here.

---

## 5. Failure taxonomy as the runtimes actually report it

*Measured here*, Docker 29.4.0:

| scenario | exit code |
|---|---|
| no such image | **125** |
| **app itself runs `exit 125`** | **125** |
| command found but not executable | 126 |
| command not found | 127 |
| OOM kill (`-m 16m`, runaway allocation) | 137 (128+SIGKILL) |

The documented reservations hold — 125 = the runtime failed, 126 = not executable,
127 = not found, 128+N = killed by signal N. **But the first two rows are the finding:
"the sandbox could not run this" and "the command ran and chose to exit 125" are
indistinguishable by exit code.** The same collision exists at 126 and 127, and 137 is
ambiguous between an OOM kill, an external `kill -9`, and a timeout enforced by SIGKILL.

⚠️ **This is a direct, concrete risk to the agreed design's three-failure-kind requirement.**
The design says the seam must distinguish "could not run at all" from "ran and failed"
because collapsing them breaks a consumer's retry policy (wfnexus ADR 0012). That
requirement is right — and **it cannot be met by inspecting an exit code.** The seam's
contract must therefore put the classification on the *host implementation*, which knows
things the exit code does not: whether it issued an image pull, whether it set a timeout
and the deadline passed, whether `docker inspect` reported `State.OOMKilled`, whether the
runtime binary was even present.

Concretely, `BuiltinResult` should carry a **host-set discriminator** — something like
`Outcome ∈ {Ran, CouldNotRun, Killed}` alongside `ExitCode` — rather than asking the
library to infer the kind from the code. And `CouldNotRun` should be the value the host
returns when it *knows*, with the honest default being `Ran`, because a library that
guesses "125 means infrastructure" will misclassify a program that exits 125.

### How a host *can* classify correctly — worth putting in the ADR's guidance

The three facts need three independent channels. None of them is the exit code.

1. **Ran vs could-not-run — use the API, not the CLI.** `docker run` fuses two channels
   into one integer. `docker create` → `start` → **`docker wait`** does not: `wait` prints
   only the container's own code, and a daemon-side failure yields no container ID from
   `create` at all. Structural markers: `State.Error != ""` — moby's own comment says it
   "contains last known error during container start, stop, or remove" — and
   `State.StartedAt == "0001-01-01T00:00:00Z"`, meaning it never started
   ([ContainerState swagger](https://github.com/moby/moby/blob/master/api/swagger.yaml),
   [state.go](https://github.com/moby/moby/blob/master/daemon/container/state.go)).
   Note how fragile the alternative is: docker's CLI derives 126/127 by
   **`strings.Contains`** on the daemon's error text, defaulting to 125
   ([run.go `toStatusError`](https://github.com/docker/cli/blob/master/cli/command/container/run.go)).
2. **OOM — read `memory.events`, not `State.OOMKilled`.** `State.OOMKilled` is unreliable
   in *both* directions and has been for a decade: false negative when the OOM killed a
   child rather than PID 1 ([moby#15621](https://github.com/moby/moby/issues/15621),
   closed *not planned*), kernel-version-dependent
   ([moby#38352](https://github.com/moby/moby/issues/38352) — same Docker, `true` on 3.10
   and `false` on 4.19), never set on Windows
   ([moby#39611](https://github.com/moby/moby/issues/39611)), and containerd's own source
   documents the race producing "flaky or missing OOMKilled detection"
   ([events.go](https://github.com/containerd/containerd/blob/main/internal/cri/server/events.go)).
   The dependable signal is cgroup v2's `memory.events` **`oom_kill`** — "the number of
   processes belonging to this cgroup killed by any kind of OOM killer"
   ([cgroup-v2 docs](https://docs.kernel.org/admin-guide/cgroup-v2.html#memory-interface-files)) —
   snapshotted before and diffed after. `OOMKilled == true` is sufficient evidence;
   **137 without it is undecidable.**
3. **Timeout — your own monotonic clock.** A deadline kill, an OOM kill and an operator
   `docker kill` are the same observable: SIGKILL → 137. Nothing at the runtime layer
   records *why*. (And wrapping in GNU `timeout` makes it worse, not better: its 125/126/127
   collide exactly with docker's, and with `--kill-after` a timed-out process reports
   **137, not 124**.)

**containerd is the shape to copy.** It never fuses the channels: task-*create* failure is
a gRPC error (`codes.NotFound`, via [errdefs](https://github.com/containerd/errdefs/blob/main/errors.go)),
while task *exit* is an event that is never an error
([task.proto](https://github.com/containerd/containerd/blob/main/api/events/task.proto)).
That is exactly the `error` vs `BuiltinResult` split the seam signature already has — the
correction is only that the *kind* must be a field the host fills, not something we decode.

One cautionary precedent for inferring from numbers: in Kubernetes, **exit 128 means
"failed to start"** (`StartError`), the precise opposite of the shell's 128+N reading, and
only the `reason` string disambiguates
([containerd helpers.go](https://github.com/containerd/containerd/blob/main/internal/cri/server/helpers.go)).
Relatedly, `state.terminated.signal` is always 0 on modern clusters — the CRI
`ContainerStatus` proto has no signal field at all.

**macOS Seatbelt is worse still, and this matters because it is the only macOS option.**
*Measured here*: a denied read under a `deny file-read-data` profile surfaces as plain
`EPERM` — `cat: secret.txt: Operation not permitted`, `cat` exits 1, and the
`sandbox-exec` wrapper exits **0**. A policy violation is therefore invisible at the
process-exit level and looks exactly like an ordinary command failure. (A *malformed
profile*, by contrast, exits **65**, and a profile that denies something the dynamic
loader needs aborts the child with **SIGABRT → 134**.) So on macOS the host cannot classify
from exit status either — it must read stderr, or consult the system log.

The library-side conclusion is the same in both cases and is the one the design already
reached under rule #89: **shape, don't infer.** Define the three kinds in the seam's type,
require the host to fill them in, and document that a host which cannot tell must say
`Ran`.

---

## 6. The escape our consumer hit

`workdir` set correctly; the agent ran `cd /elsewhere && git …`; it succeeded.

*Measured here*, reproducing both halves on macOS 26.4:

```
# cwd set to work/, command: cd ../outside && cat secret.txt
$ (cd work; /bin/sh -c 'cd ../outside && cat secret.txt')
secret                                    # exit 0 — the escape
```

```
# same command, under a Seatbelt profile denying reads outside WORK
$ sandbox-exec -f p.sb -D WORK=$PWD/work /bin/sh -c 'cd ../outside && cat secret.txt'
cat: secret.txt: Operation not permitted  # blocked
```

The write half behaves identically: under `deny file-write*` with an allow-subpath for the
worktree, a write inside succeeds and `echo > ../outside/pwn.txt` fails
`Operation not permitted`. **Unprivileged, no daemon, no container.**

| mechanism | stops the escape? | why |
|---|---|---|
| `workdir` / cwd only | **no** | sets only the *initial* directory; `cd` leaves it |
| reject `cd` / `pushd` / `git -C` in the string | **no — guardrail** | see below |
| chroot | **no**, and needs root | its own man page disclaims it: *"it is not intended to be used for any kind of security purpose, neither to fully sandbox a process nor to restrict filesystem system calls"*, and then prints the escape — `mkdir foo; chroot foo; cd ..;` ([chroot(2)](https://man7.org/linux/man-pages/man2/chroot.2.html)). Worst of both: requires root *and* does not hold |
| running as another unprivileged UID | **partial** | DAC answers "may this UID touch this file", never "may this command touch this directory" — there is no subject-scoped path domain in the model. Blocks writes to files it does not own; leaves **every world-readable file** (`/etc/passwd`, sibling repos at 0755/0644, `~/.aws/config`) and all network egress open — which for a code agent is most of the actual damage. Zero value if it reuses the invoking user's UID |
| seccomp alone | **no** | the kernel is explicit: *"BPF programs may not dereference pointers, which constrains all filters to solely evaluating the system call arguments directly"* ([seccomp_filter](https://docs.kernel.org/userspace-api/seccomp_filter.html)). A pathname arrives as a `const char *`; the filter sees the pointer, never the bytes. `seccomp_unotify` does not rescue it — its own man page says it "can not be used to implement a security policy" |
| **Landlock** | **yes** | path-hierarchy/inode based, applies to the whole process tree, inherited across `exec`, unprivileged. Note the mechanism: Landlock explicitly **cannot** restrict `chdir(2)` — it is in the CAVEATS list — and does not need to, because rights attach to the *hierarchy*, not to where the process is standing. The `cd` succeeds and every subsequent `open` fails. Limits: files opened *before* sandboxing are not restricted; no network control before ABI 4; ABI detection mandatory |
| **macOS Seatbelt deny-by-default** | **yes — verified above** | kernel-enforced via TrustedBSD MAC, unescapable even by root inside the profile. But deprecated, undocumented profile language, cannot nest — and note one real hole: a unix-domain socket `connect()` is classified `network-outbound`, **not** a file operation, so `deny file-write*` on `/var/run/docker.sock` does not block reaching it |
| **mount namespace + pivot_root, only the worktree mounted** | **yes** | the strongest: the target is not *in the namespace*, so there is nothing to `cd` into. Must also: `mount --make-rprivate` (else `pivot_root` returns EINVAL under systemd's shared `/`), close inherited fds (an fd from before survives and `fchdir` walks back out), and mount a fresh procfs in a new PID namespace — a visible host `/proc` makes `/proc/<pid>/root/` a live doorway back for any same-UID process |
| **container with only the worktree bind-mounted** | **yes** | the same mechanism, packaged — and what the agreed design proposes |

**The string-level guardrail is theatre, and the consumer's own ADR 0006 says so.** The
bypass list is unbounded, and one entry alone ends the argument: **`env -C dir cmd`**, in
coreutils on every box, whose manual advertises the property as a feature — it "differs
from the shell built-in `cd` in that it starts *command* as a subprocess… this allows it to
be chained with other commands that run commands in a different context"
([coreutils](https://www.gnu.org/software/coreutils/manual/html_node/env-invocation.html)).
A blocklist that misses `env -C` blocks nothing.

> **CORRECTION (2026-09-22), and the better evidence.** `env -C` was a bad example, and it
> was *our* error: wfnexus's tokeniser matches a bare `-C` token whatever command precedes
> it, so `env -C`, `make -C` and `tar -C` were all already denied. We inferred the deny list
> from the prose of their ADR ("cd, pushd, git -C, --git-dir, --work-tree") rather than from
> their code. The lesson is narrow — do not infer an implementation from its write-up — but
> the principle survives in a much stronger form, because they then PROBED their own
> guardrail instead of reasoning about it, and found five escapes that were open in shipped
> public code:
>
> ```
> cat /etc/passwd                                    ALLOWED
> echo pwned > /tmp/pwned                            ALLOWED
> rsync -a . /tmp/exfil/                             ALLOWED
> find / -name secret -execdir cat {} \;             ALLOWED
> python3 -c "import os; os.chdir('/etc'); print(open('passwd').read())"   ALLOWED
> ```
>
> **None of these changes directory.** The guardrail scanned for directory *changes*, so it
> could not see the category next door: `rsync -a . /tmp/exfil/` is one line, needs no `cd`,
> and walks the entire workspace out. Every `-C`-style variant is a near miss on a scanner
> that is blind to exfiltration entirely.
>
> This is the strongest available statement of the rule: the failure was not a missing verb,
> it was a missing *category*, and no amount of verb-hunting would have found it. Their fix
> (deny any absolute path resolving outside the workspace, with a read-only allowance for
> `/usr`, `/bin`, `/lib`, `/System` and Homebrew so an interpreter is reachable) is itself
> escape-enumeration one level down, and their ADR says so — the table means "these five
> holes are closed", not "it is safe now". Worked example:
> <https://github.com/muthuishere/wfnexus> ADR 0006.

Beyond it: git exposes the same capability through **three independent channels** — the
`--git-dir`/`--work-tree` flags, the `GIT_DIR`/`GIT_WORK_TREE` environment variables, *and*
`core.worktree` in config ([git(1)](https://git-scm.com/docs/git)) — so `git checkout`
against an arbitrary `--work-tree` is a destructive write with no `cd` and no `-C` anywhere.
Then `tar -C` (position-sensitive, so one command line hops between several directories),
`make -C` (relative and accumulating), `find -execdir` ("run from the subdirectory
containing the matched file, which is *not* normally the directory in which you started
find"), `install -D`, `rsync --mkpath`, a symlink, `python -c "import os; os.chdir(...)"`,
nested `sh -c`, command substitution — or simply an absolute path and never changing
directory at all. (`GIT_CEILING_DIRECTORIES` is not a control, incidentally: it is a
performance knob that explicitly does not exclude a `GIT_DIR` set on the command line or in
the environment.)

**The structural reason**, worth stating in the ADR in one line: *every* mechanism in the
"no" column works by enumerating **escapes**; every mechanism in the "yes" column works by
enumerating **inclusions**. Only the second is closed by construction. Under a mount
namespace the outside path returns **ENOENT, not EACCES** — it does not exist.

Deno makes the same admission about the same category:
`--allow-run="sh"` is `--allow-all`, and Secfault demonstrated bypassing `--deny-run` via
shell metacharacters through `["sh","-c",…]`
([Secfault](https://secfault-security.com/blog/deno.html)).

**This confirms the design's central claim** — "no host can fix this from outside the
tool" — and confirms that the mechanism it picked (a mount namespace containing only the
worktree) is one of the three that actually work rather than one that appears to.

**One addition the design should make.** Seatbelt is a *real* answer on macOS, verified
here, and it is ~12.4 ms rather than ~307 ms. A host on a Mac with no container runtime is
not out of options. It should not be the library's job to drive it — but the seam's
documentation should name it as a legitimate host implementation, because otherwise the
design reads as "container or nothing", and on developer Macs that means "nothing".

---

## 7. Deno's permission model

The closest thing to a library-level capability seam in wide use, and the most useful prior
art here because it is unusually honest about where its guarantee stops.

**What it got right:**

- **Deny-by-default with granular scoping**: `--allow-read=foo.txt`, `--allow-net="*.example.com"`,
  `--allow-env="AWS_*"`, `--allow-run="curl,whoami"`
  ([reference](https://docs.deno.com/runtime/reference/permissions/)).
- **Deny beats allow**, explicitly: `--deny-*` "take precedence over the allow flags", so
  `--allow-read --deny-read=/etc` is expressible.
- **Permissions can only narrow at runtime, never widen.** `Deno.permissions` exposes
  `query` / `request` / `revoke`; `revoke` downgrades to `prompt`. There is no API that
  grants. **This is the property worth copying into our seam**: a host-supplied executor
  should never be able to be widened by the model or by the library.
- **It names its escape hatches instead of hiding them**, and gates them separately.
- A nice specific: spawning with `LD_*` / `DYLD_*` env vars requires *unscoped*
  `--allow-run`, because those variables inject code into the child. A scoped allowlist is
  refused rather than silently defeated.

**What it admits it cannot do** — from Deno's own
[security docs](https://docs.deno.com/runtime/fundamentals/security/), verbatim:

> "A subprocess runs as a separate program with its own permissions, not the restricted set
> you granted the Deno process, so whatever it does happens outside the sandbox."

> "`--allow-run=deno` is especially dangerous: a script that can start a new deno process
> can start it with `--allow-all`."

> Of FFI: "a native library runs as compiled machine code in the same process and can issue
> system calls directly… **regardless of which `--allow-*` flags you passed**."

And the escapes that shipped anyway: symlink checks apply to the link, not the target
([#2318](https://github.com/denoland/deno/issues/2318)); a demonstrated TOCTOU race against
`Deno.chdir()` yielding arbitrary file read; `Deno.makeTemp*` path traversal
([GHSA-23rx-c3g5-hv9w](https://github.com/denoland/deno/security/advisories/GHSA-23rx-c3g5-hv9w));
and CVE-2024-34346, where read access to `/proc/self/environ` ≈ `--allow-env` and **write
access to `/proc/self/mem` ≈ `--allow-all`** — Secfault overwrote a V8 builtin with
shellcode and got execution with no `--allow-run`. The 1.43 fix was a **path blocklist**
for `/etc`, `/dev`, `/proc`, `/sys` — the same class of defence that had already failed for
symlinks. `/proc/self/root/etc/passwd` defeated `--deny-read=/etc/passwd` by the same
normalization gap.

**The lesson.** Deno is an entire runtime, with control of every syscall entry point, a
security team and a CVE process — and it still voids its own sandbox at the subprocess and
FFI boundary, by documented design. **A library inside someone else's process has strictly
less control than Deno has.** Our `bash` builtin is precisely Deno's `--allow-run`: the
place where the model ends.

The defensible position is Deno's own, stated at Deno's volume: *this is a policy layer
that prevents accidents, not a security boundary against hostile code* — and the real
boundary is whatever the host puts behind the seam.

---

## What contradicts the agreed design

Three items, none fatal; two are documentation, one is a type-shape correction.

1. **`podman run --rm` per call is the wrong default to advertise** (§3). 307 ms measured
   here vs 50.8 ms for a warm `exec`; ~60× a fork+exec. The seam is unaffected — it is
   agnostic — but the ADR should present a persistent container + `exec` as the
   recommended host shape and `run --rm` as the simplest illustration. `--network=none
   --read-only --cap-drop=ALL` should stay: they cost single-digit milliseconds.
2. **The three failure kinds cannot be derived from an exit code** (§5). `exit 125` from
   the app is byte-identical to "no such image", *measured here*; on macOS a Seatbelt denial
   exits 0 at the wrapper. The design's requirement is right and its inference path is not.
   `BuiltinResult` needs a host-set outcome discriminator, defaulting to `Ran`, and the ADR
   should carry §5's three-channel guidance: `create`/`start`/`wait` rather than `run`,
   cgroup `memory.events` `oom_kill` rather than `State.OOMKilled`, and the host's own
   monotonic deadline rather than a signal number.
3. **"Sandboxed" cannot be a boolean, and the design implies one** (§1). Nil ⇒ host exec,
   set ⇒ sandboxed is the right *shape*, but Landlock may be compiled-in-yet-disabled,
   Seatbelt cannot nest inside an already-sandboxed host, and rootless podman will not
   start on a STIG-hardened RHEL 9 or a default Ubuntu 24.04. Whatever the host reports
   about its own capability should be a named tier with a named degradation reason, not a
   flag.

One thing the design under-claims: it treats Seatbelt as absent. It is a real, verified,
unprivileged answer on macOS at ~12.4 ms, and should be named as a legitimate host
implementation (§6).

---

## The bottom line

**The realistic floor for a seven-language library.** Process-tree lifecycle control
(kill the group), argv-not-a-shell-string, a working directory, an env allowlist, a
timeout, output caps — and, the only thing on the list that is a genuine *guarantee*:
**deterministic ordering and truncation of every listing we hand the model, computed in our
own code, identically in all seven ports.** That is defensible because it depends on
nothing about the host OS. Everything else about confinement is a runtime probe with a
degraded path.

**What must be the host's job.** Owning the boundary. Every real primitive is either
root-requiring, daemon-requiring, kernel-version-gated, platform-specific, spawn-time-only,
or irreversible and process-wide — and a library may not permanently mutate its host.
Chromium, Windows' API surface, and Deno all draw the line in the same place for the same
reason. The host supplies the executor, chooses `run --rm` or a warm `exec` or Seatbelt or
Landlock, and classifies its own failures, because only the host knows whether it pulled an
image, set a deadline, or had a runtime installed at all.

**The one thing we would be wrong to promise: that setting `BuiltinExec` makes an agent
safe.** It makes it *interposable*. Whether it is safe is entirely a property of what the
host put behind the seam — and the seam cannot verify that, cannot detect a host that
passed `--privileged`, cannot stop a `bash` tool inside the sandbox from doing anything the
sandbox permits, and on macOS cannot even reliably tell that a policy denied something.
Deno, with far more leverage than we have, documents this exact limit about its own
`--allow-run`. We should say it at least as loudly: **toolnexus provides the seam, not the
sandbox.**
