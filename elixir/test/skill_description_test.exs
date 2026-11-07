defmodule Toolnexus.SkillDescriptionTest do
  @moduledoc "SPEC §3 description length guard (#84) against conformance/skill_description."
  use ExUnit.Case, async: true

  import ExUnit.CaptureIO

  alias Toolnexus.Skill

  @dir Path.expand("../../conformance/skill_description", __DIR__)

  defp cases, do: @dir |> Path.join("cases.json") |> File.read!() |> Jason.decode!()

  defp assert_warnings(warnings, expected) do
    assert length(warnings) == length(expected)

    Enum.zip(warnings, expected)
    |> Enum.each(fn {w, e} ->
      assert String.ends_with?(w.location, e["location_suffix"])
      assert w.reason == e["reason"]
      assert w.chars == e["chars"]
    end)
  end

  test "loader and inventory warn on oversized descriptions, never modify them" do
    c = cases()
    skills_dir = Path.join(@dir, "skills")

    err =
      capture_io(:stderr, fn ->
        src = Skill.load(skills_dir)
        assert_warnings(src.warnings, c["expect_warnings"])

        for {_name, desc} <- c["full_descriptions"] do
          assert String.contains?(src.prompt, desc)
        end

        for info <- src.skills do
          assert info.description == c["full_descriptions"][info.name]
        end

        inv = Skill.list(skills_dir)
        assert_warnings(inv.warnings, c["expect_warnings"])
      end)

    assert err =~ "long-desc/SKILL.md"
    refute err =~ "at-limit"
  end

  test "warnings follow the allowlist on load but not the inventory" do
    skills_dir = Path.join(@dir, "skills")

    capture_io(:stderr, fn ->
      assert Skill.load(dirs: [skills_dir], filter: %{"long-desc" => false}).warnings == []
      assert length(Skill.list(dirs: [skills_dir], filter: %{"long-desc" => false}).warnings) == 1
    end)
  end

  test "absent description never warns" do
    capture_io(:stderr, fn ->
      assert Skill.list(skills: [%{name: "n", content: "c"}]).warnings == []
    end)
  end
end
