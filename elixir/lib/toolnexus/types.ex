defmodule Toolnexus.Request do
  @moduledoc """
  A §10 suspension request. JSON keys are byte-identical across ports:
  `{id, kind, prompt, url?, data?, expiresAt?}`.
  """
  @enforce_keys [:id, :kind, :prompt]
  defstruct [:id, :kind, :prompt, :url, :data, :expires_at]

  @type t :: %__MODULE__{
          id: String.t(),
          kind: String.t(),
          prompt: String.t(),
          url: String.t() | nil,
          data: map() | nil,
          expires_at: String.t() | nil
        }

  defimpl Jason.Encoder do
    def encode(%{id: id, kind: kind, prompt: prompt} = r, opts) do
      %{"id" => id, "kind" => kind, "prompt" => prompt}
      |> maybe_put("url", r.url)
      |> maybe_put("data", r.data)
      |> maybe_put("expiresAt", r.expires_at)
      |> Jason.Encode.map(opts)
    end

    defp maybe_put(m, _k, nil), do: m
    defp maybe_put(m, k, v), do: Map.put(m, k, v)
  end

  @doc "Decode from a JSON-shaped map (string keys, `expiresAt` spelling)."
  @spec from_map(map()) :: t()
  def from_map(m) do
    %__MODULE__{
      id: m["id"],
      kind: m["kind"],
      prompt: m["prompt"],
      url: m["url"],
      data: m["data"],
      expires_at: m["expiresAt"]
    }
  end
end

defmodule Toolnexus.Answer do
  @moduledoc "A §10 answer: `{id, ok, data?}` — keys byte-identical across ports."
  @enforce_keys [:id, :ok]
  defstruct [:id, :ok, :data, :reason]

  @type t :: %__MODULE__{
          id: String.t(),
          ok: boolean(),
          data: map() | nil,
          reason: String.t() | nil
        }

  defimpl Jason.Encoder do
    def encode(%{id: id, ok: ok} = a, opts) do
      %{"id" => id, "ok" => ok}
      |> maybe_put("data", a.data)
      |> maybe_put("reason", a.reason)
      |> Jason.Encode.map(opts)
    end

    defp maybe_put(m, _k, nil), do: m
    defp maybe_put(m, k, v), do: Map.put(m, k, v)
  end

  @doc "Decode from a JSON-shaped map (string keys)."
  @spec from_map(map()) :: t()
  def from_map(m) do
    %__MODULE__{id: m["id"], ok: m["ok"], data: m["data"], reason: m["reason"]}
  end
end
defmodule Toolnexus.Tool do
  @moduledoc """
  The uniform tool: a named, described, JSON-Schema'd callable (SPEC §1).

  `execute` is `(args :: map, ctx :: Toolnexus.Context.t()) -> Toolnexus.ToolResult.t()`.
  `source` is one of `"mcp" | "skill" | "native" | "http" | "builtin" | "a2a" | "custom"`.
  """
  @enforce_keys [:name, :description, :input_schema, :source, :execute]
  defstruct [:name, :description, :input_schema, :source, :execute]

  @type t :: %__MODULE__{
          name: String.t(),
          description: String.t(),
          input_schema: map(),
          source: String.t(),
          execute: (map(), Toolnexus.Context.t() -> Toolnexus.ToolResult.t())
        }

  @doc "sanitize(x): replace every character outside [a-zA-Z0-9_-] with `_` (SPEC §0.2)."
  @spec sanitize(String.t()) :: String.t()
  def sanitize(name), do: String.replace(name, ~r/[^a-zA-Z0-9_-]/, "_")
end

defmodule Toolnexus.ToolResult do
  @moduledoc "Uniform tool result (SPEC §1). `metadata.pending` carrying a Request = suspension (§10)."
  defstruct output: "", is_error: false, metadata: nil

  @type t :: %__MODULE__{
          output: String.t(),
          is_error: boolean(),
          metadata: map() | nil
        }

  @doc "Convenience: an error result with the given text."
  @spec error(String.t()) :: t()
  def error(text), do: %__MODULE__{output: text, is_error: true}

  @doc "Convenience: a success result with the given text."
  @spec ok(String.t()) :: t()
  def ok(text), do: %__MODULE__{output: text, is_error: false}

  @doc "True when this result is a §10 suspension (metadata.pending is a Request)."
  @spec pending?(t()) :: boolean()
  def pending?(%__MODULE__{metadata: %{pending: %Toolnexus.Request{}}}), do: true
  def pending?(_), do: false
end

defmodule Toolnexus.Context do
  @moduledoc """
  Execution context passed to every tool (SPEC §1).

  `answer` is set only on the §10 re-execution after a `wait_for` resolved a suspension.
  """
  defstruct [:session_id, :message_id, :agent, :call_id, :extra, :answer, :timeout, :signal]

  @type t :: %__MODULE__{
          session_id: String.t() | nil,
          message_id: String.t() | nil,
          agent: String.t() | nil,
          call_id: String.t() | nil,
          extra: map() | nil,
          answer: Toolnexus.Answer.t() | nil,
          timeout: non_neg_integer() | nil,
          signal: reference() | pid() | nil
        }
end

