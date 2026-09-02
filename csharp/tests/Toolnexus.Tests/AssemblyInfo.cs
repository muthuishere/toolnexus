using Xunit;

// xUnit runs test classes in parallel by default. Several tests here assert on
// WALL-CLOCK behaviour — a run-level deadline of 60ms against an 800ms stub
// (LlmClientResilienceTests.RunLevelTimeoutAborts), heartbeat ticks coalescing
// (AgentSurfaceTests) — and those assertions are only meaningful if a timer
// callback gets a thread when it is due.
//
// The multimodal tests added in 0.17.0 do 26 blocking file reads. Run alongside
// the timing tests they starve the pool, the 60ms timeout fires LATE, the 800ms
// response wins, and the test fails with "No exception was thrown" — the timeout
// looking like a success. Measured: 3/3 green without them, ~1 in 3 red with.
//
// Serialising costs a couple of seconds on a suite that runs in two, and buys a
// deterministic result. A timing assertion racing the scheduler is not a test.
[assembly: CollectionBehavior(DisableTestParallelization = true)]
