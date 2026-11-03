defmodule Mcp2026.Skills do
  @moduledoc """
  Skills extension (SEP-2640) helpers: loading skill folders for the server, `skill://` URI safety
  (python-sdk #3685) and `download/3` for the client.

  A skill URI is percent-decoded ONCE, then rejected if it still contains `%` (double encoding), a
  backslash, NUL, `:` or an empty / `.` / `..` segment. Server (before lookup) and client (before
  writing files) both use `parse_uri/1`.
  """

  @doc false
  def load_dir(root) do
    Path.wildcard(Path.join(root, "**/SKILL.md"))
    |> Enum.flat_map(fn p ->
      case load_skill(Path.dirname(p)) do
        {:ok, sk} -> [sk]
        _ -> []
      end
    end)
    |> Enum.sort_by(& &1.uri)
  end

  defp load_skill(dir) do
    fm = parse_frontmatter(File.read!(Path.join(dir, "SKILL.md")))
    name = fm["name"]

    if is_binary(name) and name != "" and name == Path.basename(dir) do
      files =
        Path.wildcard(Path.join(dir, "**/*"), match_dot: true)
        |> Enum.filter(&File.regular?/1)
        |> Enum.map(&{"skill://#{name}/" <> Path.relative_to(&1, dir), File.read!(&1)})
        |> Enum.sort()

      {:ok, %{uri: "skill://#{name}/SKILL.md", frontmatter: fm, files: files}}
    else
      {:error, "skill #{dir}: name must match dir"}
    end
  end

  @doc "Flat `key: value` YAML subset (the fixture skills use nothing more)."
  def parse_frontmatter(md) do
    md = String.replace(md, "\r\n", "\n")

    with "---\n" <> rest <- md, [head, _] <- :binary.split(rest, "\n---") do
      for line <- String.split(head, "\n"), not String.starts_with?(line, " "),
          [k, v] <- [:binary.split(line, ":")], into: %{} do
        {String.trim(k), v |> String.trim() |> String.trim("\"") |> String.trim("'")}
      end
    else
      _ -> %{}
    end
  end

  @doc false
  def entry(sk) do
    res =
      for {uri, data} <- sk.files,
          do: %{"uri" => uri, "digest" => digest(data), "size" => byte_size(data)}

    %{"uri" => sk.uri, "frontmatter" => sk.frontmatter, "resources" => res}
  end

  def digest(data), do: "sha256:" <> Base.encode16(:crypto.hash(:sha256, data), case: :lower)

  @doc "Split skill://<name>/<path> into `{:ok, name, rel}` or `{:error, reason}`."
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

  # Strict single percent-decode (URI.decode raises on bad escapes).
  defp decode_once(s) do
    {:ok, URI.decode(s)}
  rescue
    ArgumentError -> :error
  end

  @doc """
  Fetch a skill (skills/get + resources/read per file) into dir/<name>/, verifying each sha256
  digest. Every resource URI is validated before anything is written.
  """
  def download(client, uri, dir) do
    with {:ok, name, _} <- parse_uri(uri),
         {:ok, r} <- Mcp2026.Client.call(client, "skills/get", %{"uri" => uri}),
         {:ok, files} <- fetch_all(client, name, get_in(r, ["skill", "resources"]) || [], dir) do
      Enum.each(files, fn {path, data} ->
        File.mkdir_p!(Path.dirname(path))
        File.write!(path, data)
      end)
    end
  end

  defp fetch_all(client, name, res, dir) do
    Enum.reduce_while(res, {:ok, []}, fn m, {:ok, acc} ->
      fu = if is_map(m) and is_binary(m["uri"]), do: m["uri"], else: ""

      with {:ok, ^name, rel} <- parse_uri(fu),
           safe when is_binary(safe) <- Path.safe_relative(rel) |> elem_ok() do
        case Mcp2026.Client.call(client, "resources/read", %{"uri" => fu}) do
          {:ok, %{"contents" => [%{"text" => t} | _]}} when is_binary(t) ->
            d = m["digest"]

            if is_binary(d) and d != "" and d != digest(t),
              do: {:halt, {:error, "#{fu}: digest mismatch"}},
              else: {:cont, {:ok, acc ++ [{Path.join([dir, name, safe]), t}]}}

          {:ok, _} -> {:halt, {:error, "#{fu}: empty resources/read"}}
          err -> {:halt, err}
        end
      else
        _ -> {:halt, {:error, "refusing skill resource #{inspect(fu)}: outside skill #{name}"}}
      end
    end)
  end

  defp elem_ok({:ok, v}), do: v
  defp elem_ok(e), do: e
end
