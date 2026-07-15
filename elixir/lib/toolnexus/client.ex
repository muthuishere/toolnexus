defmodule Toolnexus.Client.ConversationStore do
  @moduledoc """
  Where `ask/4` conversations are remembered — two callbacks (SPEC §8 Conversation memory).

  A store is any struct whose module implements this behaviour; the client
  dispatches on the struct's module. Implement it for a file/db/redis provider
  to persist across processes.
  """
  @callback get(store :: struct(), id :: String.t()) :: [map()] | nil
  @callback save(store :: struct(), id :: String.t(), messages :: [map()]) :: any()
end

defmodule Toolnexus.Client.InMemoryConversationStore do
  @moduledoc "Default conversation provider — keeps transcripts in memory for the client's lifetime."
  @behaviour Toolnexus.Client.ConversationStore

  defstruct [:pid]

  @type t :: %__MODULE__{pid: pid()}

  def new do
    {:ok, pid} = Agent.start(fn -> %{} end)
    %__MODULE__{pid: pid}
  end

  @impl true
  def get(%__MODULE__{pid: pid}, id), do: Agent.get(pid, &Map.get(&1, id))

  @impl true
  def save(%__MODULE__{pid: pid}, id, messages),
    do: Agent.update(pid, &Map.put(&1, id, messages))
end

defmodule Toolnexus.Client.RunResult do
  @moduledoc "Full outcome + telemetry of one `run`/`ask` (SPEC §8, §10)."
  defstruct text: "",
            messages: [],
            tool_calls: [],
            tool_call_count: 0,
            turns: 0,
            usage: %{prompt_tokens: 0, completion_tokens: 0, total_tokens: 0},
            model: nil,
            status: "done",
            pending: nil

  @type t :: %__MODULE__{
          text: String.t(),
          messages: [map()],
          tool_calls: [map()],
          tool_call_count: non_neg_integer(),
          turns: non_neg_integer(),
          usage: %{prompt_tokens: integer(), completion_tokens: integer(), total_tokens: integer()},
          model: String.t() | nil,
          status: String.t(),
          pending: Toolnexus.Request.t() | nil
        }
end

