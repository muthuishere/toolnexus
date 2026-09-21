using System.Net;
using System.Reflection;
using System.Text;
using System.Text.Json;
using Toolnexus.Agents;
using Xunit;

// `Toolnexus.Agent` (A2A) sits in an ENCLOSING namespace and wins simple-name resolution over a
// compilation-unit alias called `Agent`, so the alias is deliberately spelled differently.
using SubAgent = Toolnexus.Agents.Agent;

namespace Toolnexus.Tests;

/// <summary>
/// The seven-port consumer batch: issues #86–#93, decided in
/// <c>openspec/changes/fix-consumer-issues-86-93/DECISIONS.md</c> and evidenced in
/// <c>docs/adr/0023</c>–<c>0028</c> + <c>spikes/issues/</c>.
///
/// <para>Hermetic by construction: a scripted in-process <see cref="HttpMessageHandler"/> stands in
/// for the provider. No network, no API key, no cost. The spikes' harnesses are reused in shape —
/// same scenarios, same assertions, expressed in this port's idiom.</para>
/// </summary>
public class ConsumerIssuesTests
{
    // ---------------------------------------------------------------- shared scripted provider

    /// <summary>Replays canned assistant messages and RECORDS every request body, which is what
    /// the #86 wire assertion needs.</summary>
    private sealed class Recorder : HttpMessageHandler
    {
        private readonly string[] _messages;
        private int _i;

        /// <summary>Every request body, in order.</summary>
        public List<JsonElement> Bodies { get; } = new();

        /// <summary>How many times the provider was actually hit — the fail-fast assertion.</summary>
        public int Hits => Bodies.Count;

        /// <summary>When set, every response uses this status and body instead of a completion.</summary>
        public (int Status, string Body)? Failure { get; set; }

        /// <summary>Sent as <c>Retry-After</c> when set.</summary>
        public string? RetryAfter { get; set; }

        public Recorder(params string[] messages) => _messages = messages.Length > 0
            ? messages
            : new[] { "{\"role\":\"assistant\",\"content\":\"ok\"}" };

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var raw = request.Content is null
                ? "{}"
                : await request.Content.ReadAsStringAsync(cancellationToken);
            Bodies.Add(JsonDocument.Parse(raw).RootElement.Clone());

            if (Failure is { } f)
            {
                var res = new HttpResponseMessage((HttpStatusCode)f.Status)
                {
                    Content = new StringContent(f.Body, Encoding.UTF8, "application/json"),
                };
                if (RetryAfter != null) res.Headers.TryAddWithoutValidation("retry-after", RetryAfter);
                return res;
            }

