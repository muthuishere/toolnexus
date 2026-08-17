using Toolnexus.Agents;

// `Toolnexus.Agent` (A2A) sits in an ENCLOSING namespace, and C# resolves simple names
// from enclosing namespaces before compilation-unit aliases — so aliasing to the name
// `Agent` would silently keep resolving to the A2A type. A distinct alias avoids it.
using SubAgent = Toolnexus.Agents.Agent;

namespace Toolnexus.Tests;

/// Harness, loop and the completion gate (openspec/changes/add-harness-and-loop).
/// Hermetic — a scripted handler stands in for the LLM. Mirrors golang/agents/loop_test.go,
/// js/test/loop.test.ts and python/tests/test_harness_loop.py case for case: the point of the
/// change is that seven ports agree, and a test in one port only is how that stops being true.
public class HarnessLoopTests
{
    private sealed class Scripted : HttpMessageHandler
    {
        private readonly string[] _messages;
        private int _i;
        public List<string> Models { get; } = new();

        public Scripted(params string[] messages) => _messages = messages;

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var body = request.Content is null
                ? "" : await request.Content.ReadAsStringAsync(cancellationToken);
            using var doc = System.Text.Json.JsonDocument.Parse(string.IsNullOrEmpty(body) ? "{}" : body);
            Models.Add(doc.RootElement.TryGetProperty("model", out var m) ? m.GetString() ?? "" : "");

