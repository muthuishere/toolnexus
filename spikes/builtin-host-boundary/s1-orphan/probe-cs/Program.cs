// S1 probe (csharp). naive = what BuiltinTools.cs:273 does today (/bin/sh -c,
// WaitForExit(timeout), p.Kill() — direct child only).
// tree = p.Kill(entireProcessTree: true).
using System.Diagnostics;

var mode = args[0];
var marker = args[1];
var psi = new ProcessStartInfo { FileName = "/bin/sh", RedirectStandardOutput = true, RedirectStandardError = true };
psi.ArgumentList.Add("-c");
psi.ArgumentList.Add($"sleep 0.2; sh -c 'sleep 1; touch {marker}'");
using var p = Process.Start(psi)!;
if (!p.WaitForExit(300))
{
    if (mode == "naive") p.Kill();
    else p.Kill(entireProcessTree: true);
    p.WaitForExit();
}
Console.WriteLine("killed");
