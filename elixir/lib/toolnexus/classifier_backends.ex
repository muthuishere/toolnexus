# Classifier backends, images, cache and validation (SPEC.md §8B "Backends,
# images, cache and validation", OpenSpec change add-classifier-backends, ADR
# 0038). Every default here is a PROPOSED default, owner to confirm
# (openspec/changes/add-classifier-backends/design.md PD-1..PD-15).

defmodule Toolnexus.Classifier.Unsupported do
  @moduledoc """
  Returned BEFORE any network call when the backend's support table (or the
  `:supports` override) says it cannot take what the request carries. A typed
  error, not an ordinary string: an image outside the limits is the caller's
  input; THIS is a backend gap.

      classifier: <backend> (<model>) does not support <feature>
  """
  defexception backend: "typesafe", model: nil, feature: nil

  @impl true
  def message(%{backend: b, model: m, feature: f}),
    do: "classifier: #{b} (#{m}) does not support #{f}"
end

defimpl String.Chars, for: Toolnexus.Classifier.Unsupported do
  def to_string(e), do: Exception.message(e)
end

defmodule Toolnexus.Classifier.Raw do
  @moduledoc """
  The OPEN end of the question union (§8B): a System One question type the
  library does not model (e.g. Jev 1.14 `box`), sent as written —
  `{"criteria"?, "instructions", "type"}`. `:type` must be non-empty; no other
  client-side limits (the library does not know the type). `criteria: nil` omits it.
  """
  defstruct type: "", instructions: "", criteria: nil
  @type t :: %__MODULE__{type: String.t(), instructions: String.t(), criteria: term()}
end

defmodule Toolnexus.Classifier.RawAnswer do
  @moduledoc """
  An answer of a type the library does not model. `:value` is the whole answer
  object as decoded (including `"type"`).
  """
  defstruct type: nil, value: %{}
  @type t :: %__MODULE__{type: String.t(), value: map()}
end

defmodule Toolnexus.Classifier.BatchResult do
  @moduledoc "One state's outcome from `states(...) |> evaluate(qs)`: exactly one of `:decision` / `:error` is set."
  defstruct decision: nil, error: nil
end

defmodule Toolnexus.Classifier.State do
  @moduledoc "A bound state (`Classifier.state/2`). A VALUE, not a session: nothing is sent until evaluate."
  defstruct [:classifier, :value]
end

defmodule Toolnexus.Classifier.States do
  @moduledoc "A batch of bound states (`Classifier.states/2`)."
  defstruct classifier: nil, values: []
end

defmodule Toolnexus.Classifier.Thresholds do
  @moduledoc """
  jevx's INCLUSIVE cut-points (ADR 0038 T1), default `0.8 / 0.2 / 0.6`. They sit
  ALONGSIDE `Toolnexus.Judge` bands (exclusive), which stay the default of
  `ask`/`gate`.

  noul `p >= yes` ⇒ `:yes`, else `p <= no` ⇒ `:no`, else `:uncertain`;
  choice/score `confidence >= min_confidence` ⇒ `:yes`, else `:uncertain` (no
  near-uniform demotion — that is the bands' rule). A raw answer is `:uncertain`.
  """
  alias Toolnexus.Classifier.{ChoiceAnswer, NoulAnswer, ScoreAnswer}

  defstruct yes: 0.8, no: 0.2, min_confidence: 0.6
  @type t :: %__MODULE__{yes: number(), no: number(), min_confidence: number()}

  @doc "The default thresholds (0.8 / 0.2 / 0.6)."
  def default, do: %__MODULE__{}

  @doc "The band of one answer."
  @spec band(t(), struct()) :: :yes | :no | :uncertain
  def band(t \\ %__MODULE__{}, answer)

  def band(%__MODULE__{yes: y, no: n}, %NoulAnswer{noul: p}) when is_number(p) do
    cond do
      p >= y -> :yes
      p <= n -> :no
      true -> :uncertain
    end
  end

  def band(%__MODULE__{min_confidence: m}, %ChoiceAnswer{confidence: c}) when is_number(c),
    do: if(c >= m, do: :yes, else: :uncertain)

  def band(%__MODULE__{min_confidence: m}, %ScoreAnswer{confidence: c}) when is_number(c),
    do: if(c >= m, do: :yes, else: :uncertain)

  def band(%__MODULE__{}, _), do: :uncertain
end

