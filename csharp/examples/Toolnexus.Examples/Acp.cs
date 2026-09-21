using Toolnexus.Acp;

namespace Toolnexus.Examples;

/// <summary>
/// ACP (Agent Client Protocol) as the model behind the unified client (issue #96, ADR 0031):
/// a local coding-agent CLI — <c>devin acp</c> or <c>opencode acp</c> — is spawned ONCE, and
/// every turn below reuses that same warm session instead of paying process-startup cost again.
///
/// <para><b>Honesty check, read before you time anything:</b> whatever speedup turn 2/3 show
/// over turn 1 here is the CLI's own process-startup cost amortised across turns of ONE warm
/// session — it is NOT a protocol-level speedup, and ACP itself adds framing overhead, not
/// less. This example needs the agent CLI installed and already authenticated on this machine
/// (real <c>devin</c> or <c>opencode</c> credentials), so it is not hermetic and CI does not
/// run it — see <c>AcpClientTests</c> for the hermetic, fake-server-backed coverage of the
/// same client.</para>
///
/// <para>Agent CLI is selectable, not hardcoded:
/// <code>
///   dotnet run -- acp                                          # default: devin acp
///   ACP_AGENT_CMD=opencode ACP_AGENT_ARGS=acp dotnet run -- acp # or: opencode acp
///   dotnet run -- acp opencode acp                              # or pass the whole command
/// </code>
/// </para>
/// </summary>
internal static class Acp
{
    public static async Task<int> Run(string[] extraArgs)
    {
        var (command, arguments) = ResolveCommand(extraArgs);
        Console.WriteLine($"ACP agent command: {command} {string.Join(' ', arguments)}");

        AcpClient client;
        try
        {
            client = await AcpClient.StartAsync(command, arguments, cwd: Directory.GetCurrentDirectory());
        }
        catch (Exception e)
        {
            Console.WriteLine($"(could not spawn ACP agent '{command} {string.Join(' ', arguments)}' — "
                + $"is it installed and on PATH? {e.Message})");
            return 0;
        }

        try
        {
            var clock = NativeTool.Of("clock", "Return the current UTC time (ISO-8601).",
                new Dictionary<string, object?> { ["type"] = "object", ["properties"] = new Dictionary<string, object?>() },
                _ => DateTime.UtcNow.ToString("O"));

            await using var tk = await Toolkit.CreateAsync(new Toolkit.Options
            {
                ExtraTools = new List<ITool> { clock },
            });

            var agent = InProcess.CreateClient(new InProcess.Options
            {
                Model = "acp-agent",
                Generate = client.Generate,
                SystemPrompt = "You are a precise agent. Use the clock tool when asked for the time.",
            });

            string[] turns =
            {
                "What time is it right now? Use the clock tool.",
                "Thanks. Now just say 'ready' — no tool needed.",
            };

            foreach (var (turn, i) in turns.Select((t, i) => (t, i)))
            {
                var sw = System.Diagnostics.Stopwatch.StartNew();
                var res = await agent.RunAsync(turn, tk);
                sw.Stop();
                Console.WriteLine($"turn {i + 1} ({sw.ElapsedMilliseconds}ms), session={client.SessionId}: {Clip(res.Text, 120)}");
            }
        }
        finally
        {
            client.Dispose();
        }

        Console.WriteLine("\nOK ACP-backed client completed its turns on one warm session");
        return 0;
    }

    /// <summary>
    /// Resolve the ACP agent's command line: extra CLI args win if given (a whole command, e.g.
    /// <c>opencode acp</c>), else <c>ACP_AGENT_CMD</c>/<c>ACP_AGENT_ARGS</c> env vars, else the
    /// default <c>devin acp</c>. <c>opencode acp</c> works exactly the same way — swap the
    /// command, nothing else in this example changes.
    /// </summary>
    internal static (string Command, IReadOnlyList<string> Arguments) ResolveCommand(string[] extraArgs)
    {
        if (extraArgs.Length > 0)
        {
            return (extraArgs[0], extraArgs.Skip(1).ToList());
        }
        var cmd = Environment.GetEnvironmentVariable("ACP_AGENT_CMD");
        if (string.IsNullOrEmpty(cmd)) cmd = "devin";
        var argsEnv = Environment.GetEnvironmentVariable("ACP_AGENT_ARGS");
        if (string.IsNullOrEmpty(argsEnv)) argsEnv = "acp";
        var args = argsEnv.Split(' ', StringSplitOptions.RemoveEmptyEntries).ToList();
        return (cmd, args);
    }

    private static string Clip(string? s, int n)
    {
        if (string.IsNullOrEmpty(s)) return "";
        var oneLine = s.Replace("\n", " ");
        return oneLine.Length > n ? oneLine[..n] : oneLine;
    }
}
