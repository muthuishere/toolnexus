/**
 * Sub-agents & agent runtime (SPEC.md §7D), exported as the `agents` namespace:
 *
 *   import { agents } from "toolnexus"
 *   const explore = agents.agent("explore", { does: "read-only research" })
 */
export * from "./runtime.js"
export * from "./agent.js"
export * from "./home.js"
export * from "./compaction.js"
