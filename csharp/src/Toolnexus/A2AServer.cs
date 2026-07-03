using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Logging.Abstractions;
using ModelContextProtocol;
using ModelContextProtocol.Protocol;
using ModelContextProtocol.Server;

namespace Toolnexus;

// ---------------------------------------------------------------------------
// A2A (agent-to-agent) — INBOUND: serve a toolkit as a remote A2A agent.
//
// `toolkit.ServeAsync(addr, { Client, A2A })` stands up a minimal HTTP server
// (HttpListener) that, when the `a2a` profile is present, exposes:
//
//   GET  /.well-known/agent-card.json  → an Agent Card built from the toolkit's
//        SKILLS (SKILL.md name+description), never raw tools.
//   POST /                             → JSON-RPC 2.0: `SendMessage` (submit a
//        Task, fulfil it asynchronously via `client.Run`) + `GetTask` (poll).
//
// This is the inbound counterpart to A2A.cs (outbound) and speaks the same wire
// subset. Task persistence is a pluggable `ITaskStore` (in-memory default; file
// / custom selectable via `a2a.Store`). Reuses the A2A.cs wire types
// (A2ATask/A2AArtifact/...). Mirrors the JS reference (js/src/serve.ts)
// byte-for-byte. See ../../SPEC.md §7B and openspec/changes/add-a2a-agents.
// ---------------------------------------------------------------------------

// ===========================================================================
// TaskStore adapter — pluggable persistence for served Tasks.
// ===========================================================================

/// <summary>Pluggable Task persistence. A host can plug NATS/JetStream/db the same way.</summary>
public interface ITaskStore
{
    Task<A2ATask?> GetAsync(string id);
    Task SaveAsync(A2ATask task);
}

/// <summary>Default in-memory store — Tasks live only for the lifetime of the process.</summary>
public sealed class InMemoryTaskStore : ITaskStore
{
    private readonly ConcurrentDictionary<string, A2ATask> _tasks = new();

    public Task<A2ATask?> GetAsync(string id)
        => Task.FromResult(_tasks.TryGetValue(id, out var t) ? t : null);

    public Task SaveAsync(A2ATask task)
    {
        _tasks[task.Id ?? ""] = task;
        return Task.CompletedTask;
    }
}

/// <summary>File-backed store — one JSON file per Task id, under <c>dir</c>.</summary>
public sealed class FileTaskStore : ITaskStore
{
    private readonly string _dir;

    public FileTaskStore(string dir)
    {
        _dir = dir;
        Directory.CreateDirectory(dir);
    }

    private string File(string id) => Path.Combine(_dir, Tools.Sanitize(id) + ".json");

    public Task<A2ATask?> GetAsync(string id)
    {
        try
        {
            var text = System.IO.File.ReadAllText(File(id));
            return Task.FromResult(JsonSerializer.Deserialize<A2ATask>(text, A2AServer.ReadJson));
        }
        catch
        {
            return Task.FromResult<A2ATask?>(null);
        }
    }

    public Task SaveAsync(A2ATask task)
    {
        System.IO.File.WriteAllText(File(task.Id ?? ""), JsonSerializer.Serialize(task, A2AServer.TaskJson));
        return Task.CompletedTask;
    }
}

// ===========================================================================
// A2A serve config + handle.
// ===========================================================================

/// <summary>Provider block advertised on the Agent Card.</summary>
public sealed class A2AProvider
{
    public string Organization { get; set; } = "";
    public string Url { get; set; } = "";
}

/// <summary>Opt-in A2A profile for <c>Toolkit.ServeAsync</c> — configures the Agent Card + store.</summary>
public sealed class A2AConfig
{
    public string? Name { get; set; }
    public string? Description { get; set; }
    public string? Version { get; set; }
    public A2AProvider? Provider { get; set; }

    /// <summary>Subset of the toolkit's skill ids/names to advertise; omit ⇒ all.</summary>
    public List<string>? Skills { get; set; }

    /// <summary><c>"memory"</c> (default) | <c>"file:&lt;dir&gt;"</c> | a custom <see cref="ITaskStore"/>.</summary>
    public object? Store { get; set; }
}

/// <summary>Surfaced on each Task's terminal state, carrying the RunResult telemetry.</summary>
public sealed record OnTaskEvent(
    string Id,
    A2ATask Task,
    string State,
    string? Skill = null,
    LlmClient.RunResult? Result = null);

