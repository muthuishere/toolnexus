defmodule Toolnexus.Classifier.Canonical do
  @moduledoc false
  # Canonical JSON (SPEC §8B): object keys sorted RECURSIVELY in ASCII order,
  # arrays NEVER reordered, compact separators, and `<`, `>`, `&`, `'`, quotation
  # marks and non-ASCII transmitted raw.
  #
  # Hand-rolled ON PURPOSE. Neither Jason nor OTP 27+'s built-in `JSON` sorts keys:
  # both walk the map in Erlang term-iteration order. That order HAPPENS to be
  # ASCII-sorted for maps of at most 32 keys (a small flatmap stores its keys in
  # term order) and becomes arbitrary the moment a map crosses into hashmap
  # representation — so `Jason.encode!/1` passes the base fixture by accident and
  # silently breaks on the 40-key maps of `wide.json`. Only the scalar leaves are
  # delegated to Jason, which owns string escaping and float formatting.
  #
  # Binary comparison in Erlang is byte-wise, and UTF-8 byte order IS code-point
  # order, so `Enum.sort/1` on the encoded keys is the ASCII ordering §8B asks for
  # ("10_alpha" < "Alpha" < "beta" < "café") rather than a culture-sensitive one.

  @spec encode(term()) :: binary()
  def encode(value), do: value |> iodata() |> IO.iodata_to_binary()

  defp iodata(map) when is_map(map) and not is_struct(map) do
    inner =
      map
      |> Enum.map(fn {k, v} -> {key(k), v} end)
      |> Enum.sort_by(fn {k, _} -> k end)
      |> Enum.map(fn {k, v} -> [Jason.encode!(k), ?:, iodata(v)] end)
      |> Enum.intersperse(?,)

    [?{, inner, ?}]
  end

  # Arrays are NEVER sorted: in a score question the criteria order IS the level
  # numbering, so a "sort everything" canonicaliser silently renumbers the rubric.
  defp iodata(list) when is_list(list) do
    [?[, list |> Enum.map(&iodata/1) |> Enum.intersperse(?,), ?]]
  end

  defp iodata(scalar), do: Jason.encode!(scalar)

  defp key(k) when is_binary(k), do: k
  defp key(k), do: to_string(k)
end

