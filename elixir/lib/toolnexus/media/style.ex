defmodule Toolnexus.Media.Style do
  @moduledoc """
  A loaded media style (SPEC §7G.7, ADR 0042) — the value `Toolnexus.Media.load_style/2`
  returns. It is itself a media client, so `Toolnexus.Media.create(style)` works.

  A style is either an HTTP style read from a JSON file (`style` holds the parsed map), or a
  built-in CLI preset (`cli` holds the preset name: `"xai-grok-cli"`, `"openai-codex-cli"`,
  `"google-agy-cli"`, or a `-mock` twin) backed by the Grok / Codex / Antigravity CLI client.
  `config` (§7G.4) configures the client behind the style. Verb support comes only from the
  style's own declaration.
  """
  @behaviour Toolnexus.Media.Client

  alias Toolnexus.Media.{Agy, Codex, Config, Grok, StyleClient}

  defstruct label: "style", cli: nil, style: nil, config: %Config{}

  @type t :: %__MODULE__{
          label: String.t(),
          cli: String.t() | nil,
          style: map() | nil,
          config: Config.t()
        }

  @presets %{
    "xai-grok-cli" => "grok",
    "openai-codex-cli" => "codex",
    "google-agy-cli" => "agy",
    "xai-grok-cli-mock" => "grok",
    "openai-codex-cli-mock" => "codex",
    "google-agy-cli-mock" => "agy"
  }

  @doc false
  def presets, do: @presets

  @doc "Load a preset name or a style JSON file (see `Toolnexus.Media.load_style/2`)."
  @spec load(String.t(), keyword() | map()) :: {:ok, t()} | {:error, Toolnexus.Media.Error.t()}
  def load(path_or_preset, config \\ []) do
    config = Config.new(config)

    case Map.fetch(@presets, path_or_preset) do
      {:ok, label} ->
        {:ok, %__MODULE__{label: label, cli: path_or_preset, config: config}}

      :error ->
        with {:ok, style} <- StyleClient.load_style(path_or_preset) do
          {:ok, %__MODULE__{label: style["name"] || "style", style: style, config: config}}
        end
    end
  end

  @doc "The media client behind a loaded style."
  @spec client(t()) :: Toolnexus.Media.Client.client()
  def client(%__MODULE__{cli: nil} = s), do: StyleClient.new!(s.style, s.config)

  def client(%__MODULE__{cli: cli, config: config}) do
    mock = String.ends_with?(cli, "-mock")

    case @presets[cli] do
      "grok" -> %Grok{config: config, mock: mock}
      "codex" -> %Codex{config: config, mock: mock}
      "agy" -> %Agy{config: config, mock: mock}
    end
  end

  defp call(s, fun, args) do
    c = client(s)
    apply(c.__struct__, fun, [c | args])
  end

  @impl true
  def name(s), do: call(s, :name, [])
  @impl true
  def supports?(s, verb), do: call(s, :supports?, [verb])
  @impl true
  def args(s, verb), do: Toolnexus.Media.client_args(client(s), verb)

  for f <- [:image, :video, :speech, :music, :transcribe, :clone_voice, :talk] do
    @impl true
    def unquote(f)(s, args, ctx) do
      c = client(s)
      mod = c.__struct__

      if function_exported?(mod, unquote(f), 3),
        do: apply(mod, unquote(f), [c, args, ctx]),
        else:
          {:error, Toolnexus.Media.Error.failed("#{unquote(f)}: not implemented by #{name(s)}")}
    end
  end

  @mock_env "TOOLNEXUS_MEDIA_MOCK_AVAILABLE"
  @env_var ~r/\$\{([A-Za-z_][A-Za-z0-9_]*)\}/

  @doc """
  `style.available()` (§7G.10b): `%{installed, logged_in, reason}`, fast, generating nothing
  and reading no secret. A CLI preset is installed when its command (config `command`'s
  first element, else the CLI name) is found, and logged in when the CLI's own login file
  exists (never read). A mock preset is available unless `TOOLNEXUS_MEDIA_MOCK_AVAILABLE=0`.
  An HTTP style is logged in when config `key` is set or every `${VAR}` in its headers is.
  """
  @spec available(t()) :: %{installed: boolean(), logged_in: boolean(), reason: String.t()}
  def available(%__MODULE__{cli: nil} = s) do
    id = s.label || "style"

    missing =
      (s.style["headers"] || %{})
      |> Map.values()
      |> Enum.flat_map(&Regex.scan(@env_var, &1, capture: :all_but_first))
      |> List.flatten()
      |> Enum.uniq()
      |> Enum.sort()
      |> Enum.find(&(System.get_env(&1) in [nil, ""]))

    cond do
      s.config.key not in [nil, ""] -> avail(true, true, id <> ": key from config")
      missing -> avail(true, false, "#{id}: #{missing} is not set")
      true -> avail(true, true, id <> ": env present")
    end
  end

  def available(%__MODULE__{cli: cli, label: label, config: config}) do
    cond do
      String.ends_with?(cli, "-mock") and System.get_env(@mock_env) == "0" ->
        avail(false, false, "#{label}: mock set unavailable by #{@mock_env}=0")

      String.ends_with?(cli, "-mock") ->
        avail(true, true, label <> ": mock")

      true ->
        bin =
          case config.command do
            [c | _] -> to_string(c)
            c when is_binary(c) -> c
            _ -> label
          end

        auth = auth_file(label)

        cond do
          not found?(bin) ->
            avail(false, false, ~s(#{label}: "#{bin}" not found on PATH))

          File.regular?(auth) ->
            avail(true, true, "#{label}: installed, logged in (#{auth} present)")

          true ->
            avail(true, false, "#{label}: installed, not logged in (#{auth} missing)")
        end
    end
  end

  defp avail(i, l, r), do: %{installed: i, logged_in: l, reason: r}

  defp found?(bin) do
    if String.contains?(bin, "/") do
      case File.stat(bin) do
        {:ok, %File.Stat{type: :regular, mode: m}} -> Bitwise.band(m, 0o111) != 0
        _ -> false
      end
    else
      System.find_executable(bin) != nil
    end
  end

  # Same as Go os.UserHomeDir on unix: $HOME.
  defp home, do: System.get_env("HOME") || System.user_home!()

  defp auth_file("grok"), do: Path.join([home(), ".grok", "auth.json"])

  defp auth_file("codex") do
    case System.get_env("CODEX_HOME") do
      h when is_binary(h) and h != "" -> Path.join(h, "auth.json")
      _ -> Path.join([home(), ".codex", "auth.json"])
    end
  end

  defp auth_file("agy"),
    do: Path.join([home(), ".gemini", "antigravity-cli", "antigravity-oauth-token"])
end