defmodule Toolnexus.Client.MetricsRegistry do
  @moduledoc false
  # In-memory cumulative registry: turns the same semantic events `on_metric` sees into the
  # Prometheus counters/histograms of SPEC §8, and renders the text exposition format by hand.
  # The rendered text is BYTE-IDENTICAL across all ports.

  @duration_buckets [0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0]
  @bucket_labels ["0.05", "0.1", "0.25", "0.5", "1", "2.5", "5", "10", "30", "60"]

  def new do
    {:ok, pid} = Agent.start(fn -> initial() end)
    pid
  end

  defp initial do
    %{
      llm_requests: %{},
      llm_tokens: %{},
      llm_duration: %{},
      tool_calls: %{},
      tool_duration: %{},
      run_errors: %{}
    }
  end

  def record(pid, ev), do: Agent.update(pid, &do_record(&1, ev))

  def render(pid), do: Agent.get(pid, &do_render/1)

  defp do_record(st, %{event: "llm"} = ev) do
    st = %{st | llm_requests: inc(st.llm_requests, label_str([{"model", ev.model}, {"status", ev.status}]), 1)}

    st =
      if ev.status == "ok" do
        tokens =
          st.llm_tokens
          |> inc(label_str([{"type", "prompt"}]), ev.prompt_tokens)
          |> inc(label_str([{"type", "completion"}]), ev.completion_tokens)

        %{st | llm_tokens: tokens}
      else
        st
      end

    %{st | llm_duration: observe(st.llm_duration, label_str([{"model", ev.model}]), ev.ms / 1000)}
  end

  defp do_record(st, %{event: "tool"} = ev) do
    key =
      label_str([
        {"tool", ev.tool},
        {"source", ev.source},
        {"is_error", to_string(ev.is_error)},
        {"pending", to_string(Map.get(ev, :pending, false))}
      ])

    %{
      st
      | tool_calls: inc(st.tool_calls, key, 1),
        tool_duration: observe(st.tool_duration, label_str([{"tool", ev.tool}]), ev.ms / 1000)
    }
  end

  defp do_record(st, %{event: "run"} = ev) do
    if Map.get(ev, :error) do
      %{st | run_errors: inc(st.run_errors, label_str([{"model", ev.model}]), 1)}
    else
      st
    end
  end

  defp do_record(st, _), do: st

  defp inc(m, key, by), do: Map.update(m, key, by, &(&1 + by))

  defp observe(m, key, seconds) do
    h = Map.get(m, key, %{counts: List.duplicate(0, length(@duration_buckets)), sum: 0.0, count: 0})

    counts =
      @duration_buckets
      |> Enum.zip(h.counts)
      |> Enum.map(fn {bucket, c} -> if seconds <= bucket, do: c + 1, else: c end)

    Map.put(m, key, %{counts: counts, sum: h.sum + seconds, count: h.count + 1})
  end

  defp escape_label(v) do
    v
    |> String.replace("\\", "\\\\")
    |> String.replace("\"", "\\\"")
    |> String.replace("\n", "\\n")
  end

  defp label_str(pairs) do
    Enum.map_join(pairs, ",", fn {k, v} -> ~s(#{k}="#{escape_label(to_string(v))}") end)
  end

  defp do_render(st) do
    out =
      []
      |> render_counter("toolnexus_llm_requests_total", "Total LLM requests.", st.llm_requests)
      |> render_counter("toolnexus_llm_tokens_total", "Total tokens, by type.", st.llm_tokens)
      |> render_histogram("toolnexus_llm_request_duration_seconds", "LLM request duration in seconds.", st.llm_duration)
      |> render_counter("toolnexus_tool_calls_total", "Total tool calls.", st.tool_calls)
      |> render_histogram("toolnexus_tool_duration_seconds", "Tool execution duration in seconds.", st.tool_duration)
      |> render_counter("toolnexus_run_errors_total", "Total run errors.", st.run_errors)

    Enum.join(out, "\n") <> "\n"
  end

  defp render_counter(out, name, help, m) do
    out = out ++ ["# HELP #{name} #{help}", "# TYPE #{name} counter"]

    out ++
      for key <- Enum.sort(Map.keys(m)) do
        if key == "", do: "#{name} #{fmt(m[key])}", else: "#{name}{#{key}} #{fmt(m[key])}"
      end
  end

  defp render_histogram(out, name, help, m) do
    out = out ++ ["# HELP #{name} #{help}", "# TYPE #{name} histogram"]

    Enum.reduce(Enum.sort(Map.keys(m)), out, fn key, acc ->
      h = m[key]

      buckets =
        @bucket_labels
        |> Enum.zip(h.counts)
        |> Enum.map(fn {le, c} -> "#{name}_bucket{#{with_le(key, le)}} #{c}" end)

      acc ++
        buckets ++
        [
          "#{name}_bucket{#{with_le(key, "+Inf")}} #{h.count}",
          if(key == "", do: "#{name}_sum #{fmt(h.sum)}", else: "#{name}_sum{#{key}} #{fmt(h.sum)}"),
          if(key == "", do: "#{name}_count #{h.count}", else: "#{name}_count{#{key}} #{h.count}")
        ]
    end)
  end

  defp with_le("", le), do: ~s(le="#{le}")
  defp with_le(base, le), do: ~s(#{base},le="#{le}")

  # JS `String(n)`: integers render without a decimal point; other floats shortest-round-trip.
  defp fmt(v) when is_integer(v), do: Integer.to_string(v)

  defp fmt(v) when is_float(v) do
    t = trunc(v)
    if v == t, do: Integer.to_string(t), else: :erlang.float_to_binary(v, [:short])
  end
end

defmodule Toolnexus.Client do
  @moduledoc """
  Unified LLM client — the host loop (SPEC §8, §10).

  Give it a plain `base_url` + a `style` (`"openai"` | `"anthropic"`) and it runs the whole
  tool-calling agent loop against a toolkit — either a plain list of `%Toolnexus.Tool{}` or
  any struct/map with `:tools` and `:prompt` fields (the skills prompt).

      client = Toolnexus.Client.create(base_url: "...", style: "openai", model: "gpt-4o-mini")
      result = Toolnexus.Client.run(client, "add 2 and 3", tools)
  """

  require Logger

  alias Toolnexus.{Answer, Context, Request, Tool, ToolResult}
  alias Toolnexus.Client.{InMemoryConversationStore, MetricsRegistry, RunResult}

  @retryable [429, 500, 502, 503, 504]

  defstruct base_url: nil,
            style: "openai",
            model: nil,
            api_key: nil,
            headers: %{},
            system_prompt: nil,
            max_turns: 10,
            hooks: %{},
            retries: 2,
            retry_base_ms: 500,
            timeout_ms: nil,
            store: nil,
            on_metric: nil,
            wait_for: nil,
            request_params: nil,
            body_transform: nil,
            http_options: [],
            on_error: nil,
            registry: nil,
            deadline: nil

  @type t :: %__MODULE__{}

  @doc """
  Create a client. Options (SPEC §8 `ClientOptions`):

  - `:base_url`, `:style` (`"openai"` | `"anthropic"`), `:model`, `:api_key` (env fallback:
    `OPENROUTER_API_KEY` / `OPENAI_API_KEY` / `ANTHROPIC_API_KEY`), `:headers`
  - `:system_prompt` — prepended to the toolkit's skills prompt (joined with `"\\n\\n"`)
  - `:max_turns` (default 10)
  - `:on_error` — `(%{error?, status?, attempt, retryable} -> :retry | :fail)` classifies each
    failed LLM attempt (§8 Resilience). Absent ⇒ default (`retryable ⇒ :retry, else :fail`),
    byte-identical to today. A `:retry` is bounded by `:retries`. No `:suspend` tier.
  - `:hooks` — map with `:before_llm`, `:after_llm`, `:before_tool`, `:after_tool`
  - `:retries` (default 2), `:retry_base_ms` (default 500), `:timeout_ms` (whole-run deadline)
  - `:store` — a `Toolnexus.Client.ConversationStore` struct (default: in-memory)
  - `:on_metric` — `(event_map -> any)` semantic observability sink
  - `:wait_for` — §10 suspension resolver, `(Request -> Answer)`
  - `:request_params` — map shallow-merged into every LLM body AFTER base keys (caller wins;
    `messages`/`tools`/`stream` forbidden) — §8 Gap 1
  - `:body_transform` — `(body -> body)` run LAST after the merge — §8 Gap 1
  - `:http_options` — extra `Req` options for the LLM path (§8 Gap 2)
  """
  @spec create(keyword() | map()) :: t()
  def create(opts) do
    opts = if is_map(opts), do: Map.to_list(opts), else: opts

    client = struct!(__MODULE__, Keyword.take(opts, Map.keys(%__MODULE__{}) -- [:__struct__]))

    %{
      client
      | style: to_string(client.style),
        headers: client.headers || %{},
        hooks: client.hooks || %{},
        max_turns: client.max_turns || 10,
        retries: client.retries || 2,
        retry_base_ms: client.retry_base_ms || 500,
        http_options: client.http_options || [],
        store: client.store || InMemoryConversationStore.new(),
        registry: MetricsRegistry.new()
    }
  end

  @doc """
  §8 Gap 4. The client's conversation store — the exact instance passed in `:store`,
  else the default in-memory store the client created.
  """
  @spec conversation_store(t()) :: struct()
  def conversation_store(%__MODULE__{store: store}), do: store

  @doc "Prometheus text exposition of cumulative metrics (§8). Empty-but-valid before activity."
  @spec metrics(t()) :: String.t()
  def metrics(%__MODULE__{registry: registry}), do: MetricsRegistry.render(registry)

  @doc """
  Run the agent loop: system prompt → LLM call → execute tool calls (concurrently, results
  fed back in call order) → repeat to `:max_turns`. Returns a `%RunResult{}`.

  `opts`: `:history` — a prior transcript to continue.
  """
  @spec run(t(), String.t(), term(), keyword()) :: RunResult.t()
  def run(%__MODULE__{} = client, prompt, toolkit, opts \\ []) do
    client = arm_deadline(client)

    case client.style do
      "anthropic" -> run_anthropic(client, prompt, toolkit, opts[:history])
      _ -> run_openai(client, prompt, toolkit, opts[:history])
    end
  end

  @doc """
  Stateful ask. With an `:id` (or a bare conversation-id string as the 4th argument), the
  client's store remembers the conversation: load transcript → run → save. Without an id,
  a stateless one-shot identical to `run/4`. With `:on_text`, streams and forwards text
  deltas while still returning the final `%RunResult{}`.
  """
  @spec ask(t(), String.t(), term(), keyword() | String.t()) :: RunResult.t()
  def ask(client, prompt, toolkit, opts \\ [])

  def ask(%__MODULE__{} = client, prompt, toolkit, id) when is_binary(id),
    do: ask(client, prompt, toolkit, id: id)

  def ask(%__MODULE__{} = client, prompt, toolkit, opts) do
    id = opts[:id]
    on_text = opts[:on_text]

    cond do
      is_function(on_text, 1) ->
        client
        |> stream(prompt, toolkit, id: id)
        |> Enum.reduce(nil, fn
          %{type: "text", delta: d}, acc ->
            on_text.(d)
            acc

          %{type: "done", result: r}, _acc ->
            r

          _, acc ->
            acc
        end)

      id == nil ->
        run(client, prompt, toolkit)

      true ->
        history = store_get(client.store, id) || []
        result = run(client, prompt, toolkit, history: history)
        store_save(client.store, id, result.messages)
        result
    end
  end

  @doc """
  Streaming variant: returns an `Enumerable` of event maps —
  `%{type: "text", delta}`, `%{type: "tool_call", id, name, args}`,
  `%{type: "tool_result", id, name, output, is_error}`, `%{type: "usage", usage}`,
  `%{type: "pending", request}` (§10), `%{type: "done", result}`.

  With an `:id` it is stateful like `ask`: history is loaded before streaming and the
  transcript is saved back to the store on the terminal `done` event.
  """
  @spec stream(t(), String.t(), term(), keyword()) :: Enumerable.t()
  def stream(%__MODULE__{} = client, prompt, toolkit, opts \\ []) do
    Stream.resource(
      fn ->
        parent = self()
        ref = make_ref()

        {pid, mon} =
          spawn_monitor(fn ->
            emit = fn ev -> send(parent, {ref, :event, ev}) end

            try do
              do_stream(client, prompt, toolkit, opts, emit)
              send(parent, {ref, :halt})
            rescue
              e -> send(parent, {ref, :raise, e, __STACKTRACE__})
            end
          end)

        %{ref: ref, pid: pid, mon: mon}
      end,
      fn %{ref: ref, mon: mon} = st ->
        receive do
          {^ref, :event, ev} -> {[ev], st}
          {^ref, :halt} -> {:halt, st}
          {^ref, :raise, e, stack} -> reraise e, stack
          {:DOWN, ^mon, :process, _, :normal} -> {:halt, st}
          {:DOWN, ^mon, :process, _, reason} -> exit(reason)
        end
      end,
      fn %{pid: pid, mon: mon} ->
        Process.demonitor(mon, [:flush])
        if Process.alive?(pid), do: Process.exit(pid, :kill)
      end
    )
  end

  # ---- toolkit protocol: a plain list of tools, or anything with :tools / :prompt ----

  defp tools_of(tools) when is_list(tools), do: tools
  defp tools_of(%{tools: tools}) when is_list(tools), do: tools
  defp tools_of(_), do: []

  defp skills_prompt_of(tools) when is_list(tools), do: ""

  defp skills_prompt_of(%{prompt: p}) when is_binary(p), do: p
  defp skills_prompt_of(%{prompt: p}) when is_function(p, 0), do: p.() || ""
  defp skills_prompt_of(_), do: ""

  defp find_tool(toolkit, name), do: Enum.find(tools_of(toolkit), &(&1.name == name))

  defp to_openai_schema(%Tool{} = t) do
    %{
      "type" => "function",
      "function" => %{"name" => t.name, "description" => t.description, "parameters" => t.input_schema}
    }
  end

  defp to_anthropic_schema(%Tool{} = t) do
    %{"name" => t.name, "description" => t.description, "input_schema" => t.input_schema}
  end

  # system = system_prompt <> "\n\n" <> skills_prompt (empty parts filtered)
  defp system(client, toolkit) do
    [client.system_prompt || "", skills_prompt_of(toolkit)]
    |> Enum.filter(&(&1 != ""))
    |> Enum.join("\n\n")
  end

  # ---- request shaping (§8 Gap 1) ----

  defp finalize_body(client, body) do
    body =
      if client.request_params do
        Enum.reduce(client.request_params, body, fn {k, v}, acc ->
          k = to_string(k)

          if k in ["messages", "tools", "stream"] do
            Logger.warning(~s{[toolnexus] request_params key "#{k}" is not allowed — ignored (use body_transform)})
            acc
          else
            Map.put(acc, k, v)
          end
        end)
      else
        body
      end

    case client.body_transform do
      f when is_function(f, 1) ->
        case f.(body) do
          out when is_map(out) -> out
          _ -> body
        end

      _ ->
        body
    end
  end

  # §8 Gap 5: omit tools/tool_choice entirely when the effective tool list is empty.
  defp openai_body(client, messages, tools, stream) do
    body = %{"model" => client.model, "messages" => messages}

    body =
      if tools != [],
        do: body |> Map.put("tools", tools) |> Map.put("tool_choice", "auto"),
        else: body

    body =
      if stream,
        do: body |> Map.put("stream", true) |> Map.put("stream_options", %{"include_usage" => true}),
        else: body

    finalize_body(client, body)
  end

  defp anthropic_body(client, system, messages, tools, stream) do
    body = %{"model" => client.model, "max_tokens" => 4096, "system" => system, "messages" => messages}
    body = if tools != [], do: Map.put(body, "tools", tools), else: body
    body = if stream, do: Map.put(body, "stream", true), else: body
    finalize_body(client, body)
  end

  # ---- endpoints / headers / key ----

  defp openai_url(client), do: String.trim_trailing(client.base_url, "/") <> "/chat/completions"

  defp anthropic_url(client) do
    base = String.trim_trailing(client.base_url, "/")
    if String.ends_with?(base, "/v1"), do: base <> "/messages", else: base <> "/v1/messages"
  end

  defp headers_for(client, key) do
    base =
      case client.style do
        "anthropic" -> %{"x-api-key" => key, "anthropic-version" => "2023-06-01"}
        _ -> %{"authorization" => "Bearer " <> key}
      end

    Enum.reduce(client.headers || %{}, base, fn {k, v}, acc ->
      Map.put(acc, String.downcase(to_string(k)), v)
    end)
  end

  defp resolve_key(%{api_key: k}) when is_binary(k) and k != "", do: k

  defp resolve_key(client) do
    key =
      System.get_env("OPENROUTER_API_KEY") ||
        (if client.style == "anthropic",
           do: System.get_env("ANTHROPIC_API_KEY"),
           else: System.get_env("OPENAI_API_KEY")) ||
        System.get_env("OPENAI_API_KEY") || System.get_env("ANTHROPIC_API_KEY")

    key ||
      raise ArgumentError,
            "No API key: set api_key or OPENROUTER_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY"
  end

  # ---- observability ----

  defp now_ms, do: System.monotonic_time(:millisecond)

  defp emit(client, ev) do
    MetricsRegistry.record(client.registry, ev)
    if is_function(client.on_metric, 1), do: client.on_metric.(ev)
    :ok
  end

  defp emit_tool(client, name, source, %ToolResult{} = result, t0) do
    req = pending_of(result)

    ev = %{
      event: "tool",
      tool: name,
      source: source,
      is_error: if(req, do: false, else: result.is_error),
      ms: now_ms() - t0
    }

    emit(client, if(req, do: Map.put(ev, :pending, true), else: ev))
  end

  defp end_run(client, run_start, text, messages, tool_calls, turns, usage) do
    emit(client, %{
      event: "run",
      model: client.model,
      turns: turns,
      tool_calls: length(tool_calls),
      total_tokens: usage.total_tokens,
      ms: now_ms() - run_start
    })

    %RunResult{
      text: text,
      messages: messages,
      tool_calls: tool_calls,
      tool_call_count: length(tool_calls),
      turns: turns,
      usage: usage,
      model: client.model,
      status: "done"
    }
  end

  # §10: a run halted because a tool suspended and no wait_for was configured.
  defp pending_run(client, run_start, %Request{} = request, messages, tool_calls, turns, usage) do
    emit(client, %{
      event: "run",
      model: client.model,
      turns: turns,
      tool_calls: length(tool_calls),
      total_tokens: usage.total_tokens,
      ms: now_ms() - run_start
    })

    %RunResult{
      text: request.prompt,
      messages: messages,
      tool_calls: tool_calls,
      tool_call_count: length(tool_calls),
      turns: turns,
      usage: usage,
      model: client.model,
      status: "pending",
      pending: request
    }
  end

  defp emit_run_error(client, run_start, tool_calls, turns, usage, e) do
    emit(client, %{
      event: "run",
      model: client.model,
      turns: turns,
      tool_calls: length(tool_calls),
      total_tokens: usage.total_tokens,
      ms: now_ms() - run_start,
      error: Exception.message(e)
    })
  end

  # ---- usage ----

  defp zero_usage, do: %{prompt_tokens: 0, completion_tokens: 0, total_tokens: 0}

  defp add_usage(acc, nil, _style), do: acc

  defp add_usage(acc, raw, "anthropic") do
    input = raw["input_tokens"] || 0
    output = raw["output_tokens"] || 0

    %{
      prompt_tokens: acc.prompt_tokens + input,
      completion_tokens: acc.completion_tokens + output,
      total_tokens: acc.total_tokens + input + output
    }
  end

  defp add_usage(acc, raw, _openai) do
    p = raw["prompt_tokens"] || 0
    c = raw["completion_tokens"] || 0

    %{
      prompt_tokens: acc.prompt_tokens + p,
      completion_tokens: acc.completion_tokens + c,
      total_tokens: acc.total_tokens + (raw["total_tokens"] || p + c)
    }
  end

  defp per_call(nil, _style), do: {0, 0}
  defp per_call(raw, "anthropic"), do: {raw["input_tokens"] || 0, raw["output_tokens"] || 0}
  defp per_call(raw, _openai), do: {raw["prompt_tokens"] || 0, raw["completion_tokens"] || 0}

  # ---- HTTP with retry (429/5xx/network, exponential backoff + jitter, Retry-After) ----

  defp arm_deadline(%{timeout_ms: nil} = client), do: client
  defp arm_deadline(client), do: %{client | deadline: now_ms() + client.timeout_ms}

  defp check_deadline(%{deadline: nil}), do: :ok

  defp check_deadline(%{deadline: deadline} = client) do
    if now_ms() >= deadline, do: raise("run timeout after #{client.timeout_ms}ms"), else: :ok
  end

  defp llm_request(client, url, headers, body), do: llm_request(client, url, headers, body, 0)

  defp llm_request(client, url, headers, body, attempt) do
    check_deadline(client)

    base_opts = [method: :post, url: url, headers: headers, json: body, retry: false]

    base_opts =
      if client.deadline,
        do: Keyword.put(base_opts, :receive_timeout, max(client.deadline - now_ms(), 1)),
        else: base_opts

    opts = Keyword.merge(base_opts, client.http_options)

    case Req.request(opts) do
      {:ok, %Req.Response{status: status} = resp} when status in 200..299 ->
        {:ok, resp}

      {:ok, %Req.Response{status: status} = resp} ->
        retryable = status in @retryable
        tier = classify_error(client, %{status: status, attempt: attempt, retryable: retryable})

        if tier == :fail or attempt >= client.retries do
          {:ok, resp}
        else
          retry_after =
            case Req.Response.get_header(resp, "retry-after") do
              [v | _] -> parse_int(v)
              _ -> nil
            end

          sleep_ms =
            if retry_after,
              do: retry_after * 1000,
              else: client.retry_base_ms * Integer.pow(2, attempt) + :rand.uniform(100)

          Process.sleep(sleep_ms)
          llm_request(client, url, headers, body, attempt + 1)
        end

      {:error, e} ->
        tier = classify_error(client, %{error: e, attempt: attempt, retryable: true})

        if tier == :fail or attempt >= client.retries do
          raise e
        else
          Process.sleep(client.retry_base_ms * Integer.pow(2, attempt) + :rand.uniform(100))
          llm_request(client, url, headers, body, attempt + 1)
        end
    end
  end

  # §8 Resilience. Host-classified retry-vs-fail. Absent on_error ⇒ the default classifier
  # (retryable ⇒ retry, else fail), byte-identical to the prior hardcoded rule. No :suspend tier —
  # a failure never becomes a §10 Pending (suspension stays a user-action pause).
  defp classify_error(%{on_error: nil}, %{retryable: retryable}), do: if(retryable, do: :retry, else: :fail)
  defp classify_error(%{on_error: fun}, info) when is_function(fun, 1), do: fun.(info)

  defp parse_int(s) do
    case Integer.parse(to_string(s)) do
      {n, _} when n > 0 -> n
      _ -> nil
    end
  end

  defp error_text(body) when is_binary(body), do: body
  defp error_text(body), do: Jason.encode!(body)

  # One non-streaming LLM call, with an `llm` metric event (ok/error + per-call tokens + ms).
  defp llm_call_json(client, url, headers, body) do
    t0 = now_ms()

    try do
      case llm_request(client, url, headers, body) do
        {:ok, %Req.Response{status: status, body: data}} when status in 200..299 ->
          {p, c} = per_call(data["usage"], client.style)

          emit(client, %{
            event: "llm",
            model: client.model,
            status: "ok",
            ms: now_ms() - t0,
            prompt_tokens: p,
            completion_tokens: c
          })

          data

        {:ok, %Req.Response{status: status, body: data}} ->
          raise "LLM #{status}: #{error_text(data)}"
      end
    rescue
      e ->
        emit(client, %{
          event: "llm",
          model: client.model,
          status: "error",
          ms: now_ms() - t0,
          prompt_tokens: 0,
          completion_tokens: 0
        })

        reraise e, __STACKTRACE__
    end
  end

  # One streaming LLM call: returns the raw SSE body text (parsed by the caller).
  defp llm_call_sse(client, url, headers, body) do
    case llm_request(client, url, headers, body) do
      {:ok, %Req.Response{status: status, body: data}} when status in 200..299 ->
        if is_binary(data), do: data, else: error_text(data)

      {:ok, %Req.Response{status: status, body: data}} ->
        raise "LLM #{status}: #{error_text(data)}"
    end
  end

  # ---- hooks ----

  defp hook(client, name), do: Map.get(client.hooks || %{}, name)

  defp before_llm(client, messages, tools, turn) do
    case hook(client, :before_llm) do
      f when is_function(f, 1) ->
        case f.(%{messages: messages, tools: tools, model: client.model, turn: turn}) do
          %{} = ov -> {Map.get(ov, :messages) || messages, Map.get(ov, :tools) || tools}
          _ -> {messages, tools}
        end

      _ ->
        {messages, tools}
    end
  end

  defp after_llm(client, response, turn) do
    case hook(client, :after_llm) do
      f when is_function(f, 1) -> f.(%{response: response, model: client.model, turn: turn})
      _ -> :ok
    end
  end

  # ---- tool execution (§10-aware) ----

  defp pending_of(%ToolResult{metadata: %{pending: %Request{} = r}}), do: r
  defp pending_of(_), do: nil

  defp execute_tool(toolkit, name, args, %Context{} = ctx) do
    case find_tool(toolkit, name) do
      nil ->
        %ToolResult{output: "Tool not found: #{name}", is_error: true}

      %Tool{execute: f} ->
        try do
          case f.(args, ctx) do
            %ToolResult{} = r -> r
            other -> %ToolResult{output: to_string(other), is_error: false}
          end
        rescue
          e -> %ToolResult{output: Exception.message(e), is_error: true}
        end
    end
  end

  # Run one tool through before_tool/after_tool hooks (rewrite args, short-circuit, transform).
  defp run_tool(client, toolkit, name, args, id, turn) do
    source =
      case find_tool(toolkit, name) do
        %Tool{source: s} -> s
        nil -> "custom"
      end

    t0 = now_ms()

    {args, short} =
      case hook(client, :before_tool) do
        f when is_function(f, 1) ->
          case f.(%{name: name, args: args, id: id, turn: turn}) do
            %{result: %ToolResult{} = r} -> {args, r}
            %{args: %{} = a} -> {a, nil}
            _ -> {args, nil}
          end

        _ ->
          {args, nil}
      end

    if short do
      # short-circuit (deny/cache/dry-run, or a guard-raised suspension); still a tool call
      # for metrics, but a suspension is classified pending, never an error.
      emit_tool(client, name, source, short, t0)
      {args, short}
    else
      result = execute_tool(toolkit, name, args, %Context{call_id: id})

      # A suspension (§10) is not a real result: skip after_tool on it — the resolved result
      # (post-wait_for) still flows through after_tool in resolve_pending.
      result =
        case hook(client, :after_tool) do
          f when is_function(f, 1) ->
            if pending_of(result) do
              result
            else
              case f.(%{name: name, args: args, result: result, id: id, turn: turn}) do
                %{result: %ToolResult{} = r} -> r
                _ -> result
              end
            end

          _ ->
            result
        end

      emit_tool(client, name, source, result, t0)
      {args, result}
    end
  end

  # §10: resolve a suspended tool result. Returns {result, halted_request | nil}.
  defp resolve_pending(client, toolkit, name, args, %Request{} = request, id, turn) do
    case client.wait_for do
      nil ->
        {%ToolResult{output: request.prompt, is_error: true}, request}

      wait_for ->
        answer = wait_for.(request)

        if match?(%{ok: true}, answer) do
          t0 = now_ms()
          answer = as_answer(answer)
          result = execute_tool(toolkit, name, args, %Context{call_id: id, answer: answer})

          result =
            case hook(client, :after_tool) do
              f when is_function(f, 1) ->
                case f.(%{name: name, args: args, result: result, id: id, turn: turn}) do
                  %{result: %ToolResult{} = r} -> r
                  _ -> result
                end

              _ ->
                result
            end

          source =
            case find_tool(toolkit, name) do
              %Tool{source: s} -> s
              nil -> "custom"
            end

          emit_tool(client, name, source, result, t0)

          # never loop forever on the same request
          result =
            if pending_of(result),
              do: %ToolResult{output: "unresolved: #{request.prompt}", is_error: true},
              else: result

          {result, nil}
        else
          {%ToolResult{output: "declined/expired: #{request.prompt}", is_error: true}, nil}
        end
    end
  end

  defp as_answer(%Answer{} = a), do: a
  defp as_answer(%{ok: ok} = m), do: %Answer{id: Map.get(m, :id), ok: ok, data: Map.get(m, :data), reason: Map.get(m, :reason)}

  # Execute all tool calls of a turn concurrently; results come back in original call order.
  defp exec_calls(client, toolkit, calls, turn) do
    calls
    |> Task.async_stream(
      fn {call_id, name, args} ->
        {args, result} = run_tool(client, toolkit, name, args, call_id, turn)

        {result, halted} =
          case pending_of(result) do
            nil -> {result, nil}
            req -> resolve_pending(client, toolkit, name, args, req, call_id, turn)
          end

        %{id: call_id, name: name, args: args, result: result, halted: halted}
      end,
      ordered: true,
      timeout: :infinity,
      max_concurrency: max(length(calls), 1)
    )
    |> Enum.map(fn {:ok, s} -> s end)
  end

  defp tool_call_record(s) do
    %{
      name: s.name,
      args: s.args,
      output: s.result.output,
      is_error: s.result.is_error,
      metadata: s.result.metadata
    }
  end

  # ---- non-streaming: openai ----

  defp run_openai(client, prompt, toolkit, history) do
    key = resolve_key(client)
    run_start = now_ms()

    messages =
      if history && history != [] do
        history
      else
        sys = system(client, toolkit)
        if sys != "", do: [%{"role" => "system", "content" => sys}], else: []
      end

    messages = messages ++ [%{"role" => "user", "content" => prompt}]
    tools = Enum.map(tools_of(toolkit), &to_openai_schema/1)
    st = %{messages: messages, tools: tools, tool_calls: [], usage: zero_usage(), turns: 0}
    loop_openai(client, toolkit, key, run_start, st, 0)
  end

  defp loop_openai(client, _toolkit, _key, run_start, st, turn) when turn >= client.max_turns do
    end_run(client, run_start, last_text(st.messages), st.messages, st.tool_calls, st.turns, st.usage)
  end

  defp loop_openai(client, toolkit, key, run_start, st, turn) do
    st = %{st | turns: st.turns + 1}
    {messages, tools} = before_llm(client, st.messages, st.tools, turn)
    st = %{st | messages: messages, tools: tools}

    data =
      try do
        llm_call_json(client, openai_url(client), headers_for(client, key), openai_body(client, messages, tools, false))
      rescue
        e ->
          emit_run_error(client, run_start, st.tool_calls, st.turns, st.usage, e)
          reraise e, __STACKTRACE__
      end

    st = %{st | usage: add_usage(st.usage, data["usage"], client.style)}
    after_llm(client, data, turn)
    msg = data["choices"] |> List.first() |> Map.get("message")
    st = %{st | messages: st.messages ++ [msg]}
    calls = msg["tool_calls"] || []

    if calls == [] do
      end_run(client, run_start, msg["content"] || "", st.messages, st.tool_calls, st.turns, st.usage)
    else
      call_specs =
        Enum.map(calls, fn call ->
          {call["id"], call["function"]["name"], safe_json(call["function"]["arguments"])}
        end)

      settled = exec_calls(client, toolkit, call_specs, turn)

      # Record in tool-call order; on the FIRST durable halt, surface it and stop (G3) —
      # later suspensions' placeholder results never enter the transcript.
      case fold_openai_results(st, settled) do
        {:halted, st, request} ->
          pending_run(client, run_start, request, st.messages, st.tool_calls, st.turns, st.usage)

        {:ok, st} ->
          loop_openai(client, toolkit, key, run_start, st, turn + 1)
      end
    end
  end

  defp fold_openai_results(st, settled) do
    Enum.reduce_while(settled, {:ok, st}, fn s, {:ok, st} ->
      st = %{
        st
        | tool_calls: st.tool_calls ++ [tool_call_record(s)],
          messages: st.messages ++ [%{"role" => "tool", "tool_call_id" => s.id, "content" => s.result.output}]
      }

      if s.halted, do: {:halt, {:halted, st, s.halted}}, else: {:cont, {:ok, st}}
    end)
  end

  # ---- non-streaming: anthropic ----

  defp run_anthropic(client, prompt, toolkit, history) do
    key = resolve_key(client)
    run_start = now_ms()
    sys = system(client, toolkit)

    messages =
      if history && history != [],
        do: history ++ [%{"role" => "user", "content" => prompt}],
        else: [%{"role" => "user", "content" => prompt}]

    tools = Enum.map(tools_of(toolkit), &to_anthropic_schema/1)
    st = %{messages: messages, tools: tools, tool_calls: [], usage: zero_usage(), turns: 0}
    loop_anthropic(client, toolkit, key, sys, run_start, st, 0)
  end

  defp loop_anthropic(client, _toolkit, _key, _sys, run_start, st, turn) when turn >= client.max_turns do
    end_run(client, run_start, "", st.messages, st.tool_calls, st.turns, st.usage)
  end

  defp loop_anthropic(client, toolkit, key, sys, run_start, st, turn) do
    st = %{st | turns: st.turns + 1}
    {messages, tools} = before_llm(client, st.messages, st.tools, turn)
    st = %{st | messages: messages, tools: tools}

    data =
      try do
        llm_call_json(client, anthropic_url(client), headers_for(client, key), anthropic_body(client, sys, messages, tools, false))
      rescue
        e ->
          emit_run_error(client, run_start, st.tool_calls, st.turns, st.usage, e)
          reraise e, __STACKTRACE__
      end

    st = %{st | usage: add_usage(st.usage, data["usage"], client.style)}
    after_llm(client, data, turn)
    content = data["content"] || []
    st = %{st | messages: st.messages ++ [%{"role" => "assistant", "content" => content}]}
    uses = Enum.filter(content, &(&1["type"] == "tool_use"))

    if uses == [] do
      text = content |> Enum.filter(&(&1["type"] == "text")) |> Enum.map_join("", & &1["text"])
      end_run(client, run_start, text, st.messages, st.tool_calls, st.turns, st.usage)
    else
      call_specs = Enum.map(uses, fn use -> {use["id"], use["name"], use["input"] || %{}} end)
      settled = exec_calls(client, toolkit, call_specs, turn)

      # Record in tool-call order; on the FIRST durable halt include that tool_result block,
      # stop building the content, and surface it (G3).
      {blocks, records, halted} =
        Enum.reduce_while(settled, {[], [], nil}, fn s, {blocks, records, _} ->
          blocks =
            blocks ++
              [%{"type" => "tool_result", "tool_use_id" => s.id, "content" => s.result.output, "is_error" => s.result.is_error}]

          records = records ++ [tool_call_record(s)]
          if s.halted, do: {:halt, {blocks, records, s.halted}}, else: {:cont, {blocks, records, nil}}
        end)

      st = %{st | tool_calls: st.tool_calls ++ records, messages: st.messages ++ [%{"role" => "user", "content" => blocks}]}

      if halted do
        pending_run(client, run_start, halted, st.messages, st.tool_calls, st.turns, st.usage)
      else
        loop_anthropic(client, toolkit, key, sys, run_start, st, turn + 1)
      end
    end
  end

  # ---- streaming ----

  defp do_stream(client, prompt, toolkit, opts, emit) do
    client = arm_deadline(client)
    id = opts[:id]
    history = if id, do: store_get(client.store, id) || [], else: nil

    emit_ev = fn
      %{type: "done", result: result} = ev ->
        if id, do: store_save(client.store, id, result.messages)
        emit.(ev)

      ev ->
        emit.(ev)
    end

    case client.style do
      "anthropic" -> stream_anthropic(client, prompt, toolkit, history, emit_ev)
      _ -> stream_openai(client, prompt, toolkit, history, emit_ev)
    end
  end

  # ---- streaming: openai ----

  defp stream_openai(client, prompt, toolkit, history, emit) do
    key = resolve_key(client)
    run_start = now_ms()

    messages =
      if history && history != [] do
        history
      else
        sys = system(client, toolkit)
        if sys != "", do: [%{"role" => "system", "content" => sys}], else: []
      end

    messages = messages ++ [%{"role" => "user", "content" => prompt}]
    tools = Enum.map(tools_of(toolkit), &to_openai_schema/1)
    st = %{messages: messages, tools: tools, tool_calls: [], usage: zero_usage(), turns: 0}
    stream_loop_openai(client, toolkit, key, run_start, st, 0, emit)
  end

  defp stream_loop_openai(client, _toolkit, _key, run_start, st, turn, emit) when turn >= client.max_turns do
    emit.(%{type: "done", result: end_run(client, run_start, last_text(st.messages), st.messages, st.tool_calls, st.turns, st.usage)})
  end

  defp stream_loop_openai(client, toolkit, key, run_start, st, turn, emit) do
    st = %{st | turns: st.turns + 1}
    {messages, tools} = before_llm(client, st.messages, st.tools, turn)
    st = %{st | messages: messages, tools: tools}
    t0 = now_ms()
    %{prompt_tokens: before_p, completion_tokens: before_c} = st.usage

    {content, calls, usage} =
      try do
        sse = llm_call_sse(client, openai_url(client), headers_for(client, key), openai_body(client, messages, tools, true))
        parse_openai_sse(sse, st.usage, emit)
      rescue
        e ->
          emit(client, %{event: "llm", model: client.model, status: "error", ms: now_ms() - t0, prompt_tokens: 0, completion_tokens: 0})
          emit_run_error(client, run_start, st.tool_calls, st.turns, st.usage, e)
          reraise e, __STACKTRACE__
      end

    st = %{st | usage: usage}

    emit(client, %{
      event: "llm",
      model: client.model,
      status: "ok",
      ms: now_ms() - t0,
      prompt_tokens: usage.prompt_tokens - before_p,
      completion_tokens: usage.completion_tokens - before_c
    })

    after_llm(client, %{streamed: true, usage: usage}, turn)

    if calls == [] do
      st = %{st | messages: st.messages ++ [%{"role" => "assistant", "content" => content}]}
      emit.(%{type: "usage", usage: st.usage})
      emit.(%{type: "done", result: end_run(client, run_start, content, st.messages, st.tool_calls, st.turns, st.usage)})
    else
      assistant = %{
        "role" => "assistant",
        "content" => if(content == "", do: nil, else: content),
        "tool_calls" =>
          Enum.map(calls, fn c ->
            %{"id" => c.id, "type" => "function", "function" => %{"name" => c.name, "arguments" => c.args}}
          end)
      }

      st = %{st | messages: st.messages ++ [assistant]}
      Enum.each(calls, fn c -> emit.(%{type: "tool_call", id: c.id, name: c.name, args: safe_json(c.args)}) end)

      settled =
        calls
        |> Task.async_stream(
          fn c -> run_tool(client, toolkit, c.name, safe_json(c.args), c.id, turn) end,
          ordered: true,
          timeout: :infinity,
          max_concurrency: max(length(calls), 1)
        )
        |> Enum.map(fn {:ok, r} -> r end)

      outcome =
        calls
        |> Enum.zip(settled)
        |> Enum.reduce_while({:ok, st}, fn {c, {args, result}}, {:ok, st} ->
          {result, halted} =
            case pending_of(result) do
              nil ->
                {result, nil}

              req ->
                # surface BEFORE wait_for so a channel can push the link
                emit.(%{type: "pending", request: req})
                resolve_pending(client, toolkit, c.name, args, req, c.id, turn)
            end

          st = %{
            st
            | tool_calls: st.tool_calls ++ [%{name: c.name, args: args, output: result.output, is_error: result.is_error, metadata: result.metadata}],
              messages: st.messages ++ [%{"role" => "tool", "tool_call_id" => c.id, "content" => result.output}]
          }

          if halted do
            {:halt, {:halted, st, halted}}
          else
            emit.(%{type: "tool_result", id: c.id, name: c.name, output: result.output, is_error: result.is_error})
            {:cont, {:ok, st}}
          end
        end)

      case outcome do
        {:halted, st, request} ->
          emit.(%{type: "done", result: pending_run(client, run_start, request, st.messages, st.tool_calls, st.turns, st.usage)})

        {:ok, st} ->
          stream_loop_openai(client, toolkit, key, run_start, st, turn + 1, emit)
      end
    end
  end

  defp parse_openai_sse(body, usage, emit) do
    acc0 = %{content: "", slots: %{}, order: [], usage: usage}

    acc =
      body
      |> sse_lines()
      |> Enum.reduce_while(acc0, fn line, acc ->
        if String.starts_with?(line, "data:") do
          payload = line |> String.slice(5..-1//1) |> String.trim()

          if payload == "[DONE]" do
            {:halt, acc}
          else
            {:cont, apply_openai_chunk(acc, safe_parse(payload), emit)}
          end
        else
          {:cont, acc}
        end
      end)

    calls = Enum.map(acc.order, &acc.slots[&1])
    {acc.content, calls, acc.usage}
  end

  defp apply_openai_chunk(acc, nil, _emit), do: acc

  defp apply_openai_chunk(acc, j, emit) do
    acc = if j["usage"], do: %{acc | usage: add_usage(acc.usage, j["usage"], "openai")}, else: acc
    delta = j["choices"] |> List.wrap() |> List.first() |> then(&(&1 && &1["delta"]))

    acc =
      case delta && delta["content"] do
        d when is_binary(d) and d != "" ->
          emit.(%{type: "text", delta: d})
          %{acc | content: acc.content <> d}

        _ ->
          acc
      end

    Enum.reduce(List.wrap(delta && delta["tool_calls"]), acc, fn tc, acc ->
      index = tc["index"]
      slot = Map.get(acc.slots, index, %{id: "", name: "", args: ""})
      slot = if tc["id"], do: %{slot | id: tc["id"]}, else: slot
      f = tc["function"] || %{}
      slot = if f["name"], do: %{slot | name: slot.name <> f["name"]}, else: slot
      slot = if f["arguments"], do: %{slot | args: slot.args <> f["arguments"]}, else: slot
      order = if index in acc.order, do: acc.order, else: acc.order ++ [index]
      %{acc | slots: Map.put(acc.slots, index, slot), order: order}
    end)
  end

  # ---- streaming: anthropic ----

  defp stream_anthropic(client, prompt, toolkit, history, emit) do
    key = resolve_key(client)
    run_start = now_ms()
    sys = system(client, toolkit)

    messages =
      if history && history != [],
        do: history ++ [%{"role" => "user", "content" => prompt}],
        else: [%{"role" => "user", "content" => prompt}]

    tools = Enum.map(tools_of(toolkit), &to_anthropic_schema/1)
    st = %{messages: messages, tools: tools, tool_calls: [], usage: zero_usage(), turns: 0}
    stream_loop_anthropic(client, toolkit, key, sys, run_start, st, 0, emit)
  end

  defp stream_loop_anthropic(client, _toolkit, _key, _sys, run_start, st, turn, emit) when turn >= client.max_turns do
    emit.(%{type: "done", result: end_run(client, run_start, "", st.messages, st.tool_calls, st.turns, st.usage)})
  end

  defp stream_loop_anthropic(client, toolkit, key, sys, run_start, st, turn, emit) do
    st = %{st | turns: st.turns + 1}
    {messages, tools} = before_llm(client, st.messages, st.tools, turn)
    st = %{st | messages: messages, tools: tools}
    t0 = now_ms()
    %{prompt_tokens: before_p, completion_tokens: before_c} = st.usage

    {blocks, stop_reason, usage} =
      try do
        sse = llm_call_sse(client, anthropic_url(client), headers_for(client, key), anthropic_body(client, sys, messages, tools, true))
        parse_anthropic_sse(sse, st.usage, emit)
      rescue
        e ->
          emit(client, %{event: "llm", model: client.model, status: "error", ms: now_ms() - t0, prompt_tokens: 0, completion_tokens: 0})
          emit_run_error(client, run_start, st.tool_calls, st.turns, st.usage, e)
          reraise e, __STACKTRACE__
      end

    st = %{st | usage: usage}

    emit(client, %{
      event: "llm",
      model: client.model,
      status: "ok",
      ms: now_ms() - t0,
      prompt_tokens: usage.prompt_tokens - before_p,
      completion_tokens: usage.completion_tokens - before_c
    })

    after_llm(client, %{streamed: true, usage: usage}, turn)

    content =
      Enum.map(blocks, fn b ->
        if b.type == "tool_use" do
          %{"type" => "tool_use", "id" => b.id, "name" => b.name, "input" => safe_json(if(b.json == "", do: "{}", else: b.json))}
        else
          %{"type" => "text", "text" => b.text}
        end
      end)

    st = %{st | messages: st.messages ++ [%{"role" => "assistant", "content" => content}]}
    uses = Enum.filter(content, &(&1["type"] == "tool_use"))

    if stop_reason != "tool_use" or uses == [] do
      text = content |> Enum.filter(&(&1["type"] == "text")) |> Enum.map_join("", & &1["text"])
      emit.(%{type: "usage", usage: st.usage})
      emit.(%{type: "done", result: end_run(client, run_start, text, st.messages, st.tool_calls, st.turns, st.usage)})
    else
      Enum.each(uses, fn u -> emit.(%{type: "tool_call", id: u["id"], name: u["name"], args: u["input"]}) end)

      settled =
        uses
        |> Task.async_stream(
          fn u -> run_tool(client, toolkit, u["name"], u["input"] || %{}, u["id"], turn) end,
          ordered: true,
          timeout: :infinity,
          max_concurrency: max(length(uses), 1)
        )
        |> Enum.map(fn {:ok, r} -> r end)

      outcome =
        uses
        |> Enum.zip(settled)
        |> Enum.reduce_while({:ok, st, []}, fn {u, {args, result}}, {:ok, st, results} ->
          {result, halted} =
            case pending_of(result) do
              nil ->
                {result, nil}

              req ->
                emit.(%{type: "pending", request: req})
                resolve_pending(client, toolkit, u["name"], args, req, u["id"], turn)
            end

          st = %{st | tool_calls: st.tool_calls ++ [%{name: u["name"], args: args, output: result.output, is_error: result.is_error, metadata: result.metadata}]}
          results = results ++ [%{"type" => "tool_result", "tool_use_id" => u["id"], "content" => result.output, "is_error" => result.is_error}]

          if halted do
            {:halt, {:halted, %{st | messages: st.messages ++ [%{"role" => "user", "content" => results}]}, halted}}
          else
            emit.(%{type: "tool_result", id: u["id"], name: u["name"], output: result.output, is_error: result.is_error})
            {:cont, {:ok, st, results}}
          end
        end)

      case outcome do
        {:halted, st, request} ->
          emit.(%{type: "done", result: pending_run(client, run_start, request, st.messages, st.tool_calls, st.turns, st.usage)})

        {:ok, st, results} ->
          st = %{st | messages: st.messages ++ [%{"role" => "user", "content" => results}]}
          stream_loop_anthropic(client, toolkit, key, sys, run_start, st, turn + 1, emit)
      end
    end
  end

  defp parse_anthropic_sse(body, usage, emit) do
    acc0 = %{blocks: %{}, order: [], stop_reason: "", usage: usage}

    acc =
      body
      |> sse_lines()
      |> Enum.reduce(acc0, fn line, acc ->
        if String.starts_with?(line, "data:") do
          apply_anthropic_chunk(acc, safe_parse(line |> String.slice(5..-1//1) |> String.trim()), emit)
        else
          acc
        end
      end)

    {Enum.map(acc.order, &acc.blocks[&1]), acc.stop_reason, acc.usage}
  end

  defp apply_anthropic_chunk(acc, nil, _emit), do: acc

  defp apply_anthropic_chunk(acc, %{"type" => "message_start"} = j, _emit),
    do: %{acc | usage: add_usage(acc.usage, get_in(j, ["message", "usage"]), "anthropic")}

  defp apply_anthropic_chunk(acc, %{"type" => "content_block_start", "index" => i, "content_block" => cb}, _emit) do
    block = %{type: cb["type"], id: cb["id"], name: cb["name"], text: "", json: ""}
    %{acc | blocks: Map.put(acc.blocks, i, block), order: acc.order ++ [i]}
  end

  defp apply_anthropic_chunk(acc, %{"type" => "content_block_delta", "index" => i, "delta" => delta}, emit) do
    block = acc.blocks[i]

    block =
      case delta["type"] do
        "text_delta" ->
          emit.(%{type: "text", delta: delta["text"]})
          %{block | text: block.text <> delta["text"]}

        "input_json_delta" ->
          %{block | json: block.json <> delta["partial_json"]}

        _ ->
          block
      end

    %{acc | blocks: Map.put(acc.blocks, i, block)}
  end

  defp apply_anthropic_chunk(acc, %{"type" => "message_delta"} = j, _emit) do
    stop = get_in(j, ["delta", "stop_reason"]) || acc.stop_reason
    %{acc | stop_reason: stop, usage: add_usage(acc.usage, j["usage"], "anthropic")}
  end

  defp apply_anthropic_chunk(acc, _j, _emit), do: acc

  # ---- helpers ----

  defp sse_lines(body) do
    body
    |> String.split("\n")
    |> Enum.map(&String.trim_trailing(&1, "\r"))
  end

  defp safe_parse(s) do
    case Jason.decode(s) do
      {:ok, v} -> v
      _ -> nil
    end
  end

  defp safe_json(s) when is_map(s), do: s

  defp safe_json(s) do
    case Jason.decode(if(s in [nil, ""], do: "{}", else: s)) do
      {:ok, v} when is_map(v) -> v
      _ -> %{}
    end
  end

  defp last_text(messages) do
    messages
    |> Enum.reverse()
    |> Enum.find_value("", fn m ->
      if m["role"] == "assistant" and is_binary(m["content"]), do: m["content"], else: nil
    end)
  end

  defp store_get(%mod{} = store, id), do: mod.get(store, id)
  defp store_save(%mod{} = store, id, messages), do: mod.save(store, id, messages)
end
