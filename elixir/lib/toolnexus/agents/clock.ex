defmodule Toolnexus.Agents.Clock do
  @moduledoc """
  The runtime's injectable clock (SPEC §7D — lifecycle & runtime obligations).

  Every timer, timeout, and deadline the agent runtime arms goes through one clock
  value so fixtures can run on a virtual clock. A clock is a plain map:

    * `:now` — `(-> integer)` monotonic milliseconds
    * `:send_after` — `(dest :: pid(), msg :: term(), ms :: non_neg_integer() -> term())`

  `real/0` is the default (the BEAM's monotonic clock + `Process.send_after/3`).
  A virtual clock supplies its own `now`/`send_after` and delivers armed messages
  when the test advances time — determinism without sleeping.
  """

  @type t :: %{now: (-> integer()), send_after: (pid(), term(), non_neg_integer() -> term())}

  @doc "The real clock — monotonic time + `Process.send_after/3`."
  @spec real() :: t()
  def real do
    %{
      now: fn -> System.monotonic_time(:millisecond) end,
      send_after: fn dest, msg, ms -> Process.send_after(dest, msg, ms) end
    }
  end
end