defmodule Toolnexus.Classifier do
  @moduledoc """
  Typed decisions — `Classifier` (SPEC.md §8B).

  A sibling of the §8 client, not a style inside it. A **System One** model takes a
  state plus pre-declared, typed questions and returns calibrated answers with no
  free text; it has no messages, no tool calling and no streaming, so it never
  enters the client loop. `Toolnexus.Tool` is the contract for an **action**;
  `Toolnexus.Classifier` is the contract for a **judgment**.

      {:ok, classifier} = Toolnexus.Classifier.create(style: "systemone")

      {:ok, decision} =
        Toolnexus.Classifier.evaluate(classifier, "Order 4021 arrived smashed", %{
          "is_refund_request" => %Toolnexus.Classifier.Noul{
            instructions: "Is the customer asking for a refund?"
          }
        })

  **A classifier interprets; it never authorises.** Its output is a reading of
  intent inside a boundary, never the boundary. Numeric limits, permission checks
  and allowlists stay in code. Schema validity is not correctness: a decision can
  be confidently wrong, and "cannot hallucinate" means only that the returned
  value is in the declared schema. Nothing here is a security control.

  A host that constructs no classifier observes byte-identical behaviour to a
  build without this module, and constructing one alters no request the client
  loop makes.
  """

  alias Toolnexus.Classifier.Canonical

  # ---------------------------------------------------------------- constants

  @default_base_url "https://api.typesafe.ai/v1"
  @default_model "jev-latest"
  @default_api_key_env "TYPESAFE_API_KEY"
  @default_timeout 10_000
  @default_retries 2
  # Base of the retry backoff in ms — the same 500 as §8 `ClientOptions.retry_base_ms`.
  @default_retry_base_ms 500

  @max_choice_options 255
  @min_score_levels 2
  @max_score_levels 10

  # The ABSOLUTE tolerance on max|p - 1/n|, compared INCLUSIVELY (§8B). Pinned
  # across every port; `examples/judge/near-uniform.json` pins both sides.
  @near_uniform_tolerance 0.05

  @default_batch_concurrency 16

  @metric_evaluate "classifier.evaluate"
  @metric_warning "classifier.warning"

  # The default retryable set: the §8 client set (`429`/`500`/`502`/`503`/`504`/`529`) plus
  # this seam's extra `408`. `529 Overloaded` is here because TypeSafe documents it as
  # "retry with backoff" and the enumeration once made it fail hard on the first attempt.
  #
  # It is an ENUMERATION on purpose. "Any 5xx" would sweep in permanently-broken statuses
  # (`501 Not Implemented`, `505 HTTP Version Not Supported`) and change the retry behaviour
  # of every existing host without asking. A backend with its own transient status — a
  # Cloudflare origin answering `520`–`527`, say — opts in declaratively through
  # `:retryable_statuses`, which ADDS to this set and can never remove from it.
  @retryable_statuses [408, 429, 500, 502, 503, 504, 529]

  @doc "The System One endpoint base (§8B default)."
  def default_base_url, do: @default_base_url

  # ── Backends (ADR 0027 / D5) ──────────────────────────────────────────────
  # `base_url`, `model` and `api_key_env` are only JOINTLY valid. Two combinations
  # work; nothing in three independent options said so. A `:backend` sets all three
  # as a unit; the individual options stay available for a self-hosted origin.
  #
  # The table is DATA (§8B, design.md PD-2): a fail-fast for what the table says,
  # never a claim about the vendor. `:supports_images` is `true | false |
  # {:model_contains, s} | nil` (nil = `"custom"`, which requires `:supports`).
  # Vercel and Cloudflare-Jev images stay false until a live probe proves them.
  @backends %{
    "typesafe" => %{
      base_url: "https://api.typesafe.ai/v1",
      model: "jev-latest",
      api_key_env: "TYPESAFE_API_KEY",
      wire: "systemone",
      images_header: true,
      supports_images: true
    },
    "openrouter" => %{
      base_url: "https://openrouter.ai/api/v1",
      model: "typesafe/jev-1.14",
      api_key_env: "OPENROUTER_API_KEY",
      wire: "systemone",
      images_header: true,
      supports_images: true
    },
    "cloudflare" => %{
      base_url: "https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/run",
      model: "typesafe/jev",
      api_key_env: "CLOUDFLARE_JEV_API_KEY",
      wire: "cloudflare",
      images_header: false,
      supports_images: {:model_contains, "clef"}
    },
    "vercel" => %{
      base_url: "https://ai-gateway.vercel.sh/typesafe/v1",
      model: "typesafe-ai/jev",
      api_key_env: "AI_GATEWAY_API_KEY",
      wire: "systemone",
      images_header: true,
      supports_images: false
    },
    "openai" => %{
      base_url: "https://api.openai.com/v1",
      model: "gpt-6-luna",
      api_key_env: "OPENAI_API_KEY",
      wire: "decisions",
      images_header: false,
      supports_images: true
    },
    "custom" => %{
      base_url: nil,
      model: "jev-latest",
      api_key_env: "",
      wire: "systemone",
      images_header: true,
      supports_images: nil
    }
  }

  @backend_names ["typesafe", "openrouter", "cloudflare", "vercel", "openai", "custom"]

  # TypeSafe's required opt-in for image ("danger zone") input.
  @images_header "TypeSafe-Danger-Zone-Images"

  @doc """
  The backend table: `"typesafe"` (default) | `"openrouter"` | `"cloudflare"` |
  `"vercel"` | `"openai"` | `"custom"`. Each row: `base_url`, `model`, `api_key_env`,
  `wire` (`"systemone"` | `"cloudflare"` | `"decisions"`), `images_header`,
  `supports_images`.
  """
  @spec backends() :: map()
  def backends, do: @backends

  @doc "The image opt-in header name, sent iff a request carries images and the row says so."
  def images_header, do: @images_header
  @doc "The floating model alias. Pin it once thresholds are tuned."
  def default_model, do: @default_model
  @doc "The NAME of the env var holding the credential (§8B default)."
  def default_api_key_env, do: @default_api_key_env
  @doc "Per-request timeout in ms — a classifier has no loop to bound."
  def default_timeout, do: @default_timeout
  @doc "Client-side cap on a choice's named options (§8B)."
  def max_choice_options, do: @max_choice_options
  @doc "Bounds on a score rubric (§8B)."
  def min_score_levels, do: @min_score_levels
  def max_score_levels, do: @max_score_levels
  @doc "The `near_uniform` tolerance: 0.05, absolute, inclusive."
  def near_uniform_tolerance, do: @near_uniform_tolerance

  # ---------------------------------------------------------------- questions

  defmodule Noul do
    @moduledoc """
    The probability that a statement holds, one number in `0..1`. It reports **no**
    confidence: the number *is* the answer.

    `:criteria` labels the true and false cases. Absent (`nil`) and empty
    (`%{"true" => "", "false" => ""}`) are DIFFERENT values and both are preserved
    on the wire — `nil` omits the field entirely.
    """
    defstruct instructions: "", criteria: nil
    @type t :: %__MODULE__{instructions: String.t(), criteria: map() | nil}
  end

  defmodule Choice do
    @moduledoc """
    One option from a named set, 1..255 options.

    **The encoding obligation is the CALLER's** (§8B, `docs/adr/0021`):
    `criteria[id]` is the only thing that differentiates one option from another to
    the model. Passing the id itself, an empty string, or one value repeated is
    schema-valid, returns HTTP 200 and a well-formed distribution — and ranks at
    chance (measured: 17 apples described by consequence, 0/1/0 described by id).
    """
    defstruct instructions: "", criteria: %{}
    @type t :: %__MODULE__{instructions: String.t(), criteria: %{String.t() => String.t()}}
  end

  defmodule Score do
    @moduledoc """
    A rating against an **ordered** rubric of 2..10 levels. The list order IS the
    level numbering, so it is never sorted.
    """
    defstruct instructions: "", criteria: []
    @type t :: %__MODULE__{instructions: String.t(), criteria: [String.t()]}
  end

  @type question :: Noul.t() | Choice.t() | Score.t() | Toolnexus.Classifier.Raw.t()

  @doc """
  Build a `Choice` from any (name, description) pairs — a tool, a skill, an agent,
  an A2A card skill. The description must say what picking that option would MEAN;
  see the encoding obligation on `Choice`.
  """
  @spec choice_over(String.t(), %{String.t() => String.t()}) :: Choice.t()
  def choice_over(instructions, items) when is_map(items) do
    %Choice{
      instructions: instructions,
      criteria: Map.new(items, fn {k, v} -> {to_string(k), v} end)
    }
  end

  # ---------------------------------------------------------------- answers

  defmodule NoulAnswer do
    @moduledoc "The probability a statement holds. Carries NO confidence."
    defstruct noul: 0.0
    @type t :: %__MODULE__{noul: number()}
  end

  defmodule ChoiceAnswer do
    @moduledoc """
    One option from the offered set, with a probability for every offered option.

    `:near_uniform` is DERIVED from `:probabilities` on decode and never read from
    the wire. It is ADVISORY, not a correctness signal: it detects an encoding that
    gave the model nothing to rank on, and cannot distinguish a good encoding from
    a subtly wrong one.
    """
    defstruct choice: nil, probabilities: %{}, confidence: 0.0, near_uniform: false

    @type t :: %__MODULE__{
            choice: String.t() | nil,
            probabilities: %{String.t() => number()},
            confidence: number(),
            near_uniform: boolean()
          }
  end

  defmodule ScoreAnswer do
    @moduledoc """
    A rating against the ordered rubric. `:score` MAY fall between levels (`1.21`
    is a real answer) and is always within the rubric's bounds.
    """
    defstruct score: 0.0, legend: %{}, probabilities: %{}, confidence: 0.0

    @type t :: %__MODULE__{
            score: number(),
            legend: %{String.t() => String.t()},
            probabilities: %{String.t() => number()},
            confidence: number()
          }

    @doc "The legend in LEVEL order, which the map itself loses (`\"2\"` before `\"10\"`)."
    @spec levels(t()) :: [String.t()]
    def levels(%__MODULE__{legend: legend}) do
      legend
      |> Map.keys()
      |> Enum.sort_by(&{byte_size(&1), &1})
      |> Enum.map(&Map.fetch!(legend, &1))
    end
  end

  defmodule Usage do
    @moduledoc "The wire's usage block. `:cost` is absent on some backends."
    defstruct input_tokens: 0, output_tokens: 0, cost: nil

    @type t :: %__MODULE__{
            input_tokens: integer(),
            output_tokens: integer(),
            cost: number() | nil
          }
  end

  defmodule Decision do
    @moduledoc """
    One answer per question, keyed by the CALLER's keys. The keys are addressing,
    not content: they are never transmitted, so a key may be a tool, skill or agent
    name verbatim.

    `:calibrated` reports whether the probabilities are calibrated. `systemone`
    reports true; `llm` reports false unless it derived them from provider token
    probabilities. **A threshold tuned against one backend does not transfer to
    another.**
    """
    defstruct model: nil,
              answers: %{},
              usage: %Toolnexus.Classifier.Usage{},
              calibrated: true,
              cached: false

    @type t :: %__MODULE__{
            model: String.t() | nil,
            answers: %{String.t() => struct()},
            usage: Toolnexus.Classifier.Usage.t(),
            calibrated: boolean(),
            cached: boolean()
          }

    alias Toolnexus.Classifier.{ChoiceAnswer, NoulAnswer, ScoreAnswer}

    @doc "Read a `noul` answer; a wrong-type or absent key is an error, never a match failure."
    @spec noul(t(), String.t()) :: {:ok, NoulAnswer.t()} | {:error, String.t()}
    def noul(d, key), do: typed(d, key, NoulAnswer, "noul")

    @doc "Read a `choice` answer."
    @spec choice(t(), String.t()) :: {:ok, ChoiceAnswer.t()} | {:error, String.t()}
    def choice(d, key), do: typed(d, key, ChoiceAnswer, "choice")

    @doc "Read a `score` answer."
    @spec score(t(), String.t()) :: {:ok, ScoreAnswer.t()} | {:error, String.t()}
    def score(d, key), do: typed(d, key, ScoreAnswer, "score")

    defp typed(%__MODULE__{answers: answers}, key, module, want) do
      case Map.fetch(answers, key) do
        {:ok, %^module{} = a} ->
          {:ok, a}

        {:ok, other} ->
          {:error, "classifier: answer #{inspect(key)} is a #{tag(other)} answer, not #{want}"}

        :error ->
          {:error, "classifier: no answer #{inspect(key)} in this decision"}
      end
    end

    defp tag(%NoulAnswer{}), do: "noul"
    defp tag(%ChoiceAnswer{}), do: "choice"
    defp tag(%ScoreAnswer{}), do: "score"
    defp tag(%Toolnexus.Classifier.RawAnswer{type: t}), do: t
  end

  # ---------------------------------------------------------------- the wire

  @doc """
  The bytes the byte-identity claim covers: `model` + `questions`, keys sorted
  recursively in ASCII order, arrays never reordered, compact separators, and
  `<>&'"` plus non-ASCII transmitted raw.

  `state` is deliberately NOT here. It is transmitted verbatim as the host
  supplied it and is outside the claim, because numbers do not canonicalise across
  languages (`-0.0` renders four ways across our own seven runtimes). Do not
  re-widen this: a caller who needs their state pinned canonicalises it themselves
  before handing it over.
  """
  @spec canonical_request(String.t(), %{String.t() => question()}) :: binary()
  def canonical_request(model, questions) do
    Canonical.encode(%{"model" => model, "questions" => wire_questions(questions)})
  end

  defp wire_questions(questions), do: Map.new(questions, fn {k, q} -> {to_string(k), wire(q)} end)

  @doc """
  The public question -> wire conversion: one `Noul` / `Choice` / `Score` struct as
  the JSON-shaped map §8B transmits (`type`, `instructions`, `criteria`).
  """
  @spec question_to_wire(question()) :: map()
  def question_to_wire(question), do: wire(question)

  @doc "A questions map (caller key -> question) as its §8B wire map."
  @spec questions_to_wire(%{String.t() => question()}) :: map()
  def questions_to_wire(questions), do: wire_questions(questions)

  defp wire(%Noul{instructions: i, criteria: nil}), do: %{"type" => "noul", "instructions" => i}

  defp wire(%Noul{instructions: i, criteria: c}),
    do: %{"type" => "noul", "instructions" => i, "criteria" => stringify(c)}

  defp wire(%Choice{instructions: i, criteria: c}),
    do: %{"type" => "choice", "instructions" => i, "criteria" => stringify(c)}

  defp wire(%Score{instructions: i, criteria: c}),
    do: %{"type" => "score", "instructions" => i, "criteria" => Enum.map(c, &to_string/1)}

  defp wire(%Toolnexus.Classifier.Raw{type: t, instructions: i, criteria: nil}),
    do: %{"type" => t, "instructions" => i}

  defp wire(%Toolnexus.Classifier.Raw{type: t, instructions: i, criteria: c}),
    do: %{"type" => t, "instructions" => i, "criteria" => c}

  defp stringify(map), do: Map.new(map, fn {k, v} -> {to_string(k), v} end)

  @doc """
  Whether a choice answer's probability map is indistinguishable from flat (§8B):

      near_uniform  ⇔  max over i of |p_i - 1/n|  <=  0.05

  `n` is the number of ENTRIES IN THE MAP and the values are taken AS RETURNED —
  not renormalised, not sorted, not rounded; an offered option absent from the map
  counts as `0` by not being an entry. The tolerance is ABSOLUTE and the comparison
  INCLUSIVE. `n == 1` is trivially uniform; an empty map has no distribution at all
  and is reported `false`.
  """
  @spec near_uniform?(map()) :: boolean()
  def near_uniform?(probabilities) when is_map(probabilities) do
    case map_size(probabilities) do
      0 ->
        false

      1 ->
        true

      n ->
        target = 1.0 / n

        Enum.all?(probabilities, fn {_, p} -> abs(p * 1.0 - target) <= @near_uniform_tolerance end)
    end
  end

  # ---------------------------------------------------------------- options

  defstruct style: "systemone",
            backend: nil,
            base_url: @default_base_url,
            model: @default_model,
            api_key_env: @default_api_key_env,
            key_explicit: false,
            headers: %{},
            timeout: @default_timeout,
            http_options: [],
            transport: nil,
            retries: @default_retries,
            retry_base_ms: @default_retry_base_ms,
            retryable_statuses: nil,
            on_error: nil,
            request_params: nil,
            body_transform: nil,
            on_metric: nil,
            client: nil,
            evaluate: nil,
            decisions: [],
            supports: nil,
            cache: nil,
            static: %{},
            warned: nil,
            wire: "systemone",
            send_images_header: true,
            supports_images: true

  @type t :: %__MODULE__{}

  @doc """
  Create a classifier, applying the §8B defaults and rejecting a style whose
  required option is missing before any call is made.

  Options (`ClassifierOptions`, §8B — mirrors §8 `ClientOptions` wherever a field
  makes sense, so a host that has configured one has configured the other):

    * `:style` — `"systemone"` (default) | `"llm"` | `"custom"` | `"static"`.
      **`"llm"` is deprecated** (System One is the single product wire, ADR 0038):
      it keeps working byte-identically as an alias for at least one minor
      release, with no runtime warning
    * `:backend` — `"typesafe"` (default) | `"openrouter"` | `"cloudflare"` |
      `"vercel"` | `"openai"` | `"custom"` (see `backends/0`); sets `:base_url` + `:model` +
      `:api_key_env` as a unit, an explicit option still wins. `"custom"` (a
      self-hosted endpoint) requires `:base_url` and `:supports` on style
      `"systemone"`. `${NAME}` in `:base_url` expands from the environment at
      call time; errors name the endpoint UNEXPANDED
    * `:supports` — `%{images: boolean}`, overrides the backend table's image
      column. A state carrying images on a backend that does not support them
      fails before the network with `Toolnexus.Classifier.Unsupported`
    * `:cache` — opt-in answer cache `%{get: (key -> string | nil), set: (key,
      string -> any)}` (e.g. `Toolnexus.Classifier.MemoryCache.new/2`). Absent ⇒
      no caching. Style `"systemone"` only; keyed per request on the SERVED model;
      only V1-valid replies are stored
    * `:base_url` — default `"https://api.typesafe.ai/v1"`
    * `:model` — default `"jev-latest"`; pin it once thresholds are tuned
    * `:api_key_env` — the **name** of the env var, never the value. Default
      `"TYPESAFE_API_KEY"`; read at call time and never logged
    * `:headers` — extra headers; values expand `${ENV_VAR}` from the environment
      **at call time** and are never logged, identically to remote-MCP headers (§2)
    * `:timeout` — ms per request (default 10_000); a classifier has no loop to bound
    * `:http_options` — extra `Req` options for the classifier path (§8 Gap 2)
    * `:transport` — §8 Gap 2, an injectable HTTP transport
      `(%{method:, url:, headers:, body:, receive_timeout:} -> {:ok, %{status:, headers:,
      body:}} | {:error, Exception.t()})`. `body` is the canonical request BINARY
    * `:retry_base_ms` (default 500) — base of the retry backoff in ms
      (`base * 2^attempt`, no jitter); a `Retry-After` header still wins
    * `:retries` (default 2) and `:on_error` — REUSES the §8 `%{error?, status?, attempt,
      retryable} -> :retry | :fail` classifier and the `Retry-After` delay-seconds rule
      verbatim. There is no second retry policy, and no `:suspend` tier here either.
      `:retries` covers `408`/`429`/`500`/`502`/`503`/`504`/`529` plus network errors
    * `:retryable_statuses` — a list of extra HTTP statuses to treat as retryable, ADDED to
      the default set (`429`/`500`/`502`/`503`/`504`/`529` plus `408` here). It can only
      widen: a host cannot remove `429` and lose `Retry-After` handling with it. It sets the
      DEFAULT classification only — `:on_error` still runs per attempt and has the final say,
      so `:on_error` returning `:fail` overrides a status listed here. Example: a
      Cloudflare-fronted origin that answers `520`–`527`. `Retry-After` handling is untouched
    * `:request_params` / `:body_transform` — §8 Gap 1, same ordering: base body →
      `:request_params` merge (a caller key wins) → `:body_transform` → marshal
    * `:on_metric` — emits `"classifier.evaluate"` events into the **same** §8 sink,
      and carries the degenerate-criteria warning as `"classifier.warning"`
    * `:client` — `style: "llm"` only; the §8 `Toolnexus.Client` to emulate over
    * `:evaluate` — `style: "custom"` only; `(state, questions -> {:ok, Decision} |
      {:error, term})`. Every wire option is ignored
    * `:decisions` — `style: "static"` only; a list of
      `%{state: …, questions: …, response: map_or_binary}` recorded decisions
  """
  @spec create(keyword() | map()) :: {:ok, t()} | {:error, String.t()}
  def create(opts \\ []) do
    opts = if is_map(opts), do: Map.to_list(opts), else: opts

    c =
      struct!(
        __MODULE__,
        Keyword.take(
          opts,
          Map.keys(%__MODULE__{}) --
            [
              :__struct__,
              :static,
              :warned,
              :wire,
              :send_images_header,
              :supports_images,
              :key_explicit
            ]
        )
      )

    # A `:backend` sets base_url + model + api_key_env AS A UNIT. The struct already
    # carries the TypeSafe defaults, so "did the caller pass it?" is read off `opts`,
    # never off the struct — an explicit option still wins over the preset.
    #
    # `key_explicit`: a CALLER-named `:api_key_env` goes wherever base_url points; the
    # preset's own variable only to the preset's own host (`key_env/2`, #155).
    c = %{c | key_explicit: Keyword.get(opts, :api_key_env) not in [nil, ""]}

    c =
      case c.backend && Map.get(@backends, to_string(c.backend)) do
        nil ->
          c

        p ->
          Enum.reduce([:base_url, :model, :api_key_env], c, fn key, acc ->
            if Keyword.get(opts, key) != nil,
              do: acc,
              else: Map.put(acc, key, Map.fetch!(p, key))
          end)
      end

    c = %{
      c
      | style: to_string(c.style || "systemone"),
        base_url: c.base_url || @default_base_url,
        model: c.model || @default_model,
        api_key_env: c.api_key_env || @default_api_key_env,
        headers: c.headers || %{},
        timeout: c.timeout || @default_timeout,
        http_options: c.http_options || [],
        retries: c.retries || @default_retries,
        retry_base_ms: c.retry_base_ms || @default_retry_base_ms,
        decisions: c.decisions || []
    }

    with :ok <- validate_backend(c, opts),
         {:ok, c} <- validate_style(c) do
      {:ok, %{resolve_backend(c) | warned: start_warned()}}
    end
  end

  @doc false
  # The env var whose key may be sent to `url` (already ${NAME}-expanded): the
  # backend's own variable, and only when `url` is that backend's own host over
  # https (openrouter also its `*.openrouter.ai` subdomains). "" otherwise — custom
  # has no preset key. Never a key to a host the caller did not name (#155, §8B).
  def key_env(backend, url) do
    row = Map.get(@backends, to_string(backend))

    with %{api_key_env: env, base_url: own} when env not in [nil, ""] and is_binary(own) <- row,
         %URI{scheme: "https", host: h} when is_binary(h) and h != "" <- URI.parse(url),
         %URI{host: oh} <- URI.parse(own),
         h = String.downcase(h),
         true <-
           h == String.downcase(oh) or
             (to_string(backend) == "openrouter" and String.ends_with?(h, ".openrouter.ai")) do
      env
    else
      _ -> ""
    end
  end

  # The row's wire + header, and the image support: the `:supports` override wins,
  # else the table's column for this model.
  defp resolve_backend(c) do
    row = Map.fetch!(@backends, backend_name(c))

    images =
      case {supports_images(c.supports), row.supports_images} do
        {b, _} when is_boolean(b) -> b
        {_, {:model_contains, s}} -> String.contains?(c.model, s)
        {_, b} -> b == true
      end

    %{c | wire: row.wire, send_images_header: row.images_header, supports_images: images}
  end

  defp backend_name(%{backend: nil}), do: "typesafe"
  defp backend_name(%{backend: b}), do: to_string(b)

  defp supports_images(nil), do: nil
  defp supports_images(s) when is_map(s), do: Map.get(s, :images, Map.get(s, "images")) == true

  # An unknown preset name, and the ONE mismatch that costs a 700ms "Unknown model"
  # round trip: TypeSafe's floating alias pointed at the OpenRouter gateway.
  defp validate_backend(c, opts) do
    name = c.backend && to_string(c.backend)

    cond do
      name != nil and not Map.has_key?(@backends, name) ->
        {:error,
         "classifier: unknown backend #{inspect(name)} (want " <>
           (@backend_names |> Enum.drop(-1) |> Enum.map_join(", ", &inspect/1)) <>
           " or \"custom\")"}

      # A self-hosted endpoint: the library cannot know what an unknown server takes.
      # Wire styles only — static/custom/llm never touch it.
      name == "custom" and c.style == "systemone" and
          (Keyword.get(opts, :base_url) in [nil, ""] or Keyword.get(opts, :supports) == nil) ->
        {:error, ~s(classifier: backend "custom" requires baseUrl and supports)}

      String.contains?(c.base_url, "openrouter.ai") and
        is_binary(c.model) and String.starts_with?(c.model, "jev-") ->
        {:error,
         ~s(classifier: model "#{c.model}" is TypeSafe's spelling; on openrouter.ai use "typesafe/jev-1.14")}

      true ->
        :ok
    end
  end

  defp validate_style(%{style: "systemone"} = c), do: {:ok, c}

  defp validate_style(%{style: "llm"} = c) do
    if c.client, do: {:ok, c}, else: {:error, ~s(classifier: style "llm" requires :client)}
  end

  defp validate_style(%{style: "custom"} = c) do
    if is_function(c.evaluate, 2),
      do: {:ok, c},
      else: {:error, ~s(classifier: style "custom" requires :evaluate)}
  end

  defp validate_style(%{style: "static"} = c) do
    Enum.reduce_while(Enum.with_index(c.decisions), {:ok, %{}}, fn {rec, i}, {:ok, acc} ->
      rec = Map.new(rec)

      case Map.get(rec, :questions) do
        nil ->
          {:halt, {:error, "classifier: recorded decision #{i}: missing :questions"}}

        questions ->
          key = static_key(c.model, Map.get(rec, :state), questions)
          {:cont, {:ok, Map.put(acc, key, Map.get(rec, :response))}}
      end
    end)
    |> case do
      {:ok, static} -> {:ok, %{c | static: static}}
      {:error, _} = e -> e
    end
  end

  defp validate_style(%{style: style}),
    do: {:error, "classifier: unknown style #{inspect(style)}"}

  # The once-per-question-key warning set, so a per-turn judge does not flood the
  # sink. Unlinked like the §8 MetricsRegistry: a classifier is a value a host may
  # hold anywhere, not a supervised child.
  defp start_warned do
    {:ok, pid} = Agent.start(fn -> MapSet.new() end)
    pid
  end

  # ---------------------------------------------------------------- evaluate

  @doc """
  The whole contract: a state plus typed questions in, a `Decision` out.

  `state` is a string, a map, or a list — whatever the host already has, and it is
  transmitted verbatim. `questions` is a map from **caller-chosen keys** to
  question structs. Questions are **independent**: one answer is never context for
  another.
  """
  @spec evaluate(t(), term(), %{String.t() => question()}) ::
          {:ok, Decision.t()} | {:error, String.t()}
  def evaluate(%__MODULE__{} = c, state, questions), do: do_evaluate(c, state, questions, false)

  # The one path. `validate?` = fail closed on `validate_decision/2` (the
  # `state/2` builders); plain `evaluate/3` keeps its old behaviour.
  defp do_evaluate(c, state, questions, validate?) do
    t0 = System.monotonic_time(:millisecond)
    l1 = l1(c, questions)

    with :ok <- validate_questions(questions) do
      # Detection, never repair (ADR 0020/0021): the request goes out BYTE-UNCHANGED
      # and the warning is the entire observable effect.
      report_degenerate(c, questions)

      result =
        case backend(c, state, questions, validate?) do
          {:ok, %Decision{} = d} when validate? and c.style != "systemone" ->
            with :ok <- validate_decision(questions, d), do: {:ok, d}

          other ->
            other
        end

      case result do
        {:ok, %Decision{} = d} ->
          emit(
            c,
            Map.merge(l1, %{
              event: @metric_evaluate,
              model: d.model || c.model,
              status: "ok",
              ms: System.monotonic_time(:millisecond) - t0,
              prompt_tokens: d.usage.input_tokens,
              completion_tokens: d.usage.output_tokens,
              cached: d.cached
            })
          )

          {:ok, d}

        {:error, reason} ->
          emit_error(c, t0, reason, l1)
          {:error, reason}
      end
    else
      {:error, reason} ->
        emit_error(c, t0, reason, l1)
        {:error, reason}
    end
  end

  # L1: count + sha256 of the canonical request on every evaluate event; the hash
  # is "" when the questions could not be encoded.
  defp l1(c, questions) when is_map(questions) do
    hash =
      try do
        :crypto.hash(:sha256, canonical_request(c.model, questions))
        |> Base.encode16(case: :lower)
      rescue
        _ -> ""
      end

    %{questions: map_size(questions), questions_hash: hash, cached: false}
  end

  defp l1(_c, _), do: %{questions: 0, questions_hash: "", cached: false}

  # ---------------------------------------------------------------- builders

  alias Toolnexus.Classifier.{
    BatchResult,
    Decisions,
    Images,
    Raw,
    RawAnswer,
    State,
    States,
    Validate
  }

  @doc """
  Bind a state (§8B value builder). Nothing is sent until `evaluate/2`:

      c |> Classifier.state("a red mug") |> Classifier.evaluate([Judge.noul(:q, "Is it red?")])
  """
  @spec state(t(), term()) :: State.t()
  def state(%__MODULE__{} = c, value), do: %State{classifier: c, value: value}

  @doc "Bind a batch of states; see `evaluate/2`."
  @spec states(t(), [term()]) :: States.t()
  def states(%__MODULE__{} = c, values) when is_list(values),
    do: %States{classifier: c, values: values}

  @doc """
  Evaluate a bound state or batch against ordered questions (`Toolnexus.Judge`
  builders: `noul`, `choice`, `score`, `raw`) or a questions map. A duplicate
  name is an error before any request.

    * `%State{}` → `{:ok, Decision}` | `{:error, reason}`: one request, and the
      reply is validated FAIL-CLOSED (`validate_decision/2`).
    * `%States{}` → `{:ok, [%BatchResult{}]}` in state order, at most 16 in
      flight. One state's failure never fails the batch; `{:error, _}` is only for
      the question set. No states ⇒ `{:ok, []}` and no request.
  """
  def evaluate(%State{classifier: c, value: v}, qs) do
    with {:ok, questions} <- Toolnexus.Judge.questions(qs) do
      do_evaluate(c, v, questions, true)
    end
  end

  def evaluate(%States{classifier: c, values: vs}, qs) do
    with {:ok, questions} <- Toolnexus.Judge.questions(qs) do
      results =
        vs
        |> Task.async_stream(
          fn v ->
            case do_evaluate(c, v, questions, true) do
              {:ok, d} -> %BatchResult{decision: d}
              {:error, e} -> %BatchResult{error: e}
            end
          end,
          max_concurrency: @default_batch_concurrency,
          ordered: true,
          timeout: :infinity
        )
        |> Enum.map(fn {:ok, r} -> r end)

      {:ok, results}
    end
  end

  # ---------------------------------------------------------------- pure helpers

  @doc """
  V1: fail closed on a reply that does not answer exactly the questions asked,
  within their schema. First failure wins, in the §8B order. Every message starts
  `classifier: invalid decision: `.
  """
  @spec validate_decision(map(), Decision.t()) :: :ok | {:error, String.t()}
  def validate_decision(questions, %Decision{} = d), do: Validate.run(questions, d)

  @doc "C1: split a questions map into chunks of `size`, keys in ASCII order. A helper, not automatic."
  @spec chunk_questions(map(), integer()) :: {:ok, [map()]} | {:error, String.t()}
  def chunk_questions(_questions, size) when not is_integer(size) or size < 1,
    do: {:error, "classifier: chunk size must be at least 1, got #{inspect(size)}"}

  def chunk_questions(questions, size) do
    {:ok,
     questions
     |> Enum.sort_by(fn {k, _} -> to_string(k) end)
     |> Enum.chunk_every(size)
     |> Enum.map(&Map.new/1)}
  end

  @doc "§8B cache model key: which model an endpoint + alias served last."
  @spec model_cache_key(String.t(), String.t()) :: String.t()
  def model_cache_key(endpoint, model),
    do:
      sha256_hex(Canonical.encode(%{"endpoint" => endpoint, "kind" => "model", "model" => model}))

  @doc """
  §8B cache decision key, on the SERVED model. `assets` nil/empty ⇒ the `"assets"`
  member is omitted. `state` is the state as sent (after lifting).
  """
  @spec decision_cache_key(String.t(), String.t(), term(), map() | nil, map()) :: String.t()
  def decision_cache_key(endpoint, served, state, assets, questions) do
    m = %{
      "endpoint" => endpoint,
      "kind" => "decision",
      "model" => served,
      "questions" => wire_questions(questions),
      "state" => state
    }

    m = if assets in [nil, %{}], do: m, else: Map.put(m, "assets", assets)
    sha256_hex(Canonical.encode(m))
  end

  defp sha256_hex(b), do: :crypto.hash(:sha256, b) |> Base.encode16(case: :lower)

  @doc """
  The same questions over many states (§8B "Batch"). Each state goes through
  `evaluate/3`, at most `:concurrency` (default 16) in flight; decisions come back
  in STATE order. Fails closed: the first failing state's index is named and no
  decisions are returned. An empty states list is an error and sends nothing.
  """
  @spec evaluate_batch(t(), [term()], %{String.t() => question()}, keyword()) ::
          {:ok, [Decision.t()]} | {:error, String.t()}
  def evaluate_batch(c, states, questions, opts \\ [])

  def evaluate_batch(%__MODULE__{}, [], _questions, _opts),
    do: {:error, "classifier: evaluateBatch: no states to evaluate"}

  def evaluate_batch(%__MODULE__{} = c, states, questions, opts) when is_list(states) do
    states
    |> Enum.with_index()
    |> Task.async_stream(fn {state, i} -> {i, evaluate(c, state, questions)} end,
      max_concurrency: Keyword.get(opts, :concurrency, @default_batch_concurrency),
      ordered: true,
      timeout: :infinity
    )
    |> Enum.reduce_while({:ok, []}, fn
      {:ok, {_i, {:ok, d}}}, {:ok, acc} ->
        {:cont, {:ok, [d | acc]}}

      {:ok, {i, {:error, why}}}, _ ->
        {:halt, {:error, "classifier: evaluateBatch: state #{i}: #{why}"}}
    end)
    |> case do
      {:ok, ds} -> {:ok, Enum.reverse(ds)}
      err -> err
    end
  end

  # Limits are enforced CLIENT-SIDE, before the request: a caller finds out faster
  # and more legibly than from the backend's own 400, and no request is sent. Keys
  # are walked in sorted order so the same malformed set always names the same key
  # first.
  defp validate_questions(questions) when questions == %{},
    do: {:error, "classifier: no questions to evaluate"}

  defp validate_questions(questions) when is_map(questions) do
    questions
    |> Enum.sort_by(fn {k, _} -> to_string(k) end)
    |> Enum.reduce_while(:ok, fn {key, q}, :ok ->
      case validate_question(to_string(key), q) do
        :ok -> {:cont, :ok}
        {:error, _} = e -> {:halt, e}
      end
    end)
  end

  defp validate_question(key, %Choice{criteria: criteria}) do
    n = map_size(criteria)

    if n < 1 or n > @max_choice_options do
      {:error,
       "classifier: question #{inspect(key)}: a choice needs 1..#{@max_choice_options} options, got #{n}"}
    else
      :ok
    end
  end

  defp validate_question(key, %Score{criteria: criteria}) do
    n = length(criteria)

    if n < @min_score_levels or n > @max_score_levels do
      {:error,
       "classifier: question #{inspect(key)}: a score needs #{@min_score_levels}..#{@max_score_levels} ordered levels, got #{n}"}
    else
      :ok
    end
  end

  defp validate_question(_key, %Noul{}), do: :ok

  defp validate_question(key, %Raw{type: t}) do
    if is_binary(t) and t != "",
      do: :ok,
      else: {:error, "classifier: question #{inspect(key)}: a raw question needs a type"}
  end

  defp validate_question(key, other),
    do: {:error, "classifier: question #{inspect(key)} is not a question: #{inspect(other)}"}

  # ---------------------------------------------------------------- degenerate

  # Emits ONE warning per degenerate question key per classifier, naming the key,
  # and changes nothing about the request. Repairing would invent option
  # descriptions the caller did not write, and the library has no way to know what
  # the options mean.
  defp report_degenerate(c, questions) do
    questions
    |> Enum.sort_by(fn {k, _} -> to_string(k) end)
    |> Enum.each(fn
      {key, %Choice{criteria: criteria}} ->
        case degenerate_criteria(criteria) do
          {:ok, reason} -> warn_once(c, to_string(key), reason)
          :no -> :ok
        end

      {_key, _other} ->
        :ok
    end)
  end

  defp warn_once(c, key, reason) do
    first? =
      Agent.get_and_update(c.warned, fn seen ->
        {not MapSet.member?(seen, key), MapSet.put(seen, key)}
      end)

    if first? do
      emit(c, %{
        event: @metric_warning,
        question: key,
        # `:warning`, never `:error` — this event is advisory, not a failure, and a
        # consumer matching on `%{error: _}` must not count it as one.
        warning:
          "classifier: question #{inspect(key)} has degenerate criteria (#{reason}) — every " <>
            "option reads the same to the model and the answer ranks at chance; describe what " <>
            "picking each option would MEAN (SPEC.md §8B)"
      })
    end

    :ok
  end

  # The §8B predicate. Degenerate ⇔ ANY of:
  #   1. every value is empty (empty string or absent), or
  #   2. every value equals its own key, or
  #   3. every value is identical to every other value (n >= 2).
  #
  # A single-option choice (n == 1) is NEVER reported: there is nothing to
  # differentiate. Note the ordering — with n == 1 rules 1 and 2 can still hold and
  # rule 3 is vacuous, so the n < 2 gate comes first for all three.
  defp degenerate_criteria(criteria) when map_size(criteria) < 2, do: :no

  defp degenerate_criteria(criteria) do
    pairs = Enum.map(criteria, fn {k, v} -> {to_string(k), v || ""} end)
    values = Enum.map(pairs, &elem(&1, 1))

    cond do
      Enum.all?(values, &(&1 == "")) ->
        {:ok, "every description is empty"}

      Enum.all?(pairs, fn {k, v} -> v == k end) ->
        {:ok, "every description is just its own option id"}

      Enum.uniq(values) |> length() == 1 ->
        {:ok, "every description is identical"}

      true ->
        :no
    end
  end

  # ---------------------------------------------------------------- backends

  defp backend(%{style: "custom"} = c, state, questions, _v), do: c.evaluate.(state, questions)

  defp backend(%{style: "static"} = c, state, questions, _v),
    do: evaluate_static(c, state, questions)

  defp backend(%{style: "llm"} = c, state, questions, _v), do: evaluate_llm(c, state, questions)
  defp backend(c, state, questions, v), do: evaluate_systemone(c, state, questions, v)

  # The request for the backend's wire: systemone = model + questions + state (as
  # sent, after lifting) + `assets` when the state carried images; cloudflare =
  # `{model, input: {questions, state, images?}}`; decisions = `{model, input,
  # questions: [..]}` (Toolnexus.Classifier.Decisions). Then the §8 Gap 1 pipeline in §8
  # order (base → :request_params merge → :body_transform → marshal).
  defp body(c, state, questions, imgs, assets) do
    with {:ok, base} <- base_body(c, state, questions, imgs, assets) do
      merged =
        case c.request_params do
          nil -> base
          params -> Map.merge(base, Map.new(params, fn {k, v} -> {to_string(k), v} end))
        end

      final =
        case c.body_transform do
          f when is_function(f, 1) -> f.(merged) || merged
          _ -> merged
        end

      {:ok, Canonical.encode(final)}
    end
  end

  defp base_body(%{wire: "decisions"} = c, state, questions, imgs, _assets) do
    with {:ok, list} <- Decisions.questions(backend_name(c), c.model, questions) do
      {:ok, %{"model" => c.model, "input" => Decisions.input(state, imgs), "questions" => list}}
    end
  end

  defp base_body(c, state, questions, imgs, assets) do
    qs = wire_questions(questions)

    base =
      if c.wire == "cloudflare" do
        input = %{"questions" => qs, "state" => state}

        input =
          if imgs == [],
            do: input,
            else:
              Map.put(
                input,
                "images",
                Enum.map(imgs, fn {_ref, fmt, data} -> "data:image/#{fmt};base64,#{data}" end)
              )

        %{"model" => c.model, "input" => input}
      else
        b = %{"model" => c.model, "questions" => qs, "state" => state}
        if assets == nil, do: b, else: Map.put(b, "assets", assets)
      end

    {:ok, base}
  end

  # A recorded decision is identified by the canonical request AND the state:
  # several recorded entries legitimately share one questions payload and differ
  # only in state (the three guard bands of examples/judge/decisions.json do
  # exactly that), so a corpus keyed on the canonical request alone cannot tell
  # them apart.
  defp static_key(model, state, questions),
    do: canonical_request(model, questions) <> <<0>> <> Canonical.encode(state)

  defp evaluate_static(c, state, questions) do
    case Map.fetch(c.static, static_key(c.model, state, questions)) do
      {:ok, response} -> decode_decision(response)
      # Never guesses: an unrecorded request is no answer, not the nearest one.
      :error -> {:error, "classifier: static: no recorded decision for this request+state"}
    end
  end

  # The systemone style (every backend): lift, check, cache, post, unwrap, decode.
  defp evaluate_systemone(c, state, questions, validate?) do
    with {:ok, sent, imgs} <- Images.lift(state),
         :ok <- check_supported(c, imgs),
         {:ok, imgs} <- Images.check(imgs) do
      assets =
        if imgs == [],
          do: nil,
          else:
            Map.new(imgs, fn {ref, fmt, data} ->
              {ref, %{"type" => "image", "format" => fmt, "data_base64" => data}}
            end)

      case cache_lookup(c, sent, assets, questions) do
        {:hit, d} -> {:ok, d}
        :miss -> evaluate_live(c, sent, imgs, assets, questions, validate?)
      end
    end
  end

  defp check_supported(%{supports_images: false} = c, [_ | _]),
    do:
      {:error,
       %Toolnexus.Classifier.Unsupported{
         backend: backend_name(c),
         model: c.model,
         feature: "images"
       }}

  defp check_supported(_c, _imgs), do: :ok

  defp evaluate_live(c, sent, imgs, assets, questions, validate?) do
    extra =
      if imgs != [] and c.send_images_header, do: %{@images_header => "true"}, else: %{}

    with {:ok, raw_body} <- body(c, sent, questions, imgs, assets),
         {:ok, raw} <- post(c, raw_body, extra),
         {:ok, reply} <- unwrap(c, raw, questions),
         {:ok, d} <- decode_decision(reply) do
      verdict = validate_decision(questions, d)

      cond do
        validate? and verdict != :ok ->
          verdict

        verdict == :ok and c.cache != nil ->
          served = d.model || c.model
          reply = if is_binary(reply), do: reply, else: Canonical.encode(reply)
          cache_set(c.cache, model_cache_key(c.base_url, c.model), served)

          cache_set(
            c.cache,
            decision_cache_key(c.base_url, served, sent, assets, questions),
            reply
          )

          {:ok, d}

        true ->
          {:ok, d}
      end
    end
  end

  # Lookup: model key -> served model; decision key (on it) -> reply, decoded and
  # V1-checked. A corrupt or invalid entry is a miss.
  defp cache_lookup(%{cache: nil}, _sent, _assets, _questions), do: :miss

  defp cache_lookup(c, sent, assets, questions) do
    with served when is_binary(served) <- cache_get(c.cache, model_cache_key(c.base_url, c.model)),
         raw when is_binary(raw) <-
           cache_get(c.cache, decision_cache_key(c.base_url, served, sent, assets, questions)),
         {:ok, d} <- decode_decision(raw),
         :ok <- validate_decision(questions, d) do
      {:hit, %{d | cached: true}}
    else
      _ -> :miss
    end
  end

  defp cache_get(%{get: g}, k) when is_function(g, 1), do: g.(k)
  defp cache_set(%{set: s}, k, v) when is_function(s, 2), do: s.(k, v)

  # Cloudflare's /ai/run envelope -> the System One reply inside it. The envelope's
  # own error list is never echoed (it can carry an account id).
  defp unwrap(%{wire: "decisions"}, raw, questions), do: Decisions.reply(raw, questions)

  defp unwrap(%{wire: "cloudflare"}, raw, _questions) do
    env =
      case raw do
        b when is_binary(b) -> Jason.decode(b)
        m -> {:ok, m}
      end

    case env do
      {:ok, %{"success" => true} = env} ->
        case env["result"] do
          %{"state" => st} when is_binary(st) and st != "Completed" ->
            {:error, "classifier: cloudflare: run state #{inspect(st)}"}

          %{"state" => st} = r when is_binary(st) ->
            unwrapped(r["result"])

          r ->
            unwrapped(r)
        end

      {:ok, env} ->
        {:error, "classifier: cloudflare: envelope not successful" <> envelope_cause(env)}

      _ ->
        {:error, "classifier: cloudflare: envelope not successful"}
    end
  end

  defp unwrap(_c, raw, _questions), do: {:ok, raw}

  # errors[0] of a failed envelope as ": <code> <message>" (§8B), through the same
  # redaction and cap as every other backend cause; "" when there is nothing to show.
  # Cloudflare reports a missing balance, a bad model id and a malformed request all
  # as HTTP 200 + success:false, so this is the only thing that tells them apart.
  defp envelope_cause(%{"errors" => [%{} = e | _]}) do
    code =
      case e["code"] do
        c when is_binary(c) or is_number(c) -> to_string(c)
        _ -> ""
      end

    msg = if is_binary(e["message"]), do: e["message"], else: ""
    cause(200, String.trim(code <> " " <> msg))
  end

  defp envelope_cause(_), do: ""

  defp unwrapped(%{} = r), do: {:ok, Canonical.encode(r)}
  defp unwrapped(_), do: {:error, "classifier: cloudflare: no result in the envelope"}

  # ---------------------------------------------------------------- transport

  # The one POST, with the §8 retry budget, reusing the client's error tiers and
  # the `Retry-After` delay-seconds rule verbatim.
  #
  # NO CREDENTIAL VALUE AND NO EXPANDED HEADER VALUE APPEARS ON ANY PATH OUT OF
  # HERE: an authentication failure names the status and the endpoint, nothing
  # else, and a 401/403 body is never echoed back (a gateway happily reflects a bad
  # Authorization header into its own 401 text).
  defp post(c, raw, extra), do: post(c, raw, extra, 0)

  defp post(c, raw, extra, attempt) do
    # `endpoint` is what errors name: the CONFIGURED base, ${ENV} unexpanded, so an
    # account id in a templated URL never reaches an error. `url` is what is dialled.
    endpoint = String.trim_trailing(c.base_url, "/")

    endpoint =
      case c.wire do
        "cloudflare" -> endpoint
        "decisions" -> endpoint <> "/decisions"
        _ -> endpoint <> "/systemone"
      end

    url = expand_env_refs(endpoint)

    case wire_call(c, url, raw, extra) do
      {:ok, %{status: status, body: body}} when status in 200..299 ->
        {:ok, body}

      {:ok, %{status: status} = resp} ->
        retryable = retryable_status?(status, c.retryable_statuses)
        error = "classifier: POST #{endpoint}: HTTP #{status}#{cause(status, resp.body)}"

        if attempt >= c.retries or
             classify_error(c, %{status: status, attempt: attempt, retryable: retryable}) == :fail do
          {:error, error}
        else
          Process.sleep(backoff_ms(c, resp, attempt))
          post(c, raw, extra, attempt + 1)
        end

      {:error, e} ->
        error =
          "classifier: POST #{endpoint}: " <>
            String.replace(exception_message(e), url, endpoint)

        if attempt >= c.retries or
             classify_error(c, %{error: e, attempt: attempt, retryable: true}) == :fail do
          {:error, error}
        else
          Process.sleep(base_backoff_ms(c, attempt))
          post(c, raw, extra, attempt + 1)
        end
    end
  end

  # Only the DEFAULT classification; `:retryable_statuses` is ADDITIVE to `@retryable_statuses`
  # (see its comment) and never subtractive. `:on_error` still has the final say per attempt.
  defp retryable_status?(status, extra),
    do: status in @retryable_statuses or (is_list(extra) and status in extra)

  # §8 Resilience, verbatim: absent :on_error ⇒ retryable ⇒ :retry, else :fail.
  defp classify_error(%{on_error: nil}, %{retryable: retryable}),
    do: if(retryable, do: :retry, else: :fail)

  defp classify_error(%{on_error: f}, info) when is_function(f, 1), do: f.(info)

  defp base_backoff_ms(c, attempt),
    do: (c.retry_base_ms || @default_retry_base_ms) * Integer.pow(2, attempt)

  defp backoff_ms(c, resp, attempt) do
    case Toolnexus.Client.parse_retry_after(header(resp, "retry-after")) do
      nil -> base_backoff_ms(c, attempt)
      seconds -> seconds * 1000
    end
  end

  defp header(%{headers: headers}, name) when is_map(headers) do
    case Map.get(headers, name) || Map.get(headers, String.downcase(name)) do
      [v | _] -> v
      v -> v
    end
  end

  defp header(%{headers: headers}, name) when is_list(headers) do
    Enum.find_value(headers, fn {k, v} ->
      if String.downcase(to_string(k)) == name, do: if(is_list(v), do: List.first(v), else: v)
    end)
  end

  defp header(_, _), do: nil

  # A backend's reported cause is surfaced INTACT so a caller can tell a limit
  # error from a transport fault — EXCEPT on an authentication status, whose body
  # routinely reflects the credential or the header that was sent.
  # ONE implementation, shared with the §8 client path (ADR 0027 / D5): blank on
  # 401/403, account identifiers redacted, then capped at 200 bytes.
  defp cause(status, body), do: Toolnexus.ProviderError.cause(status, body)

  defp exception_message(e) when is_exception(e), do: Exception.message(e)
  defp exception_message(e), do: inspect(e)

  # §8 Gap 2 — one wire call. With a :transport the host's function makes it
  # (retries, backoff, Retry-After and classification still run around it here).
  # ${NAME} only (not $NAME), at call time; unset ⇒ empty. No `${` ⇒ unchanged.
  defp expand_env_refs(s) do
    if String.contains?(s, "${"),
      do:
        Regex.replace(~r/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/, s, fn _, n ->
          System.get_env(n) || ""
        end),
      else: s
  end

  defp wire_call(%{transport: transport} = c, endpoint, raw, extra)
       when is_function(transport, 1) do
    request = %{
      method: :post,
      url: endpoint,
      headers: request_headers(c, endpoint, extra),
      body: raw,
      receive_timeout: c.timeout
    }

    case transport.(request) do
      {:ok, resp} when is_map(resp) ->
        {:ok,
         %{
           status: resp[:status] || resp["status"],
           headers: Map.get(resp, :headers) || %{},
           body: Map.get(resp, :body)
         }}

      {:error, _} = e ->
        e
    end
  end

  defp wire_call(c, endpoint, raw, extra) do
    opts =
      [
        method: :post,
        url: endpoint,
        headers: request_headers(c, endpoint, extra),
        body: raw,
        receive_timeout: c.timeout,
        retry: false,
        decode_body: false
      ]
      |> Keyword.merge(c.http_options)

    case Req.request(Req.new(opts)) do
      {:ok, %Req.Response{status: status, headers: headers, body: body}} ->
        {:ok, %{status: status, headers: headers, body: body}}

      {:error, e} ->
        {:error, e}
    end
  end

  # Credentials resolve AT CALL TIME from the NAMED env var, and ${ENV_VAR} header
  # references expand at call time. Neither value is ever logged, returned, or put
  # in an error or a metric.
  defp request_headers(c, endpoint, extra) do
    base = Map.merge(%{"content-type" => "application/json"}, extra)

    env = if c.key_explicit, do: c.api_key_env, else: key_env(backend_name(c), endpoint)

    base =
      case env != "" and System.get_env(env) do
        k when k in [false, nil, ""] -> base
        key -> Map.put(base, "authorization", "Bearer " <> key)
      end

    Map.merge(base, Toolnexus.Http.expand_headers(stringify(c.headers)))
  end

  # ---------------------------------------------------------------- llm style

  # The three question types rendered as ONE structured-output call on any §8
  # client — the vendor-neutral fallback, so a host with no System One credential
  # runs the same questions on a cheap chat model. `calibrated` is FALSE: the
  # numbers are the model's self-report, not token probabilities.
  defp evaluate_llm(c, state, questions) do
    prompt =
      "Answer every question about the state below. Questions are INDEPENDENT: " <>
        "one answer is never context for another.\n\nSTATE:\n" <>
        Canonical.encode(state) <>
        "\n\nQUESTIONS:\n" <>
        Canonical.encode(wire_questions(questions)) <>
        "\n\nReply with JSON only, no prose and no code fence, shaped exactly:\n" <>
        ~s({"answers":{"<key>":{"type":"noul","noul":0.0}}}\n) <>
        ~s(A "noul" answer is {"type":"noul","noul":<0..1>}. A "choice" answer is ) <>
        ~s({"type":"choice","choice":"<one offered option id>","probabilities":{"<every offered option id>":<0..1>},"confidence":<0..1>}. ) <>
        ~s(A "score" answer is {"type":"score","score":<a number within the rubric bounds, fractional allowed>,) <>
        ~s("legend":{"0":"<level 0>",…},"probabilities":{"0":<0..1>,…},"confidence":<0..1>}.)

    run = Toolnexus.Client.run(c.client, prompt, nil)

    with {:ok, payload} <- first_json_object(run.text),
         {:ok, d} <- decode_decision(payload) do
      # The model reports no calibration and none is derived here. Never repaired,
      # never asserted as calibrated (ADR 0020).
      {:ok,
       %{
         d
         | calibrated: false,
           model: d.model || c.model,
           usage: %Usage{
             input_tokens: run.usage.prompt_tokens,
             output_tokens: run.usage.completion_tokens
           }
       }}
    end
  end

  # Extracts the outermost JSON object from a model reply, which may arrive wrapped
  # in a code fence or prose. It does NOT repair malformed JSON — an unparseable
  # answer is no answer (ADR 0020).
  defp first_json_object(text) do
    text = to_string(text)

    with {start, _} when is_integer(start) <- :binary.match(text, "{"),
         [_ | _] = ends <- :binary.matches(text, "}"),
         {last, _} <- List.last(ends),
         true <- last > start do
      {:ok, binary_part(text, start, last - start + 1)}
    else
      _ -> {:error, "classifier: llm: no JSON object in the reply"}
    end
  end

  # ---------------------------------------------------------------- decoding

  @doc false
  # Decode a backend response (a binary body or an already-parsed map) into a
  # Decision. Keys stay STRINGS throughout: atomising them would mangle the numeric
  # legend and probability keys ("0", "1", …) and is unbounded-atom growth on data
  # a remote backend controls.
  @spec decode_decision(binary() | map()) :: {:ok, Decision.t()} | {:error, String.t()}
  def decode_decision(raw) when is_binary(raw) do
    case Jason.decode(raw) do
      {:ok, map} -> decode_decision(map)
      {:error, e} -> {:error, "classifier: invalid JSON response: #{Exception.message(e)}"}
    end
  end

  def decode_decision(%{} = map) do
    with {:ok, answers} <- decode_answers(Map.get(map, "answers") || %{}) do
      {:ok,
       %Decision{
         model: Map.get(map, "model"),
         answers: answers,
         usage: decode_usage(Map.get(map, "usage")),
         # Absent ⇒ true: the systemone wire reports calibration by being itself. A
         # backend that is not calibrated says so explicitly.
         calibrated: Map.get(map, "calibrated", true) != false
       }}
    end
  end

  def decode_decision(other), do: {:error, "classifier: not a decision: #{inspect(other)}"}

  defp decode_answers(answers) do
    Enum.reduce_while(answers, {:ok, %{}}, fn {key, a}, {:ok, acc} ->
      case decode_answer(a) do
        {:ok, parsed} -> {:cont, {:ok, Map.put(acc, key, parsed)}}
        {:error, why} -> {:halt, {:error, "classifier: answer #{inspect(key)}: #{why}"}}
      end
    end)
  end

  defp decode_answer(%{"type" => "noul"} = a), do: {:ok, %NoulAnswer{noul: a["noul"]}}

  defp decode_answer(%{"type" => "choice"} = a) do
    probabilities = a["probabilities"] || %{}

    {:ok,
     %ChoiceAnswer{
       choice: a["choice"],
       probabilities: probabilities,
       confidence: a["confidence"],
       near_uniform: near_uniform?(probabilities)
     }}
  end

  defp decode_answer(%{"type" => "score"} = a) do
    {:ok,
     %ScoreAnswer{
       score: a["score"],
       legend: a["legend"] || %{},
       probabilities: a["probabilities"] || %{},
       confidence: a["confidence"]
     }}
  end

  # The union is OPEN (§8B): a type the library does not model (e.g. Jev 1.14
  # `box`) decodes to a RawAnswer instead of failing the whole decision.
  defp decode_answer(%{"type" => other} = a) when is_binary(other),
    do: {:ok, %RawAnswer{type: other, value: a}}

  defp decode_answer(%{"type" => other}), do: {:error, "unknown type #{inspect(other)}"}
  defp decode_answer(other), do: {:error, "no type discriminator: #{inspect(other)}"}

  defp decode_usage(nil), do: %Usage{}

  defp decode_usage(%{} = u),
    do: %Usage{
      input_tokens: u["input_tokens"] || 0,
      output_tokens: u["output_tokens"] || 0,
      cost: u["cost"]
    }

  # ---------------------------------------------------------------- metrics

  defp emit(%{on_metric: f}, ev) when is_function(f, 1), do: f.(ev)
  defp emit(_, _), do: :ok

  defp emit_error(c, t0, reason, l1) do
    emit(
      c,
      Map.merge(l1, %{
        event: @metric_evaluate,
        model: c.model,
        status: "error",
        ms: System.monotonic_time(:millisecond) - t0,
        prompt_tokens: 0,
        completion_tokens: 0,
        error: if(is_exception(reason), do: Exception.message(reason), else: reason)
      })
    )
  end
end
