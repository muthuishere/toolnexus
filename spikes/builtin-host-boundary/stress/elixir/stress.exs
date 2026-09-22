##
## Stress harness for the SHIPPED Elixir builtins (Toolnexus.Builtin).
##
## Not a unit test: a deliberate attempt to produce orphans, leaks, deadlocks and
## wrong verdicts. Every scenario carries a CONTROL arm whose result is printed on
## the same line — an assertion that passes because the harness never ran measures
## nothing.
##
## Concurrency idiom: Task.async_stream (the port's own).
##

defmodule Stress do
  alias Toolnexus.Builtin

  @tmp Path.join(System.tmp_dir!(), "toolnexus-stress-elixir")

  # --- helpers ---------------------------------------------------------------

  def tool(tools, name), do: Enum.find(tools, &(&1.name == name))

  def call(t, args), do: t.execute.(args, nil)

  def par(list, fun, conc) do
    list
    |> Task.async_stream(fun, max_concurrency: conc, timeout: :infinity, ordered: true)
    |> Enum.map(fn {:ok, v} -> v end)
  end

  def meta(res, key), do: (res.metadata || %{}) |> Map.get(key)

  def secs(t0), do: Float.round((System.monotonic_time(:millisecond) - t0) / 1000, 1)

  def fresh_dir(name) do
    d = Path.join(@tmp, name)
    File.rm_rf!(d)
    File.mkdir_p!(d)
    d
  end

  # --- S1: concurrent timeouts ----------------------------------------------

  def s1(bash, opts \\ []) do
    label = Keyword.get(opts, :label, "S1")
    wait? = Keyword.get(opts, :wait, true)
    dir = fresh_dir("s1")
    t0 = System.monotonic_time(:millisecond)

    cmd = fn m -> "sleep 0.2; sh -c 'sleep 5; touch #{m}'" end

    res =
      par(1..30, fn i ->
        call(bash, %{"command" => cmd.(Path.join(dir, "m#{i}")), "timeout" => 300})
      end, 30)

    ctl_dir = fresh_dir("s1ctl")

    ctl =
      par(1..3, fn i ->
        call(bash, %{"command" => cmd.(Path.join(ctl_dir, "c#{i}")), "timeout" => 20_000})
      end, 3)

    if wait?, do: Process.sleep(7000)

    markers = length(Path.wildcard(Path.join(dir, "m*")))
    ctl_markers = length(Path.wildcard(Path.join(ctl_dir, "c*")))
    timed = Enum.count(res, &(meta(&1, :timedOut) == true))
    killed = Enum.count(res, &(meta(&1, :killedTree) == true))
    ctl_ok = Enum.count(ctl, &(&1.is_error == false))

    verdict =
      cond do
        not wait? -> "SKIP"
        ctl_markers != 3 or ctl_ok != 3 -> "INVALID"
        markers == 0 and timed == 30 and killed == 30 -> "PASS"
        true -> "FAIL"
      end

    IO.puts(
      "#{label} verdict=#{verdict} markers=#{markers} timedOut=#{timed}/30 killedTree=#{killed}/30 " <>
        "control_markers=#{ctl_markers}/3 control_ok=#{ctl_ok}/3 wall=#{secs(t0)}s"
    )

    verdict
  end

  # --- S2: mixed load --------------------------------------------------------

  def s2(bash, opts \\ []) do
    label = Keyword.get(opts, :label, "S2")
    t0 = System.monotonic_time(:millisecond)

    jobs =
      Enum.map(1..40, fn i ->
        if rem(i, 2) == 0,
          do: {:slow, i, %{"command" => "sleep 5", "timeout" => 300}},
          else: {:fast, i, %{"command" => "echo ok-#{i}", "timeout" => 20_000}}
      end)

    out = par(jobs, fn {k, i, a} -> {k, i, call(bash, a)} end, 40)

    fast = Enum.filter(out, &(elem(&1, 0) == :fast))
    slow = Enum.filter(out, &(elem(&1, 0) == :slow))

    fast_ok =
      Enum.count(fast, fn {_, i, r} ->
        r.is_error == false and String.trim(r.output) == "ok-#{i}" and meta(r, :exitCode) == 0
      end)

    slow_err = Enum.count(slow, fn {_, _, r} -> r.is_error and meta(r, :timedOut) == true end)
    crossed = Enum.count(fast, fn {_, i, r} -> String.contains?(r.output, "ok-") and String.trim(r.output) != "ok-#{i}" end)

    verdict =
      cond do
        fast_ok == 0 -> "INVALID"
        fast_ok == 20 and slow_err == 20 and crossed == 0 -> "PASS"
        true -> "FAIL"
      end

    IO.puts(
      "#{label} verdict=#{verdict} control_success=#{fast_ok}/20 timeouts_errored=#{slow_err}/20 " <>
        "crossed_results=#{crossed} wall=#{secs(t0)}s"
    )

    verdict
  end

  # --- S3: big output + timeout ---------------------------------------------

  def s3(bash, opts \\ []) do
    label = Keyword.get(opts, :label, "S3")
    big = ~s(head -c 5000000 /dev/zero | tr "\\0" "x")

    t0 = System.monotonic_time(:millisecond)
    r = call(bash, %{"command" => "sh -c '#{big}; sleep 10'", "timeout" => 1000})
    wall = secs(t0)

    t1 = System.monotonic_time(:millisecond)
    c = call(bash, %{"command" => "sh -c '#{big}'", "timeout" => 30_000})
    cwall = secs(t1)
    cbytes = byte_size(c.output)

    control_ok = c.is_error == false and cbytes >= 5_000_000

    verdict =
      cond do
        not control_ok -> "INVALID"
        r.is_error and wall <= 5.0 -> "PASS"
        true -> "FAIL"
      end

    IO.puts(
      "#{label} verdict=#{verdict} isError=#{r.is_error} timedOut=#{meta(r, :timedOut)} " <>
        "killedTree=#{meta(r, :killedTree)} bytes=#{byte_size(r.output)} wall=#{wall}s | " <>
        "control_ok=#{control_ok} control_bytes=#{cbytes} control_wall=#{cwall}s"
    )

    verdict
  end

  # --- S4: leaks -------------------------------------------------------------

  def snapshot do
    pid = System.pid()

    kids =
      case System.cmd("ps", ["-eo", "pid=,ppid="], stderr_to_stdout: true) do
        {out, 0} ->
          me = String.to_integer(pid)

          out
          |> String.split("\n", trim: true)
          |> Enum.count(fn l ->
            case String.split(String.trim(l), ~r/\s+/) do
              [_, pp] -> pp == Integer.to_string(me)
              _ -> false
            end
          end)

        _ ->
          -1
      end

    fds =
      case System.cmd("sh", ["-c", "lsof -p #{pid} 2>/dev/null | wc -l"]) do
        {o, 0} -> o |> String.trim() |> String.to_integer()
        _ -> -1
      end

    %{
      procs: :erlang.system_info(:process_count),
      ports: :erlang.system_info(:port_count),
      children: kids,
      fds: fds
    }
  end

  def fmt(s), do: "procs=#{s.procs} ports=#{s.ports} children=#{s.children} fds=#{s.fds}"

  def s4(bash) do
    t0 = System.monotonic_time(:millisecond)
    base = snapshot()
    IO.puts("S4 baseline #{fmt(base)}")

    snaps =
      Enum.map(1..3, fn i ->
        s1(bash, label: "  S4/round#{i}-S1", wait: false)
        s2(bash, label: "  S4/round#{i}-S2")
        s3(bash, label: "  S4/round#{i}-S3")
        :erlang.garbage_collect()
        Process.sleep(500)
        s = snapshot()
        IO.puts("  S4/round#{i} #{fmt(s)}")
        s
      end)

    all = [base | snaps]

    mono =
      for k <- [:procs, :ports, :children, :fds],
          vals = Enum.map(all, &Map.get(&1, k)),
          vals == Enum.sort(vals) and List.last(vals) > hd(vals),
          do: "#{k}(#{Enum.join(vals, "->")})"

    last = List.last(snaps)

    d = fn k -> Map.get(last, k) - Map.get(base, k) end

    verdict =
      cond do
        base.children < 0 or base.fds < 0 -> "INVALID"
        d.(:children) > 0 or d.(:ports) > 0 or mono != [] -> "FAIL"
        true -> "PASS"
      end

    IO.puts(
      "S4 verdict=#{verdict} d_procs=#{d.(:procs)} d_ports=#{d.(:ports)} d_children=#{d.(:children)} " <>
        "d_fds=#{d.(:fds)} monotonic=#{if mono == [], do: "none", else: Enum.join(mono, ",")} wall=#{secs(t0)}s"
    )

    verdict
  end

  # --- S5: confinement under load -------------------------------------------

  def s5_once(label) do
    t0 = System.monotonic_time(:millisecond)
    base = fresh_dir("s5base")
    outside = fresh_dir("s5outside")
    File.mkdir_p!(Path.join(base, "sub"))
    File.write!(Path.join(base, "a.txt"), "A")
    File.write!(Path.join(base, "sub/b.txt"), "B")
    File.write!(Path.join(base, "c.txt"), "C")
    File.write!(Path.join(outside, "secret.txt"), "S")
    link = Path.join(base, "link")
    File.rm(link)
    File.ln_s!(outside, link)

    tools = Builtin.load(%{"baseDir" => base, "confineToBaseDir" => true})
    read = tool(tools, "read")
    write = tool(tools, "write")

    legal = [{"a.txt", "A"}, {"sub/b.txt", "B"}, {"./c.txt", "C"}]

    escapes = [
      "../x",
      "sub/../../x",
      Path.join(outside, "abs.txt"),
      "link/through.txt",
      "....//x",
      "nul\0byte.txt"
    ]

    cases =
      Enum.flat_map(1..100, fn i ->
        Enum.map(legal, &{:legal, i, &1}) ++ Enum.map(escapes, &{:escape, i, &1})
      end)
      # 100 * (3 legal + 6 escapes) = 900; take a deterministic 500-attempt slice
      |> Enum.take(500)

    results = par(cases, fn
      {:legal, _i, {p, want}} ->
        r = call(read, %{"path" => p})
        {:legal, p, r.is_error == false and r.output == want}

      {:escape, i, p} ->
        r = call(write, %{"path" => p, "content" => "pwned#{i}"})
        {:escape, p, r.is_error == false}
    end, 32)

    legal_n = Enum.count(results, &(elem(&1, 0) == :legal))
    legal_refused = Enum.count(results, fn {k, _p, ok} -> k == :legal and not ok end)
    escape_n = Enum.count(results, &(elem(&1, 0) == :escape))
    accepted = Enum.count(results, fn {k, _p, ok} -> k == :escape and ok end)

    # The verdict is the BYTES, not the return value: an "accepted" write that
    # landed inside the base (`....//x` is a real directory named `....`) is not an
    # escape. Anything holding `pwned` outside the base subtree is.
    landed =
      Path.wildcard(Path.join(@tmp, "**/*"), match_dot: true)
      |> Enum.reject(&(&1 == base or String.starts_with?(&1, base <> "/")))
      |> Enum.filter(&File.regular?/1)
      |> Enum.count(fn f -> match?({:ok, "pwned" <> _}, File.read(f)) end)

    accepted_paths =
      results
      |> Enum.filter(fn {k, _p, ok} -> k == :escape and ok end)
      |> Enum.map(fn {_, p, _} -> p end)
      |> Enum.uniq()
      |> Enum.map(&inspect/1)
      |> Enum.join(",")

    verdict =
      cond do
        legal_n == 0 or escape_n == 0 -> "INVALID"
        landed == 0 and legal_refused == 0 -> "PASS"
        true -> "FAIL"
      end

    IO.puts(
      "#{label} verdict=#{verdict} attempts=#{length(results)} escapes_landed_outside=#{landed}/#{escape_n} " <>
        "write_calls_accepted=#{accepted} accepted_paths=[#{accepted_paths}] " <>
        "legal_refused=#{legal_refused}/#{legal_n} control_legal_ok=#{legal_n - legal_refused}/#{legal_n} " <>
        "wall=#{secs(t0)}s"
    )

    {verdict, {landed, accepted, legal_refused}}
  end

  def s5 do
    {v1, c1} = s5_once("S5/run1")
    {v2, c2} = s5_once("S5/run2")
    stable = v1 == v2 and c1 == c2
    IO.puts("S5 verdict=#{if stable and v1 == "PASS", do: "PASS", else: "FAIL"} run1=#{v1} run2=#{v2} deterministic=#{stable}")
    if stable and v1 == "PASS", do: "PASS", else: "FAIL"
  end

  # --- main ------------------------------------------------------------------

  def main do
    File.rm_rf!(@tmp)
    File.mkdir_p!(@tmp)
    t0 = System.monotonic_time(:millisecond)

    {:ok, shell} = Builtin.shell(nil)
    IO.puts("# elixir builtin stress · shell=#{Enum.join(shell, " ")} · os_pid=#{System.pid()}")

    tools = Builtin.load(nil)
    bash = tool(tools, "bash")

    v1 = s1(bash)
    v2 = s2(bash)
    v3 = s3(bash)
    v4 = s4(bash)
    v5 = s5()

    IO.puts("TOTAL verdicts S1=#{v1} S2=#{v2} S3=#{v3} S4=#{v4} S5=#{v5} wall=#{secs(t0)}s")
  end
end

Stress.main()
