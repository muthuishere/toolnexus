defmodule Toolnexus.McpHandlerConformanceTest do
  # SPEC §7C.1 cross-port conformance: replays ../conformance/mcp_handler/cases.json
  # (harness contract in conformance/mcp_handler/README.md; Go reference
  # golang/mcphandler_conformance_test.go).
  use ExUnit.Case, async: false

  import Toolnexus.McpHandlerHelpers

  alias Toolnexus.{McpHandler, Tool, ToolAnnotations, ToolHints, ToolResult}
  alias Toolnexus.McpHandler.Decision

  @cases Path.expand("../../conformance/mcp_handler/cases.json", __DIR__)
  @external_resource @cases
  @file_data @cases |> File.read!() |> Jason.decode!()

  defp to_result(nil), do: nil

  defp to_result(r),
    do: %ToolResult{
      output: r["output"] || "",
      is_error: r["isError"] == true,
      structured: r["structured"],
      meta: r["meta"]
    }

  defp build_tool(ft, rec) do
    annotations =
      case ft["annotations"] do
        nil ->
          nil

        a ->
          %ToolAnnotations{
            title: a["title"],
            read_only_hint: a["readOnlyHint"],
            destructive_hint: a["destructiveHint"],
            idempotent_hint: a["idempotentHint"],
            open_world_hint: a["openWorldHint"]
          }
      end

    hints =
      case ft["hints"] do
        nil ->
          nil

        h ->
          %ToolHints{
            read_only: h["readOnly"] == true,
            destructive: h["destructive"] == true,
            open_world: h["openWorld"] == true
          }
      end

    %Tool{
      name: ft["name"],
      description: ft["description"],
      input_schema: ft["inputSchema"],
      output_schema: ft["outputSchema"],
      annotations: annotations,
      hints: hints,
      source: "native",
      execute: fn args, ctx ->
        Agent.update(rec, fn s -> %{s | executed: s.executed ++ [ft["name"]]} end)

        case ft["behavior"] do
          "echo" ->
            ToolResult.ok(args["text"] || "")

          "whoami" ->
            trace = (McpHandler.request_meta(ctx) || %{})["trace"] || ""
            ToolResult.ok("#{McpHandler.identity(ctx) || ""}|#{trace}")

          "error" ->
            {:error, ft["error"]}

          _ ->
            to_result(ft["result"])
        end
      end
    }
  end

  defp path(v, p) do
    Enum.reduce(String.split(p, "."), v, fn seg, acc ->
      case acc do
        m when is_map(m) ->
          Map.get(m, seg)

        l when is_list(l) ->
          case Integer.parse(seg) do
            {n, ""} -> Enum.at(l, n)
            _ -> nil
          end

        _ ->
          nil
      end
    end)
  end

  for sc <- @file_data["scenarios"] do
    @sc sc
    test "conformance: #{sc["name"]}" do
      run_scenario(@sc, @file_data["fixture"])
    end
  end

  defp run_scenario(sc, fixture) do
    {:ok, rec} = Agent.start_link(fn -> %{executed: [], on_call: []} end)
    tools = Enum.map(fixture["tools"], &build_tool(&1, rec))
    by_name = Map.new(fixture["tools"], &{&1["name"], &1})
    c = sc["config"] || %{}

    cfg =
      [
        list_tools: fn ctx ->
          id = McpHandler.identity(ctx)

          Enum.filter(tools, fn t ->
            case by_name[t.name]["visibleTo"] do
              nil -> true
              v -> id in v
            end
          end)
        end,
        before_call: fn _ctx, t, _args ->
          case by_name[t.name]["decision"] do
            nil ->
              :allow

            d ->
              %Decision{
                kind: String.to_existing_atom(d["kind"]),
                message: d["message"],
                result: to_result(d["result"])
              }
          end
        end,
        on_call: fn ev ->
          Agent.update(rec, fn s ->
            %{
              s
              | on_call:
                  s.on_call ++
                    [
                      %{
                        "name" => ev.name,
                        "isError" => ev.is_error,
                        "decision" => to_string(ev.decision)
                      }
                    ]
            }
          end)
        end
      ] ++
        for(
          {json_key, key} <- [
            {"name", :name},
            {"title", :title},
            {"version", :version},
            {"instructions", :instructions},
            {"stateless", :stateless},
            {"statelessSessionId", :stateless_session_id},
            {"protocolVersions", :protocol_versions}
          ],
          Map.has_key?(c, json_key),
          do: {key, c[json_key]}
        )

    cfg =
      if c["auth"] do
        tokens = fixture["auth"]["tokens"]

        cfg ++
          [
            authenticate: fn conn ->
              case Map.fetch(
                     tokens,
                     conn |> Plug.Conn.get_req_header("authorization") |> List.first() || ""
                   ) do
                {:ok, id} -> {:ok, id}
                :error -> {:error, "unauthorized"}
              end
            end,
            www_authenticate: fn _, _ -> fixture["auth"]["wwwAuthenticate"] end
          ]
      else
        cfg
      end

    h = Toolnexus.mcp_handler(nil, cfg)
    url = start_handler(h)

    Enum.reduce(Enum.with_index(sc["steps"]), nil, fn {st, i}, sid ->
      rq = st["request"]
      method = rq["method"] |> String.downcase() |> String.to_atom()

      opts =
        [
          body: rq["rawBody"] || rq["body"],
          accept: rq["accept"],
          content_type: rq["contentType"],
          token: rq["token"] || sc["token"],
          sid: rq["sessionId"] || if(rq["session"], do: sid),
          version: rq["version"]
        ]
        |> Enum.reject(fn {_, v} -> v in [nil, ""] end)

      {status, hdrs, body} = raw(method, url, opts)
      ex = st["expect"]
      where = "#{sc["name"]} step #{i}"

      if ex["status"], do: assert(status == ex["status"], "#{where}: status #{status} (#{body})")

      for {k, v} <- ex["headers"] || %{},
          do: assert(hdrs[String.downcase(k)] == v, "#{where}: header #{k}")

      for k <- ex["headersPresent"] || [],
          do: assert(hdrs[String.downcase(k)] not in [nil, ""], "#{where}: #{k} missing")

      for k <- ex["headersAbsent"] || [],
          do: assert(hdrs[String.downcase(k)] in [nil, ""], "#{where}: #{k} present")

      if ex["json"] || ex["jsonPath"] do
        got = Jason.decode!(body)
        if ex["json"], do: assert(got == ex["json"], "#{where}: body #{body}")

        for {p, want} <- ex["jsonPath"] || %{},
            do: assert(path(got, p) == want, "#{where}: #{p}")
      end

      if st["capture"] == "sid", do: hdrs["mcp-session-id"], else: sid
    end)

    s = Agent.get(rec, & &1)
    if sc["expectExecuted"], do: assert(s.executed == sc["expectExecuted"])
    if sc["expectOnCall"], do: assert(s.on_call == sc["expectOnCall"])
    McpHandler.stop(h)
  end
end
