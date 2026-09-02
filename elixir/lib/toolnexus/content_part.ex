defmodule Toolnexus.ContentPart.Error do
  @moduledoc "Typed construction / emission error for a `Toolnexus.ContentPart` (SPEC §1B, §8A)."
  defexception [:message]
end

defmodule Toolnexus.ContentPart do
  @moduledoc """
  A multimodal content part (SPEC §1B) — `text | image | file | audio`.

  A non-text part carries a `mime_type` (wire key `mimeType`) plus **exactly one** of
  `data` (standard base64, padded, unwrapped) or `url`. Both, or neither, is a typed
  construction error. **A part never holds a filesystem path** — a path does not survive a
  persisted-and-replayed transcript nor the MCP / A2A process boundary, so the edge
  constructors read and base64-encode the bytes at construction time.

      ContentPart.image!("shot.png")                        # path  → data
      ContentPart.image!("data:image/png;base64,iVBOR…")    # data: → {mimeType, data}
      ContentPart.image!("https://example.com/a.png")       # https → url
      ContentPart.image!({:bytes, bin}, mime_type: "image/png")
      ContentPart.image!(["chunk", [?a | "bc"]], mime_type: "image/png")   # iodata
      ContentPart.image!(File.stream!("shot.png", 2048))                   # File.Stream
      ContentPart.image!(chunks, mime_type: "image/png")                   # any Enumerable

  **Accept broadly, store narrowly.** A caller who already holds an iolist or a `File.Stream`
  should not have to flatten it by hand; whatever comes in, what lands in the part is bytes and
  a `mime_type`. A stream is consumed **eagerly at construction** — a part holding a half-read
  stream would not survive the transcript boundary any better than a path does.

  A bare binary is always read as a **path** (or a `data:`/`https:` URL), never as raw content:
  a binary is ambiguous — `"abc"` is both a plausible filename and plausible bytes — and
  guessing would silently turn a caller's file into its own name. Raw bytes therefore stay
  explicitly tagged as `{:bytes, bin}`. An iolist has no such ambiguity (a path is never a
  list), so it is accepted unwrapped.

  Every constructor has a `{:ok, part} | {:error, exception}` form and a raising `!` form,
  mirroring `Toolnexus.create_toolkit/1` / `create_toolkit!/1`.

  `type` is a **String**, never an atom, so `from_map/1` round-trips an unknown wire type
  without ever calling `String.to_atom/1` on untrusted input.

  Provider emission (`to_block/2`, `encode/3`) lives here rather than in
  `Toolnexus.Adapters` — the adapter module is tool-**schema** only (SPEC §8A) — and is
  public precisely so the `(style × part-type)` matrix is unit-testable without an LLM.
  """

  alias Toolnexus.ContentPart.Error

  defstruct [:type, :text, :mime_type, :data, :url, :name]

  @type t :: %__MODULE__{
          type: String.t(),
          text: String.t() | nil,
          mime_type: String.t() | nil,
          data: String.t() | nil,
          url: String.t() | nil,
          name: String.t() | nil
        }

  @types ~w(text image file audio)

  # SPEC §6: the fixed media extension table. No sniffing, no platform mime database —
  # /etc/mime.types varies per machine and would break cross-port parity.
  @extensions %{
    "png" => {"image/png", "image"},
    "jpg" => {"image/jpeg", "image"},
    "jpeg" => {"image/jpeg", "image"},
    "gif" => {"image/gif", "image"},
    "webp" => {"image/webp", "image"},
    "pdf" => {"application/pdf", "file"},
    "mp3" => {"audio/mpeg", "audio"},
    "wav" => {"audio/wav", "audio"}
  }

  # SPEC §8A: the positive allowlist. A part that produced no allowlisted block never
  # reaches the wire — an unknown block type upstream returns 200 with the content
  # silently discarded, which is the exact bug this rule exists to remove.
  @allowed %{
    "openai" => ~w(text image_url file input_audio),
    "anthropic" => ~w(text image document)
  }

  defimpl Jason.Encoder do
    def encode(%{type: type} = p, opts) do
      %{"type" => type}
      |> maybe_put("text", p.text)
      |> maybe_put("mimeType", p.mime_type)
      |> maybe_put("data", p.data)
      |> maybe_put("url", p.url)
      |> maybe_put("name", p.name)
      |> Jason.Encode.map(opts)
    end

    defp maybe_put(m, _k, nil), do: m
    defp maybe_put(m, k, v), do: Map.put(m, k, v)
  end

  # A part's `data` is user content; it never belongs in a log line, an exception, or an
  # `inspect/1` of a transcript (SPEC §9).
  defimpl Inspect do
    import Inspect.Algebra

    def inspect(%{type: "text", text: text}, opts),
      do: concat(["#Toolnexus.ContentPart<text ", to_doc(text, opts), ">"])

    def inspect(p, _opts) do
      loc = if p.url, do: "url", else: "#{Toolnexus.ContentPart.byte_size_of(p)} bytes"
      concat(["#Toolnexus.ContentPart<", p.type, " ", to_string(p.mime_type || "?"), " ", loc, ">"])
    end
  end

  @doc "The fixed extension → `{mimeType, part type}` media table (SPEC §6)."
  @spec media_table() :: %{String.t() => {String.t(), String.t()}}
  def media_table, do: @extensions

  @doc "`{mimeType, type}` for a path's extension, or `:error` when it is not in the table."
  @spec media_for_path(String.t()) :: {:ok, {String.t(), String.t()}} | :error
  def media_for_path(path) do
    ext = path |> Path.extname() |> String.trim_leading(".") |> String.downcase()
    Map.fetch(@extensions, ext)
  end

  @doc "A text part."
  @spec text(String.t()) :: t()
  def text(s) when is_binary(s), do: %__MODULE__{type: "text", text: s}

  @doc "Build an `image` part from any accepted source (see `new/3`)."
  @spec image(term(), keyword()) :: {:ok, t()} | {:error, Exception.t()}
  def image(source, opts \\ []), do: new("image", source, opts)

  @doc "Like `image/2` but raises on failure and returns the part."
  @spec image!(term(), keyword()) :: t()
  def image!(source, opts \\ []), do: new!("image", source, opts)

  @doc "Build a `file` part from any accepted source (see `new/3`)."
  @spec file(term(), keyword()) :: {:ok, t()} | {:error, Exception.t()}
  def file(source, opts \\ []), do: new("file", source, opts)

  @doc "Like `file/2` but raises on failure and returns the part."
  @spec file!(term(), keyword()) :: t()
  def file!(source, opts \\ []), do: new!("file", source, opts)

  @doc "Build an `audio` part from any accepted source (see `new/3`)."
  @spec audio(term(), keyword()) :: {:ok, t()} | {:error, Exception.t()}
  def audio(source, opts \\ []), do: new("audio", source, opts)

  @doc "Like `audio/2` but raises on failure and returns the part."
  @spec audio!(term(), keyword()) :: t()
  def audio!(source, opts \\ []), do: new!("audio", source, opts)

  @doc """
  Build a non-text part of `type` from `source`, normalising at construction (SPEC §1B):

    * a `data:<mime>;base64,<b64>` URL → `{mime_type, data}`, never stored as `url`
    * an `http:`/`https:` URL → kept as `url`
    * `{:bytes, binary}` → base64 now; `:mime_type` required
    * `iodata` (any iolist, improper lists included) → `IO.iodata_to_binary/1` now, base64 now;
      `:mime_type` required
    * a `File.Stream` → read to bytes now, base64 now; mime from the stream's `.path` via the
      fixed table (§6) unless `:mime_type` says otherwise
    * any other `Enumerable` of binary chunks → consumed **eagerly** now; `:mime_type` required
    * any other binary → a filesystem path: read now, base64 now, mime from the fixed table

  A bare binary is never treated as raw content — see the module doc for why `{:bytes, bin}`
  stays explicitly tagged.

  Options: `:mime_type` (required for bytes/iodata/enumerables, overrides the table for a path
  or a `File.Stream`), `:name`, `:max_part_bytes` (a cap on **decoded** bytes).
  """
  @spec new(String.t(), term(), keyword()) :: {:ok, t()} | {:error, Exception.t()}
  def new(type, source, opts \\ [])

  def new(type, source, opts) when type in @types do
    build(type, source, opts)
  rescue
    e in Error -> {:error, e}
  end

  def new(type, _source, _opts),
    do: {:error, %Error{message: "content part: unknown type #{inspect(type)}"}}

  @doc "Like `new/3` but raises on failure and returns the part."
  @spec new!(String.t(), term(), keyword()) :: t()
  def new!(type, source, opts \\ []) do
    case new(type, source, opts) do
      {:ok, part} -> part
      {:error, e} -> raise e
    end
  end

  defp build(type, {:bytes, bin}, opts) when is_binary(bin), do: from_bytes(type, bin, opts)

  # A File.Stream knows its own path, so it can carry a mime type the way a path does.
  defp build(type, %File.Stream{path: path} = stream, opts) do
    mime = mime_for_path!(type, path, opts)
    bin = consume!(type, stream)
    check_size!(byte_size(bin), opts)

    {:ok,
     %__MODULE__{
       type: type,
       mime_type: mime,
       data: Base.encode64(bin),
       name: opts[:name] || if(type == "file", do: Path.basename(path))
     }}
  end

  # iodata: a proper OR improper iolist. IO.iodata_to_binary/1 handles both; Enum cannot walk
  # an improper list at all, which is why lists take their own clause ahead of Enumerable.
  defp build(type, source, opts) when is_list(source), do: from_bytes(type, iodata!(type, source), opts)

  defp build(type, source, opts) when is_binary(source) do
    cond do
      String.starts_with?(source, "data:") -> from_data_url(type, source, opts)
      String.starts_with?(source, "http://") or String.starts_with?(source, "https://") -> from_url(type, source, opts)
      true -> from_path(type, source, opts)
    end
  end

  defp build(type, source, opts) do
    if Enumerable.impl_for(source) do
      from_bytes(type, consume!(type, source), opts)
    else
      fail(
        "content part: #{type} source must be a path, a URL, {:bytes, binary}, iodata or a " <>
          "stream, got #{inspect(source)}"
      )
    end
  end

  defp from_bytes(type, bin, opts) do
    mime = opts[:mime_type] || fail("content part: #{type} from bytes needs an explicit :mime_type")
    check_size!(byte_size(bin), opts)
    {:ok, %__MODULE__{type: type, mime_type: mime, data: Base.encode64(bin), name: opts[:name]}}
  end

  defp iodata!(type, source) do
    IO.iodata_to_binary(source)
  rescue
    ArgumentError -> fail("content part: #{type} source is a list but not valid iodata")
  end

  # Eager: the part must never hold an unread stream (SPEC §1B).
  defp consume!(type, source) do
    source |> Enum.to_list() |> IO.iodata_to_binary()
  rescue
    e in [ArgumentError, File.Error, Protocol.UndefinedError] ->
      fail("content part: #{type} stream did not yield binary chunks: #{Exception.message(e)}")
  end

  defp from_data_url(type, source, opts) do
    case Regex.run(~r/^data:([^;,]*);base64,(.*)$/s, source) do
      [_, mime, b64] ->
        bin =
          case Base.decode64(b64) do
            {:ok, bin} -> bin
            :error -> fail("content part: #{type} data: URL is not valid base64")
          end

        check_size!(byte_size(bin), opts)

        {:ok,
         %__MODULE__{
           type: type,
           mime_type: opts[:mime_type] || blank_to_nil(mime) || fail("content part: data: URL has no mime type"),
           data: Base.encode64(bin),
           name: opts[:name]
         }}

      _ ->
        fail("content part: only base64 data: URLs are supported, got #{String.slice(source, 0, 24)}…")
    end
  end

  defp from_url(type, source, opts),
    do: {:ok, %__MODULE__{type: type, mime_type: opts[:mime_type], url: source, name: opts[:name]}}

  defp mime_for_path!(_type, path, opts) do
    case opts[:mime_type] do
      m when is_binary(m) ->
        m

      _ ->
        case media_for_path(path) do
          {:ok, {mime, _}} -> mime
          :error -> fail("content part: unknown file extension #{inspect(Path.extname(path))}; pass :mime_type")
        end
    end
  end

  defp from_path(type, path, opts) do
    mime = mime_for_path!(type, path, opts)

    bin =
      case File.read(path) do
        {:ok, bin} -> bin
        {:error, reason} -> fail("content part: #{path}: #{:file.format_error(reason)}")
      end

    check_size!(byte_size(bin), opts)

    {:ok,
     %__MODULE__{
       type: type,
       mime_type: mime,
       data: Base.encode64(bin),
       name: opts[:name] || if(type == "file", do: Path.basename(path))
     }}
  end

  defp check_size!(bytes, opts) do
    case opts[:max_part_bytes] do
      n when is_integer(n) and n >= 0 and bytes > n ->
        fail("content part: #{bytes} decoded bytes exceeds max_part_bytes #{n}")

      _ ->
        :ok
    end
  end

  defp blank_to_nil(""), do: nil
  defp blank_to_nil(s), do: s

  defp fail(message), do: raise(%Error{message: message})

  @doc """
  Validate a part built by hand: exactly one of `data`/`url` on a non-text part, and a
  `mime_type` unless the source is a bare URL.
  """
  @spec validate(t()) :: {:ok, t()} | {:error, Exception.t()}
  def validate(%__MODULE__{type: "text", text: t} = p) when is_binary(t), do: {:ok, p}

  def validate(%__MODULE__{type: "text"}),
    do: {:error, %Error{message: "content part: a text part needs :text"}}

  def validate(%__MODULE__{type: type} = p) when type in @types do
    cond do
      p.data != nil and p.url != nil ->
        {:error, %Error{message: "content part: #{type} carries both :data and :url; exactly one is allowed"}}

      p.data == nil and p.url == nil ->
        {:error, %Error{message: "content part: #{type} carries neither :data nor :url; exactly one is required"}}

      p.data != nil and p.mime_type in [nil, ""] ->
        {:error, %Error{message: "content part: #{type} with :data needs a :mime_type"}}

      true ->
        {:ok, p}
    end
  end

  def validate(%__MODULE__{type: type}),
    do: {:error, %Error{message: "content part: unknown type #{inspect(type)}"}}

  @doc "Decode from a JSON-shaped map (string keys, `mimeType` spelling)."
  @spec from_map(map()) :: t()
  def from_map(%__MODULE__{} = p), do: p

  def from_map(m) when is_map(m) do
    %__MODULE__{
      type: m["type"],
      text: m["text"],
      mime_type: m["mimeType"],
      data: m["data"],
      url: m["url"],
      name: m["name"]
    }
  end

  @doc "Encode to a JSON-shaped map (string keys, nil fields omitted)."
  @spec to_map(t() | map()) :: map()
  def to_map(%__MODULE__{} = p), do: p |> Jason.encode!() |> Jason.decode!()
  def to_map(m) when is_map(m), do: m

  @doc "True when `m` is a `ContentPart` (or its wire map) rather than a provider block."
  @spec part?(term()) :: boolean()
  def part?(%__MODULE__{}), do: true
  def part?(%{"type" => "text", "text" => t}) when is_binary(t), do: true

  # A provider block is NOT a part: an Anthropic image block carries `source`, an OpenAI
  # file block carries `file`, and neither carries `data`/`url` at the top level.
  def part?(%{"type" => t} = m) when t in ["image", "file", "audio"] do
    (is_binary(m["data"]) or is_binary(m["url"])) and
      not Map.has_key?(m, "source") and not Map.has_key?(m, "file")
  end

  def part?(_), do: false

  @doc "Decoded byte length of a part's payload — 0 for a URL-backed or text part."
  @spec byte_size_of(t() | map()) :: non_neg_integer()
  def byte_size_of(p) do
    case from_map(p) do
      %__MODULE__{type: "text", text: t} when is_binary(t) -> byte_size(t)
      %__MODULE__{data: d} when is_binary(d) -> div(byte_size(d) * 3, 4) - padding(d)
      _ -> 0
    end
  end

  defp padding(d) do
    cond do
      String.ends_with?(d, "==") -> 2
      String.ends_with?(d, "=") -> 1
      true -> 0
    end
  end

  @doc """
  Log/event rendering of a part (SPEC §9): `{type, mimeType, bytes}` — never `data`.
  """
  @spec describe(t() | map()) :: map()
  def describe(p) do
    part = from_map(p)

    %{"type" => part.type, "mimeType" => part.mime_type, "bytes" => byte_size_of(part)}
  end

  @doc """
  Byte-derived token estimate for a part (SPEC §9). Never the length of the `mimeType`
  string — that would score a 5 MB image at ~3 tokens and make it uncompactable.
  """
  @spec estimated_tokens(t() | map()) :: non_neg_integer()
  def estimated_tokens(p) do
    case from_map(p) do
      %__MODULE__{type: "text", text: t} when is_binary(t) -> ceil(byte_size(t) / 4)
      part -> max(div(byte_size_of(part), 750), 85)
    end
  end

  @doc """
  A one-line, data-free description of a non-text part, used as `output` when a tool or an
  MCP server returned parts and no text at all (so `output` is never a bare empty string).
  """
  @spec summary(t() | map()) :: String.t()
  def summary(p) do
    part = from_map(p)
    mime = if part.mime_type in [nil, ""], do: "unknown", else: part.mime_type
    "#{part.type} (#{mime}, #{byte_size_of(part)} bytes)"
  end

  # ---- provider emission (SPEC §8A) -------------------------------------------------

  @doc "The positive allowlist of provider block types, per client style (SPEC §8A)."
  @spec allowlist(String.t()) :: [String.t()]
  def allowlist(style), do: Map.get(@allowed, style, @allowed["openai"])

  @doc """
  Map one part onto a provider block for `style` (`"openai"` | `"anthropic"`).

  Returns `{:ok, block}`, or `{:unsupported, reason}` when the style defines no shape for
  that part — `anthropic × audio` and `openai × file+url` are the named refusals.
  """
  @spec to_block(t() | map(), String.t()) :: {:ok, map()} | {:unsupported, String.t()}
  def to_block(part, style), do: part |> from_map() |> block(style)

  defp block(%__MODULE__{type: "text", text: t}, _style), do: {:ok, %{"type" => "text", "text" => t || ""}}

  defp block(%__MODULE__{type: "image", data: d, mime_type: m}, "openai") when is_binary(d),
    do: {:ok, %{"type" => "image_url", "image_url" => %{"url" => data_url(m, d)}}}

  defp block(%__MODULE__{type: "image", url: u}, "openai") when is_binary(u),
    do: {:ok, %{"type" => "image_url", "image_url" => %{"url" => u}}}

  defp block(%__MODULE__{type: "image", data: d, mime_type: m}, "anthropic") when is_binary(d),
    do: {:ok, %{"type" => "image", "source" => %{"type" => "base64", "media_type" => m, "data" => d}}}

  defp block(%__MODULE__{type: "image", url: u}, "anthropic") when is_binary(u),
    do: {:ok, %{"type" => "image", "source" => %{"type" => "url", "url" => u}}}

  # `file_data` REQUIRES the `data:<mime>;base64,` prefix — a bare base64 string is a 400.
  defp block(%__MODULE__{type: "file", data: d, mime_type: m, name: n}, "openai") when is_binary(d),
    do: {:ok, %{"type" => "file", "file" => %{"filename" => n || "file", "file_data" => data_url(m, d)}}}

  defp block(%__MODULE__{type: "file", url: u}, "openai") when is_binary(u),
    do: {:unsupported, "Chat Completions has no URL form for a file part"}

  defp block(%__MODULE__{type: "file", data: d, mime_type: m}, "anthropic") when is_binary(d),
    do: {:ok, %{"type" => "document", "source" => %{"type" => "base64", "media_type" => m, "data" => d}}}

  defp block(%__MODULE__{type: "file", url: u}, "anthropic") when is_binary(u),
    do: {:ok, %{"type" => "document", "source" => %{"type" => "url", "url" => u}}}

  defp block(%__MODULE__{type: "audio", data: d, mime_type: m}, "openai") when is_binary(d),
    do: {:ok, %{"type" => "input_audio", "input_audio" => %{"data" => d, "format" => audio_format(m)}}}

  defp block(%__MODULE__{type: "audio", url: u}, "openai") when is_binary(u),
    do: {:unsupported, "Chat Completions has no URL form for an audio part"}

  defp block(%__MODULE__{type: "audio"}, "anthropic"),
    do: {:unsupported, "Anthropic defines no audio content block"}

  defp block(%__MODULE__{type: type}, style),
    do: {:unsupported, "no #{style} block shape for a #{type} part"}

  defp data_url(mime, b64), do: "data:" <> to_string(mime || "application/octet-stream") <> ";base64," <> b64

  defp audio_format(mime) do
    case mime do
      "audio/mpeg" -> "mp3"
      "audio/mp3" -> "mp3"
      "audio/wav" -> "wav"
      "audio/x-wav" -> "wav"
      other -> other |> to_string() |> String.split("/") |> List.last()
    end
  end

  @doc """
  Encode a list of parts into provider blocks for `style`, applying the SPEC §8A
  provenance rule to any part the style cannot represent:

    * `provenance: :attached` (the caller attached it) ⇒ a typed error, raised at
      assembly, before any HTTP call;
    * `provenance: :derived` (it came off a tool / MCP result) ⇒ a text placeholder
      naming the type and mime type, warned at most once, the run continues.

  `on_unsupported` (`"error"` | `"text"`) overrides both uniformly. Every produced block
  is asserted against the style's positive allowlist before it is returned.

  `:max_part_bytes` is enforced HERE, at assembly, over every part whatever its
  provenance — not only in the edge constructors. A part that arrived from an MCP
  server never passed through a constructor, so a limit it can walk around is not a
  limit. Going over routes through the same provenance rule as an unsupported part,
  so a server volunteering a huge image still cannot fail the caller's run.
  """
  @spec encode([t() | map()], String.t(), keyword()) :: [map()]
  def encode(parts, style, opts \\ []) do
    provenance = Keyword.get(opts, :provenance, :attached)
    on_unsupported = Keyword.get(opts, :on_unsupported)
    max_bytes = Keyword.get(opts, :max_part_bytes)

    parts
    |> List.wrap()
    |> Enum.flat_map(fn p ->
      cond do
        oversize?(p, max_bytes) ->
          unsupported(p, style, :too_large, provenance, on_unsupported, max_bytes)

        true ->
          case to_block(p, style) do
            {:ok, blk} -> [assert_allowed!(blk, style, p)]
            {:unsupported, why} -> unsupported(p, style, why, provenance, on_unsupported, nil)
          end
      end
    end)
  end

  defp oversize?(_p, nil), do: false

  defp oversize?(p, max) when is_integer(max) do
    part = from_map(p)
    is_binary(part.data) and byte_size_of(part) > max
  end

  defp unsupported(part, style, why, provenance, on_unsupported, max_bytes) do
    mode =
      case on_unsupported do
        m when m in ["error", "text"] -> m
        _ -> if provenance == :derived, do: "text", else: "error"
      end

    p = from_map(part)
    too_large? = why == :too_large

    reason =
      if too_large?,
        do: "#{byte_size_of(p)} decoded bytes exceeds max_part_bytes #{max_bytes}",
        else: "is not supported by the #{style} style — #{why}"

    if mode == "error" do
      raise %Error{message: "content part: #{p.type} (#{p.mime_type || "?"}) #{reason}"}
    else
      # Keyed by REASON as well as style/type, so a size warning and a no-block
      # warning cannot suppress one another — two different problems, two warnings.
      warn_once(
        {style, p.type, if(too_large?, do: :too_large, else: :unsupported)},
        "toolnexus: #{p.type} content part (#{p.mime_type || "?"}) #{reason}; sending a text placeholder instead"
      )

      [%{"type" => "text", "text" => "[unsupported #{p.type} part (#{p.mime_type || "unknown"}, #{byte_size_of(p)} bytes)]"}]
    end
  end

  defp warn_once(key, message) do
    seen = Process.get(:toolnexus_unsupported_part_warned, MapSet.new())

    unless MapSet.member?(seen, key) do
      Process.put(:toolnexus_unsupported_part_warned, MapSet.put(seen, key))
      require Logger
      Logger.warning(message)
    end

    :ok
  end

  @doc """
  Assert an encoded block against the style's positive allowlist (SPEC §8A). A block that
  is not on the list never reaches the wire: an unknown block type upstream returns HTTP
  200 with the content silently discarded, not an error.
  """
  @spec assert_allowed!(map(), String.t(), term()) :: map()
  def assert_allowed!(%{"type" => t} = blk, style, source \\ nil) do
    if t in allowlist(style) do
      blk
    else
      raise %Error{
        message:
          "content part: encoded block #{inspect(t)} is not in the #{style} allowlist" <>
            if(source, do: " (from a #{from_map(source).type} part)", else: "")
      }
    end
  end
end