defmodule Toolnexus.Classifier.MemoryCache do
  @moduledoc """
  In-process answer cache for the classifier's `:cache` option. Safe for
  concurrent use (one Agent). `ttl` in ms, `0` ⇒ no expiry; `max` entries, `0` ⇒
  unbounded; over `max` the oldest-INSERTED entries go first. toolnexus persists
  nothing itself: a host persists by passing its own `%{get:, set:}`.

      cache = Toolnexus.Classifier.MemoryCache.new(3_600_000, 1000)
      {:ok, c} = Toolnexus.Classifier.create(cache: cache)
  """
  defstruct [:pid, :get, :set]

  @doc "A new cache. Option `:now` (`-> ms`) injects the clock."
  def new(ttl \\ 0, max \\ 0, opts \\ []) do
    now = Keyword.get(opts, :now, fn -> System.monotonic_time(:millisecond) end)
    {:ok, pid} = Agent.start(fn -> %{m: %{}, seq: 0} end)

    %__MODULE__{
      pid: pid,
      get: fn k -> get(pid, k, ttl, now) end,
      set: fn k, v -> set(pid, k, v, max, now) end
    }
  end

  @doc "Look a key up (nil when absent or expired)."
  def get(%__MODULE__{get: g}, k), do: g.(k)

  @doc "Store a string value."
  def set(%__MODULE__{set: s}, k, v), do: s.(k, v)

  defp get(pid, k, ttl, now) do
    t = now.()

    Agent.get_and_update(pid, fn st ->
      case Map.fetch(st.m, k) do
        {:ok, {_v, at, _}} when ttl > 0 and t - at > ttl -> {nil, %{st | m: Map.delete(st.m, k)}}
        {:ok, {v, _, _}} -> {v, st}
        :error -> {nil, st}
      end
    end)
  end

  defp set(pid, k, v, max, now) do
    t = now.()

    Agent.update(pid, fn st ->
      seq = st.seq + 1
      m = Map.put(st.m, k, {v, t, seq})

      m =
        if max > 0 and map_size(m) > max do
          m
          |> Enum.sort_by(fn {_, {_, _, s}} -> s end)
          |> Enum.drop(map_size(m) - max)
          |> Map.new()
        else
          m
        end

      %{st | m: m, seq: seq}
    end)
  end
end

