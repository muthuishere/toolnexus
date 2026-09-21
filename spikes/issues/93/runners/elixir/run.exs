# Runner: print the Elixir port's skill inventory as JSON for the issue-93 harness.
# Run from the elixir/ port dir:  mix run <abs path to this file> <dirs...>
dirs = System.argv()
inv = Toolnexus.Skill.list(dirs)

esc = fn s -> s |> String.replace("\\", "\\\\") |> String.replace("\"", "\\\"") end

skills =
  inv.skills |> Enum.map(fn s -> "{\"location\":\"#{esc.(s.location)}\"}" end) |> Enum.join(",")

skipped =
  inv.skipped
  |> Enum.map(fn s -> "{\"location\":\"#{esc.(s.location)}\",\"reason\":\"#{s.reason}\"}" end)
  |> Enum.join(",")

IO.puts("{\"skills\":[#{skills}],\"skipped\":[#{skipped}]}")
