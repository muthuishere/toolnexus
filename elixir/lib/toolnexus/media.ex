defmodule Toolnexus.Media.MediaFile do
  @moduledoc """
  One generated file (SPEC §7G.2 `MediaFile`).

  `type` is the §1B part type (`"image"`, `"audio"`, or `"file"` for video). `url` is an
  `http(s)` or `file://` URL, `path` an absolute local path, `data` standard padded base64.
  Which fields are set is decided by `gather` (§7G.5).
  """
  defstruct [:type, :mime_type, :url, :path, :data]

  @type t :: %__MODULE__{
          type: String.t(),
          mime_type: String.t(),
          url: String.t() | nil,
          path: String.t() | nil,
          data: String.t() | nil
        }
end

defmodule Toolnexus.Media.Result do
  @moduledoc """
  The outcome of one media verb (SPEC §7G.2 `MediaResult`).

  `files` is a list of `Toolnexus.Media.MediaFile`; `text` is set for the text verbs
  (`transcribe`, `cloneVoice`) and when a client returns text beside its files; `job_id`
  when the provider gave one. `usage` (§7G.12 D21) is a map with string keys
  `"inputTokens"`, `"outputTokens"`, `"costUsd"`, `"raw"` — only what the provider returned,
  never estimated; `nil` when it returned none.
  """
  defstruct files: [], text: nil, job_id: nil, status: "completed", usage: nil

  @type t :: %__MODULE__{
          files: [Toolnexus.Media.MediaFile.t()],
          text: String.t() | nil,
          job_id: String.t() | nil,
          status: String.t(),
          usage: map() | nil
        }
end

defmodule Toolnexus.Media.Error do
  @moduledoc """
  The typed media error (SPEC §7G.2): `message`, `status` (`:failed | :timeout |
  :cancelled | :unavailable`) and `job_id` when known.

  Test for `unavailable` without string matching:

      {:error, %Toolnexus.Media.Error{status: :unavailable}}
  """
  defexception message: "", status: :failed, job_id: nil

  @type t :: %__MODULE__{message: String.t(), status: atom(), job_id: String.t() | nil}

  @doc false
  def failed(msg), do: %__MODULE__{message: msg, status: :failed}
  @doc false
  def unavailable(msg), do: %__MODULE__{message: msg, status: :unavailable}
end

defmodule Toolnexus.Media.Cancelled do
  @moduledoc """
  Raised by the function face when a media call's `:signal` reports cancellation (§7G.8).
  Elixir's native cancellation is `Task.shutdown/1`; the cooperative `:signal` is the
  in-call equivalent. The tool face turns it into an `is_error` result.
  """
  defexception message: "media: cancelled"
end

defmodule Toolnexus.Media.Client do
  @moduledoc """
  The `MediaClient` behaviour (SPEC §7G.2). Every verb callback is optional;
  `supports?/2` is the truth.

  A client value is either a struct whose module implements this behaviour, or the module
  itself (then the module atom is passed as `client`). Each verb receives the canonical
  string-keyed args and a ctx map `%{signal, deadline, progress, provider_options}` and
  returns `{:ok, %Toolnexus.Media.Result{}}` or `{:error, %Toolnexus.Media.Error{}}`.

      defmodule MyClient do
        @behaviour Toolnexus.Media.Client
        defstruct []
        def name(_), do: "mine"
        def supports?(_, verb), do: verb == "image"
        def image(_, %{"prompt" => p}, _ctx), do: {:ok, %Toolnexus.Media.Result{...}}
      end

      {:ok, media} = Toolnexus.Media.create([%MyClient{}, Toolnexus.Media.fal()])
  """
  alias Toolnexus.Media.{Error, Result}

  @type client :: struct() | module()
  @type arg_spec :: %{name: String.t(), required: boolean()}
  @type ctx :: %{
          optional(:signal) => pid() | (-> boolean()) | nil,
          optional(:deadline) => integer() | nil,
          optional(:progress) => (map() -> any()) | nil,
          optional(:provider_options) => map() | nil
        }
  @type outcome :: {:ok, Result.t()} | {:error, Error.t()}

  @callback name(client) :: String.t()
  @callback supports?(client, verb :: String.t()) :: boolean()
  @callback args(client, verb :: String.t()) :: [arg_spec] | nil
  @callback image(client, map(), ctx) :: outcome
  @callback video(client, map(), ctx) :: outcome
  @callback speech(client, map(), ctx) :: outcome
  @callback music(client, map(), ctx) :: outcome
  @callback transcribe(client, map(), ctx) :: outcome
  @callback clone_voice(client, map(), ctx) :: outcome
  @callback talk(client, map(), ctx) :: outcome

  @optional_callbacks args: 2,
                      image: 3,
                      video: 3,
                      speech: 3,
                      music: 3,
                      transcribe: 3,
                      clone_voice: 3,
                      talk: 3
