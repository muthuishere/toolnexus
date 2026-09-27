defmodule JudgeSpike.MixProject do
  use Mix.Project

  def project,
    do: [app: :judge_spike, version: "0.0.1", elixir: "~> 1.16", deps: [{:toolnexus, path: "../../../elixir"}]]

  def application, do: [extra_applications: [:logger]]
end
