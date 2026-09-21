defmodule Toolnexus.TimeoutError do
  @moduledoc """
  The whole-run deadline (`:timeout_ms`) expired (ADR 0027 / D5).

  Before this existed the deadline raised a bare `RuntimeError`, so a host could only
  tell a timeout from any other failure by matching on message text. The message still
  NAMES the budget — `run timeout after 1500ms` — and the budget is also a field.
  """
  defexception [:timeout_ms, :message]

  @type t :: %__MODULE__{timeout_ms: non_neg_integer() | nil, message: String.t()}

  @impl true
  def exception(opts) when is_list(opts) do
    ms = opts[:timeout_ms]
    %__MODULE__{timeout_ms: ms, message: opts[:message] || "run timeout after #{ms}ms"}
  end
end

defmodule Toolnexus.ProviderError do
  @moduledoc """
  A provider (LLM or classifier) answered with a non-success status (ADR 0027 / D5).

  The failure is carried as a VALUE — `status`, `body`, `retry_after` — so a host can
  decide what to log without parsing a sentence. The `message` is the redacted,
  capped rendering; `body` keeps the response intact for a host that genuinely wants it.

  Redaction policy, identical to the classifier's and now shared with the §8 client path:

    * `401`/`403` bodies are never echoed (a gateway reflects the Authorization header)
    * known account-identifier keys — `user_id`, `account_id`, `org_id`, `organization` —
      are replaced with `«redacted»`, never dropped, so the shape of the body survives
    * the rendered cause is capped at 200 bytes, with an ellipsis

  A cap is NOT redaction: the cap runs LAST, after the keys are replaced.
  """
  defexception [:status, :body, :retry_after, :message]

  @type t :: %__MODULE__{
          status: non_neg_integer() | nil,
          body: term(),
          retry_after: non_neg_integer() | nil,
          message: String.t()
        }

  @redacted "«redacted»"
  @account_keys ~w(user_id account_id org_id organization)

  @doc "The account-identifier keys replaced with `«redacted»` before interpolation."
  @spec account_keys() :: [String.t()]
  def account_keys, do: @account_keys

  @doc "The replacement token. Kept as a constant so every port spells it identically."
  @spec redacted() :: String.t()
  def redacted, do: @redacted

  @impl true
  def exception(opts) when is_list(opts) do
    status = opts[:status]
    prefix = opts[:prefix] || "LLM"

    # A5: redaction applies to BOTH the typed field and the message. The 200-byte cap
    # is MESSAGE-ONLY — `body` carries the full redacted body, so a host that wants the
    # whole thing has it, and nothing anywhere carries an account identifier.
    body = redact(opts[:body])

    %__MODULE__{
      status: status,
      body: body,
      retry_after: opts[:retry_after],
      message: opts[:message] || "#{prefix} #{status}#{cause(status, body)}"
    }
  end

  @doc """
  Render an ALREADY-REDACTED body for an error message: blank on `401`/`403`, then
  capped at 200 bytes (message-only). Returns `""` or `": " <> text`.
  """
  @spec cause(non_neg_integer() | nil, term()) :: String.t()
  def cause(status, _body) when status in [401, 403], do: ""

  def cause(_status, body) do
    case body |> redact() |> to_text() |> String.trim() do
      "" -> ""
      s when byte_size(s) > 200 -> ": " <> binary_part(s, 0, 200) <> "…"
      s -> ": " <> s
    end
  end

  @doc """
  Replace every known account-identifier value with `«redacted»`, at any depth.
  Structured bodies are walked; a string body is rewritten with a key-aware regex so
  a JSON blob that never reached a decoder is redacted too.
  """
  @spec redact(term()) :: term()
  def redact(body) when is_map(body) and not is_struct(body) do
    Map.new(body, fn {k, v} ->
      if to_string(k) in @account_keys, do: {k, @redacted}, else: {k, redact(v)}
    end)
  end

  def redact(body) when is_list(body) do
    if List.ascii_printable?(body),
      do: redact(IO.iodata_to_binary(body)),
      else: Enum.map(body, &redact/1)
  rescue
    # an iolist of non-binaries: walk it as a plain list
    _ -> Enum.map(body, &redact/1)
  end

  def redact(body) when is_binary(body) do
    Enum.reduce(@account_keys, body, fn key, acc ->
      Regex.replace(
        ~r/("#{key}"\s*:\s*)(?:"[^"]*"|-?\d+(?:\.\d+)?|true|false|null)/,
        acc,
        "\\1\"#{@redacted}\""
      )
    end)
  end

  def redact(body), do: body

  defp to_text(body) when is_binary(body), do: body
  defp to_text(nil), do: ""
  defp to_text(body) when is_list(body), do: IO.iodata_to_binary(body)
  defp to_text(body), do: Jason.encode!(body)
