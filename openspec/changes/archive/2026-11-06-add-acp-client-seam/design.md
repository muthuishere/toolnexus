# Design — add-acp-client-seam

See ADR 0041 for the decision and SPEC §8C for the contract.

- **Passthrough means verbatim.** `mcpServers` is a list of JSON objects sent in order with no
  validation or shape resolution. The draft resolved an entry carrying both `url` and `command`
  against the agent's `mcpCapabilities.http`; that is a policy choice and moves to recipes (a
  caller can read the capability from a first session if it needs to).
- **`asMcpServer` reuses each port's §7C server**, wrapped with a loopback listener, a bearer
  check (constant-time) and a 405 for non-POST. It existed per port inside the CLI client's ACP
  session; it is lifted onto the toolkit unchanged except that the `skill` tool keeps its name.
- **Streaming** is #144's implementation, folded in; the conformance `acpDrip` case now drives
  `loadACP` directly instead of `createCliClient`.
- **Tests** run against each port's fake ACP agent (no vendor binary, no network).
