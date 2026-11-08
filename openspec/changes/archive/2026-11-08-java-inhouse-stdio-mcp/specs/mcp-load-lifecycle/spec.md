## ADDED Requirements

### Requirement: Stdio server requests never block the response reader

A port's frozen-path stdio MCP client SHALL answer server→client requests (ping, elicitation, roots and
unknown methods) without blocking the thread that reads the server's stdout. A server that sends many
requests while not reading stdin then cannot deadlock a tool call in flight.

#### Scenario: Many pings during a tool call

- **WHEN** a server sends thousands of `ping` requests during a `tools/call` and reads none of the replies until it has finished writing them
- **THEN** the client answers every ping and the tool call completes with the server's result

#### Scenario: The child exits with a call in flight

- **WHEN** the stdio server exits without answering a pending `tools/call`
- **THEN** that call and every later call on the server fail promptly with an error instead of waiting for the timeout

### Requirement: Abandoned stdio calls are cancelled on the server

When a frozen-path stdio `tools/call` is abandoned on an idle timeout or a caller cancel, the client SHALL
send `notifications/cancelled` with that request's `requestId` and a reason, and SHALL drop a later reply
to that id without error.

#### Scenario: Idle timeout

- **WHEN** a tool call sends nothing for longer than the idle budget and the server replies later
- **THEN** the server receives `notifications/cancelled` with reason `request timeout`, and the next call on that server succeeds