end

defmodule Toolnexus.Status do
  @moduledoc """
  The TWO status vocabularies, as first-class values (ADR 0027 / D5).

  They share a field name and they are NOT the same closed set. Reading the §7D set
  while holding a §8 `%RunResult{}` is what issue #92 did, and it is the defect this
  module exists to remove.

    * `run/0` — §8 `Client.RunResult.status`
    * `task/0` — §7D `TaskResult.status` (the agent runtime)

  `"timeout"` belongs EXCLUSIVELY to §7D: it is a `wait/2` deadline, where the child
  KEEPS RUNNING. A client run that hits `:timeout_ms` raises `Toolnexus.TimeoutError`;
  it never reports a `"timeout"` status.
  """

  @run ~w(done pending incomplete)
  @task ~w(done pending incomplete interrupted closed timeout error)

  @doc "§8 + §7D: the turn produced a final answer."
  def done, do: "done"
  @doc "§8 + §7D: a §10 durable suspension; resume with `Runtime.resume/2`."
  def pending, do: "pending"
  @doc "§8 + §7D: a limit stopped the run, and `limit` NAMES it."
  def incomplete, do: "incomplete"
  @doc "§7D: the turn was aborted; the handle is idle and alive."
  def interrupted, do: "interrupted"
  @doc "§7D: the handle was closed."
  def closed, do: "closed"
  @doc "§7D: a `wait` deadline expired — the child KEEPS RUNNING."
  def timeout, do: "timeout"
  @doc "§7D: the run failed; failures cross a handle boundary as results."
  def error, do: "error"

  @doc "§8 `RunResult.status`: #{inspect(@run)}."
  @spec run() :: [String.t()]
  def run, do: @run

  @doc "§7D `TaskResult.status`: #{inspect(@task)}."
  @spec task() :: [String.t()]
  def task, do: @task

  @doc "True when `s` is a §8 run status."
  @spec run?(String.t()) :: boolean()
  def run?(s), do: s in @run

  @doc "True when `s` is a §7D task status."
  @spec task?(String.t()) :: boolean()
  def task?(s), do: s in @task

  # A14: `limit` is a CLOSED, canonical vocabulary — it names the Budget FIELD that
  # stopped the run, spelled exactly as SPEC spells it, plus the two stops that are
  # not Budget fields (`completion`, `timeout`). Four ports were emitting four
  # different spellings in the one field hosts are meant to branch on.
  @limits ~w(maxTurns maxTokens maxToolCalls maxWallMs maxChildren maxConcurrent maxDepth completion timeout)

  @doc "The closed `limit` vocabulary: #{inspect(@limits)}."
  @spec limits() :: [String.t()]
  def limits, do: @limits

  @doc "True when `s` is a canonical `limit` value."
  @spec limit?(String.t()) :: boolean()
  def limit?(s), do: s in @limits

  defmodule Limit do
    @moduledoc """
    The canonical `limit` values as NAMED constants (A14/A18) — construction sites
    use these, never a string literal, so a spelling cannot drift in the one field
    hosts branch on.

    `max_children/0`, `max_concurrent/0` and `max_depth/0` are SPAWN/ADMISSION
    refusals: in elixir they surface as a verb error, never as a settled TaskResult
    (A17), so the port never emits them. The spelling is pinned here regardless, so
    that if one ever does settle it settles with the same word as every other port.
    """
    def max_turns, do: "maxTurns"
    def max_tokens, do: "maxTokens"
    def max_tool_calls, do: "maxToolCalls"
    def max_wall_ms, do: "maxWallMs"
    def max_children, do: "maxChildren"
    def max_concurrent, do: "maxConcurrent"
    def max_depth, do: "maxDepth"
    def completion, do: "completion"
    def timeout, do: "timeout"
  end
end
