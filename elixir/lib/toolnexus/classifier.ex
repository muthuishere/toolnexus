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

  @max_choice_options 255
  @min_score_levels 2
  @max_score_levels 10

  # The ABSOLUTE tolerance on max|p - 1/n|, compared INCLUSIVELY (§8B). Pinned
  # across every port; `examples/judge/near-uniform.json` pins both sides.
  @near_uniform_tolerance 0.05

  @metric_evaluate "classifier.evaluate"
  @metric_warning "classifier.warning"

  @retryable_statuses [408, 429, 500, 502, 503, 504]

  @doc "The System One endpoint base (§8B default)."
  def default_base_url, do: @default_base_url
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

  @type question :: Noul.t() | Choice.t() | Score.t()

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
    defstruct model: nil, answers: %{}, usage: %Toolnexus.Classifier.Usage{}, calibrated: true

    @type t :: %__MODULE__{
            model: String.t() | nil,
            answers: %{String.t() => struct()},
            usage: Toolnexus.Classifier.Usage.t(),
            calibrated: boolean()
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

  defp wire(%Noul{instructions: i, criteria: nil}), do: %{"type" => "noul", "instructions" => i}

  defp wire(%Noul{instructions: i, criteria: c}),
    do: %{"type" => "noul", "instructions" => i, "criteria" => stringify(c)}

  defp wire(%Choice{instructions: i, criteria: c}),
    do: %{"type" => "choice", "instructions" => i, "criteria" => stringify(c)}

  defp wire(%Score{instructions: i, criteria: c}),
    do: %{"type" => "score", "instructions" => i, "criteria" => Enum.map(c, &to_string/1)}

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
            base_url: @default_base_url,
            model: @default_model,
            api_key_env: @default_api_key_env,
            headers: %{},
            timeout: @default_timeout,
            http_options: [],
            transport: nil,
            retries: @default_retries,
            on_error: nil,
            request_params: nil,
            body_transform: nil,
            on_metric: nil,
            client: nil,
            evaluate: nil,
            decisions: [],
            static: %{},
            warned: nil

  @type t :: %__MODULE__{}

  @doc """
  Create a classifier, applying the §8B defaults and rejecting a style whose
  required option is missing before any call is made.

  Options (`ClassifierOptions`, §8B — mirrors §8 `ClientOptions` wherever a field
  makes sense, so a host that has configured one has configured the other):

    * `:style` — `"systemone"` (default) | `"llm"` | `"custom"` | `"static"`
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
    * `:retries` (default 2) and `:on_error` — REUSES the §8 `%{error?, status?, attempt,
      retryable} -> :retry | :fail` classifier and the `Retry-After` delay-seconds rule
      verbatim. There is no second retry policy, and no `:suspend` tier here either
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
        Keyword.take(opts, Map.keys(%__MODULE__{}) -- [:__struct__, :static, :warned])
      )

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
        decisions: c.decisions || []
    }

    with {:ok, c} <- validate_style(c) do
      {:ok, %{c | warned: start_warned()}}
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
  def evaluate(%__MODULE__{} = c, state, questions) do
    t0 = System.monotonic_time(:millisecond)

    with :ok <- validate_questions(questions) do
      # Detection, never repair (ADR 0020/0021): the request goes out BYTE-UNCHANGED
      # and the warning is the entire observable effect.
      report_degenerate(c, questions)

      case backend(c, state, questions) do
        {:ok, %Decision{} = d} ->
          emit(c, %{
            event: @metric_evaluate,
            model: d.model || c.model,
            status: "ok",
            ms: System.monotonic_time(:millisecond) - t0,
            prompt_tokens: d.usage.input_tokens,
            completion_tokens: d.usage.output_tokens
          })

          {:ok, d}

        {:error, reason} ->
          emit_error(c, t0, reason)
          {:error, reason}
      end
    else
      {:error, reason} ->
        emit_error(c, t0, reason)
        {:error, reason}
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
        error:
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

  defp backend(%{style: "custom"} = c, state, questions), do: c.evaluate.(state, questions)
  defp backend(%{style: "static"} = c, state, questions), do: evaluate_static(c, state, questions)
  defp backend(%{style: "llm"} = c, state, questions), do: evaluate_llm(c, state, questions)
  defp backend(c, state, questions), do: evaluate_systemone(c, state, questions)

  # The request: the canonical model + questions, plus state VERBATIM as the host
  # supplied it, then the §8 Gap 1 pipeline in §8 order (base → :request_params
  # merge → :body_transform → marshal).
  defp body(c, state, questions) do
    base = %{"model" => c.model, "questions" => wire_questions(questions), "state" => state}

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

    Canonical.encode(final)
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

  defp evaluate_systemone(c, state, questions) do
    with {:ok, raw} <- post(c, body(c, state, questions)) do
      decode_decision(raw)
    end
  end

  # ---------------------------------------------------------------- transport

  # The one POST, with the §8 retry budget, reusing the client's error tiers and
  # the `Retry-After` delay-seconds rule verbatim.
  #
  # NO CREDENTIAL VALUE AND NO EXPANDED HEADER VALUE APPEARS ON ANY PATH OUT OF
  # HERE: an authentication failure names the status and the endpoint, nothing
  # else, and a 401/403 body is never echoed back (a gateway happily reflects a bad
  # Authorization header into its own 401 text).
  defp post(c, raw), do: post(c, raw, 0)

  defp post(c, raw, attempt) do
    endpoint = String.trim_trailing(c.base_url, "/") <> "/systemone"

    case wire_call(c, endpoint, raw) do
      {:ok, %{status: status, body: body}} when status in 200..299 ->
        {:ok, body}

      {:ok, %{status: status} = resp} ->
        retryable = status in @retryable_statuses
        error = "classifier: POST #{endpoint}: HTTP #{status}#{cause(status, resp.body)}"

        if attempt >= c.retries or
             classify_error(c, %{status: status, attempt: attempt, retryable: retryable}) == :fail do
          {:error, error}
        else
          Process.sleep(backoff_ms(resp, attempt))
          post(c, raw, attempt + 1)
        end

      {:error, e} ->
        error = "classifier: POST #{endpoint}: #{exception_message(e)}"

        if attempt >= c.retries or
             classify_error(c, %{error: e, attempt: attempt, retryable: true}) == :fail do
          {:error, error}
        else
          Process.sleep(500 * Integer.pow(2, attempt))
          post(c, raw, attempt + 1)
        end
    end
  end

  # §8 Resilience, verbatim: absent :on_error ⇒ retryable ⇒ :retry, else :fail.
  defp classify_error(%{on_error: nil}, %{retryable: retryable}),
    do: if(retryable, do: :retry, else: :fail)

  defp classify_error(%{on_error: f}, info) when is_function(f, 1), do: f.(info)

  defp backoff_ms(resp, attempt) do
    case Toolnexus.Client.parse_retry_after(header(resp, "retry-after")) do
      nil -> 500 * Integer.pow(2, attempt)
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
  defp cause(status, _body) when status in [401, 403], do: ""

  defp cause(_status, body) do
    case String.trim(to_text(body)) do
      "" -> ""
      s when byte_size(s) > 200 -> ": " <> binary_part(s, 0, 200) <> "…"
      s -> ": " <> s
    end
  end

  defp to_text(body) when is_binary(body), do: body
  defp to_text(body) when is_list(body), do: IO.iodata_to_binary(body)
  defp to_text(nil), do: ""
  defp to_text(body), do: Jason.encode!(body)

  defp exception_message(e) when is_exception(e), do: Exception.message(e)
  defp exception_message(e), do: inspect(e)

  # §8 Gap 2 — one wire call. With a :transport the host's function makes it
  # (retries, backoff, Retry-After and classification still run around it here).
  defp wire_call(%{transport: transport} = c, endpoint, raw) when is_function(transport, 1) do
    request = %{
      method: :post,
      url: endpoint,
      headers: request_headers(c),
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

  defp wire_call(c, endpoint, raw) do
    opts =
      [
        method: :post,
        url: endpoint,
        headers: request_headers(c),
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
  defp request_headers(c) do
    base = %{"content-type" => "application/json"}

    base =
      case System.get_env(c.api_key_env) do
        nil -> base
        "" -> base
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

  defp emit_error(c, t0, reason) do
    emit(c, %{
      event: @metric_evaluate,
      model: c.model,
      status: "error",
      ms: System.monotonic_time(:millisecond) - t0,
      prompt_tokens: 0,
      completion_tokens: 0,
      error: reason
    })
  end
end
