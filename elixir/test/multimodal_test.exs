defmodule Toolnexus.MultimodalTest do
  @moduledoc """
  Multimodal content end to end: parts into the loop (§7), parts out of a tool and out of
  MCP (§2), §8A emission with the relocation rule, §11 translate, the `read` media table
  (§6), and the MCP-inbound blocks (§7B).
  """
  use ExUnit.Case, async: false

  import ExUnit.CaptureLog

  alias Toolnexus.{Client, ContentPart, Context, Mcp, Native, ToolResult, Translate}
  alias Toolnexus.ContentPart.Error

  @fixture Path.expand("../../examples/media/fixture.png", __DIR__)

  defp golden, do: Path.expand("../../examples/media/fixture.png.base64", __DIR__) |> File.read!() |> String.trim()

  defp image_part, do: ContentPart.image!(@fixture)

  # ---- LLM stubs -------------------------------------------------------------------

  defp openai_text(text) do
    %{"choices" => [%{"message" => %{"role" => "assistant", "content" => text}}], "usage" => %{}}
  end

  defp openai_calls(calls) do
    %{
      "choices" => [
        %{
          "message" => %{
            "role" => "assistant",
            "content" => nil,
            "tool_calls" =>
              Enum.map(calls, fn {id, name} ->
                %{"id" => id, "type" => "function", "function" => %{"name" => name, "arguments" => "{}"}}
              end)
          },
          "finish_reason" => "tool_calls"
        }
      ],
      "usage" => %{}
    }
  end

  defp anthropic_text(text), do: %{"content" => [%{"type" => "text", "text" => text}], "usage" => %{}}

  defp anthropic_uses(uses) do
    %{
      "content" => Enum.map(uses, fn {id, name} -> %{"type" => "tool_use", "id" => id, "name" => name, "input" => %{}} end),
      "stop_reason" => "tool_use",
      "usage" => %{}
    }
  end

  # A transport that replies with the scripted bodies in order and records every request.
  defp scripted(bodies) do
    {:ok, agent} = Agent.start_link(fn -> {bodies, []} end)

    transport = fn req ->
      body =
        Agent.get_and_update(agent, fn {[b | rest], seen} -> {b, {rest ++ [b], seen ++ [req.body]}} end)

      {:ok, %{status: 200, headers: %{}, body: body}}
    end

    {transport, fn -> Agent.get(agent, fn {_, seen} -> seen end) end}
  end

  defp client(style, transport, opts \\ []) do
    Client.create(
      Keyword.merge(
        [base_url: "http://parts.invalid", style: style, model: "m", api_key: "k", transport: transport],
        opts
      )
    )
  end

  defp image_tool(name) do
    Native.define_tool(
      name: name,
      description: "returns an image",
      execute: fn _args -> %ToolResult{output: "screenshot, 8x8 png", parts: [image_part()]} end
    )
  end

  # ---- loop input (§7) --------------------------------------------------------------

  describe "loop input accepts content parts (§7)" do
    test "the string path is byte-identical on both styles" do
      {t, sent} = scripted([openai_text("ok")])
      assert Client.run(client("openai", t), "hello", []).text == "ok"
      assert [%{"messages" => [%{"role" => "user", "content" => "hello"}]}] = sent.()

      {t, sent} = scripted([anthropic_text("ok")])
      assert Client.run(client("anthropic", t), "hello", []).text == "ok"
      assert [%{"messages" => [%{"role" => "user", "content" => "hello"}]}] = sent.()
    end

    test "openai: [text, image, text] keeps the caller's ordering" do
      {t, sent} = scripted([openai_text("ok")])
      prompt = [ContentPart.text("before"), image_part(), ContentPart.text("after")]
      assert Client.run(client("openai", t), prompt, []).text == "ok"

      assert [%{"messages" => [%{"role" => "user", "content" => blocks}]}] = sent.()
      assert Enum.map(blocks, & &1["type"]) == ["text", "image_url", "text"]
      assert Enum.at(blocks, 1)["image_url"]["url"] == "data:image/png;base64," <> golden()
    end

    test "anthropic: [text, image] becomes native blocks in order" do
      {t, sent} = scripted([anthropic_text("ok")])
      assert Client.run(client("anthropic", t), [ContentPart.text("look"), image_part()], []).text == "ok"

      assert [%{"messages" => [%{"role" => "user", "content" => blocks}]}] = sent.()
      assert Enum.map(blocks, & &1["type"]) == ["text", "image"]
      assert Enum.at(blocks, 1)["source"] == %{"type" => "base64", "media_type" => "image/png", "data" => golden()}
    end

    test "an attached part the style cannot represent errors before any HTTP call" do
      {:ok, calls} = Agent.start_link(fn -> 0 end)
      t = fn _req -> Agent.update(calls, &(&1 + 1)) && {:ok, %{status: 200, headers: %{}, body: anthropic_text("x")}} end
      audio = ContentPart.audio!({:bytes, "ID3"}, mime_type: "audio/mpeg")

      assert_raise Error, ~r/anthropic/, fn -> Client.run(client("anthropic", t), [audio], []) end
      assert Agent.get(calls, & &1) == 0
    end

    test "on_unsupported_part: \"text\" downgrades an attached part instead" do
      {t, sent} = scripted([anthropic_text("ok")])
      audio = ContentPart.audio!({:bytes, "ID3"}, mime_type: "audio/mpeg")

      capture_log(fn ->
        assert Client.run(client("anthropic", t, on_unsupported_part: "text"), [audio], []).text == "ok"
      end)

      assert [%{"messages" => [%{"content" => [%{"type" => "text", "text" => text}]}]}] = sent.()
      assert text =~ "audio/mpeg"
    end
  end

  # ---- tool results (§8A relocation) -------------------------------------------------

  describe "ToolResult.parts reach the model (§8A)" do
    test "anthropic carries the image inside tool_result.content, with no synthetic message" do
      {t, sent} = scripted([anthropic_uses([{"u1", "shot"}]), anthropic_text("a red quadrant")])
      r = Client.run(client("anthropic", t), "look", [image_tool("shot")])
      assert r.text == "a red quadrant"

      [_first, second] = sent.()
      [_user, _assistant, results] = second["messages"]
      assert results["role"] == "user"
      [%{"type" => "tool_result", "tool_use_id" => "u1", "content" => content}] = results["content"]
      assert [%{"type" => "text", "text" => "screenshot, 8x8 png"}, %{"type" => "image"} = img] = content
      assert img["source"]["data"] == golden()

      # native emission means no relocation happened
      refute Enum.any?(second["messages"], fn m -> m["role"] == "user" and is_list(m["content"]) and
                 Enum.any?(m["content"], &(&1["type"] == "text" and String.starts_with?(&1["text"] || "", "Output of tool"))) end)
    end

    test "openai relocates every non-text part into ONE synthetic user message, in call order" do
      {t, sent} = scripted([openai_calls([{"c1", "shot_a"}, {"c2", "shot_b"}]), openai_text("two images")])
      r = Client.run(client("openai", t), "look", [image_tool("shot_a"), image_tool("shot_b")])
      assert r.text == "two images"

      [_first, second] = sent.()
      msgs = second["messages"]

      # the tool messages carry only their output text — an image there is a hard 400
      tools = Enum.filter(msgs, &(&1["role"] == "tool"))
      assert length(tools) == 2
      assert Enum.all?(tools, &(&1["content"] == "screenshot, 8x8 png"))
      assert Enum.all?(tools, &(not Map.has_key?(&1, "parts") and not Map.has_key?(&1, "tool_name")))

      # exactly one synthetic user message follows the last tool message
      synthetic = List.last(msgs)
      assert synthetic["role"] == "user"

      assert Enum.map(synthetic["content"], & &1["type"]) == ["text", "image_url", "text", "image_url"]

      assert Enum.at(synthetic["content"], 0)["text"] == "Output of tool shot_a (c1):"
      assert Enum.at(synthetic["content"], 2)["text"] == "Output of tool shot_b (c2):"
      assert Enum.at(synthetic["content"], 1)["image_url"]["url"] == "data:image/png;base64," <> golden()

      assert Enum.count(msgs, &(&1["role"] == "user")) == 2
    end

    test "the synthetic message is an adapter artifact — never in the transcript or the store" do
      {t, _sent} = scripted([openai_calls([{"c1", "shot"}]), openai_text("done")])
      c = client("openai", t)
      r = Client.ask(c, "look", [image_tool("shot")], id: "conv-1")

      persisted = Client.conversation_store(c).__struct__.get(Client.conversation_store(c), "conv-1")

      for messages <- [r.messages, persisted] do
        refute Enum.any?(messages, fn m ->
                 is_list(m["content"]) and
                   Enum.any?(m["content"], &(is_map(&1) and String.starts_with?(&1["text"] || "", "Output of tool")))
               end)
      end

      # the canonical tool message keeps the parts, so a later turn can re-emit them
      tool_msg = Enum.find(r.messages, &(&1["role"] == "tool"))
      assert [%{"type" => "image", "mimeType" => "image/png"}] = tool_msg["parts"]
      assert tool_msg["content"] == "screenshot, 8x8 png"
    end

    test "text parts on a tool result stay on the tool message, not relocated" do
      tool =
        Native.define_tool(
          name: "notes",
          description: "text parts",
          execute: fn _ -> %ToolResult{output: "head", parts: [ContentPart.text("tail")]} end
        )

      {t, sent} = scripted([openai_calls([{"c1", "notes"}]), openai_text("ok")])
      Client.run(client("openai", t), "go", [tool])

      [_first, second] = sent.()
      tool_msg = Enum.find(second["messages"], &(&1["role"] == "tool"))
      assert tool_msg["content"] == [%{"type" => "text", "text" => "head"}, %{"type" => "text", "text" => "tail"}]
      assert List.last(second["messages"])["role"] == "tool"
    end

    test "a tool-derived unsupported part degrades with a warn-once instead of failing the run" do
      tool =
        Native.define_tool(
          name: "listen",
          description: "audio",
          execute: fn _ ->
            %ToolResult{output: "a clip", parts: [ContentPart.audio!({:bytes, "ID3"}, mime_type: "audio/mpeg")]}
          end
        )

      {t, sent} = scripted([anthropic_uses([{"u1", "listen"}]), anthropic_text("heard it")])

      log = capture_log(fn -> assert Client.run(client("anthropic", t), "go", [tool]).text == "heard it" end)
      assert log =~ "audio/mpeg"
      refute log =~ Base.encode64("ID3")

      [_first, second] = sent.()
      results = List.last(second["messages"])
      [%{"content" => [_text, %{"type" => "text", "text" => placeholder}]}] = results["content"]
      assert placeholder =~ "audio" and placeholder =~ "audio/mpeg"
    end

    test "on_unsupported_part: \"error\" makes the same run fail loudly" do
      tool =
        Native.define_tool(
          name: "listen",
          description: "audio",
          execute: fn _ ->
            %ToolResult{output: "a clip", parts: [ContentPart.audio!({:bytes, "ID3"}, mime_type: "audio/mpeg")]}
          end
        )

      {t, _sent} = scripted([anthropic_uses([{"u1", "listen"}]), anthropic_text("never")])

      assert_raise Error, fn ->
        Client.run(client("anthropic", t, on_unsupported_part: "error"), "go", [tool])
      end
    end

    test "parts alongside metadata.pending still suspend per §10" do
      req = %Toolnexus.Request{id: "r1", kind: "input", prompt: "who?"}

      tool =
        Native.define_tool(
          name: "ask_it",
          description: "suspends",
          execute: fn _ -> %ToolResult{output: "who?", parts: [image_part()], metadata: %{pending: req}} end
        )

      assert ToolResult.pending?(%ToolResult{output: "x", parts: [image_part()], metadata: %{pending: req}})

      {t, _sent} = scripted([openai_calls([{"c1", "ask_it"}]), openai_text("never")])
      r = Client.run(client("openai", t), "go", [tool])
      assert r.status == "pending"
      assert r.pending.id == "r1"
    end
  end

  # ---- streaming parity ---------------------------------------------------------------

  describe "the streaming loops emit parts too" do
    test "openai streaming relocates, anthropic streaming rides native" do
      sse_openai = """
      data: {"choices":[{"delta":{"content":"streamed"}}]}

      data: [DONE]
      """

      calls_sse = """
      data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"shot","arguments":"{}"}}]}}]}

      data: [DONE]
      """

      {:ok, agent} = Agent.start_link(fn -> {[calls_sse, sse_openai], []} end)

      t = fn req ->
        body = Agent.get_and_update(agent, fn {[b | rest], seen} -> {b, {rest, seen ++ [req.body]}} end)
        {:ok, %{status: 200, headers: %{}, body: body}}
      end

      events = Client.stream(client("openai", t), "go", [image_tool("shot")]) |> Enum.to_list()
      assert Enum.any?(events, &(&1.type == "done"))

      [_first, second] = Agent.get(agent, fn {_, seen} -> seen end)
      synthetic = List.last(second["messages"])
      assert synthetic["role"] == "user"
      assert Enum.at(synthetic["content"], 0)["text"] == "Output of tool shot (c1):"
      assert Enum.at(synthetic["content"], 1)["type"] == "image_url"
    end

    test "anthropic streaming keeps the image inside tool_result.content" do
      use_sse = """
      event: content_block_start
      data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"u1","name":"shot"}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{}"}}

      event: message_delta
      data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}
      """

      text_sse = """
      event: content_block_start
      data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

      event: content_block_delta
      data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"seen"}}

      event: message_delta
      data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
      """

      {:ok, agent} = Agent.start_link(fn -> {[use_sse, text_sse], []} end)

      t = fn req ->
        body = Agent.get_and_update(agent, fn {[b | rest], seen} -> {b, {rest, seen ++ [req.body]}} end)
        {:ok, %{status: 200, headers: %{}, body: body}}
      end

      Client.stream(client("anthropic", t), "go", [image_tool("shot")]) |> Enum.to_list()

      [_first, second] = Agent.get(agent, fn {_, seen} -> seen end)
      results = List.last(second["messages"])
      [%{"type" => "tool_result", "content" => content}] = results["content"]
      assert Enum.map(content, & &1["type"]) == ["text", "image"]
    end
  end

  # ---- §11 translate ------------------------------------------------------------------

  describe "§11 translate preserves non-text parts" do
    test "a text-only parts array still concatenates (regression pin)" do
      messages = [%{"role" => "user", "content" => [%{"type" => "text", "text" => "a"}, %{"type" => "text", "text" => "b"}]}]
      assert {[%{"role" => "user", "content" => "ab"}], ""} = Translate.openai_messages_to_anthropic(messages)
    end

    test "an image part survives translation, in the order given" do
      messages = [
        %{"role" => "user", "content" => [%{"type" => "text", "text" => "what's this?"}, ContentPart.to_map(image_part())]}
      ]

      assert {[%{"role" => "user", "content" => blocks}], ""} = Translate.openai_messages_to_anthropic(messages)
      assert Enum.map(blocks, & &1["type"]) == ["text", "image"]
      assert Enum.at(blocks, 1)["source"]["data"] == golden()
    end

    test "an empty array is still dropped, and unknown entries pass through" do
      assert {[], ""} = Translate.openai_messages_to_anthropic([%{"role" => "user", "content" => []}])

      assert {[%{"content" => [%{"type" => "mystery"}]}], ""} =
               Translate.openai_messages_to_anthropic([%{"role" => "user", "content" => [%{"type" => "mystery"}]}])
    end
  end

  # ---- builtin read (§6) --------------------------------------------------------------

  describe "the read builtin's media table (§6)" do
    defp read_tool, do: Enum.find(Toolnexus.Builtin.tools(), &(&1.name == "read"))

    test "reading a PNG yields a described output and one image part" do
      r = read_tool().execute.(%{"path" => @fixture}, %Context{})
      refute r.is_error
      assert r.output =~ "image/png"
      assert r.output =~ "82 bytes"
      assert [%ContentPart{type: "image", mime_type: "image/png", data: data, name: "fixture.png"}] = r.parts
      assert data == golden()
    end

    test "a text file is unchanged, offset/limit included, and carries no parts" do
      tmp = Path.join(System.tmp_dir!(), "tn-read-#{System.unique_integer([:positive])}.md")
      File.write!(tmp, "one\ntwo\nthree")

      assert read_tool().execute.(%{"path" => tmp}, %Context{}) == ToolResult.ok("one\ntwo\nthree")
      windowed = read_tool().execute.(%{"path" => tmp, "offset" => 2, "limit" => 1}, %Context{})
      assert windowed.output == "two"
      assert windowed.parts == nil

      File.rm(tmp)
    end

    test "an unrecognised binary is an error result, never a raised exception" do
      tmp = Path.join(System.tmp_dir!(), "tn-read-#{System.unique_integer([:positive])}.bin")
      File.write!(tmp, <<0xFF, 0xFE, 0xFD>>)

      r = read_tool().execute.(%{"path" => tmp}, %Context{})
      assert r.is_error
      assert r.output =~ tmp
      assert r.output =~ "UTF-8"

      File.rm(tmp)
    end
  end

  # ---- compaction accounting (§9) ------------------------------------------------------

  describe "token estimation charges for parts (§9)" do
    test "a part is charged from its bytes and never contributes its base64 to the estimate" do
      big = ContentPart.image!({:bytes, :binary.copy(<<0>>, 750_000)}, mime_type: "image/png")
      msg = %{"role" => "user", "content" => [ContentPart.to_map(big)]}

      estimate = Toolnexus.Agents.Compaction.estimate_tokens([msg])

      # ~1 000 tokens for the part plus the tiny redacted envelope — not the 250 000 the
      # raw base64 would have cost, and not the ~3 the mimeType string would have.
      assert estimate > 900 and estimate < 1_100
      assert Toolnexus.Agents.Compaction.estimate_tokens([%{"role" => "user", "content" => "hi"}]) < 20
    end
  end

  # ---- MCP outbound (§2) ---------------------------------------------------------------

  defmodule PartsStub do
    @moduledoc false
    import Plug.Conn

    @results %{
      "text_only" => %{"content" => [%{"type" => "text", "text" => "plain"}]},
      "shot" => %{
        "content" => [
          %{"type" => "text", "text" => "here"},
          %{"type" => "image", "data" => "aW1n", "mimeType" => "image/png"}
        ]
      },
      "structured" => %{
        "structuredContent" => %{"ok" => true},
        "content" => [%{"type" => "image", "data" => "aW1n", "mimeType" => "image/png"}]
      },
      "boom" => %{
        "isError" => true,
        "content" => [
          %{"type" => "text", "text" => "it broke"},
          %{"type" => "image", "data" => "aW1n", "mimeType" => "image/png"}
        ]
      },
      "linked" => %{
        "content" => [%{"type" => "resource_link", "uri" => "https://x/y.pdf", "mimeType" => "application/pdf", "name" => "y.pdf"}]
      },
      "blobbed" => %{
        "content" => [%{"type" => "resource", "resource" => %{"uri" => "file:///y.pdf", "mimeType" => "application/pdf", "blob" => "YmxvYg=="}}]
      },
      "resourcetext" => %{
        "content" => [%{"type" => "resource", "resource" => %{"uri" => "file:///a.txt", "text" => "inline text"}}]
      },
      "noisy" => %{"content" => [%{"type" => "audio", "data" => "c25k", "mimeType" => "audio/wav"}]}
    }

    def names, do: Map.keys(@results)

    def init(o), do: o

    def call(conn, _) do
      {:ok, body, conn} = read_body(conn)

      case Jason.decode!(body) do
        %{"method" => "initialize", "id" => id} ->
          json(conn, id, %{
            "protocolVersion" => "2025-06-18",
            "capabilities" => %{"tools" => %{}},
            "serverInfo" => %{"name" => "parts", "version" => "0"}
          })

        %{"method" => "notifications/" <> _} ->
          send_resp(conn, 202, "")

        %{"method" => "tools/list", "id" => id} ->
          json(conn, id, %{"tools" => Enum.map(names(), &%{"name" => &1, "description" => &1, "inputSchema" => %{"type" => "object"}})})

        %{"method" => "tools/call", "id" => id, "params" => %{"name" => name}} ->
          json(conn, id, @results[name])
      end
    end

    defp json(conn, id, result) do
      conn
      |> put_resp_content_type("application/json")
      |> send_resp(200, Jason.encode!(%{"jsonrpc" => "2.0", "id" => id, "result" => result}))
    end
  end

  describe "MCP results keep every non-text content entry (§2)" do
    setup do
      {:ok, sock} = :gen_tcp.listen(0, [])
      {:ok, port} = :inet.port(sock)
      :gen_tcp.close(sock)

      start_supervised!({Bandit, plug: PartsStub, scheme: :http, port: port, ip: {127, 0, 0, 1}})

      source =
        Mcp.load(%{"mcpServers" => %{"p" => %{"url" => "http://127.0.0.1:#{port}/mcp", "transport" => "http"}}})

      on_exit(fn -> Mcp.close(source) end)

      call = fn name ->
        Enum.find(source.tools, &(&1.name == "p_" <> name)).execute.(%{}, %Context{})
      end

      %{call: call}
    end

    test "a text-only result is byte-identical to before — no parts key at all", %{call: call} do
      r = call.("text_only")
      assert r.output == "plain"
      assert r.parts == nil
      refute r.is_error
    end

    test "a screenshot tool's image survives alongside its text", %{call: call} do
      r = call.("shot")
      assert r.output == "here"
      assert [%ContentPart{type: "image", mime_type: "image/png", data: "aW1n"}] = r.parts
    end

    test "structuredContent no longer swallows the image", %{call: call} do
      r = call.("structured")
      assert r.output == ~s({"ok":true})
      assert [%ContentPart{type: "image"}] = r.parts
    end

    test "an error result keeps its image too", %{call: call} do
      r = call.("boom")
      assert r.is_error
      assert r.output == "it broke"
      assert [%ContentPart{type: "image"}] = r.parts
    end

    test "a resource_link becomes a file part carrying its uri", %{call: call} do
      r = call.("linked")
      assert [%ContentPart{type: "file", url: "https://x/y.pdf", mime_type: "application/pdf", name: "y.pdf"}] = r.parts
      # an entry-only result names what came back rather than returning ""
      assert r.output == "file (application/pdf, 0 bytes)"
    end

    test "an embedded blob becomes a file part; embedded text is appended to output", %{call: call} do
      r = call.("blobbed")
      assert [%ContentPart{type: "file", data: "YmxvYg==", name: "file:///y.pdf"}] = r.parts

      r = call.("resourcetext")
      assert r.output == "inline text"
      assert r.parts == nil
    end

    test "audio is mapped, not dropped", %{call: call} do
      assert [%ContentPart{type: "audio", mime_type: "audio/wav", data: "c25k"}] = call.("noisy").parts
    end
  end

  # ---- MCP inbound (§7B) ----------------------------------------------------------------

  describe "serve() emits a tool's parts as MCP content blocks (§7B)" do
    defp call_served(tools, name) do
      tk = Toolnexus.create_toolkit!(extra_tools: tools, builtins: false)
      handle = Toolnexus.Toolkit.serve(tk, "127.0.0.1:0", mcp: %{})
      on_exit(fn -> Toolnexus.Serve.stop(handle) end)

      body = %{
        "jsonrpc" => "2.0",
        "id" => 1,
        "method" => "tools/call",
        "params" => %{"name" => name, "arguments" => %{}}
      }

      resp =
        Req.post!(
          url: handle.url <> "/mcp",
          json: body,
          headers: [{"accept", "application/json, text/event-stream"}],
          retry: false,
          decode_body: false
        )

      Jason.decode!(resp.body)["result"]
    end

    test "the text block comes first, then one block per non-text part" do
      tools = [
        image_tool("shot"),
        Native.define_tool(
          name: "docs",
          description: "a file and a link",
          execute: fn _ ->
            %ToolResult{
              output: "two files",
              parts: [
                ContentPart.file!({:bytes, "%PDF-"}, mime_type: "application/pdf", name: "r.pdf"),
                ContentPart.file!("https://x/y.pdf", mime_type: "application/pdf"),
                ContentPart.text("ignored — text rides in output")
              ]
            }
          end
        )
      ]

      result = call_served(tools, "shot")
      assert [%{"type" => "text", "text" => "screenshot, 8x8 png"}, image] = result["content"]
      assert image == %{"type" => "image", "data" => golden(), "mimeType" => "image/png"}
      assert result["isError"] == false
    end

    test "a file part with data is an embedded resource; with a url, a resource_link" do
      tools = [
        Native.define_tool(
          name: "docs",
          description: "a file and a link",
          execute: fn _ ->
            %ToolResult{
              output: "two files",
              parts: [
                ContentPart.file!({:bytes, "%PDF-"}, mime_type: "application/pdf", name: "r.pdf"),
                ContentPart.file!("https://x/y.pdf", mime_type: "application/pdf", name: "y.pdf"),
                ContentPart.text("text rides in output")
              ]
            }
          end
        )
      ]

      assert [%{"type" => "text"}, blob, link] = call_served(tools, "docs")["content"]

      assert blob == %{
               "type" => "resource",
               "resource" => %{"uri" => "r.pdf", "blob" => Base.encode64("%PDF-"), "mimeType" => "application/pdf"}
             }

      assert link == %{"type" => "resource_link", "uri" => "https://x/y.pdf", "mimeType" => "application/pdf", "name" => "y.pdf"}
    end

    test "a part-free tool still emits exactly one text block" do
      tools = [Native.define_tool(name: "plain", description: "text", execute: fn _ -> "just text" end)]
      assert call_served(tools, "plain")["content"] == [%{"type" => "text", "text" => "just text"}]
    end
  end

  # SPEC §1B pins :max_part_bytes at request ASSEMBLY, not construction. Construction-only
  # enforcement leaks: a part that arrived from an MCP server never passed through an edge
  # constructor, so a remote server could hand us any size it liked. Equally it must not be a
  # blanket failure — a server volunteering a huge image would then kill a run that works today.
  describe "max_part_bytes is enforced at assembly, by provenance (§1B)" do
    test "an attached oversized part fails before any HTTP call" do
      {t, sent} = scripted([anthropic_text("never reached")])
      big = ContentPart.image!({:bytes, String.duplicate("A", 400)}, mime_type: "image/png")

      assert_raise ContentPart.Error, ~r/max_part_bytes/, fn ->
        Client.run(client("anthropic", t, max_part_bytes: 4), [big], [])
      end

      assert sent.() == [], "the caller's own part is their intent — fail before the wire"
    end

    test "a tool-derived oversized part degrades and the run completes" do
      tool =
        Native.define_tool(
          name: "shot",
          description: "screenshot",
          execute: fn _ ->
            %ToolResult{
              output: "screenshot",
              parts: [ContentPart.image!({:bytes, String.duplicate("A", 400)}, mime_type: "image/png")]
            }
          end
        )

      {t, sent} = scripted([anthropic_uses([{"u1", "shot"}]), anthropic_text("saw it")])

      log =
        capture_log(fn ->
          assert Client.run(client("anthropic", t, max_part_bytes: 4), "go", [tool]).text == "saw it"
        end)

      assert log =~ "max_part_bytes"
      refute log =~ Base.encode64(String.duplicate("A", 400))

      [_first, second] = sent.()
      body = Jason.encode!(second)
      assert body =~ "[unsupported image part (image/png, 400 bytes)]"
      refute body =~ String.duplicate("QUFB", 4)
    end

    test "a size warning and an unsupported warning do not suppress one another" do
      tool =
        Native.define_tool(
          name: "both",
          description: "one big image and one audio clip anthropic cannot take",
          execute: fn _ ->
            %ToolResult{
              output: "two parts",
              parts: [
                ContentPart.image!({:bytes, String.duplicate("A", 400)}, mime_type: "image/png"),
                ContentPart.audio!({:bytes, "ID3"}, mime_type: "audio/mpeg")
              ]
            }
          end
        )

      {t, _sent} = scripted([anthropic_uses([{"u1", "both"}]), anthropic_text("ok")])

      log =
        capture_log(fn ->
          assert Client.run(client("anthropic", t, max_part_bytes: 4), "go", [tool]).text == "ok"
        end)

      assert log =~ "max_part_bytes", "the size problem must be reported"
      assert log =~ "audio/mpeg", "and so must the unrelated unsupported-part problem"
    end
  end
end