defmodule Toolnexus.Classifier.Images do
  @moduledoc false
  # The §8B lifting walk + client-side limits. A state with no ContentPart is
  # returned VERBATIM (the same term), so a text-only state is byte-identical to
  # before.
  alias Toolnexus.ContentPart

  @min_side 280
  @max_side 1568
  @max_bytes 16 * 1024 * 1024
  @request_bytes 32 * 1024 * 1024

  def limits,
    do: %{
      min_side: @min_side,
      max_side: @max_side,
      max_bytes: @max_bytes,
      request_bytes: @request_bytes
    }

  @spec lift(term()) ::
          {:ok, term(), [{String.t(), String.t(), String.t()}]} | {:error, String.t()}
  def lift(state) do
    if contains_part?(state) do
      try do
        {out, {_used, imgs}} = walk(state, nil, {MapSet.new(), []})
        {:ok, out, Enum.reverse(imgs)}
      catch
        {:lift_error, msg} -> {:error, msg}
      end
    else
      {:ok, state, []}
    end
  end

  defp contains_part?(%ContentPart{}), do: true

  defp contains_part?(m) when is_map(m) and not is_struct(m),
    do: Enum.any?(m, fn {_, v} -> contains_part?(v) end)

  defp contains_part?(l) when is_list(l), do: Enum.any?(l, &contains_part?/1)
  defp contains_part?(_), do: false

  defp walk(%ContentPart{} = p, key, {used, imgs} = acc) do
    case ContentPart.validate(p) do
      {:error, e} -> throw({:lift_error, "classifier: state: " <> Exception.message(e)})
      {:ok, _} -> :ok
    end

    case p.type do
      "text" ->
        {p.text, acc}

      "image" ->
        if p.data in [nil, ""],
          do:
            throw(
              {:lift_error,
               "classifier: state: an image part needs inline data (a URL image cannot be sent)"}
            )

        ref = if key == nil or MapSet.member?(used, key), do: next_ref(used, 1), else: key
        {ref, {MapSet.put(used, ref), [{ref, p.mime_type, p.data} | imgs]}}

      t ->
        throw({:lift_error, "classifier: state: #{t} parts are not supported"})
    end
  end

  defp walk(m, _key, acc) when is_map(m) and not is_struct(m) do
    m
    |> Enum.sort_by(fn {k, _} -> to_string(k) end)
    |> Enum.reduce({%{}, acc}, fn {k, v}, {out, acc} ->
      {v2, acc} = walk(v, to_string(k), acc)
      {Map.put(out, k, v2), acc}
    end)
  end

  defp walk(l, _key, acc) when is_list(l),
    do: Enum.map_reduce(l, acc, fn e, acc -> walk(e, nil, acc) end)

  defp walk(v, _key, acc), do: {v, acc}

  defp next_ref(used, n) do
    name = "image#{n}"
    if MapSet.member?(used, name), do: next_ref(used, n + 1), else: name
  end

  @formats %{"image/png" => "png", "image/jpeg" => "jpeg", "image/jpg" => "jpeg"}

  @doc false
  # Format, per-image and per-request limits, in collection order; normalises the
  # format. Errors name the reference.
  def check([]), do: {:ok, []}

  def check(imgs) do
    imgs
    |> Enum.reduce_while({:ok, [], 0}, fn {ref, mime, data}, {:ok, acc, total} ->
      case check_one(ref, mime, data) do
        {:ok, fmt, n} -> {:cont, {:ok, [{ref, fmt, data} | acc], total + n}}
        {:error, _} = e -> {:halt, e}
      end
    end)
    |> case do
      {:ok, [{last, _, _} | _], total} when total > @request_bytes ->
        {:error,
         "classifier: image #{inspect(last)}: request images total #{total} bytes, over 32 MiB"}

      {:ok, acc, _} ->
        {:ok, Enum.reverse(acc)}

      e ->
        e
    end
  end

  defp check_one(ref, mime, data) do
    p = "classifier: image #{inspect(ref)}: "

    with {:fmt, fmt} when is_binary(fmt) <- {:fmt, Map.get(@formats, mime)},
         {:b64, {:ok, raw}} <- {:b64, Base.decode64(data)},
         {:size, n} when n <= @max_bytes <- {:size, byte_size(raw)},
         {:dims, {:ok, w, h}} <- {:dims, size(fmt, raw)},
         true <- (w in @min_side..@max_side and h in @min_side..@max_side) || {:sides, w, h} do
      {:ok, fmt, n}
    else
      {:fmt, _} ->
        {:error, p <> "type #{inspect(mime)} is not supported (PNG or JPEG only)"}

      {:b64, _} ->
        {:error, p <> "data is not base64"}

      {:size, n} ->
        {:error, p <> "#{n} bytes, over 16 MiB"}

      {:dims, _} ->
        {:error, p <> "not a readable #{String.upcase(Map.get(@formats, mime))}"}

      {:sides, w, h} ->
        {:error, p <> "#{w}x#{h}; each side must be #{@min_side}..#{@max_side} px"}
    end
  end

  @doc false
  # Width/height from a PNG IHDR or the first JPEG SOF marker.
  def size("png", <<0x89, "PNG\r\n", 0x1A, "\n", _len::32, "IHDR", w::32, h::32, _::binary>>),
    do: {:ok, w, h}

  def size("jpeg", <<0xFF, 0xD8, rest::binary>>), do: jpeg(rest)
  def size(_, _), do: :error

  # fill byte
  defp jpeg(<<0xFF, 0xFF, rest::binary>>) when byte_size(rest) >= 2,
    do: jpeg(<<0xFF, rest::binary>>)

  defp jpeg(<<0xFF, m, _len::16, _p, h::16, w::16, _::binary>>)
       when m in 0xC0..0xCF and m not in [0xC4, 0xC8, 0xCC],
       do: {:ok, w, h}

  defp jpeg(<<0xFF, m, _::binary>>) when m in 0xC0..0xCF and m not in [0xC4, 0xC8, 0xCC],
    do: :error

  defp jpeg(<<0xFF, _m, len::16, rest::binary>>) when len >= 2 and byte_size(rest) >= len - 2 do
    jpeg(binary_part(rest, len - 2, byte_size(rest) - (len - 2)))
  end

  defp jpeg(_), do: :error
end

