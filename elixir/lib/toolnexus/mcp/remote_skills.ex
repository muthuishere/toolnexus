defmodule Toolnexus.Mcp.RemoteSkills do
  @moduledoc """
  Skills extension client (SEP-2640, SPEC §2.7). A server advertising
  `io.modelcontextprotocol/skills` contributes its skills to the toolkit's ONE
  `skill` inventory as data skills (logical base `skill://<name>/`), with the
  SKILL.md body read through `resources/read`.

  URI containment: percent-decode once, then reject any leftover `%`, `\\`, NUL,
  `:` and any empty / `.` / `..` segment. `download/4` also rejects a resource
  outside the skill and a sha256 mismatch before writing anything.
  """
  alias Toolnexus.Mcp.Client2026

  @doc "Split `skill://<name>/<path>` into `{:ok, name, rel}` or `{:error, reason}`."
  def parse_uri("skill://" <> rest) do
    case decode_once(rest) do
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

  def parse_uri(_), do: {:error, "not a skill:// URI"}

  defp decode_once(s) do
    {:ok, URI.decode(s)}
  rescue
    ArgumentError -> :error
  end

  @doc false
  def digest(data), do: "sha256:" <> Base.encode16(:crypto.hash(:sha256, data), case: :lower)

  @doc """
  Every remote skill as a data skill `%{name, description, content, resources, base}`.
  A skill whose URIs fail containment or whose SKILL.md cannot be read is skipped
  with a warning; `{:error, reason}` only when `skills/list` itself fails.
  """
  def fetch(c, wait_for) do
    case Client2026.resolve(Client2026.request(c, "skills/list"), wait_for) do
      {:ok, r} ->
        {:ok,
         r["skills"]
         |> List.wrap()
         |> Enum.flat_map(fn s ->
           case one(c, s, wait_for) do
             {:ok, sk} ->
               [sk]

             {:error, e} ->
               require Logger
               Logger.warning("[toolnexus] remote skill #{inspect(s["uri"])} skipped: #{e}")
               []
           end
         end)}

      {:pending, _, _} ->
        {:error, "authorization pending"}

      err ->
        err
    end
  end

  defp one(c, %{"uri" => uri}, wait_for) when is_binary(uri) do
    with {:ok, name, _} <- parse_uri(uri),
         {:ok, %{"skill" => sk}} <- rpc(c, "skills/get", uri, wait_for),
         res = for(%{"uri" => u} <- List.wrap(sk["resources"]), is_binary(u), do: u),
         :ok <- contained(res, name),
         {:ok, %{"contents" => [%{"text" => text} | _]}} when is_binary(text) <-
           rpc(c, "resources/read", uri, wait_for) do
      {data, body} = Toolnexus.Skill.split_skill_md(text)
      fm = if is_map(sk["frontmatter"]), do: sk["frontmatter"], else: %{}

      {:ok,
       %{
         name: data["name"] || fm["name"] || name,
         description: data["description"] || fm["description"],
         content: body,
         resources: Enum.reject(res, &(&1 == uri)),
         base: "skill://#{name}/"
       }}
    else
      {:error, e} -> {:error, err_text(e)}
      _ -> {:error, "unreadable skill"}
    end
  end

  defp one(_c, _s, _w), do: {:error, "skill entry without uri"}

  defp rpc(c, method, uri, wait_for),
    do: Client2026.resolve(Client2026.request(c, method, %{"uri" => uri}), wait_for)

  defp contained(uris, name) do
    Enum.reduce_while(uris, :ok, fn u, :ok ->
      case parse_uri(u) do
        {:ok, ^name, _} -> {:cont, :ok}
        _ -> {:halt, {:error, "refusing skill resource #{inspect(u)}: outside skill #{name}"}}
      end
    end)
  end

  @doc """
  Fetch a whole skill (skills/get + resources/read per file) into `dir/<name>/`,
  verifying every URI and sha256 digest before anything is written.
  """
  def download(c, uri, dir, wait_for \\ nil) do
    with {:ok, name, _} <- parse_uri(uri),
         {:ok, %{"skill" => sk}} <- rpc(c, "skills/get", uri, wait_for),
         {:ok, files} <- fetch_all(c, name, List.wrap(sk["resources"]), dir, wait_for) do
      Enum.each(files, fn {path, data} ->
        File.mkdir_p!(Path.dirname(path))
        File.write!(path, data)
      end)
    else
      {:error, e} -> {:error, err_text(e)}
      _ -> {:error, "unreadable skill"}
    end
  end

  defp fetch_all(c, name, res, dir, wait_for) do
    Enum.reduce_while(res, {:ok, []}, fn m, {:ok, acc} ->
      fu = if is_map(m) and is_binary(m["uri"]), do: m["uri"], else: ""

      with {:ok, ^name, rel} <- parse_uri(fu),
           {:ok, safe} <- Path.safe_relative(rel) do
        case rpc(c, "resources/read", fu, wait_for) do
          {:ok, %{"contents" => [%{"text" => t} | _]}} when is_binary(t) ->
            d = m["digest"]

            if is_binary(d) and d != "" and d != digest(t),
              do: {:halt, {:error, "#{fu}: digest mismatch"}},
              else: {:cont, {:ok, acc ++ [{Path.join([dir, name, safe]), t}]}}

          {:ok, _} ->
            {:halt, {:error, "#{fu}: empty resources/read"}}

          {:error, e} ->
            {:halt, {:error, e}}

          {:pending, _, _} ->
            {:halt, {:error, "authorization pending"}}
        end
      else
        _ -> {:halt, {:error, "refusing skill resource #{inspect(fu)}: outside skill #{name}"}}
      end
    end)
  end

  defp err_text(%Toolnexus.Mcp.RpcError{message: m}), do: m
  defp err_text(e) when is_binary(e), do: e
  defp err_text(e), do: inspect(e)
end
