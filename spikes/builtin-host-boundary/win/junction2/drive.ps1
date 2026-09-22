$base = Join-Path $env:TEMP ("j2-base-" + [guid]::NewGuid().ToString('N').Substring(0,8))
$out  = Join-Path $env:TEMP ("j2-out-"  + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Path $base,$out | Out-Null
"secret" | Set-Content (Join-Path $out "secret.txt")
cmd /c "mklink /J `"$base\junc`" `"$out`"" | Out-Null
$p = Join-Path $base "junc\secret.txt"
"BASE=$base"
"TARGET_OUTSIDE=$out"
.\junction2.exe $p
node probe.js $p
python probe.py $p
Remove-Item -Recurse -Force $base,$out -ErrorAction SilentlyContinue
