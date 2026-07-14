defmodule Toolnexus.MixProject do
  use Mix.Project

  @version "0.9.3"
  @source_url "https://github.com/muthuishere/toolnexus"

  def project do
    [
      app: :toolnexus,
      version: @version,
      elixir: "~> 1.16",
      start_permanent: Mix.env() == :prod,
      deps: deps(),
      description:
        "Your LLM, with MCP tools and agent skills built in — vendor-neutral, byte-identical across six languages.",
      package: package(),
      docs: docs(),
      test_coverage: [tool: ExCoveralls],
      preferred_cli_env: [
        coveralls: :test,
        "coveralls.html": :test,
        "coveralls.json": :test
      ],
      elixirc_paths: elixirc_paths(Mix.env())
    ]
  end

  def application do
    [extra_applications: [:logger, :inets, :ssl]]
  end

  defp elixirc_paths(:test), do: ["lib", "test/support"]
  defp elixirc_paths(_), do: ["lib"]

  defp deps do
    [
      {:jason, "~> 1.4"},
      {:yaml_elixir, "~> 2.9"},
      {:req, "~> 0.5"},
      {:plug, "~> 1.16"},
      {:bandit, "~> 1.5"},
      {:excoveralls, "~> 0.18", only: :test},
      {:ex_doc, "~> 0.34", only: :dev, runtime: false}
    ]
  end

  defp package do
    [
      name: "toolnexus",
      licenses: ["MIT"],
      links: %{"GitHub" => @source_url},
      files: ~w(lib mix.exs README.md)
    ]
  end

  defp docs do
    [main: "readme", extras: ["README.md"], source_url: @source_url]
  end
end
