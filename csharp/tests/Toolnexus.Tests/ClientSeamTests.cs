namespace Toolnexus.Tests;

/// <summary>
/// Client seams landed for SPEC §7D: (1) external cancellation is classified distinctly from a
/// run timeout — by TOKEN STATE, not exception type (an external cancel surfaces as
/// <see cref="OperationCanceledException"/>, a deadline expiry as
/// <see cref="LlmClient.RunTimeoutException"/>); (2) the loud <c>"incomplete"</c> RunResult
/// status when the loop stops at MaxTurns still emitting tool calls (§8 addendum, QG5).
/// </summary>
public class ClientSeamTests
{
    private static async Task<Toolkit> LoopToolkitAsync()
    {
        var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        toolkit.Register(MockLlm.Lookup());
        return toolkit;
    }

    [Fact]
    public async Task MaxTurnsStillEmittingToolCalls_IsIncomplete_NotSilentDone()
    {
        await using var toolkit = await LoopToolkitAsync();
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://mock.local", Style = "openai", Model = "m-loop", ApiKey = "test",
            MaxTurns = 3, HttpHandler = new SubagentMockHandler { DelayMs = 0 },
        });
        var r = await client.RunAsync("loop forever", toolkit);
        Assert.Equal("incomplete", r.Status);
        Assert.Equal(3, r.Turns);
        Assert.True(r.ToolCallCount == 3, $"partial work preserved: {r.ToolCallCount} tool calls");
    }

    [Fact]
    public async Task MaxTurnsWithFinalAnswer_StaysDone()
    {
        await using var toolkit = await LoopToolkitAsync();
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://mock.local", Style = "openai", Model = "m-peer", ApiKey = "test",
            MaxTurns = 3, HttpHandler = new SubagentMockHandler { DelayMs = 0 },
        });
        var r = await client.RunAsync("hello", toolkit);
        Assert.Equal("done", r.Status);
    }

    [Fact]
    public async Task ExternalCancel_SurfacesAsOperationCanceled_ClassifiedByTokenState()
    {
        var handler = new SubagentMockHandler { SlowGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously) };
        await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://mock.local", Style = "openai", Model = "m-slow", ApiKey = "test",
            HttpHandler = handler,
        });
        using var cts = new CancellationTokenSource(50);
        var ex = await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => client.RunAsync("park", toolkit, null, cts.Token));
        Assert.True(cts.Token.IsCancellationRequested,
            "the caller's token state is the classifier — it reads cancelled");
        Assert.IsNotType<LlmClient.RunTimeoutException>(ex); // never conflated with a timeout
    }

    [Fact]
    public async Task RunDeadline_SurfacesAsRunTimeout_NotAsCancellation()
    {
        var handler = new SubagentMockHandler { SlowGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously) };
        await using var toolkit = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = "http://mock.local", Style = "openai", Model = "m-slow", ApiKey = "test",
            HttpHandler = handler, TimeoutMs = 50,
        });
        await Assert.ThrowsAsync<LlmClient.RunTimeoutException>(
            () => client.RunAsync("park", toolkit));
    }
}