/// <summary>Callback fired when a served Task reaches a terminal state.</summary>
public delegate Task OnTask(OnTaskEvent ev);

/// <summary>Handle returned by <c>Toolkit.ServeAsync</c> — the base URL plus a stop/close method.</summary>
public sealed class ServeHandle : IAsyncDisposable, IDisposable
{
    private readonly Func<Task> _stop;

    /// <summary>Base URL of the server, e.g. <c>http://127.0.0.1:PORT</c>.</summary>
    public string Url { get; }

    internal ServeHandle(string url, Func<Task> stop)
    {
        Url = url;
        _stop = stop;
    }

    public Task StopAsync() => _stop();

    /// <summary>Alias for <see cref="StopAsync"/>.</summary>
    public Task CloseAsync() => _stop();

    public void Dispose() => _stop().GetAwaiter().GetResult();

    public async ValueTask DisposeAsync() => await _stop().ConfigureAwait(false);
}

// ===========================================================================
// Server.
// ===========================================================================

/// <summary>
/// The inbound A2A HTTP server. <see cref="Toolkit.ServeAsync"/> delegates here.
/// When <c>a2a</c> is absent, every request 404s (a minimal base for now).
/// </summary>
public static class A2AServer
{
    private const string ProtocolVersion = "0.3.0";
    private const string DefaultVersion = "0.1.0";
    private const string CardPath = "/.well-known/agent-card.json";

    /// <summary>Serialize a Task, dropping unset (null) fields — matches the JS wire shape.</summary>
    internal static readonly JsonSerializerOptions TaskJson = new()
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    internal static readonly JsonSerializerOptions ReadJson = new()
    {
        PropertyNameCaseInsensitive = true,
    };

    // -----------------------------------------------------------------------
    // Card builder.
    // -----------------------------------------------------------------------

    /// <summary>
    /// Build an Agent Card from the toolkit's skills. <paramref name="skills"/> are the
    /// SkillSource's <see cref="SkillSource.SkillInfo"/>s (never raw tools); filtered to
    /// <c>cfg.Skills</c> when given. <paramref name="url"/> is the JSON-RPC endpoint peers POST to.
    /// </summary>
    public static Dictionary<string, object?> BuildAgentCard(
        A2AConfig cfg, IReadOnlyList<SkillSource.SkillInfo> skills, string url)
    {
        var wanted = cfg.Skills;
        var selected = wanted != null
            ? skills.Where(s => wanted.Contains(s.Name)).ToList()
            : skills.ToList();

        var card = new Dictionary<string, object?>
        {
            ["name"] = cfg.Name ?? "toolnexus-agent",
            ["description"] = cfg.Description ?? "",
            ["version"] = cfg.Version ?? DefaultVersion,
            ["protocolVersion"] = ProtocolVersion,
            ["capabilities"] = new Dictionary<string, object?>
            {
                ["streaming"] = false,
                ["pushNotifications"] = false,
            },
            ["defaultInputModes"] = new List<object?> { "text" },
            ["defaultOutputModes"] = new List<object?> { "text" },
            ["skills"] = selected.Select(s => (object?)new Dictionary<string, object?>
            {
                ["id"] = s.Name,
                ["name"] = s.Name,
                ["description"] = s.Description ?? "",
            }).ToList(),
            ["url"] = url,
        };
        if (cfg.Provider != null)
            card["provider"] = new Dictionary<string, object?>
            {
                ["organization"] = cfg.Provider.Organization,
                ["url"] = cfg.Provider.Url,
            };
        return card;
    }

    // -----------------------------------------------------------------------
    // Store resolution.
    // -----------------------------------------------------------------------

    /// <summary>Map a <c>store</c> selector to a concrete <see cref="ITaskStore"/>. <c>"file:&lt;dir&gt;"</c> ⇒ FileTaskStore.</summary>
    public static ITaskStore ResolveStore(object? store)
    {
        if (store == null || (store is string ms && ms == "memory")) return new InMemoryTaskStore();
        if (store is ITaskStore ts) return ts;
        if (store is string s)
        {
            if (s.StartsWith("file:")) return new FileTaskStore(s.Substring("file:".Length));
            throw new ArgumentException($"Unknown A2A store: \"{s}\" (expected \"memory\" or \"file:<dir>\")");
        }
        throw new ArgumentException($"Unknown A2A store: {store}");
    }