end

defmodule Toolnexus.Media.Config do
  @moduledoc """
  The one config shape every built-in client takes (SPEC §7G.4). Pass a keyword list or
  map; atom keys are snake_case, string keys may be the JSON spelling (`intervalMs`).

    * `:model` — the model of the client's primary verb (CLI: `-m <model>`)
    * `:models` — `%{verb => model}` per-verb override; wins over `:model` and the default
    * `:key` — the API key; default: the client's env var, read **at call time**
    * `:url` — base URL override (trailing `/` trimmed) — any compatible server
    * `:command` — CLI argv prefix (string = one executable, never shell-split)
    * `:loop` — `%{interval_ms, timeout_ms, on_progress}`
    * `:gather` — `%{mode: "url" | "download" | "inline", dir, max_bytes, keep}`
    * `:http_client` — the HTTP seam: `fn %{method, url, headers, body, receive_timeout} ->
      {:ok, %{status, headers, body}} | {:error, e}` (`body` is the encoded binary or
      `nil`); `:http_options` — extra `Req` options instead (e.g. `plug:`)

  A key is never logged and never appears in an error message.
  """
  defstruct model: nil,
            models: %{},
            key: nil,
            url: nil,
            command: nil,
            loop: %{interval_ms: nil, timeout_ms: nil, on_progress: nil},
            gather: %{mode: nil, dir: nil, max_bytes: nil, keep: true},
            http_client: nil,
            http_options: []

  @type t :: %__MODULE__{}

  @doc "Normalise a keyword list / map (atom or JSON string keys) into a config."
  @spec new(keyword() | map() | t() | nil) :: t()
  def new(%__MODULE__{} = c), do: c
  def new(nil), do: %__MODULE__{}

  def new(opts) do
    o = Map.new(opts)
    loop = Map.new(get(o, :loop, "loop") || %{})
    gather = Map.new(get(o, :gather, "gather") || %{})

    %__MODULE__{
      model: get(o, :model, "model"),
      models: Map.new(get(o, :models, "models") || %{}, fn {k, v} -> {verb_key(k), v} end),
      key: get(o, :key, "key"),
      url: get(o, :url, "url"),
      command: get(o, :command, "command"),
      loop: %{
        interval_ms: get(loop, :interval_ms, "intervalMs"),
        timeout_ms: get(loop, :timeout_ms, "timeoutMs"),
        on_progress: get(loop, :on_progress, "onProgress")
      },
      gather: %{
        mode: to_mode(get(gather, :mode, "mode")),
        dir: get(gather, :dir, "dir"),
        max_bytes: get(gather, :max_bytes, "maxBytes"),
        keep: get(gather, :keep, "keep") != false
      },
      http_client: get(o, :http_client, "httpClient"),
      http_options: get(o, :http_options, "httpOptions") || []
    }
  end

  defp get(m, atom, string) do
    case Map.fetch(m, atom) do
      {:ok, v} -> v
      :error -> Map.get(m, string)
    end
  end

  defp to_mode(nil), do: nil
  defp to_mode(m), do: to_string(m)

  defp verb_key(:clone_voice), do: "cloneVoice"
  defp verb_key(k), do: to_string(k)

  @doc false
  # §7G.4: absent, <= 0 or not a number ⇒ the default.
  def positive(v, _default) when is_number(v) and v > 0, do: v
  def positive(_v, default), do: default

  @doc false
  def model_for(%__MODULE__{} = c, verb, primary?, default) do
    Map.get(c.models, verb) || if(primary?, do: c.model) || default
  end
end

