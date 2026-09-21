"""Runner: print the Python port's skill inventory as JSON for the issue-93 harness."""
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "..", "..", "..", "python", "src"))

from toolnexus.skill import list_skills  # noqa: E402

inv = list_skills(sys.argv[1:])
print(
    json.dumps(
        {
            "skills": [{"location": s.location} for s in inv.skills],
            "skipped": [{"location": s.location, "reason": s.reason} for s in inv.skipped],
        }
    )
)
