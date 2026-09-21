defmodule ClassifierSpike.MixProject do
  use Mix.Project

  # Throwaway spike (see ../SPIKE.md). Deps deliberately mirror the real port:
  # jason for JSON, req for HTTP. `toolnexus` is a path dep so gate 4 can run
  # against the SHIPPED first-deny-wins compiler, not a copy of it.
  def project do
    [
      app: :classifier_spike,
      version: "0.0.0",
      elixir: "~> 1.16",
      deps: [
        {:jason, "~> 1.4"},
        {:req, "~> 0.5"},
        {:toolnexus, path: "../../../elixir"}
      ]
    ]
  end

  def application, do: [extra_applications: [:logger]]
end
