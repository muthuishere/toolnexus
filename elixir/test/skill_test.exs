defmodule Toolnexus.SkillTest do
  @moduledoc """
  Skill source conformance (SPEC §3, §0.5–0.6) + host-facing extensions S1–S5.

  The fixture golden strings were generated from the authoritative JS port
  (`js/dist/skill.js` via `loadSkills("../examples/skills")`) and are asserted
  byte-exactly here, parameterized only by the absolute repo path.
  """
  use ExUnit.Case, async: true

  import ExUnit.CaptureIO

  alias Toolnexus.{Context, Skill, ToolResult}
  alias Toolnexus.Skill.{Info, Source}

  @examples_dir Path.expand("../../examples/skills", __DIR__)

  defp run(tool, args), do: tool.execute.(args, %Context{})

  defp write_skill!(dir, rel, frontmatter, body) do
    path = Path.join(dir, rel)
    File.mkdir_p!(Path.dirname(path))
    File.write!(path, "---\n#{frontmatter}\n---\n#{body}")
    path
  end

  # ── Conformance against the shared fixture (SPEC §0.5/§0.6) ────────────────

  describe "shared fixture (examples/skills)" do
    test "skill tool output is byte-exact" do
      src = Skill.load(@examples_dir)
      hw = Path.join(@examples_dir, "hello-world")

      expected =
        """
        <skill_content name="hello-world">
        # Skill: hello-world

        # Hello World Skill

        This is the skill body. When the model calls the `skill` tool with
        `{ "name": "hello-world" }`, everything below the frontmatter is injected into the
        conversation, along with a sampled list of files in this directory.

        ## Steps

        1. Read `scripts/greet.sh` in this skill's base directory.
        2. Run it with the user's name.
        3. Report the greeting back.

        Relative paths (like `scripts/greet.sh`) are resolved against the base directory
        printed in the tool output.

        Base directory for this skill: file://#{hw}
        Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.
        Note: file list is sampled.

        <skill_files>
        <file>#{hw}/scripts/greet.sh</file>
        </skill_files>
        </skill_content>
        """
        |> String.trim_trailing("\n")

      result = run(src.tool, %{"name" => "hello-world"})
      assert %ToolResult{is_error: false} = result
      assert result.output == expected
      assert result.metadata == %{name: "hello-world", dir: hw}
    end

    test "skills prompt is byte-exact" do
      src = Skill.load(@examples_dir)

      assert src.prompt ==
               "Skills provide specialized instructions and workflows for specific tasks.\n" <>
                 "Use the skill tool to load a skill when a task matches its description.\n" <>
                 "\n" <>
                 "## Available Skills\n" <>
                 "- **hello-world**: A tiny example skill. Use when you want to see how skill loading (progressive disclosure) works end to end."
    end

    test "unknown name yields the exact not-found error" do
      src = Skill.load(@examples_dir)
      result = run(src.tool, %{"name" => "nope"})
      assert %ToolResult{is_error: true} = result
      assert result.output == "Skill \"nope\" not found. Available skills: hello-world"
    end

    test "discovered skill info" do
      assert %Source{skills: [%Info{} = info]} = Skill.load(@examples_dir)
      assert info.name == "hello-world"
      assert info.description =~ "A tiny example skill."
      assert info.location == Path.join(@examples_dir, "hello-world/SKILL.md")
      assert info.origin == :fs
      assert info.content =~ "# Hello World Skill"
    end
  end

  # ── The `skill` tool shape ──────────────────────────────────────────────────

  describe "skill tool shape" do
    test "name, description, schema, source" do
      src = Skill.load(dirs: [])
      assert src.tool.name == "skill"
      assert src.tool.source == "skill"

      assert src.tool.description ==
               "Load a specialized skill when the task at hand matches one of the skills listed in the system prompt.\n" <>
                 "\n" <>
                 "Use this tool to inject the skill's instructions and resources into current conversation. The output may contain detailed workflow guidance as well as references to scripts, files, etc in the same directory as the skill.\n" <>
                 "\n" <>
                 "The skill name must match one of the skills listed in your system prompt."

      assert src.tool.input_schema == %{
               "type" => "object",
               "properties" => %{
                 "name" => %{"type" => "string", "description" => "The name of the skill to load"}
               },
               "required" => ["name"],
               "additionalProperties" => false
             }
    end

    test "no skills: not-found lists none; prompt says no skills" do
      src = Skill.load(dirs: [])
      result = run(src.tool, %{"name" => "x"})
      assert result.is_error
      assert result.output == "Skill \"x\" not found. Available skills: none"
      assert src.prompt == "No skills are currently available."
    end

    test "available skills in the not-found message are sorted" do
      src =
        Skill.load(
          skills: [
            %{name: "zeta", content: "z"},
            %{name: "alpha", content: "a"},
            %{name: "mid", content: "m"}
          ]
        )

      result = run(src.tool, %{"name" => "missing"})
      assert result.output == "Skill \"missing\" not found. Available skills: alpha, mid, zeta"
    end

    test "missing/atom name argument handled" do
      src = Skill.load(skills: [%{name: "a", content: "c"}])
      assert run(src.tool, %{}).output == "Skill \"\" not found. Available skills: a"
      refute run(src.tool, %{name: "a"}).is_error
    end
  end

  # ── Discovery: frontmatter edge cases ──────────────────────────────────────

  describe "frontmatter" do
    @describetag :tmp_dir

    test "missing name is skipped", %{tmp_dir: dir} do
      write_skill!(dir, "a/SKILL.md", "description: no name here", "body")
      src = Skill.load(dirs: [dir])
      assert src.skills == []
      assert %{skipped: [%{reason: "missing-name"}]} = Skill.list(dirs: [dir])
    end

    test "no frontmatter fences at all is skipped as missing-name", %{tmp_dir: dir} do
      path = Path.join(dir, "a/SKILL.md")
      File.mkdir_p!(Path.dirname(path))
      File.write!(path, "# Just a body\n")

      assert %{skills: [], skipped: [%{location: ^path, reason: "missing-name"}]} =
               Skill.list(dirs: [dir])
    end

    test "malformed YAML is skipped with malformed-frontmatter", %{tmp_dir: dir} do
      path = write_skill!(dir, "bad/SKILL.md", "name: \"unterminated", "body")
      write_skill!(dir, "good/SKILL.md", "name: good", "ok")

      src = Skill.load(dirs: [dir])
      assert Enum.map(src.skills, & &1.name) == ["good"]

      %{skipped: skipped} = Skill.list(dirs: [dir])

      assert %{location: ^path, reason: "malformed-frontmatter"} =
               Enum.find(skipped, &(&1.reason == "malformed-frontmatter"))
    end

    test "duplicate names: first wins, warning emitted, skip recorded", %{tmp_dir: dir} do
      write_skill!(dir, "a1/SKILL.md", "name: dup\ndescription: first", "FIRST")
      write_skill!(dir, "z2/SKILL.md", "name: dup\ndescription: second", "SECOND")

      {src, warnings} = with_io(:stderr, fn -> Skill.load(dirs: [dir]) end)

      assert warnings =~ "duplicate skill name \"dup\""
      # Same winner as the JS reference: sorted entries + LIFO stack means the
      # z2/ candidate is discovered first (verified against js/dist/skill.js).
      assert [%Info{name: "dup", content: "SECOND"}] = src.skills

      {%{skills: skills, skipped: skipped}, _} =
        with_io(:stderr, fn -> Skill.list(dirs: [dir]) end)

      assert length(skills) == 1
      assert [%{reason: "duplicate-name"}] = skipped
    end

    test "scalar values are coerced to trimmed strings; non-scalars dropped", %{tmp_dir: dir} do
      write_skill!(
        dir,
        "s/SKILL.md",
        "name: 123\ndescription: |\n  block line1\n  block line2\nextra:\n  - a list",
        "body"
      )

      assert [%Info{name: "123", description: "block line1\nblock line2"}] =
               Skill.load(dirs: [dir]).skills
    end

    test "non-scalar name is dropped, so the skill is skipped", %{tmp_dir: dir} do
      write_skill!(dir, "s/SKILL.md", "name:\n  - not-a-scalar", "body")
      assert %{skills: [], skipped: [%{reason: "missing-name"}]} = Skill.list(dirs: [dir])
    end

    test "boolean scalar coerces like the JS String()", %{tmp_dir: dir} do
      write_skill!(dir, "s/SKILL.md", "name: real-name\ndescription: true", "body")
      assert [%Info{description: "true"}] = Skill.load(dirs: [dir]).skills
    end

    test "unreadable SKILL.md is skipped as unreadable", %{tmp_dir: dir} do
      path = write_skill!(dir, "s/SKILL.md", "name: locked", "body")
      File.chmod!(path, 0o000)
      on_exit(fn -> File.chmod!(path, 0o644) end)

      assert %{skills: [], skipped: [%{location: ^path, reason: "unreadable"}]} =
               Skill.list(dirs: [dir])
    end

    test "nonexistent skills dir warns and yields nothing" do
      {src, warnings} = with_io(:stderr, fn -> Skill.load(dirs: ["/nonexistent/nowhere"]) end)
      assert warnings =~ "[toolnexus] skills dir not found: /nonexistent/nowhere"
      assert src.skills == []
      assert src.prompt == "No skills are currently available."
    end
  end

  # ── S1: skills as data / provider ──────────────────────────────────────────

  describe "S1 data + provider skills" do
    test "data skill with resources: logical base, resources verbatim" do
      src =
        Skill.load(
          skills: [
            %{
              name: "notes",
              description: "team notes",
              content: "Use the notes.\n",
              resources: ["guide.md", "templates/daily.md"]
            }
          ]
        )

      result = run(src.tool, %{"name" => "notes"})

      assert result.output ==
               """
               <skill_content name="notes">
               # Skill: notes

               Use the notes.

               Base directory for this skill: skill://notes/
               Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.
               Note: file list is sampled.

               <skill_files>
               <file>guide.md</file>
               <file>templates/daily.md</file>
               </skill_files>
               </skill_content>
               """
               |> String.trim_trailing("\n")

      assert result.metadata == %{name: "notes", dir: "skill://notes/"}
    end

    test "instruction-only data skill omits the <skill_files> block entirely" do
      src = Skill.load(skills: [%{name: "solo", content: "Just instructions."}])
      result = run(src.tool, %{"name" => "solo"})

      assert result.output ==
               """
               <skill_content name="solo">
               # Skill: solo

               Just instructions.

               Base directory for this skill: skill://solo/
               Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.
               </skill_content>
               """
               |> String.trim_trailing("\n")

      refute result.output =~ "Note: file list is sampled."
    end

    test "supplied logical base is honored (S4)" do
      src = Skill.load(skills: [%{name: "b", content: "c", base: "vault://kb/b/"}])
      out = run(src.tool, %{"name" => "b"}).output
      assert out =~ "Base directory for this skill: vault://kb/b/"

      assert [%Info{location: "vault://kb/b/", base: "vault://kb/b/", origin: :logical}] =
               src.skills
    end

    test "data skill with empty base falls back to skill://name/" do
      src = Skill.load(skills: [%{name: "e", content: "c", base: ""}])
      assert run(src.tool, %{"name" => "e"}).output =~ "Base directory for this skill: skill://e/"
    end

    test "data skill without a name is skipped as missing-name" do
      assert %{skills: [], skipped: [%{location: "skill://", reason: "missing-name"}]} =
               Skill.list(skills: [%{content: "orphan"}])

      assert %{skipped: [%{location: "custom://x/"}]} =
               Skill.list(skills: [%{content: "orphan", base: "custom://x/"}])
    end

    test "string-keyed data maps are accepted" do
      src = Skill.load(skills: [%{"name" => "sk", "description" => "d", "content" => "c"}])
      assert [%Info{name: "sk", description: "d"}] = src.skills
    end

    test "provider is resolved once and merged after data skills" do
      {src, warnings} =
        with_io(:stderr, fn ->
          Skill.load(
            skills: [%{name: "a", content: "from-data"}],
            provider: fn ->
              [%{name: "b", content: "from-provider"}, %{name: "a", content: "dupe"}]
            end
          )
        end)

      assert warnings =~ "duplicate skill name \"a\""

      assert Enum.map(src.skills, & &1.name) == ["a", "b"]
      assert run(src.tool, %{"name" => "a"}).output =~ "from-data"
    end

    test "a failing provider is isolated with a warning" do
      {src, warnings} =
        with_io(:stderr, fn ->
          Skill.load(
            skills: [%{name: "keep", content: "kept"}],
            provider: fn -> raise "boom" end
          )
        end)

      assert warnings =~ "[toolnexus] skill provider failed"
      assert Enum.map(src.skills, & &1.name) == ["keep"]
    end

    test "a provider returning a non-list is treated as a failure" do
      {src, warnings} =
        with_io(:stderr, fn ->
          Skill.load(skills: [%{name: "keep", content: "k"}], provider: fn -> :nope end)
        end)

      assert warnings =~ "skill provider failed"
      assert Enum.map(src.skills, & &1.name) == ["keep"]
    end

    @tag :tmp_dir
    test "directory candidates precede data candidates on dedupe", %{tmp_dir: dir} do
      write_skill!(dir, "hw/SKILL.md", "name: shared", "DISK")

      {src, _} =
        with_io(:stderr, fn ->
          Skill.load(dirs: [dir], skills: [%{name: "shared", content: "DATA"}])
        end)

      assert [%Info{origin: :fs}] = src.skills
      assert run(src.tool, %{"name" => "shared"}).output =~ "DISK"
    end
  end

  # ── S2: filter ──────────────────────────────────────────────────────────────

  describe "S2 filter" do
    defp three_skills do
      [
        %{name: "a", description: "da", content: "ca"},
        %{name: "b", description: "db", content: "cb"},
        %{name: "c", description: "dc", content: "cc"}
      ]
    end

    test "nil / empty filter exposes all" do
      assert length(Skill.load(skills: three_skills(), filter: nil).skills) == 3
      assert length(Skill.load(skills: three_skills(), filter: %{}).skills) == 3
    end

    test ">=1 true means allowlist: only true-mapped names survive" do
      src = Skill.load(skills: three_skills(), filter: %{"a" => true, "b" => false})
      assert Enum.map(src.skills, & &1.name) == ["a"]
      # prompt and tool lookup agree
      assert src.prompt =~ "- **a**: da"
      refute src.prompt =~ "**b**"
      assert run(src.tool, %{"name" => "b"}).is_error

      assert run(src.tool, %{"name" => "b"}).output ==
               "Skill \"b\" not found. Available skills: a"
    end

    test "only-false means drop-list over the all-on baseline" do
      src = Skill.load(skills: three_skills(), filter: %{"b" => false})
      assert Enum.map(src.skills, & &1.name) == ["a", "c"]
    end

    test "unknown filter names are ignored with a warning" do
      {src, warnings} =
        with_io(:stderr, fn ->
          Skill.load(skills: three_skills(), filter: %{"ghost" => false})
        end)

      assert warnings =~ "[toolnexus] skill filter name \"ghost\" matched no skill"
      assert length(src.skills) == 3
    end

    test "list/1 inventory is unfiltered" do
      inv = Skill.list(skills: three_skills(), filter: %{"a" => true})
      assert length(inv.skills) == 3
    end
  end

  # ── S3: list inventory ──────────────────────────────────────────────────────

  describe "S3 list inventory" do
    @describetag :tmp_dir

    test "returns skills plus all typed skips", %{tmp_dir: dir} do
      write_skill!(dir, "ok/SKILL.md", "name: ok-skill\ndescription: fine", "body")
      write_skill!(dir, "noname/SKILL.md", "description: nope", "body")
      write_skill!(dir, "bad/SKILL.md", "name: \"broken", "body")
      write_skill!(dir, "dup/SKILL.md", "name: ok-skill", "clone")

      {%{skills: skills, skipped: skipped}, _} =
        with_io(:stderr, fn -> Skill.list(dirs: [dir]) end)

      assert Enum.map(skills, & &1.name) == ["ok-skill"]
      reasons = skipped |> Enum.map(& &1.reason) |> Enum.sort()
      assert reasons == ["duplicate-name", "malformed-frontmatter", "missing-name"]
      assert Enum.all?(skipped, &String.ends_with?(&1.location, "SKILL.md"))
    end

    test "accepts a bare directory string and a list of roots", %{tmp_dir: dir} do
      write_skill!(dir, "one/SKILL.md", "name: one", "body")
      assert %{skills: [%Info{name: "one"}], skipped: []} = Skill.list(dir)
      assert %{skills: [%Info{name: "one"}]} = Skill.list([dir])
    end
  end

  # ── S5: sample cap ──────────────────────────────────────────────────────────

  describe "S5 sample cap" do
    @describetag :tmp_dir

    defp skill_with_siblings!(dir, n) do
      write_skill!(dir, "big/SKILL.md", "name: big", "body")

      for i <- 1..n,
          do: File.write!(Path.join(dir, "big/res#{String.pad_leading("#{i}", 2, "0")}.txt"), "x")

      Path.join(dir, "big")
    end

    defp file_lines(output) do
      ~r/<file>(.*?)<\/file>/ |> Regex.scan(output) |> Enum.map(fn [_, f] -> f end)
    end

    test "sample_limit 0 keeps the default cap of 10", %{tmp_dir: dir} do
      skill_with_siblings!(dir, 12)
      out = run(Skill.load(dirs: [dir]).tool, %{"name" => "big"}).output
      assert length(file_lines(out)) == 10
      assert out =~ "Note: file list is sampled."
    end

    test "sample_limit 3 caps at 3", %{tmp_dir: dir} do
      skill_dir = skill_with_siblings!(dir, 12)
      out = run(Skill.load(dirs: [dir], sample_limit: 3).tool, %{"name" => "big"}).output
      files = file_lines(out)
      assert length(files) == 3
      assert Enum.all?(files, &String.starts_with?(&1, skill_dir))
    end

    test "sample_limit -1 omits the <skill_files> block entirely", %{tmp_dir: dir} do
      skill_with_siblings!(dir, 2)
      out = run(Skill.load(dirs: [dir], sample_limit: -1).tool, %{"name" => "big"}).output
      refute out =~ "<skill_files>"
      refute out =~ "Note: file list is sampled."
      assert out =~ "Base directory for this skill: file://"

      assert String.ends_with?(
               out,
               "Relative paths in this skill (e.g., scripts/, reference/) are relative to this base directory.\n</skill_content>"
             )
    end

    test "sample_limit caps data-skill resources too", %{tmp_dir: _dir} do
      src =
        Skill.load(
          skills: [%{name: "d", content: "c", resources: ["r1", "r2", "r3"]}],
          sample_limit: 2
        )

      assert file_lines(run(src.tool, %{"name" => "d"}).output) == ["r1", "r2"]
    end

    test "sample_limit -1 omits resources for data skills too" do
      src = Skill.load(skills: [%{name: "d", content: "c", resources: ["r1"]}], sample_limit: -1)
      refute run(src.tool, %{"name" => "d"}).output =~ "<skill_files>"
    end

    test "fs skill with zero siblings still emits an empty <skill_files> block", %{tmp_dir: dir} do
      write_skill!(dir, "lonely/SKILL.md", "name: lonely", "body")
      out = run(Skill.load(dirs: [dir]).tool, %{"name" => "lonely"}).output
      assert out =~ "<skill_files>\n\n</skill_files>"
    end

    test "nested sibling files are sampled recursively", %{tmp_dir: dir} do
      write_skill!(dir, "deep/SKILL.md", "name: deep", "body")
      File.mkdir_p!(Path.join(dir, "deep/scripts/inner"))
      File.write!(Path.join(dir, "deep/scripts/inner/run.sh"), "#!/bin/sh")
      out = run(Skill.load(dirs: [dir]).tool, %{"name" => "deep"}).output
      assert file_lines(out) == [Path.join(dir, "deep/scripts/inner/run.sh")]
    end
  end

  # ── Symlinks ────────────────────────────────────────────────────────────────

  describe "symlinks" do
    @describetag :tmp_dir

    test "symlinked skill directories are followed", %{tmp_dir: dir} do
      external = Path.join(dir, "external")
      root = Path.join(dir, "root")
      write_skill!(external, "ext-skill/SKILL.md", "name: ext", "external body")
      File.mkdir_p!(root)
      File.ln_s!(Path.join(external, "ext-skill"), Path.join(root, "linked"))

      src = Skill.load(dirs: [root])
      assert [%Info{name: "ext"}] = src.skills
      assert run(src.tool, %{"name" => "ext"}).output =~ "external body"
    end

    test "symlinked SKILL.md files are discovered", %{tmp_dir: dir} do
      real = Path.join(dir, "real.md")
      File.write!(real, "---\nname: via-link\n---\nlinked body")
      root = Path.join(dir, "root/sk")
      File.mkdir_p!(root)
      File.ln_s!(real, Path.join(root, "SKILL.md"))

      assert [%Info{name: "via-link"}] = Skill.load(dirs: [Path.join(dir, "root")]).skills
    end

    test "symlink cycles terminate (cycle guard)", %{tmp_dir: dir} do
      root = Path.join(dir, "root")
      write_skill!(root, "SKILL.md", "name: cyclic", "body")
      sub = Path.join(root, "sub")
      File.mkdir_p!(sub)
      # sub/loop -> root: without the guard this traversal never ends.
      File.ln_s!(root, Path.join(sub, "loop"))

      {src, _warnings} = with_io(:stderr, fn -> Skill.load(dirs: [root]) end)
      assert Enum.map(src.skills, & &1.name) == ["cyclic"]

      # The one re-descent through the symlink re-finds SKILL.md → duplicate skip.
      {%{skipped: skipped}, _} = with_io(:stderr, fn -> Skill.list(dirs: [root]) end)
      assert Enum.all?(skipped, &(&1.reason == "duplicate-name"))
    end

    test "sibling sampling follows symlinks with the same cycle guard", %{tmp_dir: dir} do
      skill_dir = Path.join(dir, "sk")
      write_skill!(dir, "sk/SKILL.md", "name: sym-sample", "body")
      File.write!(Path.join(skill_dir, "a.txt"), "a")
      File.ln_s!(skill_dir, Path.join(skill_dir, "loop"))

      out = run(Skill.load(dirs: [dir]).tool, %{"name" => "sym-sample"}).output
      files = Regex.scan(~r/<file>(.*?)<\/file>/, out) |> Enum.map(fn [_, f] -> f end)
      # Terminates, and only real files (a.txt via at most two path spellings) appear.
      assert Enum.all?(files, &String.ends_with?(&1, "a.txt"))
      assert length(files) <= 10
    end
  end

  # ── node_modules / .git pruning ─────────────────────────────────────────────

  describe "pruned directories" do
    @describetag :tmp_dir

    test "skills under node_modules and .git are ignored", %{tmp_dir: dir} do
      write_skill!(dir, "node_modules/pkg/SKILL.md", "name: hidden-nm", "x")
      write_skill!(dir, ".git/hooks/SKILL.md", "name: hidden-git", "x")
      write_skill!(dir, "visible/SKILL.md", "name: visible", "x")

      assert Enum.map(Skill.load(dirs: [dir]).skills, & &1.name) == ["visible"]
    end

    test "sampling prunes node_modules and .git too", %{tmp_dir: dir} do
      write_skill!(dir, "sk/SKILL.md", "name: pruned", "x")
      File.mkdir_p!(Path.join(dir, "sk/node_modules"))
      File.write!(Path.join(dir, "sk/node_modules/dep.js"), "x")
      File.write!(Path.join(dir, "sk/real.txt"), "x")

      out = run(Skill.load(dirs: [dir]).tool, %{"name" => "pruned"}).output
      assert out =~ "real.txt"
      refute out =~ "dep.js"
    end
  end

  # ── Prompt catalog ──────────────────────────────────────────────────────────

  describe "skills prompt" do
    test "described-only, sorted by name" do
      src =
        Skill.load(
          skills: [
            %{name: "zeta", description: "last alphabetically", content: "z"},
            %{name: "alpha", description: "first alphabetically", content: "a"},
            %{name: "silent", content: "no description"}
          ]
        )

      assert src.prompt ==
               "Skills provide specialized instructions and workflows for specific tasks.\n" <>
                 "Use the skill tool to load a skill when a task matches its description.\n" <>
                 "\n" <>
                 "## Available Skills\n" <>
                 "- **alpha**: first alphabetically\n" <>
                 "- **zeta**: last alphabetically"
    end

    test "skills exist but none described: no-skills message, no preamble" do
      src = Skill.load(skills: [%{name: "quiet", content: "c"}])
      assert src.prompt == "No skills are currently available."
    end
  end

  # ── Edge cases ──────────────────────────────────────────────────────────────

  describe "edge cases" do
    @describetag :tmp_dir

    test "public constants are exposed" do
      assert Skill.skill_tool_description() =~ "Load a specialized skill"
      assert Skill.skills_prompt_preamble() =~ "Skills provide specialized instructions"
    end

    test "options as a plain map, with dirs as a single binary", %{tmp_dir: dir} do
      write_skill!(dir, "m/SKILL.md", "name: map-opts", "body")
      assert [%Info{name: "map-opts"}] = Skill.load(%{dirs: dir}).skills
    end

    test "frontmatter that parses to a non-map yields no data (missing-name)", %{tmp_dir: dir} do
      write_skill!(dir, "s/SKILL.md", "just a bare scalar", "body")
      assert %{skills: [], skipped: [%{reason: "missing-name"}]} = Skill.list(dirs: [dir])
    end

    test "unreadable subdirectories are skipped during walk and sampling", %{tmp_dir: dir} do
      write_skill!(dir, "sk/SKILL.md", "name: guarded", "body")
      locked = Path.join(dir, "sk/locked")
      File.mkdir_p!(locked)
      File.chmod!(locked, 0o000)
      on_exit(fn -> File.chmod!(locked, 0o755) end)
      File.write!(Path.join(dir, "sk/visible.txt"), "x")

      src = Skill.load(dirs: [dir])
      assert [%Info{name: "guarded"}] = src.skills
      out = run(src.tool, %{"name" => "guarded"}).output
      assert out =~ "visible.txt"
    end

    test "sampling stops with directories still on the stack once the cap is hit", %{tmp_dir: dir} do
      write_skill!(dir, "sk/SKILL.md", "name: capped", "body")
      File.mkdir_p!(Path.join(dir, "sk/asub"))
      File.write!(Path.join(dir, "sk/asub/never.txt"), "x")
      File.write!(Path.join(dir, "sk/b.txt"), "x")

      out = run(Skill.load(dirs: [dir], sample_limit: 1).tool, %{"name" => "capped"}).output
      assert file_lines(out) == [Path.join(dir, "sk/b.txt")]
    end

    test "broken symlinks are skipped", %{tmp_dir: dir} do
      write_skill!(dir, "sk/SKILL.md", "name: broken-link", "body")
      File.ln_s!(Path.join(dir, "sk/nothing-here"), Path.join(dir, "sk/dangling"))
      File.write!(Path.join(dir, "sk/real.txt"), "x")

      src = Skill.load(dirs: [dir])
      out = run(src.tool, %{"name" => "broken-link"}).output
      assert file_lines(out) == [Path.join(dir, "sk/real.txt")]
    end
  end

  # ── Multiple roots ──────────────────────────────────────────────────────────

  describe "multiple roots" do
    @describetag :tmp_dir

    test "all roots are globbed; first root wins duplicates", %{tmp_dir: dir} do
      r1 = Path.join(dir, "r1")
      r2 = Path.join(dir, "r2")
      write_skill!(r1, "a/SKILL.md", "name: shared\ndescription: from r1", "R1")
      write_skill!(r2, "a/SKILL.md", "name: shared\ndescription: from r2", "R2")
      write_skill!(r2, "b/SKILL.md", "name: only-r2", "B")

      {src, warnings} = with_io(:stderr, fn -> Skill.load(dirs: [r1, r2]) end)
      assert warnings =~ "duplicate skill name \"shared\""
      assert Enum.map(src.skills, & &1.name) |> Enum.sort() == ["only-r2", "shared"]
      assert run(src.tool, %{"name" => "shared"}).output =~ "R1"
    end
  end
end