            var message = _messages[Math.Min(_i, _messages.Length - 1)];
            _i++;
            var finish = message.Contains("tool_calls") ? "tool_calls" : "stop";
            var json = "{\"choices\":[{\"index\":0,\"message\":" + message
                     + ",\"finish_reason\":\"" + finish + "\"}],"
                     + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(json, Encoding.UTF8, "application/json"),
            };
        }
    }

    private static LlmClient.Options Opts(Recorder rec, Action<LlmClient.Options>? tweak = null)
    {
        var o = new LlmClient.Options
        {
            BaseUrl = "http://scripted.invalid", Style = "openai",
            Model = "test-model", ApiKey = "not-a-real-key", HttpHandler = rec,
        };
        tweak?.Invoke(o);
        return o;
    }

    private static string Say(string content) => $"{{\"role\":\"assistant\",\"content\":\"{content}\"}}";

    private static string CallTool(string name, string argsJson = "{}") =>
        "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\","
        + "\"function\":{\"name\":\"" + name + "\",\"arguments\":\""
        + argsJson.Replace("\"", "\\\"") + "\"}}]}";

    // ================================================================ D1 (#86, ADR 0023)

    /// <summary>
    /// The bug was ONE line: building the §0.10 system message dereferenced the toolkit for
    /// <c>SkillsPrompt()</c> before anything touched tools, so <c>RunAsync(p, null!)</c> compiled
    /// with a warning and then threw a NullReferenceException. The parameter is now
    /// <c>Toolkit?</c>, so this reads as intent rather than as a null-forgiving dare.
    /// </summary>
    [Fact]
    public async Task ToolkitLessRunSucceeds()
    {
        var rec = new Recorder(Say("a haiku"));
        var client = LlmClient.Create(Opts(rec));

        var r = await client.RunAsync("write me a haiku", (Toolkit?)null);

        Assert.Equal("a haiku", r.Text);
        Assert.Equal("done", r.Status);
    }

    /// <summary>
    /// The wire assertion, pinned in every port: a toolkit-less request body carries NO
    /// <c>tools</c> key and NO <c>tool_choice</c> key. Not an empty array — several providers
    /// reject or bill differently for a declared-but-empty tool list.
    /// </summary>
    [Fact]
    public async Task ToolkitLessRequestOmitsToolsAndToolChoice()
    {
        var rec = new Recorder(Say("hi"));
        var client = LlmClient.Create(Opts(rec));

        await client.RunAsync("hello", (Toolkit?)null);

        var body = Assert.Single(rec.Bodies);
        Assert.False(body.TryGetProperty("tools", out _));
        Assert.False(body.TryGetProperty("tool_choice", out _));
        // And no system message either: with no toolkit there is no skills prompt to inject.
        var messages = body.GetProperty("messages").EnumerateArray().ToList();
        Assert.All(messages, m => Assert.NotEqual("system", m.GetProperty("role").GetString()));
    }

    /// <summary>The same for ask and stream — three methods, one null-guard.</summary>
    [Fact]
    public async Task ToolkitLessAskAndStreamSucceed()
    {
        var rec = new Recorder(Say("ok"));
        var client = LlmClient.Create(Opts(rec));

        var asked = await client.AskAsync("q", (Toolkit?)null);
        Assert.Equal("ok", asked.Text);

        // The streaming path builds the same system message from the same null toolkit. The stub
        // answers non-SSE, so the run yields no deltas — what is asserted is that it got as far as
        // a well-formed request at all, which is exactly where the NRE used to land.
        var streamRec = new Recorder(Say("ok"));
        var streamClient = LlmClient.Create(Opts(streamRec));
        await streamClient.StreamAsync("q", (Toolkit?)null, _ => { });
        Assert.NotEmpty(streamRec.Bodies);
        Assert.False(streamRec.Bodies[0].TryGetProperty("tools", out _));
        Assert.False(streamRec.Bodies[0].TryGetProperty("tool_choice", out _));
    }

    /// <summary>
    /// A toolkit-less run is NOT the same thing as a <c>Toolkit.empty()</c> factory, which this
    /// batch explicitly declined to add: <c>Builtins = false</c> already expresses "no tools" and
    /// must keep behaving identically to passing null.
    /// </summary>
    [Fact]
    public async Task BuiltinsFalseToolkitMatchesNullToolkitOnTheWire()
    {
        var recNull = new Recorder(Say("x"));
        await LlmClient.Create(Opts(recNull)).RunAsync("p", (Toolkit?)null);

        var recEmpty = new Recorder(Say("x"));
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false));
        await LlmClient.Create(Opts(recEmpty)).RunAsync("p", tk);

        Assert.False(recEmpty.Bodies[0].TryGetProperty("tools", out _));
        Assert.Equal(recNull.Bodies[0].GetRawText(), recEmpty.Bodies[0].GetRawText());
    }

    // ================================================================ D2 (#87, ADR 0024)

    private static ITool Counter(List<string> log) => NativeTool.Of("danger", "does a thing",
        new Dictionary<string, object?> { ["type"] = "object", ["properties"] = new Dictionary<string, object?>() },
        args => { log.Add("entered"); return "done"; });

    /// <summary>
    /// The regression the ADR asks every port to assert, and it asserts on EXECUTION, not on the
    /// model's text: the text is what made this survivable for a release. A guardrail declared on
    /// the spec must deny on the Loop path, so the tool's body is never entered.
    /// </summary>
    [Fact]
    public async Task LoopAppliesSpecGuardrailsSoDeniedToolNeverExecutes()
    {
        var entered = new List<string>();
        var rec = new Recorder(CallTool("danger"), Say("gave up"));
        var agent = new SubAgent("shipper", new AgentSpec
        {
            Does = "ships",
            Guardrails = new List<Guardrail> { _ => "danger is not allowed here" },
        });
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false));
        tk.Register(Counter(entered));

        await agent.Loop(Opts(rec), tk).RunAsync("ship it");

        Assert.Empty(entered); // the WHOLE assertion
    }

    /// <summary>
    /// Soul-vs-caller precedence. C# was already correct (the caller wins) and this pins it, so
    /// aligning the other ports cannot quietly flip this one on the way past.
    /// </summary>
    [Fact]
    public async Task CallerSystemPromptWinsOverSpecSoul()
    {
        var rec = new Recorder(Say("x"));
        var agent = new SubAgent("a", new AgentSpec { Does = "d", Soul = "SOUL" });
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false));

        await agent.Loop(Opts(rec, o => o.SystemPrompt = "CALLER"), tk).RunAsync("go");

        var system = rec.Bodies[0].GetProperty("messages").EnumerateArray().First();
        Assert.Equal("CALLER", system.GetProperty("content").GetString());
    }

    /// <summary>...and with no caller prompt, the soul is what travels.</summary>
    [Fact]
    public async Task SpecSoulAppliesWhenCallerSetsNoSystemPrompt()
    {
        var rec = new Recorder(Say("x"));
        var agent = new SubAgent("a", new AgentSpec { Does = "d", Soul = "SOUL" });
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false));

        await agent.Loop(Opts(rec), tk).RunAsync("go");

        var system = rec.Bodies[0].GetProperty("messages").EnumerateArray().First();
        Assert.Equal("SOUL", system.GetProperty("content").GetString());
    }

    /// <summary><c>Spec.Model</c> and <c>Spec.Budget.MaxTurns</c> are Loop defaults now — both are
    /// on the spec precisely because they are meant to travel with the agent.</summary>
    [Fact]
    public async Task SpecModelAndMaxTurnsBecomeLoopDefaults()
    {
        var rec = new Recorder(Say("x"));
        var agent = new SubAgent("a", new AgentSpec
        {
            Does = "d", Model = "spec-model", Budget = new Budget { MaxTurns = 1 },
        });
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false));

        // The caller leaves Model blank, so the SPEC's model is what travels.
        await agent.Loop(Opts(rec, o => o.Model = ""), tk).RunAsync("go");

        Assert.Equal("spec-model", rec.Bodies[0].GetProperty("model").GetString());

        // MaxTurns: a model that never stops calling tools stops after exactly one turn.
        var loopRec = new Recorder(CallTool("danger"));
        var log = new List<string>();
        await using var tk2 = await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false));
        tk2.Register(Counter(log));
        var outcome = await agent.Loop(Opts(loopRec, o => o.Model = ""), tk2).RunAsync("go");
        Assert.Equal(1, outcome.Turns);
        Assert.Equal("incomplete", outcome.Status);
    }

    /// <summary>A caller-supplied model/maxTurns still wins — the spec only fills a blank.</summary>
    [Fact]
    public async Task CallerModelWinsOverSpecModel()
    {
        var rec = new Recorder(Say("x"));
        var agent = new SubAgent("a", new AgentSpec { Does = "d", Model = "spec-model" });
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false));

        await agent.Loop(Opts(rec, o => o.Model = "caller-model"), tk).RunAsync("go");

        Assert.Equal("caller-model", rec.Bodies[0].GetProperty("model").GetString());
    }

    /// <summary>
    /// The residue is NAMED, not thrown. A construction-time error would break every existing
    /// caller, so the limitation is reported additively and the host decides.
    /// </summary>
    [Fact]
    public void LoopUnsupportedNamesOnlyTheUnhonourableFields()
    {
        Assert.Empty(LoopSupport.LoopUnsupported(new AgentSpec
        {
            Does = "d", Soul = "s", Model = "m",
            Guardrails = new List<Guardrail> { _ => "allow" },
            Budget = new Budget { MaxTurns = 2 },
        }));

        var named = LoopSupport.LoopUnsupported(new AgentSpec
        {
            Does = "d",
            Uses = new List<ITool> { MockLlm.Lookup() },
            Team = new List<SubAgent> { new("other", new AgentSpec { Does = "x" }) },
            WaitFor = _ => Task.FromResult(new Answer()),
            OnMetric = _ => { },
        });
        // (A6) A FIXED CANONICAL VOCABULARY, identical in all seven ports — `Uses` reports as
        // "tools", never as this language's own spelling of the field.
        Assert.Equal(new[] { "tools", "team", "waitFor", "onMetric" }, named);
        Assert.Empty(LoopSupport.LoopUnsupported(null));
    }

    // ================================================================ D3 (#88/#90, ADR 0025)

    private static RuntimeOptions RuntimeOpts(HttpMessageHandler handler, Dictionary<string, AgentDef> reg)
        => new()
        {
            Handler = handler, Registry = reg, BaseUrl = "http://runtime.invalid",
            Style = "openai", Model = "m", ApiKey = "not-a-real-key",
        };

    /// <summary>
    /// <c>TotalTokens</c> AND <c>Turns</c> are the CUMULATIVE TREE TOTAL on every status, and
    /// <c>OwnTokens</c> keeps the per-run token figure. Before this both fields meant one thing on
    /// done/pending/incomplete and another on error/closed/timeout, which is worse than either
    /// candidate meaning. (A13: no <c>OwnTurns</c> — tokens are billed, turns are not.)
    /// </summary>
    [Fact]
    public async Task TotalTokensIsTheHandleLedgerAndOwnTokensIsTheRun()
    {
        var rec = new Recorder(Say("first"), Say("second"));
        var agent = new SubAgent("solo", new AgentSpec { Does = "works alone" });
        var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
        var h = rt.Spawn(rt.Root, "solo").Handle!;

        var first = await rt.RunTurnAsync(h, "one");
        var second = await rt.RunTurnAsync(h, "two");

        Assert.Equal(first.OwnTokens, first.TotalTokens);            // one turn: they agree
        Assert.Equal(second.OwnTokens * 2, second.TotalTokens);      // two turns: the ledger grows
        Assert.True(second.TotalTokens > first.TotalTokens,
            "a second turn on the same handle must never report LESS than the first");

        // (A13) Turns behaves identically — cumulative, never reset by a later turn.
        Assert.Equal(1, first.Turns);
        Assert.Equal(2, second.Turns);
        await rt.CloseAsync(rt.Root);
    }

    /// <summary>
    /// (ADR 0025 D1 / A13) The delegation case, which is what #88 actually measured: 67% of the
    /// bill was invisible with ONE child. <c>TotalTokens</c> must carry the whole tree. <c>Turns</c>
    /// deliberately does not — turns are not billed, and a parent's turn count is its own — so this
    /// pins BOTH halves, because "cumulative" means different things for the two fields.
    /// </summary>
    [Fact]
    public async Task DelegatedChildRollsUpIntoTheParentsTurnsAndTokens()
    {
        // coordinator: delegate to `worker`, then answer. worker: answer.
        var rec = new Recorder(
            CallTool("task", "{\"agent\":\"worker\",\"prompt\":\"dig\"}"),
            Say("child answered"),
            Say("coordinator answer"));
        var worker = new SubAgent("worker", new AgentSpec { Does = "digs" });
        var coordinator = new SubAgent("coordinator", new AgentSpec
        {
            Does = "delegates", Team = new List<SubAgent> { worker },
        });
        var rt = new AgentRuntime(RuntimeOpts(rec, coordinator.Registry()));
        var h = rt.Spawn(rt.Root, "coordinator").Handle!;

        var r = await rt.RunTurnAsync(h, "go");

        Assert.Equal(AgentStatus.Done, r.Status);
        // Tokens: the child's spend is INSIDE the parent's TotalTokens, and OwnTokens is what the
        // parent alone spent — the 67% that used to be invisible is the gap between them.
        Assert.True(r.TotalTokens > r.OwnTokens,
            "the parent's TotalTokens must include the delegated child's spend");
        // Turns: the coordinator's OWN 2 round trips (the `task` call, then the final answer).
        // Not 3 — a delegated child's turns are the child's, and that is pinned in every port.
        Assert.Equal(2, r.Turns);
        await rt.CloseAsync(rt.Root);
    }

    /// <summary>A turn-cap stop names its limit as a value to branch on, and keeps the sentence
    /// that shipped.</summary>
    [Fact]
    public async Task IncompleteCarriesTheMaxTurnsLimit()
    {
        var rec = new Recorder(CallTool("lookup", "{\"q\":\"x\"}"));
        var agent = new SubAgent("looper", new AgentSpec
        {
            Does = "loops", Uses = new List<ITool> { MockLlm.Lookup() },
            Budget = new Budget { MaxTurns = 2 },
        });
        var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
        var h = rt.Spawn(rt.Root, "looper").Handle!;

        var r = await rt.RunTurnAsync(h, "go");

        Assert.Equal(AgentStatus.Incomplete, r.Status);
        Assert.Equal(StopLimit.MaxTurns, r.Limit);
        Assert.Equal("hit maxTurns without a final answer", r.Text); // byte-identical
        await rt.CloseAsync(rt.Root);
    }

    /// <summary>
    /// The C#-specific defect this batch also fixes: the incomplete sentence HARDCODED the word
    /// "maxTurns", so a COMPLETION-GATE stop was reported as a turn-cap stop. It now reports the
    /// real limit, in the field and in the text.
    /// </summary>
    [Fact]
    public async Task CompletionGateStopIsNotMislabelledAsMaxTurns()
    {
        var rec = new Recorder(Say("I am finished"));
        var agent = new SubAgent("gated", new AgentSpec
        {
            Does = "is verified",
            Completion = new Completion { MaxAttempts = 2, Verify = _ => new Verdict(false, "nope") },
        });
        var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
        var h = rt.Spawn(rt.Root, "gated").Handle!;

        var r = await rt.RunTurnAsync(h, "go");

        Assert.Equal(AgentStatus.Incomplete, r.Status);
        Assert.Equal(StopLimit.Completion, r.Limit);
        Assert.DoesNotContain("maxTurns", r.Text);
        Assert.Contains("completion.verify failed 2x: nope", r.Text);
        await rt.CloseAsync(rt.Root);
    }

    /// <summary>A budget-pool stop carries its pool name as a field too, not only inside prose.</summary>
    [Fact]
    public async Task BudgetStopCarriesTheExhaustedPool()
    {
        var rec = new Recorder(Say("x"));
        var agent = new SubAgent("broke", new AgentSpec
        {
            Does = "spends", Budget = new Budget { MaxTokens = 1 },
        });
        var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
        var h = rt.Spawn(rt.Root, "broke").Handle!;

        await rt.RunTurnAsync(h, "one");   // drains the 1-token pool
        var second = await rt.RunTurnAsync(h, "two");

        Assert.Equal(AgentStatus.Incomplete, second.Status);
        // (A14) The FIELD carries the canonical spelling, not this runtime's internal pool name.
        Assert.Equal(StopLimit.MaxTokens, second.Limit);
        Assert.Contains(second.Limit, StopLimit.All);
        // The TEXT keeps the internal name it always had — the prose is byte-identical.
        Assert.Equal("budget exhausted (tokens); partial work preserved", second.Text);
        await rt.CloseAsync(rt.Root);
    }

    /// <summary>
    /// A turn started on an ALREADY-CLOSED handle used to report literal <c>0</c> turns and
    /// <c>0</c> tokens, so a host that closes a handle and reads the result bills zero for work
    /// that demonstrably happened. Same under-reporting as #88, on the closed path.
    /// </summary>
    [Fact]
    public async Task ClosedHandleStillReportsWhatItSpent()
    {
        var rec = new Recorder(Say("did the work"));
        var agent = new SubAgent("worker", new AgentSpec { Does = "works" });
        var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
        var h = rt.Spawn(rt.Root, "worker").Handle!;

        var done = await rt.RunTurnAsync(h, "go");
        Assert.True(done.TotalTokens > 0 && done.Turns > 0);

        await rt.CloseAsync(h);
        var afterClose = await rt.RunTurnAsync(h, "again");

        Assert.Equal(AgentStatus.Closed, afterClose.Status);
        Assert.Equal(done.Turns, afterClose.Turns);
        Assert.Equal(done.TotalTokens, afterClose.TotalTokens);
        await rt.CloseAsync(rt.Root);
    }

    /// <summary>
    /// <c>ResumeAsync</c> returns the resumed result. Before this it returned nothing at all, and
    /// the agent's final answer after a durable resume was unreachable through the public API —
    /// which is why the reporting consumer stopped using <c>Resume</c> altogether.
    /// </summary>
    [Fact]
    public async Task ResumeReturnsTheResumedResult()
    {
        var rec = new Recorder(CallTool("check_secret"), Say("resumed answer"));
        var agent = new SubAgent("durable", new AgentSpec
        {
            Does = "suspends", Uses = new List<ITool> { MockLlm.CheckSecret() },
        });
        var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
        var h = rt.Spawn(rt.Root, "durable").Handle!;

        var pending = await rt.RunTurnAsync(h, "go");
        Assert.Equal(AgentStatus.Pending, pending.Status);
        Assert.NotNull(pending.Pending);

        var resumed = await rt.ResumeAsync(Answer.Output(pending.Pending!.Id, "approved"));

        Assert.Equal(AgentStatus.Done, resumed.Status);
        Assert.Equal("resumed answer", resumed.Text);
        await rt.CloseAsync(rt.Root);
    }

    /// <summary>
    /// (A14) The <c>limit</c> vocabulary is CLOSED and canonical: it names the BUDGET FIELD that
    /// stopped the run, spelled as SPEC spells it on <c>Budget</c>, plus the two non-budget stops.
    /// Four ports were emitting four different vocabularies in the very field D3 added so that
    /// hosts could branch on it — #90's own complaint, re-created inside its own fix.
    /// </summary>
    [Fact]
    public void LimitVocabularyIsClosedAndCanonical()
    {
        Assert.Equal(
            new[]
            {
                "maxTurns", "maxTokens", "maxToolCalls", "maxWallMs", "maxChildren",
                "maxConcurrent", "maxDepth", "completion", "timeout",
            },
            StopLimit.All);

        // Every internal pool name this runtime uses maps onto the canonical set — an internal
        // name is an implementation detail and may not leak into the field.
        foreach (var pool in new[] { "tokens", "toolCalls", "wallMs", "children", "concurrent", "depth" })
            Assert.Contains(StopLimit.FromPool(pool), StopLimit.All);
        Assert.Equal("maxTokens", StopLimit.FromPool("tokens"));
        Assert.Equal("maxWallMs", StopLimit.FromPool("wallMs"));
        Assert.Equal("maxToolCalls", StopLimit.FromPool("toolCalls"));
    }

    /// <summary>
    /// (A18) THE INVARIANT, not the instance: <b>a limit stop MUST name its limit; a non-limit
    /// stop MUST leave it empty</b> — and every value must be a member of its own closed
    /// vocabulary. The wait-deadline bug (status <c>"timeout"</c>, <c>limit</c> empty — the two
    /// fields contradicting each other inside the very feature #90 asked for) was present in three
    /// of five finished ports. C# was correct only by accident, because one site happened to pass
    /// its constant explicitly; nothing stopped the next settle site from omitting it.
    ///
    /// <para>An instance test would not have caught the two LATENT sites this found: the
    /// <c>done</c> and <c>pending</c> branches forwarded <c>r.Limit</c> straight through, which
    /// reproduces the contradiction the moment a result arrives carrying a limit its status does
    /// not claim.</para>
    /// </summary>
    [Theory]
    [InlineData("done")]
    [InlineData("budget-incomplete")]
    [InlineData("maxturns-incomplete")]
    [InlineData("pending")]
    [InlineData("closed")]
    [InlineData("settled-closed")]
    [InlineData("timeout")]
    public async Task StatusAndLimitNeverContradictEachOther(string scenario)
    {
        var r = await DriveSettle(scenario);
        AssertStatusLimitInvariant(r, scenario);
    }

    /// <summary>The stops that NAME a limit. Everything else must leave the field empty.</summary>
    private static readonly HashSet<string> LimitStops = new()
    {
        AgentStatus.Incomplete, AgentStatus.Timeout,
    };

    private static void AssertStatusLimitInvariant(AgentResult r, string scenario)
    {
        Assert.Contains(r.Status, AgentStatus.All);          // the 7-value set
        if (LimitStops.Contains(r.Status))
        {
            Assert.False(string.IsNullOrEmpty(r.Limit),
                $"{scenario}: status \"{r.Status}\" is a LIMIT stop and must name its limit");
            Assert.Contains(r.Limit, StopLimit.All);         // the 9-value set
        }
        else
        {
            Assert.True(string.IsNullOrEmpty(r.Limit),
                $"{scenario}: status \"{r.Status}\" is not a limit stop, so Limit must be empty "
                + $"— got \"{r.Limit}\"");
        }
    }

    /// <summary>
    /// Drives ONE settle site per scenario. The closed branches are driven EXPLICITLY: golang's
    /// warning is that waiting on a handle with a zero timeout after a close hands back the
    /// handle's SETTLED LAST result (e.g. an earlier budget stop), not a fresh `closed` one — a
    /// test that did it that way would assert something other than what it claims.
    /// </summary>
    private static async Task<AgentResult> DriveSettle(string scenario)
    {
        switch (scenario)
        {
            case "done":
            {
                var rec = new Recorder(Say("finished"));
                var agent = new SubAgent("a", new AgentSpec { Does = "d" });
                var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
                var r = await rt.RunTurnAsync(rt.Spawn(rt.Root, "a").Handle!, "go");
                await rt.CloseAsync(rt.Root);
                Assert.Equal(AgentStatus.Done, r.Status);
                return r;
            }
            case "budget-incomplete":
            {
                var rec = new Recorder(Say("x"));
                var agent = new SubAgent("a", new AgentSpec { Does = "d", Budget = new Budget { MaxTokens = 1 } });
                var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
                var h = rt.Spawn(rt.Root, "a").Handle!;
                await rt.RunTurnAsync(h, "one");
                var r = await rt.RunTurnAsync(h, "two"); // the pool is drained ⇒ budget stop
                await rt.CloseAsync(rt.Root);
                Assert.Equal(AgentStatus.Incomplete, r.Status);
                return r;
            }
            case "maxturns-incomplete":
            {
                var rec = new Recorder(CallTool("lookup", "{\"q\":\"x\"}"));
                var agent = new SubAgent("a", new AgentSpec
                {
                    Does = "d", Uses = new List<ITool> { MockLlm.Lookup() },
                    Budget = new Budget { MaxTurns = 2 },
                });
                var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
                var r = await rt.RunTurnAsync(rt.Spawn(rt.Root, "a").Handle!, "go");
                await rt.CloseAsync(rt.Root);
                Assert.Equal(AgentStatus.Incomplete, r.Status);
                return r;
            }
            case "pending":
            {
                var rec = new Recorder(CallTool("check_secret"));
                var agent = new SubAgent("a", new AgentSpec
                {
                    Does = "d", Uses = new List<ITool> { MockLlm.CheckSecret() },
                });
                var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
                var r = await rt.RunTurnAsync(rt.Spawn(rt.Root, "a").Handle!, "go");
                Assert.Equal(AgentStatus.Pending, r.Status);
                return r;
            }
            case "closed":
            {
                // EXPLICIT: a turn started on an already-closed handle (the :474 settle site).
                var rec = new Recorder(Say("x"));
                var agent = new SubAgent("a", new AgentSpec { Does = "d" });
                var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
                var h = rt.Spawn(rt.Root, "a").Handle!;
                await rt.CloseAsync(h);
                var r = await rt.RunTurnAsync(h, "go");
                Assert.Equal(AgentStatus.Closed, r.Status);
                return r;
            }
            case "settled-closed":
            {
                // EXPLICIT: the SYNTHESISED closed result. The handle never ran, so it has no
                // LastResult for the settled view to hand back instead — which is precisely the
                // trap golang warned about.
                var rec = new Recorder(Say("x"));
                var agent = new SubAgent("a", new AgentSpec { Does = "d" });
                var rt = new AgentRuntime(RuntimeOpts(rec, agent.Registry()));
                var h = rt.Spawn(rt.Root, "a").Handle!;
                await rt.CloseAsync(h);
                var r = await rt.WaitAsync(h);
                Assert.Equal(AgentStatus.Closed, r.Status);
                return r;
            }
            case "timeout":
            {
                // The site the reported bug was about: a wait deadline that expires while the
                // child is still running. Driven on the VIRTUAL clock so the deadline is a
                // decision, not a race — a timing assertion racing the scheduler is not a test.
                var handler = new SubagentMockHandler
                {
                    SlowGate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously),
                };
                var clock = new VirtualClock();
                var opts = RuntimeOpts(handler, new Dictionary<string, AgentDef>
                {
                    ["slow"] = new() { Name = "slow", Does = "slow worker", Model = "m-slow" },
                });
                opts.Clock = clock;
                var rt = new AgentRuntime(opts);
                var h = rt.Spawn(rt.Root, "slow").Handle!;
                rt.Wake(h, "park");
                await Task.Delay(40); // let the turn reach the parked LLM call
                var wait = rt.WaitAsync(h, timeoutMs: 5_000);
                clock.Advance(TimeSpan.FromMilliseconds(5_001));
                var r = await wait.WaitAsync(TimeSpan.FromSeconds(5));
                handler.SlowGate.TrySetResult();
                Assert.Equal(AgentStatus.Timeout, r.Status);
                return r;
            }
            default: throw new ArgumentOutOfRangeException(nameof(scenario), scenario, null);
        }
    }

    /// <summary>
    /// (A19a) The two vocabularies are PUBLIC API, not internals. A host has to branch on
    /// <c>Limit</c> without hard-coding strings — that is the whole point of D3 and A14 — so
    /// <c>All</c> and every individual constant must be reachable from outside the assembly.
    ///
    /// <para>This asserts REFLECTED VISIBILITY rather than merely compiling against them, because
    /// the test project has <c>InternalsVisibleTo</c>: a constant demoted to <c>internal</c> would
    /// still compile here while breaking every real consumer. Naming stays port-local
    /// (ADDENDUM 5 — <c>AgentStatus</c> avoids the
    /// <c>System.Threading.Tasks.TaskStatus</c> collision); only the VALUES conform.</para>
    /// </summary>
    [Fact]
    public void StatusAndLimitVocabulariesArePublicApi()
    {
        foreach (var t in new[] { typeof(AgentStatus), typeof(RunStatus), typeof(StopLimit) })
        {
            Assert.True(t.IsPublic, $"{t.Name} must be public, not internal");

            var all = t.GetField("All", BindingFlags.Public | BindingFlags.Static);
            Assert.True(all != null, $"{t.Name}.All must be public and static");
            Assert.NotEmpty((IReadOnlyList<string>)all!.GetValue(null)!);

            // Every value in `All` must also exist as a public named constant, so a host can write
            // StopLimit.MaxWallMs rather than the string "maxWallMs".
            var constants = t.GetFields(BindingFlags.Public | BindingFlags.Static | BindingFlags.FlattenHierarchy)
                .Where(f => f.IsLiteral && f.FieldType == typeof(string))
                .Select(f => (string)f.GetRawConstantValue()!)
                .ToHashSet(StringComparer.Ordinal);
            foreach (var value in (IReadOnlyList<string>)all.GetValue(null)!)
                Assert.Contains(value, constants);
        }

        // (A19b) And NO public invariant-predicate helper: the invariant lives in the theory
        // above, test-only. Shipping a predicate makes the library's own rule a host's dependency.
        Assert.DoesNotContain(
            typeof(StopLimit).GetMethods(BindingFlags.Public | BindingFlags.Static),
            m => m.ReturnType == typeof(bool));
    }

    /// <summary>The two status vocabularies are distinct and now both have names (ADR 0027).</summary>
    [Fact]
    public void TheTwoStatusVocabulariesAreNamedAndDistinct()
    {
        Assert.Equal(7, AgentStatus.All.Count);
        Assert.Equal(3, RunStatus.All.Count);
        Assert.Contains(AgentStatus.Timeout, AgentStatus.All);
        Assert.DoesNotContain("timeout", RunStatus.All); // the #92.1 collision, pinned
        Assert.All(RunStatus.All, s => Assert.Contains(s, AgentStatus.All));
    }

    // ================================================================ D4 (#89, ADR 0026)

    /// <summary>
    /// The typed constructor is the fix that REMOVES the failure mode rather than reporting it:
    /// the payload map stops being hand-built, so there is no key left to spell wrong.
    /// </summary>
    [Fact]
    public void AnswerOutputBuildsThePinnedPayload()
    {
        var a = Answer.Output("req-1", "the result");

        Assert.Equal("req-1", a.Id);
        Assert.True(a.Ok);
        Assert.Equal("the result", a.Data!["output"]);

        // A non-string output cannot degrade to "" — the type is the contract.
        Assert.Throws<ArgumentNullException>(() => Answer.Output("req-1", null!));

        var d = Answer.Declined("req-1", "declined");
        Assert.False(d.Ok);
        Assert.Equal("declined", d.Reason);
    }

    /// <summary>The whole Answer still reaches <c>ctx.Answer</c> unfiltered on the inline
    /// <c>WaitFor</c> path — the part that was already at parity in all seven ports.</summary>
    [Fact]
    public async Task AnswerOutputFlowsThroughWaitForIntact()
    {
        string? seen = null;
        var tool = NativeTool.Of("ask", "asks",
            new Dictionary<string, object?> { ["type"] = "object", ["properties"] = new Dictionary<string, object?>() },
            (IDictionary<string, object?> _, ToolContext? ctx) =>
            {
                if (ctx?.Answer?.Ok != true)
                    return ToolResult.Pending(new Request { Id = "r1", Kind = "input", Prompt = "value?" });
                seen = ctx.Answer.Data!["output"]?.ToString();
                return (object)("got " + seen);
            });

        var rec = new Recorder(CallTool("ask"), Say("finished"));
        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options().WithBuiltins(false));
        tk.Register(tool);
        var client = LlmClient.Create(Opts(rec, o => o.WaitFor = req => Task.FromResult(Answer.Output(req.Id, "42"))));

        await client.RunAsync("go", tk);

        Assert.Equal("42", seen);
    }

    // ================================================================ D5 (#91/#92, ADR 0027)

    /// <summary>
    /// The preset sets base + model + credential env-var name AS A UNIT, because the three only
    /// ever make sense together and mixing them is the reported failure.
    /// </summary>
    [Fact]
    public void ClassifierBackendPresetSetsTheThreeAsAUnit()
    {
        var ts = new Classifier(new ClassifierOptions().WithBackend(ClassifierBackend.TypeSafe));
        Assert.Equal(Classifier.DefaultBaseUrl, ts.BaseUrl);
        Assert.Equal(Classifier.DefaultModel, ts.Model);
        Assert.Equal(Classifier.DefaultApiKeyEnv, ts.ApiKeyEnv);

        var or = new Classifier(new ClassifierOptions().WithBackend(ClassifierBackend.OpenRouter));
        Assert.Equal(Classifier.OpenRouterBaseUrl, or.BaseUrl);
        Assert.Equal(Classifier.OpenRouterModel, or.Model);
        Assert.Equal(Classifier.OpenRouterApiKeyEnv, or.ApiKeyEnv);

        // An explicit option still wins over the preset.
        var mixed = new Classifier(new ClassifierOptions()
            .WithBackend(ClassifierBackend.OpenRouter).WithModel("typesafe/jev-1.13").WithApiKeyEnv("MY_KEY"));
        Assert.Equal("MY_KEY", mixed.ApiKeyEnv);
    }

    /// <summary>The known mismatch fails at CONSTRUCTION, naming the correct spelling — not at the
    /// first 404, in production, saying nothing.</summary>
    [Fact]
    public void ClassifierRefusesTypeSafeAliasOnOpenRouter()
    {
        var e = Assert.Throws<ClassifierException>(() => new Classifier(new ClassifierOptions()
            .WithBaseUrl(Classifier.OpenRouterBaseUrl).WithModel(Classifier.DefaultModel)));

        Assert.Contains("jev-latest", e.Message);
        Assert.Contains("typesafe/jev-1.13", e.Message);
    }

    /// <summary>
    /// A CAP IS NOT REDACTION. The leaking body that motivated this is 96 bytes and sails through a
    /// 200-character cap untouched, so the account-identifier keys are replaced BEFORE the cap.
    /// The raw body stays reachable on the typed error for a host that genuinely wants it.
    /// </summary>
    [Fact]
    public async Task ProviderErrorIsTypedAndRedacted()
    {
        const string leaky = "{\"error\":{\"message\":\"no credit\",\"user_id\":\"user_2abcdefghijkl\","
                             + "\"org_id\":\"org_9\",\"account_id\":\"acct_7\",\"organization\":\"acme\"}}";
        var rec = new Recorder { Failure = (402, leaky), RetryAfter = "3" };
        var client = LlmClient.Create(Opts(rec, o => o.Retries = 0));

        var e = await Assert.ThrowsAsync<LlmClient.ProviderException>(
            async () => await client.RunAsync("p", (Toolkit?)null));

        Assert.Equal(402, e.Status);
        Assert.Equal(3000, e.RetryAfterMs);
        // (A5) The typed field is REDACTED TOO — a typed error is not a hole in the guarantee —
        // but UNCAPPED, because a host that opted into it asked for the whole thing.
        Assert.DoesNotContain("user_2abcdefghijkl", e.Body);
        Assert.Contains(LlmClient.RedactionToken, e.Body);
        Assert.Contains("no credit", e.Body);
        Assert.DoesNotContain("…", e.Body);
        foreach (var leaked in new[] { "user_2abcdefghijkl", "org_9", "acct_7", "acme" })
            Assert.DoesNotContain(leaked, e.Message);
        Assert.Contains(LlmClient.RedactionToken, e.Message);
        Assert.Contains("no credit", e.Message); // the shape and the real cause survive
        // Still an InvalidOperationException, so existing catch blocks keep working.
        Assert.IsAssignableFrom<InvalidOperationException>(e);
    }

    /// <summary>A 401/403 body routinely reflects the credential that was sent, so it never
    /// reaches a message at all.</summary>
    [Fact]
    public async Task AuthFailureBodyIsNeverEchoed()
    {
        var rec = new Recorder { Failure = (401, "{\"error\":\"invalid key sk-live-DEADBEEF\"}") };
        var client = LlmClient.Create(Opts(rec, o => o.Retries = 0));

        var e = await Assert.ThrowsAsync<LlmClient.ProviderException>(
            async () => await client.RunAsync("p", (Toolkit?)null));

        Assert.Equal("LLM 401", e.Message);
        Assert.DoesNotContain("DEADBEEF", e.Message);
    }

    /// <summary>
    /// MUST NOT REGRESS #1: fail-fast on 4xx despite a retry budget. The mechanism is an
    /// ENUMERATED retryable set — never "any 5xx" — so a 400 costs exactly one attempt while a 503
    /// spends the budget.
    /// </summary>
    [Fact]
    public async Task FailFastOn4xxButRetryTheEnumeratedSet()
    {
        var bad = new Recorder { Failure = (400, "{\"error\":\"bad request\"}") };
        await Assert.ThrowsAsync<LlmClient.ProviderException>(async () =>
            await LlmClient.Create(Opts(bad, o => { o.Retries = 4; o.RetryBaseMs = 1; }))
                .RunAsync("p", (Toolkit?)null));
        Assert.Equal(1, bad.Hits); // exactly one, with Retries = 4

        var retryable = new Recorder { Failure = (503, "{\"error\":\"try later\"}") };
        await Assert.ThrowsAsync<LlmClient.ProviderException>(async () =>
            await LlmClient.Create(Opts(retryable, o => { o.Retries = 2; o.RetryBaseMs = 1; }))
                .RunAsync("p", (Toolkit?)null));
        Assert.Equal(3, retryable.Hits); // the initial attempt plus two retries

        foreach (var s in new[] { 429, 500, 502, 503, 504, 529 })
            Assert.True(LlmClient.IsRetryableStatus(s), $"{s} must stay in the enumerated set");
        foreach (var s in new[] { 400, 401, 403, 404, 422, 501 })
            Assert.False(LlmClient.IsRetryableStatus(s), $"{s} must NOT be retryable");
    }

    /// <summary>A timeout message names the budget that produced it, so the reader knows which
    /// knob to turn.</summary>
    [Fact]
    public async Task TimeoutMessageNamesTheBudget()
    {
        using var stub = new StubServer(ctx =>
        {
            Thread.Sleep(600);
            StubServer.Respond(ctx, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"late\"}}]}");
        });
        var client = LlmClient.Create(new LlmClient.Options
        {
            BaseUrl = stub.BaseUrl, Style = "openai", Model = "m",
            ApiKey = "not-a-real-key", TimeoutMs = 60, Retries = 0,
        });

        var e = await Assert.ThrowsAsync<LlmClient.RunTimeoutException>(
            async () => await client.RunAsync("p", (Toolkit?)null));

        Assert.Contains("60ms", e.Message);
        Assert.Contains("TimeoutMs", e.Message); // names the budget, not just the number
    }

    /// <summary>
    /// MUST NOT REGRESS #2: <c>ClassifierUsage.Cost</c> is an OPTIONAL — absent is not zero. ADR
    /// 0022 records why; "simplifying" it to a plain <c>double</c> would make every backend that
    /// does not report cost print <c>$0.00</c>.
    /// </summary>
    [Fact]
    public void ClassifierUsageCostStaysOptional()
    {
        Assert.Null(new ClassifierUsage().Cost);
        Assert.Equal(0d, new ClassifierUsage { Cost = 0 }.Cost); // a real zero is a real answer
        Assert.Equal(typeof(double?), typeof(ClassifierUsage).GetProperty(nameof(ClassifierUsage.Cost))!.PropertyType);
    }

    // ================================================================ D6 (#93, ADR 0028)

    private static string Skills(params (string Dir, string Text)[] files)
    {
        var root = Path.Combine(Path.GetTempPath(), "tn-skills-" + Guid.NewGuid().ToString("N"));
        foreach (var (dir, text) in files)
        {
            var d = Path.Combine(root, dir);
            Directory.CreateDirectory(d);
            File.WriteAllText(Path.Combine(d, "SKILL.md"), text);
        }
        return root;
    }

    /// <summary>
    /// The inversion IS the fix. The line-wise <c>key: rest-of-line</c> read runs ONLY on
    /// frontmatter a real YAML parser has already refused — <c>colon-space</c> here — so a file
    /// whose YAML semantics matter can never reach it.
    /// </summary>
    [Fact]
    public void LenientRescueRecoversOnlyWhatYamlRefused()
    {
        var root = Skills(
            // YAML refuses this (`a: b` inside an unquoted plain scalar) — the rescue takes it.
            ("colon-space", "---\nname: colon-space\ndescription: Use when: you need a thing\n---\nbody\n"),
            // Valid YAML with a block scalar — must go the STRICT path, byte-identical.
            ("block-literal", "---\nname: block-literal\ndescription: |\n  line one\n  line two\n---\nbody\n"),
            ("block-folded", "---\nname: block-folded\ndescription: >\n  folded one\n  folded two\n---\nbody\n"));

        var inv = SkillSource.ListSkills(new SkillSource.LoadOptions { Dirs = new[] { root } });

        Assert.Empty(inv.Skipped);
        var byName = inv.Skills.ToDictionary(s => s.Name);
        Assert.Equal("Use when: you need a thing", byName["colon-space"].Description);
        // Block scalars keep YAML's own rendering — the rescue never saw them.
        Assert.Equal("line one\nline two", byName["block-literal"].Description);
        Assert.Equal("folded one folded two", byName["block-folded"].Description);
        Directory.Delete(root, true);
    }

    /// <summary>
    /// The guards: column 0 only, first-wins, and a value opening a construct the rescue does not
    /// implement is REFUSED rather than taken. A half-broken block scalar degrades to "no
    /// description" — never to garbage, and never to an invented description.
    /// </summary>
    [Fact]
    public void LenientRescueRefusesConstructsItDoesNotImplement()
    {
        var root = Skills(
            // YAML refuses (tab indent); `description: |` opens a block scalar the rescue declines.
            ("broken-block", "---\nname: broken-block\ndescription: |\n\tbad tab indent: x: y\n---\nbody\n"),
            // First wins: the second `name` is ignored.
            ("dup", "---\nname: first-name\nname: second: name\n---\nbody\n"),
            // An indented line is a continuation, not a top-level key.
            ("indented", "---\n  name: indented: x\ndescription: d: e\n---\nbody\n"));

        var inv = SkillSource.ListSkills(new SkillSource.LoadOptions { Dirs = new[] { root } });
        var byName = inv.Skills.ToDictionary(s => s.Name);

        Assert.True(byName.ContainsKey("broken-block"));
        Assert.Null(byName["broken-block"].Description); // NOT invented, NOT garbage
        Assert.True(byName.ContainsKey("first-name"));   // first wins
        // The indented file has no column-0 `name` the rescue will take ⇒ still refused.
        Assert.Contains(inv.Skipped, s => s.Location.Contains("indented"));
        Directory.Delete(root, true);
    }

    /// <summary>A genuinely malformed file is STILL refused — leniency is not permissiveness.</summary>
    [Fact]
    public void GenuinelyMalformedFilesAreStillRefusedAndCarryDetail()
    {
        var root = Skills(
            ("broken-flow", "---\nname: [unclosed\ndescription: x\n---\nbody\n"),
            ("no-name", "---\ndescription: has no name\n---\nbody\n"));

        var inv = SkillSource.ListSkills(new SkillSource.LoadOptions { Dirs = new[] { root } });

        Assert.Empty(inv.Skills);
        Assert.Equal(2, inv.Skipped.Count);
        var flow = inv.Skipped.First(s => s.Location.Contains("broken-flow"));
        // `reason` stays one of the four byte-identical strings; `detail` is the new carrier.
        Assert.Equal(SkillSource.SkillSkip.Malformed, flow.Reason);
        Assert.NotEqual("", flow.Detail); // the native parser error, verbatim
        Directory.Delete(root, true);
    }

    /// <summary>
    /// (A1c) CODE-POINT order, not UTF-16 code-unit order. Above U+FFFF the two DISAGREE: a
    /// surrogate pair (0xD800–0xDBFF) sorts BELOW an unpaired BMP character in U+E000–U+FFFF, so
    /// <c>string.CompareOrdinal</c> would put an emoji-named directory before a "\uFB00"-named one
    /// while Go/Python/Elixir put it after. First-wins makes that a WINNER difference, not a
    /// cosmetic one. ASCII and BMP paths are unaffected.
    /// </summary>
    [Fact]
    public void DiscoveryOrderComparesCodePointsNotCodeUnits()
    {
        const string astral = "\U0001F600";   // U+1F600, a surrogate pair in UTF-16
        const string bmpHigh = "\uFB00";       // U+FB00, a single code unit ABOVE the surrogates

        // Both fixtures are top-level, so depth ties and A1a's code-point rule decides.
        // The unit: code-point order puts U+FB00 first; UTF-16 code-unit order would not.
        Assert.True(SkillSource.CompareCodePoints(bmpHigh, astral) < 0);
        Assert.True(string.CompareOrdinal(bmpHigh, astral) > 0, "the two orders must genuinely differ here");

        var root = Skills(
            (astral, "---\nname: dup\ndescription: the ASTRAL one\n---\nbody\n"),
            (bmpHigh, "---\nname: dup\ndescription: the BMP one\n---\nbody\n"));

        var inv = SkillSource.ListSkills(new SkillSource.LoadOptions { Dirs = new[] { root } });

        Assert.Equal("the BMP one", Assert.Single(inv.Skills).Description);
        Directory.Delete(root, true);
    }

    /// <summary>
    /// (A22) The §0.10 SKILLS PROMPT is pinned BYTE-IDENTICAL across ports, so the order it lists
    /// skills in is contract, not presentation. It was sorted with <c>StringComparer.Ordinal</c> —
    /// UTF-16 CODE-UNIT order — which is the exact distinction A1c drew for the discovery sort, in
    /// a DIFFERENT sort nobody had looked at. One astral-plane skill name would have made this
    /// port emit a differently ordered prompt than golang/python/elixir.
    ///
    /// <para>The <c>skill</c> tool's "Available skills: …" list is the same class of output and is
    /// fixed alongside it.</para>
    /// </summary>
    [Fact]
    public async Task PromptAndNotFoundListOrderByCodePoint()
    {
        const string astral = "a\U0001F600";   // a + U+1F600, a surrogate pair in UTF-16
        const string bmpHigh = "a\uFB00";       // a + U+FB00, one code unit ABOVE the surrogates

        // The two rules genuinely disagree here, or this fixture proves nothing.
        Assert.True(SkillSource.CompareCodePoints(bmpHigh, astral) < 0);
        Assert.True(StringComparer.Ordinal.Compare(bmpHigh, astral) > 0);

        var root = Skills(
            (astral, $"---\nname: {astral}\ndescription: the astral one\n---\nbody\n"),
            (bmpHigh, $"---\nname: {bmpHigh}\ndescription: the bmp one\n---\nbody\n"));
        var src = SkillSource.Load(root);

        // The prompt lists U+FB00 BEFORE U+1F600 — code-point order, as every other port emits.
        var prompt = src.Prompt();
        Assert.True(prompt.IndexOf(bmpHigh, StringComparison.Ordinal)
                    < prompt.IndexOf(astral, StringComparison.Ordinal),
            "the §0.10 prompt must list skills in CODE-POINT order");

        // ...and so does the `skill` tool's not-found list.
        var miss = await src.Tool.ExecuteAsync(new Dictionary<string, object?> { ["name"] = "nope" });
        Assert.True(miss.IsError);
        Assert.True(miss.Output.IndexOf(bmpHigh, StringComparison.Ordinal)
                    < miss.Output.IndexOf(astral, StringComparison.Ordinal),
            "the not-found list must use the same order as the prompt");

        Directory.Delete(root, true);
    }

    /// <summary>
    /// (A22 4th site / A25) The <c>&lt;skill_files&gt;</c> SAMPLE LIST is model-visible output.
    /// It was built from raw <c>Directory.EnumerateFileSystemEntries</c> order — the filesystem's
    /// order, which promises nothing — and because the traversal was bounded by the sample cap,
    /// an unsorted read changed WHICH files appear, not merely their order. That is what makes it
    /// a CONTENT bug.
    ///
    /// <para>(A25) The rule is COLLECT → SORT by path relative to the skill dir, plain code point
    /// → TRUNCATE. Sorting during the walk would make the answer a function of each port's stack
    /// discipline; five ports fixed A24 five different ways, which would have been a byte-identity
    /// break introduced by the fix for one. A NESTED tree is what separates the two rules: a
    /// global relative-path sort interleaves directories and files, a per-directory sort cannot.</para>
    /// </summary>
    [Fact]
    public async Task SampledSkillFilesAreOrderedByRelativePathThenCapped()
    {
        // `ß` (U+00DF), not an accented letter: it collates as "ss" but is 0xDF by code point, and
        // it has NO NFD decomposition — so macOS filename normalisation cannot make this vacuous
        // the way `café` would, where the accent is mid-word and discriminates nothing.
        var root = Path.Combine(Path.GetTempPath(), "tn-sample-" + Guid.NewGuid().ToString("N"));
        var dir = Path.Combine(root, "res");
        Directory.CreateDirectory(dir);
        Directory.CreateDirectory(Path.Combine(dir, "b-nested"));
        File.WriteAllText(Path.Combine(dir, "SKILL.md"), "---\nname: res\ndescription: d\n---\nbody\n");

        foreach (var n in new[] { "zeta.txt", "alpha.txt", "m\U0001F600.txt", "m\uFB00.txt", "stra\u00DFe.txt" })
            File.WriteAllText(Path.Combine(dir, n), "x");
        // Named so that SORTING BY BARE NAME and SORTING BY RELATIVE PATH disagree: by relative
        // path these sit right after "alpha.txt" (the "b-" prefix), by bare name they sort LAST.
        foreach (var n in new[] { "zz-b.txt", "zz-a.txt" })
            File.WriteAllText(Path.Combine(dir, "b-nested", n), "x");

        var src = SkillSource.Load(root);
        var listed = SampledNames(await src.Tool.ExecuteAsync(
            new Dictionary<string, object?> { ["name"] = "res" }));

        // ONE code-point sort over paths RELATIVE to the skill dir. The nested files sort by
        // their DIRECTORY's "b-" prefix, immediately after "alpha.txt" — even though their own
        // names ("zz-…") would put them last. That is what separates a relative-path sort from a
        // bare-name sort, and from any per-directory sort during the walk, whatever stack
        // discipline it used.
        Assert.Equal(
            new[]
            {
                "alpha.txt",
                "b-nested/zz-a.txt",
                "b-nested/zz-b.txt",
                "m\uFB00.txt",          // U+FB00 before U+1F600: code point, not UTF-16 code unit
                "m\U0001F600.txt",
                "stra\u00DFe.txt",      // 0xDF sorts after 'm', though it COLLATES as "ss"
                "zeta.txt",
            },
            listed);

        // The CAP is applied LAST, so it selects a PREFIX of that order — never whatever the
        // filesystem happened to yield first. This is the content half of the bug.
        var capped = SkillSource.LoadWith(new SkillSource.LoadOptions
        {
            Dirs = new[] { root }, SampleLimit = 3,
        });
        Assert.Equal(
            new[] { "alpha.txt", "b-nested/zz-a.txt", "b-nested/zz-b.txt" },
            SampledNames(await capped.Tool.ExecuteAsync(
                new Dictionary<string, object?> { ["name"] = "res" })));

        Directory.Delete(root, true);
    }

    /// <summary>The <c>&lt;file&gt;</c> lines of a skill result, as paths relative to the skill dir.</summary>
    private static List<string> SampledNames(ToolResult r)
    {
        var dir = (string)r.Metadata!["dir"]!;
        return r.Output.Split('\n')
            .Where(l => l.StartsWith("<file>", StringComparison.Ordinal))
            .Select(l => Path.GetRelativePath(dir, l[6..^7]).Replace('\\', '/'))
            .ToList();
    }

    /// <summary>
    /// (A26) The rule governs EVERY capped listing of filesystem entries that reaches the model,
    /// not only the skill sample. <c>glob</c> broke at the cap UPSTREAM of its sort, so the
    /// FILESYSTEM chose which files the model saw and the sort merely ordered the survivors;
    /// <c>grep</c> broke at the cap and never sorted at all. Both are CONTENT bugs.
    /// </summary>
    [Theory]
    [InlineData("glob")]
    [InlineData("grep")]
    public async Task CappedBuiltinListingsSortBeforeTruncating(string tool)
    {
        // A tree whose DIRECTORY names order differently from the file names inside them, so a
        // relative-path sort is distinguishable from a bare-name one; `ß` (U+00DF) collates as
        // "ss" but is 0xDF by code point and has no NFD decomposition, so macOS filename
        // normalisation cannot make this vacuous.
        var root = Path.Combine(Path.GetTempPath(), "tn-builtin-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(Path.Combine(root, "a-dir"));
        Directory.CreateDirectory(Path.Combine(root, "z-dir"));
        // The walk yields ROOT files before nested ones, so two root files fill a cap of 2 before
        // "a-dir/zz.txt" — which sorts FIRST — is ever reached. That makes the filesystem-order
        // prefix and the sorted prefix different SETS, not merely different orders. A fixture
        // where they coincide proves nothing; mine did, until this.
        File.WriteAllText(Path.Combine(root, "a-dir", "zz.txt"), "needle\n");
        File.WriteAllText(Path.Combine(root, "m\u00DF.txt"), "needle\n");
        File.WriteAllText(Path.Combine(root, "n.txt"), "needle\n");
        File.WriteAllText(Path.Combine(root, "z-dir", "aa.txt"), "needle\n");

        await using var tk = await Toolkit.CreateAsync(new Toolkit.Options());
        var t = tk.Get(tool)!;

        // Uncapped: the full, deterministic order — relative path in code point.
        var all = await t.ExecuteAsync(tool == "glob"
            ? new Dictionary<string, object?> { ["pattern"] = "**/*.txt", ["path"] = root }
            : new Dictionary<string, object?> { ["pattern"] = "needle", ["path"] = root });
        var order = Lines(all.Output).Select(l => Rel(root, tool, l)).ToList();
        Assert.Equal(
            tool == "glob"
                ? new[] { "a-dir/zz.txt", "m\u00DF.txt", "n.txt", "z-dir/aa.txt" }
                : new[] { "a-dir/zz.txt:1", "m\u00DF.txt:1", "n.txt:1", "z-dir/aa.txt:1" },
            order);

        // CAPPED: a PREFIX of that same order. Under the old mid-walk break this was whatever the
        // filesystem yielded first — which is the content half of the defect.
        var capped = await t.ExecuteAsync(tool == "glob"
            ? new Dictionary<string, object?> { ["pattern"] = "**/*.txt", ["path"] = root, ["limit"] = 2d }
            : new Dictionary<string, object?> { ["pattern"] = "needle", ["path"] = root, ["limit"] = 2d });
        Assert.Equal(order.Take(2), Lines(capped.Output).Select(l => Rel(root, tool, l)));
        Assert.Equal(2L, capped.Metadata!["count"]);

        Directory.Delete(root, true);
    }

    private static List<string> Lines(string s) =>
        s.Split('\n').Where(l => l.Length > 0).ToList();

    /// <summary>(A28) BOTH tools emit RELATIVE, <c>/</c>-separated paths — the same strings they
    /// sort on — so this helper asserts that rather than repairing it.</summary>
    private static string Rel(string root, string tool, string line)
    {
        Assert.DoesNotContain('\\', line);                    // never a native separator
        Assert.False(Path.IsPathRooted(line.Split(':')[0]));  // never an absolute path
        return tool == "glob" ? line : string.Join(":", line.Split(':').Take(2));
    }

    /// <summary>
    /// (A10) THE SHARED FIXTURE TABLE IS THE ARBITER. <c>spikes/issues/93/fixtures/</c> is one
    /// directory of 17 SKILL.md files that every port must accept, skip and detail IDENTICALLY.
    /// It is what catches the YAML-library divergence: YamlDotNet THROWS on
    /// <c>description: [unterminated</c> so the rescue runs, while JS's <c>yaml</c> recovers it
    /// into a sequence so its rescue never does — both must still land on the same row.
    /// </summary>
    [Fact]
    public void SharedFixtureTableMatchesTheSevenPortContract()
    {
        var dir = SharedFixturesDir();
        Assert.True(Directory.Exists(dir), $"shared fixtures missing at {dir}");

        var inv = SkillSource.ListSkills(new SkillSource.LoadOptions { Dirs = new[] { dir } });
        var rows = new SortedDictionary<string, string>(StringComparer.Ordinal);
        foreach (var s in inv.Skills)
            rows[Path.GetFileName(Path.GetDirectoryName(s.Location)!)] = "ok\t" + (s.Description ?? "<none>");
        foreach (var k in inv.Skipped)
            rows[Path.GetFileName(Path.GetDirectoryName(k.Location)!)] = "skip\t" + k.Reason;

        // The contract table. A description here is the FULL string the port must produce — an
        // invented or truncated one fails, and so does silently keeping a structurally-wrong one.
        var expected = new SortedDictionary<string, string>(StringComparer.Ordinal)
        {
            ["anchors"] = "ok\tA skill using a YAML anchor and alias.",
            ["block-folded"] = "ok\tA folded description that runs across two source lines.",
            ["block-literal"] = "ok\tFirst line of the description.\nSecond line, with a colon: still fine inside a block scalar.",
            // YAML refuses (or mis-recovers) the unterminated flow sequence; the rescue declines a
            // value opening `[`, so the file keeps its NAME and gains NO description.
            ["broken-flow"] = "ok\t<none>",
            ["broken-tab"] = "ok\ttab-indented continuation",
            // No closing fence ⇒ no frontmatter at all ⇒ no name.
            ["broken-unclosed"] = "skip\tmissing-name",
            ["colon-space"] = "ok\tWork out billable hours from git commits and session logs. Trigger on: update the timesheet, do my timesheet, how many hours did I work.",
            ["colon-space-quoted"] = "ok\tWork out billable hours. Trigger on: update the timesheet, do my timesheet.",
            ["colon-space-single"] = "ok\tWork out billable hours. Trigger on: update the timesheet.",
            // ` #` opens a YAML comment, so the STRICT path truncates here — and it is strict YAML
            // that succeeded, so the rescue never ran. Every port's YAML does the same; this row
            // is the contract, not a defect of this port.
            ["hash-inline"] = "ok\tTag things with",
            ["list-block"] = "ok\tA skill with a block-sequence key.",
            ["list-value"] = "ok\tA skill that also declares a list-valued key.",
            ["nested-map"] = "ok\tA skill with a nested mapping key.",
            ["no-frontmatter"] = "skip\tmissing-name",
            ["no-name"] = "skip\tmissing-name",
            ["plain"] = "ok\tAn ordinary skill with an ordinary one-line description.",
            ["url-colon"] = "ok\tFetch pages from https://example.com/docs and summarise them.",
        };

        Assert.Equal(
            string.Join("\n", expected.Select(kv => kv.Key + "\t" + kv.Value)),
            string.Join("\n", rows.Select(kv => kv.Key + "\t" + kv.Value)));

        // `detail` carries the native parser error wherever the strict path refused; `reason`
        // stays one of the four byte-identical strings.
        Assert.All(inv.Skipped, k => Assert.Contains(k.Reason, new[]
        {
            SkillSource.SkillSkip.MissingName, SkillSource.SkillSkip.Malformed,
            SkillSource.SkillSkip.DuplicateName, SkillSource.SkillSkip.Unreadable,
        }));
    }

    private static string SharedFixturesDir()
    {
        foreach (var start in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
        {
            var d = new DirectoryInfo(start);
            while (d != null)
            {
                var c = Path.Combine(d.FullName, "spikes", "issues", "93", "fixtures");
                if (Directory.Exists(c)) return c;
                d = d.Parent;
            }
        }
        return "";
    }

    /// <summary>
    /// (A1/A1a) DISCOVERY ORDER is part of the contract, because first-wins decides WHICH of two
    /// same-named skills survives — and <c>docx</c>/<c>pdf</c>/<c>pptx</c> demonstrably resolved to
    /// DIFFERENT FILES per port before this. The rule: dirs in the caller's order; within a dir,
    /// DEPTH ASCENDING (A15), then ORDINAL (code-point) comparison of the path relative to that
    /// dir. Not
    /// <c>string.CompareTo</c>/<c>Comparer&lt;string&gt;.Default</c>, which are culture-sensitive
    /// and would diverge from the other six ports on non-ASCII paths.
    /// </summary>
    [Fact]
    public void DiscoveryOrderIsOrdinalAndShallowWins()
    {
        // (A15) THE FIXTURE THAT ACTUALLY PROVES THE RULE. `xlsx` begins with `x`, which sorts
        // AFTER `synced/`, so under a pure code-point sort the NESTED copy would win — while the
        // otherwise-identical `docx` case would pass, because `d` sorts before `s`. A docx-only
        // fixture is satisfied by both rules and therefore proves nothing; this pair separates
        // them. Depth-first makes the shallow one win for EVERY name.
        var root = Skills(
            ("synced/a1b2c3/xlsx", "---\nname: xlsx\ndescription: the DEEP xlsx\n---\nbody\n"),
            ("xlsx", "---\nname: xlsx\ndescription: the SHALLOW xlsx\n---\nbody\n"),
            ("synced/a1b2c3/docx", "---\nname: docx\ndescription: the DEEP docx\n---\nbody\n"),
            ("docx", "---\nname: docx\ndescription: the SHALLOW docx\n---\nbody\n"));

        var inv = SkillSource.ListSkills(new SkillSource.LoadOptions { Dirs = new[] { root } });
        var byName = inv.Skills.ToDictionary(s => s.Name);

        Assert.Equal("the SHALLOW xlsx", byName["xlsx"].Description); // the case that used to fail
        Assert.Equal("the SHALLOW docx", byName["docx"].Description); // and the one that did not
        Assert.Equal(2, inv.Skipped.Count(k => k.Reason == SkillSource.SkillSkip.DuplicateName));

        // The comparator itself: depth beats code point, and code point breaks a depth tie.
        Assert.True(SkillSource.CompareDiscovery("xlsx/SKILL.md", "synced/a/xlsx/SKILL.md") < 0);
        Assert.True(SkillSource.CompareCodePoints("xlsx/SKILL.md", "synced/a/xlsx/SKILL.md") > 0,
            "the two rules must genuinely disagree here, or the fixture proves nothing");
        Assert.True(SkillSource.CompareDiscovery("a/x/SKILL.md", "a/y/SKILL.md") < 0);

        // Caller dir order beats within-dir order: the SAME two trees, passed deep-first.
        var deep = Skills(("docx", "---\nname: docx\ndescription: from dir B\n---\nbody\n"));
        var shallow = Skills(("docx", "---\nname: docx\ndescription: from dir A\n---\nbody\n"));
        var ordered = SkillSource.ListSkills(new SkillSource.LoadOptions { Dirs = new[] { deep, shallow } });
        Assert.Equal("from dir B", Assert.Single(ordered.Skills).Description);

        Directory.Delete(root, true);
        Directory.Delete(deep, true);
        Directory.Delete(shallow, true);
    }

    /// <summary>
    /// A load that silently drops files is indistinguishable from a directory that never had them.
    /// <c>LoadWith</c> now exposes the skips both as a hook and as a property, so a host does not
    /// need a second <c>ListSkills</c> pass to learn that 6 of 87 skills vanished.
    /// </summary>
    [Fact]
    public void LoadExposesItsSkips()
    {
        var root = Skills(
            ("good", "---\nname: good\ndescription: fine\n---\nbody\n"),
            ("bad", "---\nname: [unclosed\n---\nbody\n"));

        var src = SkillSource.LoadWith(new SkillSource.LoadOptions { Dirs = new[] { root } });

        // (A2) RETURNED DATA on the result, not a hook: a hook shape differs per port and cannot
        // be a parity gate.
        Assert.Single(src.Skills);
        Assert.Single(src.Skipped);
        Assert.Equal(SkillSource.SkillSkip.Malformed, src.Skipped[0].Reason);
        Directory.Delete(root, true);
    }
}
