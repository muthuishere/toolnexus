defmodule Toolnexus.McpHandler.Wire2026 do
  @moduledoc """
  MCP — INBOUND, the 2026-07-28 wire on `Toolnexus.McpHandler` (SPEC §7C.2, ADR 0040).

  Opt-in: list `"2026-07-28"` in `:protocol_versions`. A POST is served here when it
  is a 2026 request (`route?/3`); everything else stays on the §7C.1 wire. The
  profile is stateless: no initialize, no `Mcp-Session-Id`, no server→client
  request. Every hook keeps its meaning: `:authenticate` (after header
  validation), `:list_tools`, `:before_call`, `:on_call`, resources, prompts, plus
  `:list_skills` for the SEP-2640 skills extension. A confirm decision and a §10
  tool suspension leave as an MRTR `input_required` result; the client's retry
  carries `inputResponses` and the echoed `requestState`.
  """

  import Plug.Conn

  alias Toolnexus.{Answer, McpHandler, Request, Tool, ToolResult}
  alias Toolnexus.McpHandler.{Decision, Skill}

  @v2026 "2026-07-28"
  @meta_version "io.modelcontextprotocol/protocolVersion"
  @meta_client_caps "io.modelcontextprotocol/clientCapabilities"
  @meta_server_info "io.modelcontextprotocol/serverInfo"
  @skills_ext "io.modelcontextprotocol/skills"

  @doc false
  # {:handled, conn} | {:legacy, conn, read_result | nil}
  def post(conn, h, ctx) do
    if McpHandler.json_content_type?(conn) do
      max = McpHandler.max_body(h)

      case read_body(conn, length: max + 1, read_length: max + 1) do
        {:ok, body, conn} when byte_size(body) <= max -> post_body(conn, h, ctx, body)
        {:ok, _, conn} = r -> {:legacy, conn, r}
        {:more, _, conn} = r -> {:legacy, conn, r}
        {:error, _} = r -> {:legacy, conn, r}
      end
    else
      {:legacy, conn, nil}
    end
  end

  defp post_body(conn, h, ctx, body) do
    trimmed = String.trim(body)
    batch? = String.starts_with?(trimmed, "[")

    decoded =
      if batch?, do: :error, else: Jason.decode(body)

    case decoded do
      {:ok, %{"jsonrpc" => "2.0"} = msg} ->
        method = if is_binary(msg["method"]), do: msg["method"], else: ""
        params = if is_map(msg["params"]), do: msg["params"], else: %{}
        meta = if is_map(params["_meta"]), do: params["_meta"], else: nil

        if route?(conn, h, method, meta),
          do: {:handled, serve(conn, h, ctx, msg, method, params, meta)},
          else: {:legacy, conn, {:ok, body, conn}}

      _ ->
        cond do
          h.legacy != [] ->
            {:legacy, conn, {:ok, body, conn}}

          batch? ->
            {:handled, write(conn, 400, nil, err(-32600, "batch requests are not supported"))}

          true ->
            {:handled, write(conn, 400, nil, err(-32700, "Parse error"))}
        end
    end
  end

  # SPEC §7C.2 routing.
  defp route?(_conn, %{legacy: []}, _method, _meta), do: true
  defp route?(_conn, _h, "initialize", _meta), do: false

  defp route?(conn, h, method, meta) do
    hv = McpHandler.header(conn, "mcp-protocol-version")

    cond do
      hv != nil and hv in h.legacy ->
        false

      true ->
        method == "server/discover" or McpHandler.header(conn, "mcp-method") != nil or
          (is_map(meta) and Map.has_key?(meta, @meta_version))
    end
  end

  defp serve(conn, h, ctx, msg, method, params, meta) do
    id = msg["id"]

    cond do
      method == "" or id == nil ->
        send_resp(conn, 202, "")

      (e = validate(conn, method, params, meta)) != nil ->
        write(conn, 400, id, e)

      true ->
        case auth(conn, h, ctx) do
          {:halt, conn} ->
            conn

          {:ok, ctx} ->
            ctx = %{ctx | conn: conn, meta: meta}

            case dispatch(h, ctx, method, params, meta) do
              {:ok, result} -> write(conn, 200, id, {:result, result})
              {:error, status, e} -> write(conn, status, id, e)
            end
        end
    end
  end

  defp auth(conn, %{cfg: %{authenticate: f} = cfg}, ctx) when is_function(f, 1) do
    case McpHandler.safe(fn -> f.(conn) end) do
      {:ok, identity} -> {:ok, %{ctx | identity: identity}}
      {:error, r} -> {:halt, McpHandler.write_auth_failure(conn, cfg, r)}
      {:raised, r} -> {:halt, McpHandler.write_auth_failure(conn, cfg, r)}
      r -> {:halt, McpHandler.write_auth_failure(conn, cfg, r)}
    end
  end

  defp auth(_conn, _h, ctx), do: {:ok, ctx}

  # ── header validation (SPEC §7C.2, in order) ───────────────────────────

  defp validate(conn, method, p, meta) do
    mismatch = fn m -> err(-32020, "Header mismatch: " <> m) end
    hv = McpHandler.header(conn, "mcp-protocol-version")
    hm = McpHandler.header(conn, "mcp-method") || ""
    mv = if is_map(meta) and is_binary(meta[@meta_version]), do: meta[@meta_version], else: ""

    name_check =
      if method in ["tools/call", "prompts/get", "resources/read"] do
        key = if method == "resources/read", do: "uri", else: "name"
        want = if is_binary(p[key]), do: p[key], else: ""
        {got, ok} = decode_header_value(McpHandler.header(conn, "mcp-name") || "")
        if ok and got == want, do: nil, else: {got, want}
      end

    cond do
      hv == nil ->
        mismatch.("MCP-Protocol-Version header missing")

      hm != method ->
        mismatch.("Mcp-Method header value '#{hm}' does not match body value '#{method}'")

      name_check != nil ->
        {got, want} = name_check
        mismatch.("Mcp-Name header value '#{got}' does not match body value '#{want}'")

      mv != hv ->
        mismatch.("MCP-Protocol-Version header value '#{hv}' does not match _meta value '#{mv}'")

      mv != @v2026 ->
        err(-32022, "Unsupported protocol version", %{
          "supported" => [@v2026],
          "requested" => mv
        })

      true ->
        nil
    end
  end

  @doc false
  # The `=?base64?…?=` sentinel (SPEC §2.3): {value, ok}. A plain value is ok when non-empty.
  def decode_header_value("=?base64?" <> rest = v) when byte_size(v) >= 11 do
    if String.ends_with?(rest, "?=") do
      case Base.decode64(binary_part(rest, 0, byte_size(rest) - 2)) do
        {:ok, b} -> {b, true}
        :error -> {"", false}
      end
    else
      {v, true}
    end
  end

  def decode_header_value(v), do: {v, v != ""}

  # ── results ────────────────────────────────────────────────────────────

  defp info(h, ctx) do
    i =
      case h.cfg[:server_info] do
        f when is_function(f, 1) -> Map.new(f.(ctx))
        _ -> h.cfg
      end

    %{
      name: McpHandler.blank_default(i[:name], "toolnexus"),
      title: i[:title],
      version: McpHandler.blank_default(i[:version], "0.1.0"),
      instructions: i[:instructions]
    }
  end

  # Stamps resultType and _meta.serverInfo (result `_meta` keys first, serverInfo last).
  defp complete(h, ctx, m, type \\ "complete") do
    i = info(h, ctx)
    meta = if is_map(m["_meta"]), do: m["_meta"], else: %{}

    server_info =
      McpHandler.omit_empty(%{"name" => i.name, "title" => i.title, "version" => i.version})

    m
    |> Map.put("_meta", Map.put(meta, @meta_server_info, server_info))
    |> Map.put("resultType", type)
  end

  defp cache_scope(h),
    do: if(is_function(h.cfg[:authenticate], 1), do: "private", else: "public")

  defp dispatch(h, ctx, "server/discover", _p, _meta) do
    i = info(h, ctx)

    caps =
      %{"tools" => %{}}
      |> then(
        &if(h.cfg[:list_resources] || h.cfg[:list_skills],
          do: Map.put(&1, "resources", %{}),
          else: &1
        )
      )
      |> then(&if(h.cfg[:list_prompts], do: Map.put(&1, "prompts", %{}), else: &1))
      |> then(
        &if(h.cfg[:list_skills],
          do: Map.put(&1, "extensions", %{@skills_ext => %{}}),
          else: &1
        )
      )

    res = %{
      "supportedVersions" => [@v2026],
      "capabilities" => caps,
      "ttlMs" => 3_600_000,
      "cacheScope" => "public"
    }

    res =
      if i.instructions in [nil, ""], do: res, else: Map.put(res, "instructions", i.instructions)

    {:ok, complete(h, ctx, res)}
  end

  defp dispatch(h, ctx, "ping", _p, _meta), do: {:ok, complete(h, ctx, %{})}

  defp dispatch(h, ctx, "tools/list", _p, _meta) do
    case McpHandler.tools(h, ctx) do
      {:ok, tools} ->
        out = tools |> Enum.sort_by(& &1.name) |> Enum.map(&McpHandler.wire_tool/1)

        {:ok,
         complete(h, ctx, %{"tools" => out, "ttlMs" => 60_000, "cacheScope" => cache_scope(h)})}

      {:error, msg} ->
        {:error, 200, err(-32603, msg)}
    end
  end

  defp dispatch(h, ctx, "tools/call", p, meta), do: call_tool(h, ctx, p, meta)

  defp dispatch(%{cfg: %{list_skills: f}} = h, ctx, "skills/list", _p, _meta)
       when is_function(f, 1) do
    with {:ok, skills} <- skills(h, ctx) do
      list =
        Enum.map(skills, fn s ->
          %{"uri" => "skill://#{s.name}/SKILL.md", "frontmatter" => frontmatter(s)}
        end)

      {:ok,
       complete(h, ctx, %{"skills" => list, "ttlMs" => 60_000, "cacheScope" => cache_scope(h)})}
    end
  end

  defp dispatch(%{cfg: %{list_skills: f}} = h, ctx, "skills/get", p, _meta)
       when is_function(f, 1) do
    uri = if is_binary(p["uri"]), do: p["uri"], else: ""

    with {:uri, {:ok, _, _}} <- {:uri, parse_skill_uri(uri)},
         {:ok, skills} <- skills(h, ctx) do
      case Enum.find(skills, &("skill://#{&1.name}/SKILL.md" == uri)) do
        nil -> {:error, 200, err(-32602, "Unknown skill: " <> uri)}
        s -> {:ok, complete(h, ctx, %{"skill" => skill_entry(s)})}
      end
    else
      {:uri, {:error, why}} -> {:error, 200, err(-32602, "Invalid skill URI: " <> why)}
      other -> other
    end
  end

  defp dispatch(h, ctx, "resources/read", p, meta) do
    uri = if is_binary(p["uri"]), do: p["uri"], else: ""

    if String.starts_with?(uri, "skill://") and is_function(h.cfg[:list_skills], 1) do
      read_skill(h, ctx, uri)
    else
      legacy(h, ctx, "resources/read", p, meta, h.cfg[:list_resources] && h.cfg[:read_resource])
    end
  end

  defp dispatch(h, ctx, "resources/list", p, meta),
    do: legacy(h, ctx, "resources/list", p, meta, h.cfg[:list_resources])

  defp dispatch(h, ctx, method, p, meta) when method in ["prompts/list", "prompts/get"],
    do: legacy(h, ctx, method, p, meta, h.cfg[:list_prompts])

  defp dispatch(_h, _ctx, method, _p, _meta), do: not_found(method)

  defp not_found(method), do: {:error, 404, err(-32601, "Method not found: " <> method)}

  # Same payload as the §7C.1 wire; only the envelope differs.
  defp legacy(h, ctx, method, p, _meta, enabled) do
    if enabled do
      case McpHandler.dispatch_legacy(ctx.conn, h, ctx, method, p) do
        {_conn, {:ok, res}} -> {:ok, complete(h, ctx, res)}
        {_conn, {:error, code, msg}} -> {:error, 200, err(code, msg)}
      end
    else
      not_found(method)
    end
  end

  defp read_skill(h, ctx, uri) do
    with {:uri, {:ok, name, rel}} <- {:uri, parse_skill_uri(uri)},
         {:ok, skills} <- skills(h, ctx) do
      file =
        Enum.find_value(skills, fn s ->
          s.name == name && Enum.find(files(s), &(&1.path == rel))
        end)

      case file do
        nil ->
          {:error, 200, err(-32602, "Resource not found: " <> uri)}

        f ->
          mt = if String.ends_with?(rel, ".md"), do: "text/markdown", else: "text/plain"

          {:ok,
           complete(h, ctx, %{
             "contents" => [%{"uri" => uri, "mimeType" => mt, "text" => f.text}],
             "ttlMs" => 60_000,
             "cacheScope" => cache_scope(h)
           })}
      end
    else
      {:uri, {:error, why}} -> {:error, 200, err(-32602, "Invalid resource URI: " <> why)}
      other -> other
    end
  end

  defp skills(h, ctx) do
    case McpHandler.hook(fn -> h.cfg[:list_skills].(ctx) end) do
      {:ok, list} -> {:ok, list |> Enum.map(&to_skill/1) |> Enum.sort_by(& &1.name)}
      {:error, msg} -> {:error, 200, err(-32603, msg)}
    end
  end

  defp to_skill(%Skill{} = s), do: s

  defp to_skill(m) when is_map(m),
    do: %Skill{
      name: m[:name] || m["name"],
      frontmatter: m[:frontmatter] || m["frontmatter"],
      files: m[:files] || m["files"] || []
    }

  defp files(%Skill{files: fs}) do
    Enum.map(fs || [], fn f ->
      %{path: f[:path] || f["path"], text: f[:text] || f["text"] || ""}
    end)
  end

  defp frontmatter(%Skill{frontmatter: fm}) when is_map(fm), do: fm
  defp frontmatter(%Skill{name: n}), do: %{"name" => n}

  defp skill_entry(s) do
    res =
      s
      |> files()
      |> Enum.sort_by(& &1.path)
      |> Enum.map(fn f ->
        %{
          "uri" => "skill://#{s.name}/#{f.path}",
          "digest" => "sha256:" <> Base.encode16(:crypto.hash(:sha256, f.text), case: :lower),
          "size" => byte_size(f.text)
        }
      end)

    %{"uri" => "skill://#{s.name}/SKILL.md", "frontmatter" => frontmatter(s), "resources" => res}
  end

  @doc false
  # skill://<name>/<path> containment (SPEC §2.7): {:ok, name, rel} | {:error, why}.
  def parse_skill_uri("skill://" <> rest) do
    case pct_decode(rest) do
      :error ->
        {:error, "bad percent-encoding"}

      {:ok, dec} ->
        segs = String.split(dec, "/")

        cond do
          String.contains?(dec, ["%", "\\", <<0>>]) ->
            {:error, "skill URI has encoded, backslash or NUL characters"}

          length(segs) < 2 ->
            {:error, "skill URI needs skill://<name>/<path>"}

          Enum.any?(segs, &(&1 in ["", ".", ".."] or String.contains?(&1, ":"))) ->
            {:error, "skill URI path escapes the skill (empty, '.', '..' or ':' segment)"}

          true ->
            [name | rel] = segs
            {:ok, name, Enum.join(rel, "/")}
        end
    end
  end

  def parse_skill_uri(_), do: {:error, "not a skill:// URI"}

  # Go url.PathUnescape: every `%` must start two hex digits.
  defp pct_decode(s) do
    if Regex.match?(~r/%(?![0-9A-Fa-f]{2})/, s), do: :error, else: {:ok, URI.decode(s)}
  end

  # ── tools/call with MRTR ───────────────────────────────────────────────

  defp call_tool(h, ctx, p, meta) do
    name = if is_binary(p["name"]), do: p["name"], else: ""

    case McpHandler.tools(h, ctx) do
      {:error, msg} ->
        {:error, 200, err(-32603, msg)}

      {:ok, tools} ->
        case Enum.find(tools, &(&1.name == name)) do
          %Tool{execute: f} = tool when is_function(f) -> call_found(h, ctx, tool, p, meta)
          _ -> {:error, 200, err(-32602, "Unknown tool: " <> name)}
        end
    end
  end

  defp call_found(h, ctx, tool, p, meta) do
    started = System.monotonic_time(:millisecond)
    name = tool.name

    finish = fn m, decision, is_error, exec_err ->
      case h.cfg[:on_call] do
        f when is_function(f, 1) ->
          event = %{
            name: name,
            source: tool.source,
            ms: System.monotonic_time(:millisecond) - started,
            is_error: is_error,
            err: exec_err,
            result_bytes: byte_size(Jason.encode!(m)),
            decision: decision,
            identity: ctx.identity,
            session_id: nil
          }

          _ = McpHandler.safe(fn -> f.(event) end)

        _ ->
          :ok
      end

      {:ok, m}
    end

    result = fn %ToolResult{} = r, decision, exec_err ->
      finish.(complete(h, ctx, McpHandler.wire_result(r)), decision, r.is_error, exec_err)
    end

    case Map.fetch(p, "arguments") do
      {:ok, a} when not is_nil(a) and not is_map(a) ->
        result.(ToolResult.error("invalid arguments: must be an object"), :allow, nil)

      fetched ->
        args =
          case fetched do
            {:ok, a} when is_map(a) -> a
            _ -> %{}
          end

        state =
          case decode_state(p["requestState"]) do
            {:ok, %{tool: ^name} = s} -> s
            _ -> nil
          end

        responses = if is_map(p["inputResponses"]), do: p["inputResponses"], else: %{}

        decision =
          case h.cfg[:before_call] do
            f when is_function(f, 3) ->
              McpHandler.normalize_decision(McpHandler.safe(fn -> f.(ctx, tool, args) end))

            _ ->
              %Decision{}
          end

        gate(h, ctx, tool, args, state, responses, meta, decision, finish, result)
    end
  end

  defp gate(h, ctx, tool, args, state, responses, meta, decision, finish, result) do
    name = tool.name

    case decision do
      %Decision{kind: :deny, result: %ToolResult{} = r} ->
        result.(r, :deny, nil)

      %Decision{kind: :deny, message: m} ->
        result.(ToolResult.error(McpHandler.blank_default(m, "denied")), :deny, nil)

      %Decision{kind: :confirm} = d ->
        ans = input_response("confirm", responses["confirm"])

        cond do
          state != nil and state.confirmed ->
            run(h, ctx, tool, args, state, responses, :confirm, finish, result)

          state != nil and ans != nil ->
            if not ans.ok or (is_map(ans.data) and ans.data["confirm"] == false) do
              result.(ToolResult.error("declined"), :confirm, nil)
            else
              # the confirm round is consumed: not passed on as an answer
              run(
                h,
                ctx,
                tool,
                args,
                %{tool: name, confirmed: true},
                %{},
                :confirm,
                finish,
                result
              )
            end

          not can_elicit?(meta) ->
            case d.result do
              %ToolResult{} = r ->
                result.(r, :confirm, nil)

              _ ->
                result.(ToolResult.error("confirmation required: #{d.message}"), :confirm, nil)
            end

          true ->
            msg = McpHandler.blank_default(d.message, "Confirm this action?")

            req = %{
              "method" => "elicitation/create",
              "params" => %{
                "mode" => "form",
                "message" => msg,
                "requestedSchema" => %{
                  "type" => "object",
                  "properties" => %{
                    "confirm" => %{
                      "type" => "boolean",
                      "title" => "Confirm",
                      "description" => msg
                    }
                  }
                }
              }
            }

            m =
              complete(
                h,
                ctx,
                %{
                  "inputRequests" => %{"confirm" => req},
                  "requestState" => encode_state(name, false)
                },
                "input_required"
              )

            finish.(m, :confirm, false, nil)
        end

      %Decision{} ->
        run(h, ctx, tool, args, state, responses, :allow, finish, result)
    end
  end

  defp run(h, ctx, tool, args, state, responses, decision, finish, result) do
    answer =
      case {state, Map.to_list(responses)} do
        {%{}, [{k, v}]} -> input_response(k, v)
        _ -> nil
      end

    {res, exec_err} = McpHandler.execute(tool, args, ctx, answer)

    case {res, exec_err} do
      {%ToolResult{metadata: %{pending: %Request{} = req}}, nil} ->
        confirmed = state != nil and state.confirmed

        m =
          complete(
            h,
            ctx,
            %{
              "inputRequests" => %{req.id => input_request(req)},
              "requestState" => encode_state(tool.name, confirmed)
            },
            "input_required"
          )

        finish.(m, decision, false, nil)

      _ ->
        result.(res, decision, exec_err)
    end
  end

  # The opaque requestState: base64url-nopad of compact JSON, keys in this order.
  # Not integrity-protected (the client could equally answer "accept").
  defp encode_state(tool, confirmed) do
    json =
      ~s({"tool":#{Jason.encode!(tool)}) <> if(confirmed, do: ~s(,"confirmed":true}), else: "}")

    Base.url_encode64(json, padding: false)
  end

  defp decode_state(v) when is_binary(v) do
    with {:ok, raw} <- Base.url_decode64(v, padding: false),
         {:ok, %{} = m} <- Jason.decode(raw),
         t when is_binary(t) or is_nil(t) <- m["tool"],
         c when is_boolean(c) or is_nil(c) <- m["confirmed"] do
      {:ok, %{tool: t || "", confirmed: c == true}}
    else
      _ -> :error
    end
  end

  defp decode_state(_), do: :error

  # One MRTR input response ⇒ a §10 Answer.
  defp input_response(key, %{} = m) do
    action = m["action"]
    ok = action == "accept"

    %Answer{
      id: key,
      ok: ok,
      data: if(is_map(m["content"]), do: m["content"]),
      reason:
        cond do
          ok -> nil
          action == "decline" -> "declined"
          true -> "cancelled"
        end
    }
  end

  defp input_response(_key, _), do: nil

  defp can_elicit?(%{} = meta) do
    case meta[@meta_client_caps] do
      %{} = caps -> Map.has_key?(caps, "elicitation")
      _ -> false
    end
  end

  defp can_elicit?(_), do: false

  # A §10 Request ⇒ an MRTR input request: authorization + url ⇒ URL mode, else form.
  defp input_request(%Request{kind: "authorization", url: url} = r) when url not in [nil, ""] do
    %{
      "method" => "elicitation/create",
      "params" => %{"mode" => "url", "message" => r.prompt, "url" => url, "elicitationId" => r.id}
    }
  end

  defp input_request(%Request{} = r) do
    schema =
      case r.data do
        %{} = d -> d["schema"] || d[:schema]
        _ -> nil
      end

    schema = if is_map(schema), do: schema, else: %{"type" => "object", "properties" => %{}}

    %{
      "method" => "elicitation/create",
      "params" => %{"mode" => "form", "message" => r.prompt, "requestedSchema" => schema}
    }
  end

  # ── wire ───────────────────────────────────────────────────────────────

  defp err(code, msg, data \\ nil) do
    e = %{"code" => code, "message" => msg}
    {:error, if(data, do: Map.put(e, "data", data), else: e)}
  end

  defp write(conn, status, id, outcome) do
    msg =
      case outcome do
        {:result, r} -> %{"jsonrpc" => "2.0", "id" => id, "result" => r}
        {:error, e} -> %{"jsonrpc" => "2.0", "id" => id, "error" => e}
      end

    conn
    |> put_resp_header("content-type", "application/json")
    |> send_resp(status, Jason.encode!(msg))
  end

  # ── loadMcpSkills ──────────────────────────────────────────────────────

  @doc false
  def load_mcp_skills(dir) do
    case File.lstat(dir) do
      {:ok, %{type: :directory}} ->
        skills =
          dir
          |> skill_files()
          |> Enum.flat_map(&load_skill/1)
          |> Enum.sort_by(& &1.name)

        {:ok, skills}

      {:ok, _} ->
        {:error, "not a directory: #{dir}"}

      {:error, reason} ->
        {:error, "#{dir}: #{:file.format_error(reason)}"}
    end
  end

  # Every regular file under `root` (symlinks not followed), as absolute paths.
  defp walk(root) do
    case File.ls(root) do
      {:ok, entries} ->
        entries
        |> Enum.sort()
        |> Enum.flat_map(fn e ->
          full = Path.join(root, e)

          case File.lstat(full) do
            {:ok, %{type: :directory}} -> walk(full)
            {:ok, %{type: :regular}} -> [full]
            _ -> []
          end
        end)

      _ ->
        []
    end
  end

  defp skill_files(dir), do: dir |> walk() |> Enum.filter(&(Path.basename(&1) == "SKILL.md"))

  defp load_skill(path) do
    with {:ok, raw} <- File.read(path),
         {fm, false} <- Toolnexus.Skill.frontmatter(raw),
         name when is_binary(name) and name != "" <- fm["name"] do
      root = Path.dirname(path)

      files =
        for fp <- walk(root),
            {:ok, b} <- [File.read(fp)],
            String.valid?(b),
            do: %{path: Path.relative_to(fp, root), text: b}

      [
        %Skill{
          name: name,
          frontmatter: %{"name" => name, "description" => fm["description"] || ""},
          files: Enum.sort_by(files, & &1.path)
        }
      ]
    else
      _ -> []
    end
  end
end
