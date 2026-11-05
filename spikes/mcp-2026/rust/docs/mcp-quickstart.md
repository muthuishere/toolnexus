# MCP quickstart: zero to tool call

> **Rust port of the MCP 2026-07-28 spike (openspec `upgrade-mcp-2026-07-28`).** The API below is the
> spike in `spikes/mcp-2026/rust` (crate `mcp2026`; reference: `spikes/mcp-2026/golang`). Every code block is
> copied verbatim from a program under `examples/` that `tests/examples.rs` runs and checks; `tests/docs.rs`
> fails if a block here stops matching its source.

MCP 2026-07-28 is stateless: no `initialize` handshake, no session. Each request carries its protocol
version, so connecting is constructing a client.

## Over HTTP

<!-- from: examples/01-connect-http/main.rs -->
```rust
    // 1. Point a client at the server's MCP endpoint. No handshake happens here.
    let c = Client::new(format!("{}/mcp", ts.url));

    // 2. Optional: server/discover tells you which versions and capabilities the server has.
    let info = c.call("server/discover", Value::Null).await?;
    let versions: Vec<&str> = info["supportedVersions"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|v| v.as_str())
        .collect();
    println!("supported versions: [{}]", versions.join(" "));

    // 3. List the tools (sorted by name; the result also carries ttlMs/cacheScope cache hints).
    let tools = c.tools().await?;
    let names: Vec<&str> = tools.iter().filter_map(|t| t["name"].as_str()).collect();
    println!("tools: {}", names.join(", "));

    // 4. Call a tool. mcp::text returns the first text content of the result.
    let res = c
        .call_tool("echo", json!({"text": "hello over HTTP"}), None)
        .await?;
    println!("echo: {}", mcp::text(&res));
```

Output (`cargo run --example 01-connect-http`):

```text
supported versions: [2026-07-28]
tools: echo, greet
echo: hello over HTTP
```

## Over stdio

Spawn the server and talk JSON-RPC, one message per line. stderr is the server's log and is never parsed
(`Client::stdio_capture(cmd, sink)` captures it instead of passing it through). stdio has no HTTP headers and
no OAuth: the server reads its credentials from its own environment.

<!-- from: examples/02-connect-stdio/main.rs -->
```rust
    // 1. Describe the process to spawn (env, args, working dir: plain tokio::process::Command).
    let mut cmd = Command::new(me);
    cmd.env("SERVE_STDIO", "1");

    // 2. Start it and get a client. Same API as the HTTP client: call, tools, call_tool.
    let c = Client::stdio(cmd)?;

    // 3. Use it. Concurrent calls are fine: responses are matched by JSON-RPC id.
    let tools = c.tools().await?;
    println!("tool count: {}", tools.len());

    let res = c
        .call_tool("echo", json!({"text": "hello over stdio"}), None)
        .await?;
    println!("echo: {}", mcp::text(&res));

    c.close().await; // stops the server process
```

## When the tool needs the user (MRTR)

A server never calls the client. A `tools/call` can instead **return** `resultType: "input_required"` with
input requests (for example an elicitation form). `call_tool` answers them with your `Responder` and re-sends
the call with `inputResponses` and the opaque `requestState`. You write only the callback:

<!-- from: examples/03-tool-input/main.rs -->
```rust
    // The Responder is called once per input request. key identifies the request inside the
    // round; req is the request itself ({"method": "elicitation/create", "params": {...}}).
    // In an app this is where you show a form, ask in chat, or route to SPEC §10 waitFor.
    let ask_user = mcp::responder(|_key, req| {
        let message = req["params"]["message"].as_str().unwrap_or_default();
        println!(
            "server asks ({}): {message}",
            req["method"].as_str().unwrap_or_default()
        );
        // Answer like a person who filled the form. "decline" / "cancel" are the other actions.
        Ok(obj!({"action": "accept", "content": {"name": "Ada"}}))
    });

    let res = c.call_tool("greet", json!({}), ask_user).await?;
    println!("result: {}", mcp::text(&res));
```

```text
server asks (elicitation/create): What is your name?
result: Hello, Ada!
```

## Next

- [Authentication](mcp-authentication.md): protected servers, both client modes, protecting your own server.
- [Skills](mcp-skills.md), [Tool search](mcp-tool-search.md), [Providers](mcp-providers.md),
  [Spec support](mcp-spec-support.md).
