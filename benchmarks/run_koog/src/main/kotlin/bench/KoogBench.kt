// JetBrains Koog benchmark runner. Same mock LLM (Koog's OpenAILLMClient with
// baseUrl -> mock) + the same shared stdio MCP server, via Koog's
// McpToolRegistryProvider.fromTransport over the MCP Kotlin SDK's
// StdioClientTransport (Koog 1.3 ships no stdio helper, so the process is spawned here).
//
//   cold init   = spawn MCP server + connect/initialize + tools/list -> ToolRegistry
//                 + OpenAILLMClient + MultiLLMPromptExecutor (single client)
//   per-request = AIAgent(...).run(question)  (Koog agents are single-run, so a
//                 fresh AIAgent with the default single-run strategy per request)
//
//   -runs N   -warmup W
package bench

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.mcp.McpToolRegistryProvider
import ai.koog.agents.mcp.metadata.McpServerInfo
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

const val QUESTION = "What's the weather in Paris and what is 2+2?"

fun arg(a: Array<String>, k: String, d: String): String {
    val i = a.indexOf(k); return if (i >= 0 && i + 1 < a.size) a[i + 1] else d
}

fun main(args: Array<String>) = runBlocking {
    val runs = arg(args, "-runs", "30").toInt()
    val warmup = arg(args, "-warmup", "5").toInt()
    val mockUrl = System.getenv("MOCK_URL") ?: "http://127.0.0.1:8900"
    val repo = System.getenv("BENCH_REPO") ?: ""
    val mcpPy = System.getenv("MCP_PYTHON") ?: "python3"

    // ---- cold init ----
    val t0 = System.nanoTime()
    val proc = ProcessBuilder(mcpPy, "$repo/benchmarks/mcp_server.py")
        .redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val transport = StdioClientTransport(
        proc.inputStream.asSource().buffered(),
        proc.outputStream.asSink().buffered(),
    )
    val registry = McpToolRegistryProvider.fromTransport(transport, McpServerInfo(command = "$mcpPy mcp_server.py"))
    val executor = MultiLLMPromptExecutor(
        OpenAILLMClient("sk-mock-not-a-real-key", OpenAIClientSettings(baseUrl = mockUrl))
    )
    val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "mock-model",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.Temperature, LLMCapability.OpenAIEndpoint.Completions),
        contextLength = 128_000,
    )
    val initMs = (System.nanoTime() - t0) / 1e6
    val toolCount = registry.tools.size

    suspend fun once(): String = AIAgent(
        promptExecutor = executor,
        llmModel = model,
        toolRegistry = registry,
        systemPrompt = "You are a helpful assistant.",
    ).run(QUESTION)

    repeat(warmup) { once() }
    val lat = ArrayList<Double>()
    var finalText = ""
    repeat(runs) {
        val s = System.nanoTime()
        finalText = once()
        lat.add((System.nanoTime() - s) / 1e6)
    }

    val sorted = lat.sorted()
    fun pct(p: Int) = sorted[minOf(sorted.size - 1, (p / 100.0 * (sorted.size - 1) + 0.5).toInt())]
    fun r(v: Double) = Math.round(v * 1000) / 1000.0
    println(
        "{\"name\":\"koog\",\"framework\":\"koog-mcp\",\"language\":\"kotlin\"" +
            ",\"init_ms\":${r(initMs)},\"tool_count\":$toolCount,\"mean_ms\":${r(lat.average())}" +
            ",\"p50_ms\":${r(pct(50))},\"p95_ms\":${r(pct(95))},\"n\":${lat.size}" +
            ",\"final_ok\":${finalText.startsWith("The weather in Paris")}}"
    )
    proc.destroy()
    System.exit(0)
}
