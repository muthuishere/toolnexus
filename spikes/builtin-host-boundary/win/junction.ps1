# W3 — Windows-only escape surfaces that a POSIX-shaped containment check does
# not see. Run as an ORDINARY user (no admin, no Developer Mode): that is the
# deployment we have to be correct on.
$ErrorActionPreference = 'Continue'
$base = Join-Path $env:TEMP ("tn-base-" + [guid]::NewGuid().ToString('N').Substring(0,8))
$out  = Join-Path $env:TEMP ("tn-out-"  + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $base, $out | Out-Null
"secret" | Set-Content (Join-Path $out "secret.txt")

"IS_ADMIN=$([bool](New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator))"

# 1. Directory JUNCTION — no privilege required, unlike a symlink.
cmd /c "mklink /J `"$base\junc`" `"$out`"" 2>&1 | ForEach-Object { "MKLINK_J: $_" }
"JUNCTION_READ: " + (Get-Content (Join-Path $base "junc\secret.txt") -ErrorAction SilentlyContinue)

# 2. Does .NET canonicalisation see through a junction? This is the exact call a
#    C#/Java/Node/Python containment check would make.
$p = Join-Path $base "junc\secret.txt"
"GETFULLPATH:  " + [IO.Path]::GetFullPath($p)
try { "RESOLVE_LINK: " + ((Get-Item (Join-Path $base "junc")).ResolveLinkTarget($true)).FullName } catch { "RESOLVE_LINK_ERR: $_" }
try { "FILEINFO_TARGET: " + ((Get-Item $p).ResolveLinkTarget($true)) } catch { "FILEINFO_TARGET_ERR: $_" }

# 3. Reserved device names: writing INSIDE the base, but not to a file in it.
try { "x" | Set-Content (Join-Path $base "CON") ; "WROTE_CON=ok" } catch { "WROTE_CON_ERR: $_" }
"CON_EXISTS_AS_FILE=" + (Test-Path (Join-Path $base "CON") -PathType Leaf)

# 4. What a `workdir`-pinned cmd.exe can still reach.
foreach ($c in @("cd /d .. && dir /b", "type `"$out\secret.txt`"", "powershell -c `"Get-Content '$out\secret.txt'`"")) {
  $r = cmd /c "cd /d `"$base`" && $c" 2>&1 | Select-Object -First 1
  "ESCAPE[$c] -> $r"
}

Remove-Item -Recurse -Force $base, $out -ErrorAction SilentlyContinue
