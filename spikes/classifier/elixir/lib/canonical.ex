defmodule Spike.Canonical do
  @moduledoc """
  Canonical JSON: keys sorted recursively (ASCII/byte order), arrays left alone,
  compact separators, no trailing newline.

  Hand-rolled ON PURPOSE. Neither Jason nor OTP 27+'s built-in `JSON` sorts keys:
  both walk the map in Erlang term-iteration order. That order HAPPENS to be
  ASCII-sorted for maps of <= 32 keys (small flatmaps store keys in term order),
  and becomes arbitrary the moment a map crosses into hashmap representation.
  So the naive `Jason.encode!/1` passes the fixture and silently breaks on a
  33-key `probabilities` map. Only the scalar leaves are delegated to Jason,
  which owns string escaping and shortest-round-trip float formatting.
  """

  @spec encode(term()) :: binary()
  def encode(value), do: value |> iodata() |> IO.iodata_to_binary()

  defp iodata(map) when is_map(map) and not is_struct(map) do
    inner =
      map
      |> Enum.map(fn {k, v} -> {to_string(k), v} end)
      |> Enum.sort_by(fn {k, _} -> k end, :asc)
      |> Enum.map(fn {k, v} -> [Jason.encode!(k), ?:, iodata(v)] end)
      |> Enum.intersperse(?,)

    [?{, inner, ?}]
  end

  # Arrays are NEVER sorted — in a Score question the criteria order IS the
  # level numbering, so reordering would silently renumber the scale.
  defp iodata(list) when is_list(list) do
    [?[, list |> Enum.map(&iodata/1) |> Enum.intersperse(?,), ?]]
  end

  defp iodata(scalar), do: Jason.encode!(scalar)
end
