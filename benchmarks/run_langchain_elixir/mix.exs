defmodule RunLangchainElixir.MixProject do
  use Mix.Project

  # Runner for the Elixir LangChain competitor (brainlid/langchain on hex),
  # measured against the SAME mock LLM + SAME fixed scenario as the toolnexus
  # Elixir runner. LangChain Elixir has no MCP client, so it uses NATIVE
  # `LangChain.Function` tools with the same names/behavior (labelled "native"),
  # which is exactly what the toolnexus "native" variant uses — apples-to-apples.
  def project do
    [
      app: :run_langchain_elixir,
      version: "0.1.0",
      elixir: "~> 1.16",
      deps: deps()
    ]
  end

  def application, do: [extra_applications: [:logger, :inets]]

  defp deps, do: [{:langchain, "~> 0.9"}, {:jason, "~> 1.4"}, {:req, "~> 0.5"}]
end
