// The hermetic examples under examples/ run in CI here, so an example that drifts from the
// library (as pending.ts did when 0.22 removed the env-key fallback) fails the suite.
import { test } from "node:test"
import assert from "node:assert"
import { spawnSync } from "node:child_process"

for (const name of ["prompt_cache.ts", "pending.ts", "mcp_handler.ts"]) {
  test(`examples/${name} runs`, () => {
    const r = spawnSync(process.execPath, ["--experimental-strip-types", `examples/${name}`], { encoding: "utf8", timeout: 60_000 })
    assert.equal(r.status, 0, r.stdout + r.stderr)
  })
}

// examples/media.ts needs a provider key or the grok/codex CLI; with every media key removed and an
// empty PATH it must still import the named clients from dist and exit 0 without touching the network.
test("examples/media.ts runs (no keys: hint, exit 0)", () => {
  const env = { ...process.env }
  for (const k of ["A2E_API_KEY", "FAL_API_KEY", "OPENAI_API_KEY", "ELEVENLABS_API_KEY"]) delete env[k]
  env.PATH = ""
  const r = spawnSync(process.execPath, ["--experimental-strip-types", "examples/media.ts"], { encoding: "utf8", timeout: 60_000, env })
  assert.equal(r.status, 0, r.stdout + r.stderr)
  assert.match(r.stdout, /no network call made/)
})
