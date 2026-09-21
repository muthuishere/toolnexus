defmodule Spike.Classifier do
  @moduledoc """
  `Classifier` is to judgments what `Tool` is to actions (ADR 0020), spiked.

  The union that the typed ports fear — `criteria` being three different JSON
  shapes on one field — is three structs here, and the discriminated `answers`
  map is three function clauses matching on `%{"type" => _}`. There is no
  tagging machinery: the tag IS the struct name going out, and the `"type"`
  string IS the pattern coming in.
  """

  # ---- Questions: three tagged structs, one per criteria shape -------------

  defmodule Noul do
    @moduledoc "Yes/no. `criteria` is ABSENT on the wire (not null, not {})."
    defstruct [:instructions]
    @type t :: %__MODULE__{instructions: String.t()}
  end

  defmodule Choice do
    @moduledoc "Pick one. `criteria` is an OBJECT of name => description."
    defstruct [:instructions, :criteria]
    @type t :: %__MODULE__{instructions: String.t(), criteria: %{String.t() => String.t()}}
  end

  defmodule Score do
    @moduledoc "Graded scale. `criteria` is an ARRAY — index N is level N."
    defstruct [:instructions, :criteria]
    @type t :: %__MODULE__{instructions: String.t(), criteria: [String.t()]}
  end

  @type question :: Noul.t() | Choice.t() | Score.t()

  # ---- Answers: three tagged structs, parsed by matching on "type" ---------

  defmodule NoulAnswer do
    defstruct [:noul]
  end

  defmodule ChoiceAnswer do
    defstruct [:choice, :probabilities, :confidence]
  end

  defmodule ScoreAnswer do
    defstruct [:score, :legend, :probabilities, :confidence]
  end

  defmodule Decision do
    defstruct [:model, :answers, :usage, :id, :provider]
  end

  @default_model "typesafe/jev-1.13"
  @default_base "https://openrouter.ai/api/v1/systemone"

  # ---- Wire form (pure) ----------------------------------------------------

  @doc "Build the request map. Pattern matching does all the union work."
  @spec request(term(), %{String.t() => question()}, keyword()) :: map()
  def request(state, questions, opts \\ []) do
    %{
      "model" => Keyword.get(opts, :model, @default_model),
      "questions" => Map.new(questions, fn {k, q} -> {k, wire(q)} end),
      "state" => state
    }
  end

  defp wire(%Noul{instructions: i}), do: %{"type" => "noul", "instructions" => i}
  defp wire(%Choice{instructions: i, criteria: c}),
    do: %{"type" => "choice", "instructions" => i, "criteria" => c}
  defp wire(%Score{instructions: i, criteria: c}),
    do: %{"type" => "score", "instructions" => i, "criteria" => c}

  @doc "Canonical bytes for a request — sorted keys, arrays untouched, compact."
  @spec encode(map()) :: binary()
  def encode(req), do: Spike.Canonical.encode(req)

  # ---- Parsing (pure) ------------------------------------------------------

  @spec decode(binary()) :: Decision.t()
  def decode(body), do: body |> Jason.decode!() |> from_json()

  @spec from_json(map()) :: Decision.t()
  def from_json(%{"answers" => answers} = d) do
    %Decision{
      model: d["model"],
      answers: Map.new(answers, fn {k, a} -> {k, answer(a)} end),
      usage: d["usage"],
      id: d["id"],
      provider: d["provider"]
    }
  end

  # The whole discriminated-union cost, in three lines.
  defp answer(%{"type" => "noul"} = a), do: %NoulAnswer{noul: a["noul"]}

  defp answer(%{"type" => "choice"} = a),
    do: %ChoiceAnswer{choice: a["choice"], probabilities: a["probabilities"], confidence: a["confidence"]}

  defp answer(%{"type" => "score"} = a),
    do: %ScoreAnswer{
      score: a["score"],
      legend: a["legend"],
      probabilities: a["probabilities"],
      confidence: a["confidence"]
    }

  # ---- Backends ------------------------------------------------------------

  @doc """
  A backend is just a 1-arity function `request_map -> Decision`. `:static`
  replays a fixture; `:live` posts the canonical bytes with Req.
  """
  @spec static(%{optional(term()) => binary()}, (map() -> term())) :: (map() -> Decision.t())
  def static(by_key, key_fn) do
    fn req ->
      by_key |> Map.fetch!(key_fn.(req)) |> File.read!() |> decode()
    end
  end

  @spec live(keyword()) :: (map() -> Decision.t())
  def live(opts \\ []) do
    fn req ->
      key = System.fetch_env!("OPENROUTER_API_KEY")

      Req.post!(
        url: Keyword.get(opts, :base_url, @default_base),
        headers: [
          {"authorization", "Bearer " <> key},
          {"content-type", "application/json"}
        ],
        body: encode(req),
        retry: false,
        receive_timeout: 60_000
      ).body
      |> from_json()
    end
  end

  @doc "evaluate(state, questions) -> Decision, over a backend."
  @spec evaluate((map() -> Decision.t()), term(), %{String.t() => question()}, keyword()) ::
          Decision.t()
  def evaluate(backend, state, questions, opts \\ []),
    do: state |> request(questions, opts) |> backend.()
end