defmodule Toolnexus.Media do
  @moduledoc """
  Media generation (SPEC §7G, source `"media"`): image, video, speech, music,
  transcription, voice cloning and talking-head video behind one interface with two
  faces — the typed verbs below (function face) and `tools/1` (tool face, for an LLM).

  Providers are **media clients** (`Toolnexus.Media.Client`): built-in API clients
  (`a2e/1`, `elevenlabs/1`, `openai/1`, `openrouter/1`, `fal/1`), local CLI clients
  (`grok/1`, `codex/1`), style clients over the JSON style engine (`load_style/1` +
  `style_client/2`), or your own module. `create/1` routes each verb to the first client
  that supports it and falls through **only** on status `:unavailable`.

      {:ok, media} = Toolnexus.Media.create([Toolnexus.Media.grok(), Toolnexus.Media.fal()])
      {:ok, %Toolnexus.Media.Result{files: files}} = Toolnexus.Media.image(media, prompt: "a cat")

      Toolnexus.create_toolkit!(media: media)   # registers generate_image, ...

  **Arguments** are a map or keyword list of the canonical args (`verbs.json`). Atom keys
  may be snake_case (`:aspect_ratio` ⇒ `"aspectRatio"`). `:provider_options` (or
  `"providerOptions"`) is a map whose top-level keys overwrite the request body last;
  the tool face never exposes it.

  **Options** (third argument): `:signal` (pid or zero-arity predicate — cooperative
  cancellation), `:deadline` (absolute, `System.monotonic_time(:millisecond)`),
  `:progress` (`fn %{verb, client, job_id, status, elapsed_ms} -> ... end`),
  `:provider_options`.

  **Errors** are `{:error, %Toolnexus.Media.Error{message, status, job_id}}`.
  Cancellation raises `Toolnexus.Media.Cancelled` (the tool face returns `is_error`).
  """

  alias Toolnexus.{ContentPart, Context, Tool, ToolResult}
  alias Toolnexus.Media.{Error, MediaFile, Result}

  defstruct clients: []

  @type t :: %__MODULE__{clients: [Toolnexus.Media.Client.client()]}

  @verbs_path Path.expand("../../priv/media/verbs.json", __DIR__)
  @a2e_path Path.expand("../../priv/media/a2e.json", __DIR__)
  @external_resource @verbs_path
  @external_resource @a2e_path

  @verbs_json File.read!(@verbs_path)
  @a2e_json File.read!(@a2e_path)

  # verbs.json — embedded verbatim (priv/media/verbs.json, asserted against the conformance copy).
  @verbs @verbs_json
         |> Jason.decode!()
         |> Map.fetch!("verbs")
         |> Enum.map(fn v ->
           %{
             verb: v["verb"],
             tool: v["tool"],
             description: v["description"],
             args:
               Enum.map(v["args"], fn a ->
                 %{name: a["name"], type: a["type"], description: a["description"]}
                 |> then(&if(a["required"], do: Map.put(&1, :required, true), else: &1))
                 |> then(&if(a["items"], do: Map.put(&1, :items, a["items"]), else: &1))
               end)
           }
         end)

  @verb_names Enum.map(@verbs, & &1.verb)
  @text_verbs ["transcribe", "cloneVoice"]
  @fun %{
    "image" => :image,
    "video" => :video,
    "speech" => :speech,
    "music" => :music,
    "transcribe" => :transcribe,
    "cloneVoice" => :clone_voice,
    "talk" => :talk
  }

  @doc "The canonical verb table (`conformance/media/verbs.json`)."
  @spec verbs() :: [map()]
  def verbs, do: @verbs

  @doc false
  def verbs_json, do: @verbs_json
  @doc false
  def a2e_json, do: @a2e_json

  @doc false
  def canonical_args(verb) do
    case Enum.find(@verbs, &(&1.verb == verb)) do
      nil -> []
      v -> Enum.map(v.args, &%{name: &1.name, required: Map.get(&1, :required, false)})
    end
  end

  # ---- construction ----

  @doc """
  `createMedia(client | [client])` (§7G.3). `{:ok, media}`, or `{:error, %Error{}}` with
  `media: no clients` for an empty list.
  """
  @spec create(Toolnexus.Media.Client.client() | [Toolnexus.Media.Client.client()]) ::
          {:ok, t()} | {:error, Error.t()}
  def create(clients) do
    case List.wrap(clients) do
      [] -> {:error, Error.failed("media: no clients")}
      list -> {:ok, %__MODULE__{clients: list}}
    end
  end

  @doc "`create/1`, raising `Toolnexus.Media.Error` on an empty list."
  @spec create!(Toolnexus.Media.Client.client() | [Toolnexus.Media.Client.client()]) :: t()
  def create!(clients) do
    case create(clients) do
      {:ok, m} -> m
      {:error, e} -> raise e
    end
  end

  @doc "Read one style JSON file. `{:ok, style}` (string-keyed map) or `{:error, %Error{}}`."
  defdelegate load_style(path), to: Toolnexus.Media.StyleClient

  @doc """
  A style client over a parsed style (§7G.7), named `style["name"] || "style"`. Uses `url`,
  `loop`, `gather` (default mode `url`), `key` (what every `${…}` header expands to) and
  `http_client` from `config`. `{:error, %Error{}}` for an invalid style.
  """
  defdelegate style_client(style, config \\ []), to: Toolnexus.Media.StyleClient, as: :new

  @doc "`style_client/2`, raising on an invalid style."
  defdelegate style_client!(style, config \\ []), to: Toolnexus.Media.StyleClient, as: :new!

  @doc "Built-in a2e.ai client (`A2E_API_KEY`): image, video, speech, talk."
  defdelegate a2e(config \\ []), to: Toolnexus.Media.A2E, as: :new
  @doc "Built-in ElevenLabs client (`ELEVENLABS_API_KEY`): speech, music, transcribe, cloneVoice."
  defdelegate elevenlabs(config \\ []), to: Toolnexus.Media.ElevenLabs, as: :new
  @doc "Built-in OpenAI client (`OPENAI_API_KEY`): image, video, speech, transcribe."
  defdelegate openai(config \\ []), to: Toolnexus.Media.OpenAI, as: :new
  @doc "Built-in OpenRouter client (`OPENROUTER_API_KEY`): image."
  defdelegate openrouter(config \\ []), to: Toolnexus.Media.OpenRouter, as: :new
  @doc "Built-in fal client (`FAL_API_KEY`): image, video."
  defdelegate fal(config \\ []), to: Toolnexus.Media.Fal, as: :new
  @doc "Grok CLI client (subscription login): image, video."
  defdelegate grok(config \\ []), to: Toolnexus.Media.Grok, as: :new
  @doc "Codex CLI client (ChatGPT plan): image."
  defdelegate codex(config \\ []), to: Toolnexus.Media.Codex, as: :new

  # ---- function face ----

  @doc "Generate an image (`prompt`, `width`, `height`, `aspectRatio`, `seed`)."
  def image(media, args, opts \\ []), do: generate(media, "image", args, opts)
  @doc "Generate a video (`prompt`, `image`, `seconds`, `aspectRatio`)."
  def video(media, args, opts \\ []), do: generate(media, "video", args, opts)
  @doc "Text to speech (`text`, `voice`)."
  def speech(media, args, opts \\ []), do: generate(media, "speech", args, opts)
  @doc "Generate music (`prompt`, `seconds`, `instrumental`, `lyrics`)."
  def music(media, args, opts \\ []), do: generate(media, "music", args, opts)
  @doc "Speech to text (`audio`, `language`); the result's `text` is set."
  def transcribe(media, args, opts \\ []), do: generate(media, "transcribe", args, opts)
  @doc "Clone a voice (`name`, `samples`); the result's `text` is the new voice id."
  def clone_voice(media, args, opts \\ []), do: generate(media, "cloneVoice", args, opts)
  @doc "Talking-head video (`image`, `audio`, `text`, `voice`)."
  def talk(media, args, opts \\ []), do: generate(media, "talk", args, opts)

  @doc "Whether any client supports `verb`."
  @spec supports?(t(), String.t() | atom()) :: boolean()
  def supports?(%__MODULE__{clients: clients}, verb) do
    verb = verb_name(verb)
    Enum.any?(clients, &client_supports?(&1, verb))
  end

  @doc "Run any verb (`\"image\"`, `:clone_voice`, ...) with canonical args (§7G.3)."
  @spec generate(t(), String.t() | atom(), map() | keyword(), keyword()) ::
          {:ok, Result.t()} | {:error, Error.t()}
  def generate(%__MODULE__{} = media, verb, args, opts \\ []) do
    {provider_options, args} = split_args(args)
    po = opts[:provider_options] || provider_options

    ctx = %{
      signal: opts[:signal],
      deadline: opts[:deadline],
      progress: opts[:progress],
      provider_options: po && Map.new(po, fn {k, v} -> {to_string(k), v} end)
    }

    case route(media, verb_name(verb), args, ctx) do
      {:ok, r, _client} -> {:ok, r}
      err -> err
    end
  end

  # ---- routing (§7G.3) ----

  @doc false
  def route(%__MODULE__{clients: clients}, verb, args, ctx) do
    args = Map.reject(args, fn {_k, v} -> is_nil(v) end)
    canonical = MapSet.new(canonical_args(verb), & &1.name)

    with [_ | _] = candidates <- Enum.filter(clients, &client_supports?(&1, verb)),
         nil <- first_unaccepted(args, canonical) do
      try_candidates(candidates, verb, args, ctx, [])
    else
      [] -> {:error, Error.failed(~s(media: no client supports "#{verb}"))}
      # §7G.3 step 3 / D7: a non-canonical key fails the call before any client is tried.
      key -> {:error, Error.failed(~s(#{verb}: argument "#{key}" not supported))}
    end
  end

  defp try_candidates([], verb, _args, _ctx, reasons) do
    joined = reasons |> Enum.reverse() |> Enum.join("; ")
    {:error, Error.unavailable(~s(media: no client available for "#{verb}": ) <> joined)}
  end

  # D19: every candidate, the first included, is skipped (never called) like `unavailable`
  # when it does not accept an arg or lacks a required one. Args are never rewritten.
  defp try_candidates([c | rest], verb, args, ctx, reasons) do
    case skip_reason(c, verb, args) do
      nil ->
        case call_client(c, verb, args, ctx) do
          {:ok, %Result{} = r} ->
            {:ok, r, client_name(c)}

          {:error, %Error{status: :unavailable, message: m}} ->
            try_candidates(rest, verb, args, ctx, [m | reasons])

          {:error, %Error{}} = err ->
            err
        end

      reason ->
        try_candidates(rest, verb, args, ctx, [reason | reasons])
    end
  end

  defp skip_reason(client, verb, args) do
    accepted = MapSet.new(client_args(client, verb), & &1.name)

    cond do
      key = first_unaccepted(args, accepted) ->
        ~s(#{client_name(client)}: argument "#{key}" not supported)

      key = missing_required(client, verb, args) ->
        ~s(#{client_name(client)}: missing required argument "#{key}")

      true ->
        nil
    end
  end

  # The first key (sorted by code point) not in `accepted`, or nil.
  defp first_unaccepted(args, accepted),
    do: args |> Map.keys() |> Enum.sort() |> Enum.find(&(not MapSet.member?(accepted, &1)))

  defp call_client(c, verb, args, ctx) do
    mod = module(c)
    fun = Map.fetch!(@fun, verb)

    if function_exported?(mod, fun, 3) do
      case apply(mod, fun, [c, args, ctx]) do
        {:ok, %Result{}} = ok -> ok
        {:error, %Error{}} = err -> err
        {:error, msg} when is_binary(msg) -> {:error, Error.failed(msg)}
        other -> {:error, Error.failed("#{verb}: invalid client result #{inspect(other)}")}
      end
    else
      {:error, Error.failed("#{verb}: not implemented by #{client_name(c)}")}
    end
  end

  # The first required arg (canonical order) the client declares that `args` lacks.
  defp missing_required(client, verb, args) do
    order = Enum.map(canonical_args(verb), & &1.name)

    for(a <- client_args(client, verb), a.required, not Map.has_key?(args, a.name), do: a.name)
    |> Enum.sort_by(&(Enum.find_index(order, fn n -> n == &1 end) || 999))
    |> List.first()
  end

  defp module(c) when is_atom(c), do: c
  defp module(%{__struct__: mod}), do: mod

  defp client_name(c), do: module(c).name(c)
  defp client_supports?(c, verb), do: module(c).supports?(c, verb) == true

  @doc false
  def client_args(c, verb) do
    mod = module(c)

    # A client without `args/2`, or one returning `nil` for a verb, accepts every
    # canonical arg of the verb with the canonical required flags (§7G.2).
    case function_exported?(mod, :args, 2) && mod.args(c, verb) do
      list when is_list(list) ->
        Enum.map(list, fn a ->
          %{
            name: to_string(a[:name] || a["name"]),
            required: (a[:required] || a["required"]) == true
          }
        end)

      _ ->
        canonical_args(verb)
    end
  end

  # ---- tool face (§7G.6) ----

  @doc "One `Tool` (source `\"media\"`) per verb any client supports, in `verbs.json` order."
  @spec tools(t()) :: [Tool.t()]
  def tools(%__MODULE__{clients: clients} = media) do
    for v <- @verbs, first = Enum.find(clients, &client_supports?(&1, v.verb)), first != nil do
      spec = client_args(first, v.verb)
      accepted = MapSet.new(spec, & &1.name)
      required = MapSet.new(for a <- spec, a.required, do: a.name)
      args = Enum.filter(v.args, &MapSet.member?(accepted, &1.name))

      %Tool{
        name: v.tool,
        description: v.description,
        source: "media",
        # Each call is an independent job: always concurrency-safe (§7G.6, §8).
        concurrency_safe: fn _args -> true end,
        input_schema: %{
          "type" => "object",
          "properties" => Map.new(args, &{&1.name, arg_schema(&1)}),
          "required" => for(a <- v.args, MapSet.member?(required, a.name), do: a.name)
        },
        execute: fn tool_args, ctx -> execute(media, v.verb, tool_args, ctx) end
      }
    end
  end

  defp arg_schema(%{type: "array"} = a),
    do: %{"type" => "array", "items" => %{"type" => a.items}, "description" => a.description}

  defp arg_schema(a), do: %{"type" => a.type, "description" => a.description}

  defp execute(media, verb, args, ctx) do
    signal = if is_struct(ctx, Context), do: ctx.signal
    tctx = %{signal: signal, deadline: nil, progress: nil, provider_options: nil}

    outcome =
      try do
        route(media, verb, stringify(args || %{}), tctx)
      rescue
        e in Toolnexus.Media.Cancelled -> {:error, Error.failed(Exception.message(e))}
      end

    case outcome do
      {:ok, %Result{} = r, client} ->
        meta = %{source: "media", verb: verb, client: client, status: r.status}
        meta = if r.job_id, do: Map.put(meta, :jobId, r.job_id), else: meta
        meta = if r.usage, do: Map.put(meta, :usage, r.usage), else: meta

        if verb in @text_verbs do
          %ToolResult{output: r.text || "", metadata: meta}
        else
          lines = Enum.map_join(r.files, "\n", &line/1)

          %ToolResult{
            output: "#{word(verb, r.files)} ready: " <> lines,
            parts: Enum.map(r.files, &part/1),
            paths: Enum.map(r.files, & &1.path),
            metadata: meta
          }
        end

      {:error, %Error{message: msg}} ->
        ToolResult.error(msg)
    end
  end

  defp word(_verb, [%MediaFile{type: "image"} | _]), do: "image"
  defp word(_verb, [%MediaFile{type: "audio"} | _]), do: "audio"
  defp word(_verb, [%MediaFile{} | _]), do: "video"
  defp word(verb, []) when verb in ["speech", "music"], do: "audio"
  defp word("image", []), do: "image"
  defp word(_verb, []), do: "video"

  defp line(%MediaFile{path: p}) when is_binary(p), do: p
  defp line(%MediaFile{url: u}) when is_binary(u), do: u

  defp line(%MediaFile{data: d, mime_type: m}),
    do: "inline #{m} #{byte_size(Base.decode64!(d))} bytes"

  # §7G.6: data ⇒ data part; http(s) url ⇒ url part; a local file ⇒ its bytes read now.
  defp part(%MediaFile{data: d} = f) when is_binary(d),
    do: %ContentPart{type: f.type, mime_type: f.mime_type, data: d}

  defp part(%MediaFile{url: "http" <> _ = u} = f),
    do: %ContentPart{type: f.type, mime_type: f.mime_type, url: u}

  defp part(%MediaFile{} = f) do
    path = f.path || String.replace_prefix(f.url || "", "file://", "")
    %ContentPart{type: f.type, mime_type: f.mime_type, data: Base.encode64(File.read!(path))}
  end

  # ---- args ----

  defp split_args(args) do
    args = stringify(args)
    {po, args} = Map.pop(args, "providerOptions")
    {po2, args} = Map.pop(args, "provider_options")
    {po || po2, args}
  end

  @doc false
  def stringify(args) do
    Map.new(args, fn
      {k, v} when is_atom(k) -> {k |> Atom.to_string() |> camelize(), v}
      {k, v} -> {to_string(k), v}
    end)
  end

  defp camelize("provider_options"), do: "provider_options"

  defp camelize(s) do
    [h | t] = String.split(s, "_")
    h <> Enum.map_join(t, &String.capitalize/1)
  end

  defp verb_name(v) when is_atom(v), do: v |> Atom.to_string() |> camelize()
  defp verb_name(v), do: v

  @doc false
  def verb_names, do: @verb_names
end
