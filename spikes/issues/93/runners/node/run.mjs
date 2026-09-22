// Runner: print the JS port's skill inventory as JSON for the issue-93 harness.
import { listSkills } from "../../../../../js/dist/skill.js"

const inv = listSkills({ dirs: process.argv.slice(2) })
console.log(
  JSON.stringify({
    skills: inv.skills.map((s) => ({ location: s.location })),
    skipped: inv.skipped.map((s) => ({ location: s.location, reason: s.reason })),
  }),
)
