package main

import tn "github.com/muthuishere/toolnexus/golang"

// The bug-fixer-platform spikes/04-classifier fixture: the checkout-500 bug.
var bug = map[string]any{
	"title": "Checkout returns 500 for coupon codes with a trailing space",
	"body": "Since the 4.2 release, POST /api/checkout returns HTTP 500 when the coupon " +
		"code has a trailing space. Stack trace points at pricing/coupon.go:88, " +
		"strconv.Atoi on an untrimmed string. Affects ~4% of checkouts. " +
		"Reproduced on staging with curl.",
	"reporter": "support-team",
}

var qs = []Q{
	Noul("fixable", "An autonomous agent can fix this bug from the report alone.",
		"the report names a reproducible trigger, a file or endpoint, and the failure",
		"the report is vague, needs product input, or has no reproducible failure"),
	Choice("component", "Which component owns the fix for this bug report?", map[string]string{
		"pricing":  "the defect is in coupon/discount calculation code and a pricing engineer should own the fix",
		"checkout": "the defect is in the HTTP checkout handler or its request validation",
		"frontend": "the defect is in the browser client and no server change is needed",
		"infra":    "the defect is in deployment, config or capacity and no application code changes",
	}),
}

func questions() map[string]tn.Question { m, _ := Questions(qs...); return m }

var questionTypes = map[string]string{"fixable": "noul", "component": "choice"}

func f(v float64) *float64 { return &v }

// The gates as a wfnexus decide: block would declare them.
var rules = []Rule{
	{Question: "fixable", Below: f(0.30), Action: "fail"},
	{Question: "component", Is: "pricing", Action: "skip_to", Target: "fix-pricing"},
}