defmodule Toolnexus.Classifier.Validate do
  @moduledoc false
  # V1 fail-closed reply validation (§8B). First failure wins, in the spec order.
  alias Toolnexus.Classifier.{
    Choice,
    ChoiceAnswer,
    Noul,
    NoulAnswer,
    Raw,
    RawAnswer,
    Score,
    ScoreAnswer
  }

  def run(questions, %{answers: answers}) do
    qkeys = questions |> Map.keys() |> Enum.map(&to_string/1) |> Enum.sort()
    qs = Map.new(questions, fn {k, q} -> {to_string(k), q} end)

    with :ok <-
           first(qkeys, fn k ->
             if Map.has_key?(answers, k), do: :ok, else: bad(~s(no answer #{inspect(k)}))
           end),
         :ok <-
           first(answers |> Map.keys() |> Enum.sort(), fn k ->
             if Map.has_key?(qs, k), do: :ok, else: bad(~s(unexpected answer #{inspect(k)}))
           end) do
      first(qkeys, fn k -> check(k, qs[k], answers[k]) end)
    end
  end

  defp first(keys, f), do: Enum.find_value(keys, :ok, fn k -> if (r = f.(k)) != :ok, do: r end)

  defp bad(msg), do: {:error, "classifier: invalid decision: " <> msg}

  def qtype(%Noul{}), do: "noul"
  def qtype(%Choice{}), do: "choice"
  def qtype(%Score{}), do: "score"
  def qtype(%Raw{type: t}), do: t
  def qtype(_), do: ""

  def atype(%NoulAnswer{}), do: "noul"
  def atype(%ChoiceAnswer{}), do: "choice"
  def atype(%ScoreAnswer{}), do: "score"
  def atype(%RawAnswer{type: t}), do: t
  def atype(_), do: ""

  defp check(k, q, a) do
    {qt, at} = {qtype(q), atype(a)}

    if qt != at do
      bad(~s(answer #{inspect(k)} is a #{at} answer, not #{qt}))
    else
      check_typed(k, q, a)
    end
  end

  defp check_typed(k, _q, %NoulAnswer{noul: p}) do
    if in01?(p), do: :ok, else: bad(~s(answer #{inspect(k)}: noul #{num(p)} outside [0,1]))
  end

  defp check_typed(k, %Choice{criteria: crit}, %ChoiceAnswer{} = a) do
    offered = crit |> Map.keys() |> MapSet.new(&to_string/1)

    cond do
      not MapSet.member?(offered, a.choice) ->
        bad(~s(answer #{inspect(k)}: choice #{inspect(a.choice)} was not offered))

      (e = dist(k, a.probabilities, offered)) != :ok ->
        e

      not in01?(a.confidence) ->
        bad(~s(answer #{inspect(k)}: confidence #{num(a.confidence)} outside [0,1]))

      true ->
        :ok
    end
  end

  defp check_typed(k, %Score{criteria: levels}, %ScoreAnswer{} = a) do
    n = length(levels)
    offered = MapSet.new(0..(n - 1)//1, &Integer.to_string/1)
    s = a.score || 0

    cond do
      not is_number(s) or s < 0 or s > n - 1 ->
        bad(~s(answer #{inspect(k)}: score #{num(s)} outside [0,#{n - 1}]))

      (e = dist(k, a.probabilities, offered)) != :ok ->
        e

      not in01?(a.confidence) ->
        bad(~s(answer #{inspect(k)}: confidence #{num(a.confidence)} outside [0,1]))

      true ->
        :ok
    end
  end

  # A raw question matches only an answer of its own type and gets no further checks.
  defp check_typed(_k, _q, _a), do: :ok

  defp dist(k, probs, offered) do
    probs = probs || %{}

    probs
    |> Enum.sort_by(fn {o, _} -> o end)
    |> Enum.reduce_while(0.0, fn {o, p}, sum ->
      cond do
        not MapSet.member?(offered, o) ->
          {:halt,
           bad(~s(answer #{inspect(k)}: probability for #{inspect(o)}, which was not offered))}

        not in01?(p) ->
          {:halt, bad(~s(answer #{inspect(k)}: probability #{num(p)} outside [0,1]))}

        true ->
          {:cont, sum + p}
      end
    end)
    |> case do
      {:error, _} = e ->
        e

      sum when map_size(probs) > 0 and abs(sum - 1) > 0.02 ->
        bad(
          ~s(answer #{inspect(k)}: probabilities sum to #{:erlang.float_to_binary(sum * 1.0, decimals: 4)})
        )

      _ ->
        :ok
    end
  end

  # An absent number decodes as 0, as in every typed port.
  defp in01?(nil), do: true
  defp in01?(p), do: is_number(p) and p >= 0 and p <= 1

  defp num(p), do: to_string(p)
end
