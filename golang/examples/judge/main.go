// Classifier (SPEC.md §8B) — the contract for a JUDGMENT, as Tool is the contract for an ACTION.
//
//	go run ./examples/judge
//
// With OPENROUTER_API_KEY set it calls the live System One backend; with no key it replays one
// recorded decision through the `static` backend, so the example runs offline with no credential.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"math"
	"os"
	"strconv"

	"github.com/muthuishere/toolnexus/golang"
)

// ---- the state: whatever the host already has. Sent verbatim, never canonicalised. ----
const ticket = "Ticket 4021: my card was charged twice for the annual plan on Tuesday, and the second charge " +
	"has not been refunded. I am not blocked from working, but I would like the money back this week."

const model = "typesafe/jev-1.13"

// All three question types in ONE call: many questions, one round trip, one state ingest.
// The questions are INDEPENDENT — one answer is never context for another.
//
// The `choice` descriptions are the whole ball game (ADR 0021): Criteria[id] is the only thing that
// tells the model what picking `billing` rather than `technical` would MEAN. Options described by
// their own id are schema-valid, return HTTP 200 — and rank at chance (17 apples -> 0). So: every
// option carries a real sentence, all three use the SAME template ("own it here when the problem is
// X: a, b, c"), and no arithmetic is pushed onto the model — the host does the counting and hands
// over the conclusion.
var questions = map[string]toolnexus.Question{
	"wants_money_back": toolnexus.NoulQuestion{
		Instructions: "Is the customer asking for money to be returned?",
	},
	"department": toolnexus.ChoiceQuestion{
		Instructions: "Which desk should own this ticket?",
		Criteria: map[string]string{
			"billing":   "own it here when the problem is money that moved: a duplicate charge, a wrong invoice, a refund owed",
			"shipping":  "own it here when the problem is a physical parcel: a late delivery, a package damaged in transit",
			"technical": "own it here when the problem is the product itself: a login that fails, a feature that errors",
		},
	},
	"urgency": toolnexus.ScoreQuestion{
		Instructions: "How fast does this ticket need a human?",
		Criteria: []string{
			"the customer is working normally and is waiting on an answer",
			"the customer is inconvenienced and will chase if nobody replies today",
			"the customer is blocked from working right now and every hour costs them",
		},
	},
}

// One decision recorded off the live backend, so this file runs with no key and no network.
const recorded = `{"model":"typesafe/jev-1.13-20260917",
 "answers":{
   "wants_money_back":{"type":"noul","noul":0.99},
   "department":{"type":"choice","choice":"billing","probabilities":{"technical":0,"shipping":0,"billing":1},"confidence":1},
   "urgency":{"type":"score","score":0.49,
     "legend":{"0":"the customer is working normally and is waiting on an answer","1":"the customer is inconvenienced and will chase if nobody replies today","2":"the customer is blocked from working right now and every hour costs them"},
     "probabilities":{"0":0.52,"1":0.48,"2":0},"confidence":0.27}},
 "usage":{"input_tokens":516,"output_tokens":72,"cost":0.000021672}}`

func main() {
	live := os.Getenv("OPENROUTER_API_KEY") != ""

	opts := toolnexus.ClassifierOptions{
		Style:     toolnexus.StyleStatic,
		Model:     model,
		Decisions: []toolnexus.RecordedDecision{{State: ticket, Questions: questions, Response: []byte(recorded)}},
	}
	if live {
		opts = toolnexus.ClassifierOptions{
			BaseURL:   "https://openrouter.ai/api/v1", // serves the System One wire today
			Model:     model,
			APIKeyEnv: "OPENROUTER_API_KEY", // the NAME of an env var, never the value
			OnMetric: func(ev toolnexus.MetricEvent) {
				if ev.Event == toolnexus.MetricClassifierWarning {
					fmt.Println("warning:", ev.Warning)
				}
			},
		}
	}

	judge, err := toolnexus.CreateClassifier(opts)
	if err != nil {
		log.Fatal(err)
	}

	if live {
		fmt.Println("backend: systemone (live)")
	} else {
		fmt.Println("backend: static (recorded — set OPENROUTER_API_KEY to go live)")
	}

	d, err := judge.Evaluate(context.Background(), ticket, questions)
	if err != nil {
		log.Fatal(err)
	}

	want, err := d.Noul("wants_money_back")
	if err != nil {
		log.Fatal(err)
	}
	dept, err := d.Choice("department")
	if err != nil {
		log.Fatal(err)
	}
	urg, err := d.Score("urgency")
	if err != nil {
		log.Fatal(err)
	}

	level := int(math.Round(urg.Score))
	fmt.Printf("\nmodel answering: %s\n", d.Model)
	fmt.Printf("wants_money_back: %v   (a noul carries NO confidence — the number IS the answer)\n", want.Noul)
	fmt.Printf("department:       %s  p=%s confidence=%v\n", dept.Choice, jsonOf(dept.Probabilities), dept.Confidence)
	fmt.Printf("urgency:          %v  of 0..%d  p=%s\n", urg.Score, len(urg.Legend)-1, jsonOf(urg.Probabilities))
	fmt.Printf("  level %d: %s   (a score MAY fall between levels)\n", level, urg.Legend[strconv.Itoa(level)])

	// The two health flags, and what they actually mean.
	fmt.Printf("\ncalibrated: %v  — these probabilities came from a calibrated backend, so a threshold "+
		"tuned here transfers. An 'llm'-style backend reports false and your thresholds do NOT carry over.\n",
		d.Calibrated)
	fmt.Printf("nearUniform(department): %v  — max|p - 1/n| <= 0.05, derived from the response. "+
		"True would mean the model had nothing to rank on (usually undescribed options). Advisory, NOT correctness.\n",
		dept.NearUniform)

	cost := ""
	if d.Usage.Cost != 0 {
		cost = " / $" + strconv.FormatFloat(d.Usage.Cost, 'f', -1, 64)
	}
	fmt.Printf("\nusage: %d in / %d out%s\n", d.Usage.InputTokens, d.Usage.OutputTokens, cost)
}

// jsonOf renders a probability map the way the other ports print it: sorted keys, compact.
func jsonOf(m map[string]float64) string {
	b, _ := json.Marshal(m)
	return string(b)
}
