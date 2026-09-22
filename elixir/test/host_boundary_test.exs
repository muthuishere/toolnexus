defmodule Toolnexus.HostBoundaryTest do
  @moduledoc """
  The host boundary (ADR 0034, issues #100/#101/#102): which interpreter runs,
  what a relative path means, and what a timeout kills.

  Every assertion carries its control. The spike that produced these fixes twice
  reported a clean kill from a broken probe, so "no orphan" is evidence only next
  to a run proving the command can write the marker at all
  (spikes/builtin-host-boundary/SPIKE.md §1).
  """
  use ExUnit.Case, async: false

  alias Toolnexus.Builtin

  defp tool(cfg, name) do
    Enum.find(Builtin.tools(cfg), &(&1.name == name))
  end

  defp run(cfg, name, args), do: tool(cfg, name).execute.(args, nil)

  defp tmp_dir do
    dir = Path.join(System.tmp_dir!(), "tn-boundary-#{:erlang.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    dir
  end

  # The GRANDCHILD writes the marker, and `sleep 0.2` in front stops the shell
  # exec-optimising the single command away — the difference between measuring an
  # orphan and measuring nothing.
  defp orphan_command(marker), do: "sleep 0.2; sh -c 'sleep 1; touch #{marker}'"

  # ---------------------------------------------------------------------------
  # #102 — a timeout kills the job, not the shell
  # ---------------------------------------------------------------------------

  test "a timeout kills the whole job, not just the interpreter" do
    marker = Path.join(tmp_dir(), "orphan.marker")
    result = run(nil, "bash", %{"command" => orphan_command(marker), "timeout" => 300})

    assert result.is_error
    assert result.output =~ "timed out"
    assert result.metadata[:timedOut] == true
    assert result.metadata[:killedTree] == true

    Process.sleep(2000)
    refute File.exists?(marker), "the grandchild outlived the kill"
  end

  test "control — the probe command can actually write the marker" do
    marker = Path.join(tmp_dir(), "control.marker")
    result = run(nil, "bash", %{"command" => orphan_command(marker), "timeout" => 20_000})

    refute result.is_error, result.output
    assert File.exists?(marker),
           "the command cannot write the marker at all — the orphan test proves nothing"
  end

  test "killing the caller stops the job, because the kill does not live inside it" do
    # Measured (spikes/builtin-host-boundary/ports/elixir): Port.close does not
    # kill the OS process, Task.shutdown(:brutal_kill) kills nothing outside the
    # BEAM, and a kill written inside the task never runs when the task is what
    # is being cancelled. The port owner traps exits so it runs on the way out.
    marker = Path.join(tmp_dir(), "cancelled.marker")

    task =
      Task.async(fn ->
        run(nil, "bash", %{"command" => orphan_command(marker), "timeout" => 60_000})
      end)

    Process.sleep(300)
    Task.shutdown(task, :brutal_kill)

    Process.sleep(3500)
    refute File.exists?(marker), "cancelling the caller left the job running"
  end

  test "a timeout returns as soon as the job is gone, not after the whole grace window" do
    # The 2000 ms window bounds how long the KILL may take, not how long the
    # CALLER waits. This port used to sleep through it whether or not the job had
    # already died, turning a 300 ms timeout into a 2.3 s call — measured against
    # five other ports at ~1.0 s (spikes/builtin-host-boundary/stress/STRESS.md §3).
    marker = Path.join(tmp_dir(), "grace.marker")
    started = System.monotonic_time(:millisecond)
    result = run(nil, "bash", %{"command" => orphan_command(marker), "timeout" => 300})
    elapsed = System.monotonic_time(:millisecond) - started

    assert result.is_error
    assert elapsed < 1500, "a 300 ms timeout took #{elapsed} ms — the caller waited out the grace window"
  end

  test "a job that IGNORES the polite signal is killed after the grace window" do
    # The other half of the contract: TERM first, and only after the window is
    # the job killed. A shell that traps TERM cannot be asked to leave, so this
    # is the arm that exercises the forceful step — and it must still end with no
    # survivors.
    dir = tmp_dir()
    marker = Path.join(dir, "stubborn.marker")
    command = "trap '' TERM; sleep 0.2; sh -c 'sleep 5; touch #{marker}' & wait"

    started = System.monotonic_time(:millisecond)
    result = run(nil, "bash", %{"command" => command, "timeout" => 300})
    elapsed = System.monotonic_time(:millisecond) - started

    assert result.is_error
    assert result.metadata[:timedOut] == true
    # It took the grace window, because TERM was ignored — but not much more.
    assert elapsed >= 2000, "a TERM-ignoring job should have used the grace window, took #{elapsed} ms"
    assert elapsed < 4000, "the kill overran the grace window: #{elapsed} ms"

    Process.sleep(2000)
    refute File.exists?(marker), "the job survived the forceful kill"
  end

  test "a shell that does not resolve is reported, not spawned" do
    assert {:error, message} = Builtin.shell(%{"shell" => ["definitely-not-a-real-shell-xyz"]})
    assert message =~ "does not resolve"

    result = run(%{"shell" => ["definitely-not-a-real-shell-xyz"]}, "bash", %{"command" => "echo hi"})
    assert result.is_error
    assert result.output =~ "does not resolve"
  end

  test "confinement with no base_dir is a reported error, not a silent pass" do
    result = run(%{"confine_to_base_dir" => true}, "read", %{"path" => "anything.txt"})
    assert result.is_error
    assert result.output =~ "base_dir is empty"
  end

  # ---------------------------------------------------------------------------
  # #100 — the interpreter is chosen, and reported
  # ---------------------------------------------------------------------------

  test "the resolved interpreter is reported on every result" do
    result = run(nil, "bash", %{"command" => "echo hi"})
    assert is_binary(result.metadata[:shell])
    assert result.metadata[:shell] =~ "sh"
  end

  test "a host-supplied shell is used verbatim" do
    result = run(%{"shell" => ["/bin/sh", "-c"]}, "bash", %{"command" => "echo verbatim"})
    refute result.is_error, result.output
    assert result.output =~ "verbatim"
    assert result.metadata[:shell] == "/bin/sh -c"
  end

  test "shell/1 reports the detection, and bash can be disabled instead" do
    assert {:ok, [_ | _]} = Builtin.shell(nil)
    names = Builtin.load(%{"tools" => %{"bash" => false}}) |> Enum.map(& &1.name)
    refute "bash" in names
  end

  # ---------------------------------------------------------------------------
  # #101 — one base directory, and optional confinement
  # ---------------------------------------------------------------------------

  test "base_dir scopes relative paths" do
    base = tmp_dir()
    cfg = %{"base_dir" => base}

    result = run(cfg, "write", %{"path" => "sub/nested.txt", "content" => "landed"})
    refute result.is_error, result.output
    assert File.exists?(Path.join([base, "sub", "nested.txt"]))
    refute File.exists?(Path.join([File.cwd!(), "sub", "nested.txt"])),
           "relative write leaked into the process working directory"

    read = run(cfg, "read", %{"path" => "sub/nested.txt"})
    assert read.output == "landed"
  end

  test "an empty base_dir is today's behaviour" do
    target = Path.join(tmp_dir(), "absolute.txt")
    result = run(nil, "write", %{"path" => target, "content" => "x"})
    refute result.is_error, result.output
    assert File.exists?(target)
  end

  test "apply_patch resolves the paths inside the patch text" do
    base = tmp_dir()
    patch = "*** Begin Patch\n*** Add File: pkg/new.txt\n+hello\n*** End Patch"

    result = run(%{"base_dir" => base}, "apply_patch", %{"patchText" => patch})
    refute result.is_error, result.output
    assert File.exists?(Path.join([base, "pkg", "new.txt"])),
           "the path inside the patch text was not resolved"
  end

  test "bash defaults its workdir to base_dir" do
    base = tmp_dir()
    File.write!(Path.join(base, "marker.txt"), "x")

    result = run(%{"base_dir" => base}, "bash", %{"command" => "ls marker.txt"})
    refute result.is_error, result.output
    assert result.output =~ "marker.txt"
  end

  test "confinement refuses escapes and still serves what is inside" do
    root = tmp_dir()
    base = Path.join(root, "base")
    outside = Path.join(root, "outside")
    File.mkdir_p!(base)
    File.mkdir_p!(outside)
    File.write!(Path.join(outside, "secret.txt"), "secret")

    cfg = %{"base_dir" => base, "confine_to_base_dir" => true}

    for p <- ["../outside/secret.txt", Path.join(outside, "secret.txt")] do
      result = run(cfg, "read", %{"path" => p})
      assert result.is_error, "#{p} should be refused"
      assert result.output =~ "outside baseDir"
    end

    File.write!(Path.join(base, "ok.txt"), "fine")
    refute run(cfg, "read", %{"path" => "ok.txt"}).is_error,
           "a path inside base_dir must still be read"
  end

  test "confinement follows symlinks before deciding" do
    root = tmp_dir()
    base = Path.join(root, "base")
    outside = Path.join(root, "outside")
    File.mkdir_p!(base)
    File.mkdir_p!(outside)
    File.write!(Path.join(outside, "secret.txt"), "secret")
    File.ln_s!(outside, Path.join(base, "link"))

    cfg = %{"base_dir" => base, "confine_to_base_dir" => true}
    assert run(cfg, "read", %{"path" => "link/secret.txt"}).is_error,
           "a symlink out of base_dir must be refused"
  end

  test "confinement covers paths that do not exist yet" do
    cfg = %{"base_dir" => tmp_dir(), "confine_to_base_dir" => true}
    assert run(cfg, "write", %{"path" => "../escape.txt", "content" => "x"}).is_error
    refute run(cfg, "write", %{"path" => "deep/new/file.txt", "content" => "x"}).is_error
  end

  # ---------------------------------------------------------------------------
  # §4A — grep emits the string it sorted by
  # ---------------------------------------------------------------------------

  test "grep emits the /-separated walk-root-relative path" do
    base = tmp_dir()
    File.mkdir_p!(Path.join([base, "tree", "sub"]))
    File.write!(Path.join([base, "tree", "sub", "a.txt"]), "needle\n")

    result = run(%{"base_dir" => base}, "grep", %{"pattern" => "needle", "path" => "tree"})
    refute result.is_error, result.output
    assert result.output == "sub/a.txt:1:needle"
    refute result.output =~ "\\"
  end
end