    // -----------------------------------------------------------------------
    // Server.
    // -----------------------------------------------------------------------

    /// <summary>
    /// Start the HTTP server. When <paramref name="a2a"/> is absent the server answers 404
    /// to everything; otherwise it mounts the card + JSON-RPC routes.
    /// </summary>
    public static Task<ServeHandle> StartAsync(
        string addr,
        A2AConfig? a2a,
        IReadOnlyList<SkillSource.SkillInfo> skills,
        Func<string, string?, Task<LlmClient.RunResult>> runTask,
        OnTask? onTask,
        MCPServeConfig? mcp = null,
        IReadOnlyList<ITool>? mcpTools = null,
        OnCall? onCall = null)
    {
        var (host, port) = SplitAddr(addr);
        if (port == 0) port = FreePort();
        var store = a2a != null ? ResolveStore(a2a.Store) : null;
        // The MCP inbound profile (independent of A2A): the toolkit's unified tools filtered to
        // the profile's `tools` list, re-exposed as one MCP server at POST /mcp.
        var exposedMcpTools = mcp != null
            ? McpServe.ExposedMcpTools(mcpTools ?? Array.Empty<ITool>(), mcp)
            : (IReadOnlyList<ITool>)Array.Empty<ITool>();

        var bindHost = host is "0.0.0.0" or "::" ? "+" : host;
        var reportHost = host is "0.0.0.0" or "::" ? "127.0.0.1" : host;
        var baseUrl = $"http://{reportHost}:{port}";

        var listener = new HttpListener();
        listener.Prefixes.Add($"http://{bindHost}:{port}/");
        listener.Start();

        var cts = new CancellationTokenSource();
        _ = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync().ConfigureAwait(false); }
                catch { break; }
                // Handle each request without blocking the accept loop; guard so a
                // fulfilment / handler error never crashes the server.
                _ = Task.Run(async () =>
                {
                    try { await HandleAsync(ctx, a2a, store, skills, runTask, onTask, baseUrl, mcp, exposedMcpTools, onCall).ConfigureAwait(false); }
                    catch { /* isolated — the server keeps serving */ }
                });
            }
        });

        var handle = new ServeHandle(baseUrl, () =>
        {
            cts.Cancel();
            try { listener.Stop(); } catch { }
            try { listener.Close(); } catch { }
            return Task.CompletedTask;
        });
        return Task.FromResult(handle);
    }

    private static async Task HandleAsync(
        HttpListenerContext ctx,
        A2AConfig? a2a,
        ITaskStore? store,
        IReadOnlyList<SkillSource.SkillInfo> skills,
        Func<string, string?, Task<LlmClient.RunResult>> runTask,
        OnTask? onTask,
        string baseUrl,
        MCPServeConfig? mcp,
        IReadOnlyList<ITool> mcpTools,
        OnCall? onCall)
    {
        var req = ctx.Request;

        // No profile at all ⇒ no routes.
        if (store == null && mcp == null)
        {
            Respond(ctx, 404, "not found", "text/plain");
            return;
        }

        // MCP streamable-HTTP profile (independent of A2A): a fresh server+transport per request
        // (stateless). Checked first so /mcp is never shadowed by the A2A POST handler.
        if (mcp != null && req.Url!.AbsolutePath == "/mcp")
        {
            await HandleMcpAsync(ctx, mcpTools, mcp, onCall).ConfigureAwait(false);
            return;
        }

        // No A2A profile ⇒ no A2A routes (an MCP-only server 404s everything else).
        if (a2a == null || store == null)
        {
            Respond(ctx, 404, "not found", "text/plain");
            return;
        }

        if (req.HttpMethod == "GET" && req.Url!.AbsolutePath == CardPath)
        {
            var card = BuildAgentCard(a2a, skills, baseUrl + "/");
            Respond(ctx, 200, Json.Stringify(card), "application/json");
            return;
        }

        if (req.HttpMethod == "POST")
        {
            string body;
            using (var reader = new StreamReader(req.InputStream, Encoding.UTF8))
                body = await reader.ReadToEndAsync().ConfigureAwait(false);

            object? parsed = Json.ParseLoose(body);
            if (parsed is not IDictionary<string, object?> rpc)
            {
                RpcError(ctx, null, -32700, "Parse error");
                return;
            }

            var id = rpc.Get("id");
            var method = rpc.Get("method") as string;

            if (method == "SendMessage")
            {
                var text = MessageText(rpc.Get("params"));
                var contextId = MessageContextId(rpc.Get("params"));
                var taskId = Guid.NewGuid().ToString();
                var submitted = new A2ATask { Id = taskId, ContextId = contextId, Status = new A2ATaskStatus { State = "submitted" } };
                // Persist submitted, kick fulfilment async, return the id immediately.
                await store.SaveAsync(submitted).ConfigureAwait(false);
                _ = Task.Run(() => FulfilAsync(taskId, text, store, runTask, onTask, contextId));
                RpcResult(ctx, id, submitted);
                return;
            }

            if (method == "GetTask")
            {
                var getParams = rpc.Get("params") as IDictionary<string, object?>;
                var taskId = getParams?.Get("id")?.ToString() ?? "";
                var task = await store.GetAsync(taskId).ConfigureAwait(false);
                if (task == null) RpcError(ctx, id, -32001, $"Task not found: {taskId}");
                else RpcResult(ctx, id, task);
                return;
            }

            RpcError(ctx, id, -32601, $"Method not found: {method}");
            return;
        }

        Respond(ctx, 404, "not found", "text/plain");
    }

    /// <summary>
    /// Handle one streamable-HTTP MCP POST: a fresh, stateless MCP server + transport per request
    /// (no session), matching the JS reference's <c>sessionIdGenerator: undefined</c>. The SDK
    /// server processes the message and writes the response over the SSE response body.
    /// </summary>
    private static async Task HandleMcpAsync(
        HttpListenerContext ctx, IReadOnlyList<ITool> tools, MCPServeConfig mcp, OnCall? onCall)
    {
        if (ctx.Request.HttpMethod != "POST")
        {
            Respond(ctx, 405, "method not allowed", "text/plain");
            return;
        }

        string body;
        using (var reader = new StreamReader(ctx.Request.InputStream, Encoding.UTF8))
            body = await reader.ReadToEndAsync().ConfigureAwait(false);

        JsonRpcMessage? message;
        try
        {
            message = JsonSerializer.Deserialize<JsonRpcMessage>(body, McpJsonUtilities.DefaultOptions);
        }
        catch
        {
            message = null;
        }
        if (message == null)
        {
            Respond(ctx, 400, "invalid JSON-RPC message", "text/plain");
            return;
        }

        var transport = new StreamableHttpServerTransport(NullLoggerFactory.Instance) { Stateless = true };
        var server = McpServe.BuildMcpServer(transport, tools, mcp, onCall);
        var runTask = server.RunAsync(CancellationToken.None);
        try
        {
            ctx.Response.ContentType = "text/event-stream";
            ctx.Response.Headers["Cache-Control"] = "no-cache,no-store";
            ctx.Response.SendChunked = true;
            var wrote = await transport
                .HandlePostRequestAsync(message, ctx.Response.OutputStream, CancellationToken.None)
                .ConfigureAwait(false);
            if (!wrote) ctx.Response.StatusCode = 202; // notification/response — nothing to write back
        }
        finally
        {
            try { ctx.Response.OutputStream.Close(); } catch { }
            try { await transport.DisposeAsync().ConfigureAwait(false); } catch { }
            try { await server.DisposeAsync().ConfigureAwait(false); } catch { }
            try { await runTask.ConfigureAwait(false); } catch { }
        }
    }

    /// <summary>
    /// Background fulfilment: submitted → working → (completed | failed). Never
    /// throws — a fulfilment error becomes a <c>failed</c> Task, keeping the server alive.
    /// </summary>
    private static async Task FulfilAsync(
        string id,
        string text,
        ITaskStore store,
        Func<string, string?, Task<LlmClient.RunResult>> runTask,
        OnTask? onTask,
        string? contextId = null)
    {
        A2ATask task;
        LlmClient.RunResult? result = null;
        string state;
        try
        {
            await store.SaveAsync(new A2ATask { Id = id, Status = new A2ATaskStatus { State = "working" } })
                .ConfigureAwait(false);
            // contextId groups a peer's turns into one conversation — thread it so the client's
            // IConversationStore remembers across tasks in the same context.
            result = await runTask(text, contextId).ConfigureAwait(false);
            var artifact = new A2AArtifact
            {
                ArtifactId = Guid.NewGuid().ToString(),
                Parts = new List<A2APart> { new() { Kind = "text", Text = result.Text } },
            };
            task = new A2ATask
            {
                Id = id,
                Status = new A2ATaskStatus { State = "completed" },
                Artifacts = new List<A2AArtifact> { artifact },
            };
            state = "completed";
        }
        catch (Exception e)
        {
            var detail = e.Message ?? e.ToString();
            task = new A2ATask
            {
                Id = id,
                Status = new A2ATaskStatus
                {
                    State = "failed",
                    Message = new A2AMessage
                    {
                        Role = "agent",
                        Parts = new List<A2APart> { new() { Kind = "text", Text = detail } },
                    },
                },
            };
            state = "failed";
        }

        try { await store.SaveAsync(task).ConfigureAwait(false); }
        catch { /* A store write failure must not crash the server. */ }

        if (onTask != null)
        {
            try { await onTask(new OnTaskEvent(id, task, state, null, result)).ConfigureAwait(false); }
            catch { /* Host callback errors are isolated. */ }
        }
    }

    // -----------------------------------------------------------------------
    // JSON-RPC + HTTP helpers.
    // -----------------------------------------------------------------------

    /// <summary>Extract the concatenated text of a JSON-RPC <c>SendMessage</c> message's parts.</summary>
    private static string MessageText(object? @params)
    {
        if (@params is not IDictionary<string, object?> p) return "";
        if (p.Get("message") is not IDictionary<string, object?> message) return "";
        if (message.Get("parts") is not IEnumerable<object?> parts) return "";
        var sb = new StringBuilder();
        foreach (var part in parts)
        {
            if (part is not IDictionary<string, object?> pd) continue;
            if (pd.Get("kind") as string != "text") continue;
            if (pd.Get("text") is string t) sb.Append(t);
        }
        return sb.ToString();
    }

    /// <summary>Extract a JSON-RPC <c>SendMessage</c> message's A2A <c>contextId</c>, or null.</summary>
    private static string? MessageContextId(object? @params)
    {
        if (@params is not IDictionary<string, object?> p) return null;
        if (p.Get("message") is not IDictionary<string, object?> message) return null;
        return message.Get("contextId") as string;
    }

    /// <summary>Convert a Task to the plain object model (null fields dropped) for the RPC envelope.</summary>
    private static object? TaskToPlain(A2ATask task)
        => Json.ParseLoose(JsonSerializer.Serialize(task, TaskJson));

    private static void RpcResult(HttpListenerContext ctx, object? id, A2ATask task)
    {
        var payload = new Dictionary<string, object?>
        {
            ["jsonrpc"] = "2.0",
            ["id"] = id,
            ["result"] = TaskToPlain(task),
        };
        Respond(ctx, 200, Json.Stringify(payload), "application/json");
    }

    private static void RpcError(HttpListenerContext ctx, object? id, int code, string message)
    {
        var payload = new Dictionary<string, object?>
        {
            ["jsonrpc"] = "2.0",
            ["id"] = id,
            ["error"] = new Dictionary<string, object?> { ["code"] = code, ["message"] = message },
        };
        Respond(ctx, 200, Json.Stringify(payload), "application/json");
    }

    private static void Respond(HttpListenerContext ctx, int status, string body, string contentType)
    {
        try
        {
            var bytes = Encoding.UTF8.GetBytes(body);
            ctx.Response.StatusCode = status;
            ctx.Response.ContentType = contentType;
            ctx.Response.ContentLength64 = bytes.Length;
            ctx.Response.OutputStream.Write(bytes, 0, bytes.Length);
            ctx.Response.OutputStream.Close();
        }
        catch { /* client hung up — nothing to do */ }
    }

    // -----------------------------------------------------------------------
    // Address helpers.
    // -----------------------------------------------------------------------

    /// <summary>Split <c>host:port</c> on the last colon. Missing host ⇒ 127.0.0.1.</summary>
    private static (string Host, int Port) SplitAddr(string addr)
    {
        var idx = addr.LastIndexOf(':');
        if (idx == -1) return ("127.0.0.1", ParsePort(addr));
        var host = addr.Substring(0, idx);
        if (host.Length == 0) host = "127.0.0.1";
        return (host, ParsePort(addr.Substring(idx + 1)));
    }

    private static int ParsePort(string s) => int.TryParse(s, out var p) ? p : 0;

    /// <summary>HttpListener cannot bind port 0; pick a free ephemeral port ourselves.</summary>
    private static int FreePort()
    {
        var l = new TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }
}
