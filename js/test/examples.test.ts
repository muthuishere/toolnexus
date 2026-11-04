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
