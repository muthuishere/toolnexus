defmodule Mcp2026.MixProject do
  use Mix.Project

  def project do
    [
      app: :mcp2026,
      version: "0.0.1",
      elixir: "~> 1.18",
      start_permanent: Mix.env() == :prod,
      deps: deps()
    ]
  end

  def application, do: [extra_applications: [:logger, :inets, :ssl, :public_key, :crypto]]

  # HTTP server only. Client HTTP is OTP :httpc, JSON is Elixir's built-in JSON, crypto is :public_key.
  defp deps, do: [{:bandit, "~> 1.5"}, {:plug, "~> 1.16"}]
end
