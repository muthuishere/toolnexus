using System.Text.RegularExpressions;

namespace Toolnexus;

/// <summary>
/// Tool scheduling (SPEC §8 "Tool scheduling"): the calls of one model response, offered in
/// emitted order, run as consecutive runs. Safe calls overlap, at most the cap at once; an unsafe
/// call waits for every earlier call, runs alone, and nothing after it starts until it finishes.
/// Results keep emitted order because each call still writes its own slot.
/// </summary>
public sealed class ToolScheduler
{
    /// <summary>The cap when neither the option nor <c>TOOLNEXUS_MAX_TOOL_CONCURRENCY</c> sets one.</summary>
    public const int DefaultMaxToolConcurrency = 10;

    private static readonly Regex PositiveInt = new(@"^\s*\+?\d+\s*$");

    private readonly SemaphoreSlim _slots;
    private readonly List<Task> _started = new();

    internal ToolScheduler(int cap) => _slots = new SemaphoreSlim(Math.Max(1, cap));

    /// <summary>option &gt; env <c>TOOLNEXUS_MAX_TOOL_CONCURRENCY</c> &gt; 10; non-positive or
    /// non-numeric values fall back.</summary>
    public static int ResolveMaxToolConcurrency(int? option)
        => ResolveMaxToolConcurrency(option, Environment.GetEnvironmentVariable);

    internal static int ResolveMaxToolConcurrency(int? option, Func<string, string?> env)
    {
        if (option is > 0) return option.Value;
        var raw = env("TOOLNEXUS_MAX_TOOL_CONCURRENCY");
        if (raw != null && PositiveInt.IsMatch(raw) && int.TryParse(raw.Trim().TrimStart('+'), out var n) && n > 0)
            return n;
        return DefaultMaxToolConcurrency;
    }

    /// <summary>Whether a call to <paramref name="tool"/> with <paramref name="args"/> may run
    /// alongside other safe calls. Unknown (null) ⇒ unsafe.</summary>
    public static bool IsConcurrencySafe(ITool? tool, IDictionary<string, object?> args)
        => tool != null && tool.IsConcurrencySafe(args);

    /// <summary>Schedules one call; call in emitted order. An unsafe call has finished when the
    /// returned task is handed back.</summary>
    internal async Task<Task> RunAsync(bool safe, Func<Task> start, CancellationToken ct)
    {
        if (!safe)
        {
            await Settled(Task.WhenAll(_started)).WaitAsync(ct).ConfigureAwait(false);
            var alone = start();
            _started.Add(alone);
            await Settled(alone).WaitAsync(ct).ConfigureAwait(false);
            return alone;
        }
        await _slots.WaitAsync(ct).ConfigureAwait(false);
        Task t;
        try
        {
            t = start();
        }
        catch
        {
            _slots.Release();
            throw;
        }
        _ = t.ContinueWith(_ => _slots.Release(), TaskScheduler.Default);
        _started.Add(t);
        return t;
    }

    private static async Task Settled(Task t)
    {
        try { await t.ConfigureAwait(false); } catch { /* surfaced by the caller's WhenAll */ }
    }
}