            var message = _messages[Math.Min(_i, _messages.Length - 1)];
            _i++;
            var finish = message.Contains("tool_calls") ? "tool_calls" : "stop";
            var json = "{\"choices\":[{\"index\":0,\"message\":" + message
                     + ",\"finish_reason\":\"" + finish + "\"}],"
                     + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            return new HttpResponseMessage(System.Net.HttpStatusCode.OK)
            {
                Content = new StringContent(json, System.Text.Encoding.UTF8, "application/json"),
            };
        }
    }

    private static string Say(string content) => $"{{\"role\":\"assistant\",\"content\":\"{content}\"}}";

    private static string CallTodo(params (string Id, string Text, bool Done)[] todos)
    {
        var items = string.Join(",", todos.Select(t =>
            $"{{\\\"id\\\":\\\"{t.Id}\\\",\\\"text\\\":\\\"{t.Text}\\\",\\\"completed\\\":{(t.Done ? "true" : "false")}}}"));
        return "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\","
             + "\"function\":{\"name\":\"todowrite\",\"arguments\":\"{\\\"todos\\\":[" + items + "]}\"}}]}";
    }

    private static LlmClient.Options BaseOptions(Scripted handler) => new()
    {
        BaseUrl = "http://scripted.invalid", Style = "openai",
        Model = "test-model", ApiKey = "unused", HttpHandler = handler,
    };

    // Builtins takes a bool or a {tools:{name:bool}} map that disables named tools on the
    // all-on baseline — so everything except todowrite is switched off here.
    private static Task<Toolkit> TodoToolkitAsync() => Toolkit.CreateAsync(new Toolkit.Options
    {
        Builtins = new Dictionary<string, object?>
        {
            ["tools"] = new Dictionary<string, object?>
            {
                ["todowrite"] = true, ["bash"] = false, ["read"] = false, ["write"] = false,
                ["edit"] = false, ["glob"] = false, ["grep"] = false, ["webfetch"] = false,
                ["apply_patch"] = false, ["question"] = false,
            },
        },
    });

    [Fact]
    public async Task AbsentOptionsAreUnchanged()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var a = new SubAgent("plain", new AgentSpec { Does = "answers" });
        var outcome = await a.Loop(BaseOptions(new Scripted(Say("hello"))), tk).RunAsync("hi");
        Assert.Equal("done", outcome.Status);
        Assert.Equal("hello", outcome.Text);
        Assert.Equal(1, outcome.Attempts);
        Assert.Null(outcome.StoppedBy);
    }

    [Fact]
    public async Task GateBlocksAnOpenTodoThenPasses()
    {
        // Attempt 1 must END with an open item: the client loops on tool calls, so a closing
        // todowrite in the same run would be judged and pass with no retry.
        var handler = new Scripted(
            CallTodo(("1", "draft", true), ("2", "proofread", false)),
            Say("I think I am finished"),
            CallTodo(("1", "draft", true), ("2", "proofread", true)),
            Say("all done"));
        await using var tk = await TodoToolkitAsync();
        var a = new SubAgent("gated", new AgentSpec
        {
            Does = "plans",
            Completion = new Completion { Verify = LoopSupport.AllTodosDone, MaxAttempts = 3 },
        });
        var outcome = await a.Loop(BaseOptions(handler), tk).RunAsync("do the thing");
        Assert.Equal("done", outcome.Status);
        Assert.True(outcome.Attempts >= 2, $"expected a retry, got {outcome.Attempts}");
    }

    [Fact]
    public async Task UnverifiableRunStopsLoudly()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var a = new SubAgent("never", new AgentSpec
        {
            Does = "never verifies",
            Completion = new Completion { Verify = _ => new Verdict(false, "always red"), MaxAttempts = 2 },
        });
        var outcome = await a.Loop(BaseOptions(new Scripted(Say("done!"))), tk).RunAsync("go");
        Assert.Equal("incomplete", outcome.Status);
        Assert.Equal(2, outcome.Attempts);
        Assert.Contains("always red", outcome.StoppedBy);
        Assert.Equal("completion", outcome.Result!.Limit);
    }

    [Fact]
    public async Task MaxAttemptsIsRequired()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var a = new SubAgent("bad", new AgentSpec
        {
            Does = "x",
            Completion = new Completion { Verify = _ => new Verdict(true), MaxAttempts = 0 },
        });
        await Assert.ThrowsAsync<ArgumentException>(
            () => a.Loop(BaseOptions(new Scripted(Say("hi"))), tk).RunAsync("go"));
    }

    [Fact]
    public async Task NoPlanDeclaredPasses()
    {
        await using var tk = await TodoToolkitAsync();
        var a = new SubAgent("noplan", new AgentSpec
        {
            Does = "x",
            Completion = new Completion { Verify = LoopSupport.AllTodosDone, MaxAttempts = 2 },
        });
        var outcome = await a.Loop(BaseOptions(new Scripted(Say("answered without a plan"))), tk).RunAsync("go");
        Assert.Equal("done", outcome.Status);
        Assert.Equal(1, outcome.Attempts);
    }

    [Fact]
    public async Task GateJudgesAccumulatedWork()
    {
        // Attempt 1 declares an open item; attempt 2 declares no plan at all. Judging only the
        // latest attempt would see "no plan" and pass.
        var handler = new Scripted(
            CallTodo(("1", "ship it", false)),
            Say("I am finished, honest"));
        await using var tk = await TodoToolkitAsync();
        var a = new SubAgent("escaper", new AgentSpec
        {
            Does = "x",
            Completion = new Completion { Verify = LoopSupport.AllTodosDone, MaxAttempts = 2 },
        });
        var outcome = await a.Loop(BaseOptions(handler), tk).RunAsync("go");
        Assert.Equal("incomplete", outcome.Status);
        Assert.Contains("ship it", outcome.StoppedBy);
    }

    [Fact]
    public void GuardrailsFirstDenyWins()
    {
        var seen = 0;
        var hooks = LoopSupport.GuardedHooks(
            new List<Guardrail>
            {
                ev => ev.Name == "danger" ? "policy: no" : "allow",
                _ => { seen++; return "allow"; },
            },
            null);

        var denied = hooks!.BeforeTool!(new LlmClient.BeforeToolEvent(
            "danger", new Dictionary<string, object?>(), null, 1));
        Assert.True(denied!.Result!.IsError);
        Assert.Contains("policy: no", denied.Result.Output);
        Assert.Equal(0, seen);

        var allowed = hooks.BeforeTool(new LlmClient.BeforeToolEvent(
            "safe", new Dictionary<string, object?>(), null, 1));
        Assert.Null(allowed);
        Assert.Equal(1, seen);
    }

    [Fact]
    public void GuardrailsRunBeforeAnExistingHook()
    {
        var prior = 0;
        var hooks = LoopSupport.GuardedHooks(
            new List<Guardrail> { ev => ev.Name == "danger" ? "nope" : "allow" },
            new LlmClient.Hooks { BeforeTool = _ => { prior++; return null; } });

        hooks!.BeforeTool!(new LlmClient.BeforeToolEvent("danger", new Dictionary<string, object?>(), null, 1));
        Assert.Equal(0, prior);
        hooks.BeforeTool(new LlmClient.BeforeToolEvent("safe", new Dictionary<string, object?>(), null, 1));
        Assert.Equal(1, prior);
    }

    [Fact]
    public void GuardrailsAndGateSurviveTheRegistryProjection()
    {
        var child = new SubAgent("child", new AgentSpec
        {
            Does = "does work",
            Guardrails = new List<Guardrail> { _ => "denied by policy" },
            Completion = new Completion { Verify = LoopSupport.AllTodosDone, MaxAttempts = 2 },
        });
        var def = child.Registry()["child"];
        Assert.NotNull(def.Hooks?.BeforeTool);
        Assert.Equal(2, def.Completion!.MaxAttempts);
    }

    [Fact]
    public async Task PerCallModelOverrideReachesTheWire()
    {
        var handler = new Scripted(Say("a"), Say("b"));
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var loop = new SubAgent("m", new AgentSpec { Does = "x" }).Loop(BaseOptions(handler), tk);
        await loop.RunAsync("one", new LoopRunOptions { Model = "override-model" });
        await loop.RunAsync("two");
        Assert.Equal("override-model", handler.Models[0]);
        Assert.Equal("test-model", handler.Models[1]);
    }

    [Fact]
    public async Task TurnsAccumulateAndStatusIsObserved()
    {
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options { Builtins = false });
        var loop = new SubAgent("t", new AgentSpec { Does = "x" })
            .Loop(BaseOptions(new Scripted(Say("a"), Say("b"))), tk);
        Assert.Equal("idle", loop.Status);
        await loop.RunAsync("one");
        var afterFirst = loop.Turns;
        await loop.RunAsync("two");
        Assert.True(loop.Turns > afterFirst);
        Assert.Equal("idle", loop.Status);
    }
}
