//go:build live

// classifier-gate runs the two LIVE validation items of the add-classifier-backends
// change against a real System One endpoint. It never runs in CI: the file needs
// `-tags live` and TN_LIVE=1.
//
//	TN_LIVE=1 go run -tags live . -mode gate  -profile myjev
//	TN_LIVE=1 sec run TYPESAFE_API_KEY -- go run -tags live . -mode gate  -profile jev -images
//	TN_LIVE=1 go run -tags live . -mode joint -profile myjev
//	TN_LIVE=1 go run -tags live . -mode wire     # no network: both arms against a capture server
//
// gate  (ADR 0038 D8): every item in fixture/items.jsonl goes through the CURRENT
//
//	jevx binary (--json --fresh --no-context) and through toolnexus
//	(Classifier.State(..).Evaluate, no cache, Thresholds.Band). Verdicts must be
//	identical. jevx cannot send images, so an image item's old arm is the
//	hand-built System One wire (assets + TypeSafe-Danger-Zone-Images) that jevx
//	would have to send.
//
// joint (design §9): each state in fixture/joint.json is asked once with all
//
//	three questions and once per question (and the joint call is repeated to
//	measure the endpoint's own noise). Reports max |Δ| per answer.
//
// Results are written as JSON to -out.
package main

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

type profile struct {
	URL, Base, Model, KeyEnv string
	Backend                  tn.ClassifierBackend
	Images                   bool
}

var profiles = map[string]profile{
	// The owner's local model (free). Text only.
	"myjev": {URL: "http://127.0.0.1:21131/v1/systemone", Base: "http://127.0.0.1:21131/v1", Model: "myjev",
		Backend: tn.BackendCustom},
	// Hosted TypeSafe Jev (paid; the key comes from `sec run TYPESAFE_API_KEY`).
	"jev": {URL: "https://api.typesafe.ai/v1/systemone", Model: "jev-latest", KeyEnv: "TYPESAFE_API_KEY",
		Backend: tn.BackendTypeSafe, Images: true},
}

type item struct {
	ID       string   `json:"id"`
	Verb     string   `json:"verb"`
	Question string   `json:"question"`
	Options  []string `json:"options,omitempty"`
	Levels   []string `json:"levels,omitempty"`
	In       string   `json:"in"`
	Image    string   `json:"image,omitempty"`
	Source   string   `json:"source"`
}

type arm struct {
	Verdict   string         `json:"verdict"`
	Answer    map[string]any `json:"answer,omitempty"`
	Model     string         `json:"model,omitempty"`
	LatencyMs int64          `json:"latencyMs"`
	InTokens  int            `json:"inputTokens,omitempty"`
	Error     string         `json:"error,omitempty"`
}

type row struct {
	ID       string  `json:"id"`
	Verb     string  `json:"verb"`
	Image    bool    `json:"image"`
	OldPath  string  `json:"oldPath"`
	Old      arm     `json:"old"`
	New      arm     `json:"toolnexus"`
	Match    bool    `json:"match"`
	MaxDelta float64 `json:"maxDelta"`
}

func die(f string, a ...any) { fmt.Fprintf(os.Stderr, "classifier-gate: "+f+"\n", a...); os.Exit(4) }

func main() {
	mode := flag.String("mode", "gate", "gate | joint")
	prof := flag.String("profile", "myjev", "myjev | jev")
	images := flag.Bool("images", false, "include image items (profile must support images)")
	out := flag.String("out", "../../openspec/changes/add-classifier-backends/validation", "results dir")
	jevxBin := flag.String("jevx", "jevx", "the current jevx binary")
	only := flag.String("only", "", "gate: run only this item id")
	repeat := flag.Int("repeat", 1, "gate: ask each item this many times per arm (noise check)")
	flag.Parse()
	if os.Getenv("TN_LIVE") != "1" {
		die("live run refused: set TN_LIVE=1 (this calls a real endpoint)")
	}
	p, ok := profiles[*prof]
	if !ok {
		die("unknown profile %q", *prof)
	}
	if p.KeyEnv != "" && os.Getenv(p.KeyEnv) == "" {
		die("%s is not set; run under `sec run %s -- …`", p.KeyEnv, p.KeyEnv)
	}
	if *images && !p.Images {
		die("profile %s has no proven image support", *prof)
	}
	cl := newClassifier(p)
	_ = os.MkdirAll(*out, 0o755)
	stamp := time.Now().UTC().Format("2006-01-02")
	switch *mode {
	case "gate":
		rows := gate(cl, p, *prof, *jevxBin, *images, *only, *repeat)
		name := fmt.Sprintf("gate-%s-%s.json", *prof, stamp)
		if *only != "" {
			name = fmt.Sprintf("gate-%s-%s-%s-x%d.json", *prof, *only, stamp, *repeat)
		}
		write(filepath.Join(*out, name), rows)
	case "wire":
		wire(*jevxBin)
		return
	case "joint":
		write(filepath.Join(*out, fmt.Sprintf("joint-%s-%s.json", *prof, stamp)), joint(cl))
	default:
		die("unknown mode %q", *mode)
	}
}

func newClassifier(p profile) *tn.Classifier {
	o := tn.ClassifierOptions{Backend: p.Backend, Model: p.Model, Retries: 2, Timeout: 60 * time.Second}
	if p.Backend == tn.BackendCustom {
		o.BaseURL, o.Supports = p.Base, &tn.ClassifierSupports{}
		// The local model takes no key; a fixed dummy env var keeps the header path uniform.
		os.Setenv("TN_GATE_NO_KEY", "none")
		o.APIKeyEnv = "TN_GATE_NO_KEY"
	}
	cl, err := tn.CreateClassifier(o)
	if err != nil {
		die("create classifier: %v", err)
	}
	return cl
}

func write(path string, v any) {
	b, _ := json.MarshalIndent(v, "", "  ")
	if err := os.WriteFile(path, append(b, '\n'), 0o644); err != nil {
		die("%v", err)
	}
	fmt.Println("wrote", path)
}

// ------------------------------------------------------------------ gate

func question(it item) tn.JudgeQuestion {
	switch it.Verb {
	case "is":
		return tn.Noul("is", it.Question)
	case "pick":
		return tn.Choice("pick", it.Question, options(it.Options))
	case "rate":
		return tn.Score("q", it.Question, it.Levels...)
	}
	die("bad verb %q", it.Verb)
	return tn.JudgeQuestion{}
}

func options(kv []string) map[string]string {
	m := map[string]string{}
	for _, o := range kv {
		k, d, _ := strings.Cut(o, "=")
		m[k] = d
	}
	return m
}

func readItems() []item {
	b, err := os.ReadFile("fixture/items.jsonl")
	if err != nil {
		die("%v", err)
	}
	var its []item
	for _, l := range strings.Split(string(b), "\n") {
		if l = strings.TrimSpace(l); l == "" {
			continue
		}
		var it item
		if err := json.Unmarshal([]byte(l), &it); err != nil {
			die("bad item: %v", err)
		}
		its = append(its, it)
	}
	return its
}

func gate(cl *tn.Classifier, p profile, name, jevxBin string, images bool, only string, repeat int) []row {
	cfg := jevxConfig(p, name)
	defer os.Remove(cfg)
	var rows []row
	for _, it := range readItems() {
		if (it.Image != "" && !images) || (only != "" && it.ID != only) {
			continue
		}
		for rep := 0; rep < repeat; rep++ {
			r := row{ID: it.ID, Verb: it.Verb, Image: it.Image != ""}
			jq := question(it)
			if it.Image == "" {
				r.OldPath = "jevx --json --fresh --no-context"
				r.Old = viaJevx(jevxBin, cfg, name, it)
				r.New = viaToolnexus(cl, it.In, jq)
			} else {
				mime, data := loadImage(it.Image)
				r.OldPath = "hand-built System One wire (jevx has no image input)"
				r.Old = viaRawWire(p, it, jq, mime, data)
				r.New = viaToolnexus(cl, map[string]any{"note": it.In,
					"image": tn.ContentPart{Type: tn.PartImage, MimeType: mime, Data: data}}, jq)
			}
			r.Match = r.Old.Error == "" && r.New.Error == "" && r.Old.Verdict == r.New.Verdict
			r.MaxDelta = maxDelta(r.Old.Answer, r.New.Answer)
			rows = append(rows, r)
			fmt.Printf("%-26s %-8s %-10s %-10s Δ=%.3f %v\n", r.ID, r.Verb, r.Old.Verdict, r.New.Verdict, r.MaxDelta, r.Match)
		}
	}
	n := 0
	for _, r := range rows {
		if r.Match {
			n++
		}
	}
	fmt.Printf("\n%d/%d verdicts match\n", n, len(rows))
	return rows
}

// jevxConfig writes a throwaway jevx config holding only this profile, so the
// owner's real config (and its question packs) cannot change the old arm.
func jevxConfig(p profile, name string) string {
	pr := map[string]any{"url": p.URL, "model": p.Model, "defaults": map[string]any{}}
	if p.KeyEnv != "" {
		pr["headers"] = map[string]string{"Authorization": "Bearer $" + p.KeyEnv}
	}
	b, _ := json.Marshal(map[string]any{"default_profile": name, "profiles": map[string]any{name: pr}})
	f, err := os.CreateTemp("", "jevx-gate-*.json")
	if err != nil {
		die("%v", err)
	}
	f.Write(b)
	f.Close()
	return f.Name()
}

func viaJevx(bin, cfg, name string, it item) arm {
	var args []string
	switch it.Verb {
	case "is":
		args = []string{"is", it.Question}
	case "pick":
		args = append([]string{"pick", it.Question}, it.Options...)
	case "rate":
		args = []string{"ask", "--score", "q=" + it.Question + "|" + strings.Join(it.Levels, ";")}
	}
	args = append(args, "--json", "--fresh", "--no-context", "--profile", name, "--in", it.In)
	cmd := exec.Command(bin, args...)
	cmd.Env = append(os.Environ(), "JEVX_CONFIG="+cfg, "JEVX_LEDGER=off")
	t0 := time.Now()
	out, err := cmd.Output()
	a := arm{LatencyMs: time.Since(t0).Milliseconds()}
	if ee, ok := err.(*exec.ExitError); err != nil && (!ok || (ee.ExitCode() != 1 && ee.ExitCode() != 3)) {
		a.Error = fmt.Sprintf("jevx: %v", err)
		return a
	}
	var res struct {
		Model   string `json:"model"`
		Answers map[string]struct {
			Answer  map[string]any `json:"answer"`
			Verdict string         `json:"verdict"`
		} `json:"answers"`
	}
	if err := json.Unmarshal(out, &res); err != nil || len(res.Answers) != 1 {
		a.Error = fmt.Sprintf("jevx output: %v %q", err, out)
		return a
	}
	for _, v := range res.Answers {
		a.Verdict, a.Answer = v.Verdict, v.Answer
	}
	a.Model = res.Model
	return a
}

func viaToolnexus(cl *tn.Classifier, state any, jq tn.JudgeQuestion) arm {
	t0 := time.Now()
	d, err := cl.State(state).Evaluate(context.Background(), jq)
	a := arm{LatencyMs: time.Since(t0).Milliseconds()}
	if err != nil {
		a.Error = err.Error()
		return a
	}
	qs, _ := tn.Questions(jq)
	if err := tn.ValidateDecision(qs, d); err != nil {
		a.Error = err.Error()
		return a
	}
	ans := d.Answers[jq.Name]
	a.Verdict, a.Model, a.InTokens = verdictOf(jq, ans), d.Model, d.Usage.InputTokens
	b, _ := json.Marshal(ans)
	_ = json.Unmarshal(b, &a.Answer)
	a.Answer["type"] = ans.AnswerType()
	return a
}

// verdictOf is jevx's verdict over toolnexus's inclusive Thresholds.Band.
func verdictOf(jq tn.JudgeQuestion, a tn.DecisionAnswer) string {
	band := tn.DefaultThresholds.Band(a)
	switch x := a.(type) {
	case tn.NoulAnswer:
		return map[string]string{tn.BandYes: "yes", tn.BandNo: "no"}[band] + map[bool]string{true: "unsure"}[band == tn.BandUncertain]
	case tn.ChoiceAnswer:
		if band != tn.BandYes {
			return "unsure"
		}
		return x.Choice
	case tn.ScoreAnswer:
		if band != tn.BandYes {
			return "unsure"
		}
		lv := jq.Question.(tn.ScoreQuestion).Criteria
		if i := int(math.Round(x.Score)); i >= 0 && i < len(lv) {
			return lv[i]
		}
		return fmt.Sprintf("%.2f", x.Score)
	}
	return "unsure"
}

// viaRawWire is the old arm for an image item: the System One request built by
// hand, independent of toolnexus's encoder, decoded by hand.
func viaRawWire(p profile, it item, jq tn.JudgeQuestion, mime, data string) arm {
	qs, _ := tn.Questions(jq)
	q := map[string]any{}
	switch x := qs[jq.Name].(type) {
	case tn.NoulQuestion:
		q = map[string]any{"type": "noul", "instructions": x.Instructions}
	case tn.ChoiceQuestion:
		q = map[string]any{"type": "choice", "instructions": x.Instructions, "criteria": x.Criteria}
	case tn.ScoreQuestion:
		q = map[string]any{"type": "score", "instructions": x.Instructions, "criteria": x.Criteria}
	}
	format := strings.TrimPrefix(mime, "image/")
	if format == "jpg" {
		format = "jpeg"
	}
	body, _ := json.Marshal(map[string]any{
		"model":     p.Model,
		"state":     map[string]any{"note": it.In, "image": "image"},
		"questions": map[string]any{jq.Name: q},
		"assets":    map[string]any{"image": map[string]any{"type": "image", "format": format, "data_base64": data}},
	})
	req, _ := http.NewRequest("POST", p.URL, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("TypeSafe-Danger-Zone-Images", "true")
	if p.KeyEnv != "" {
		req.Header.Set("Authorization", "Bearer "+os.Getenv(p.KeyEnv))
	}
	t0 := time.Now()
	resp, err := (&http.Client{Timeout: 60 * time.Second}).Do(req)
	a := arm{}
	if err != nil {
		a.Error = "raw wire: request failed"
		return a
	}
	defer resp.Body.Close()
	rb, _ := io.ReadAll(resp.Body)
	a.LatencyMs = time.Since(t0).Milliseconds()
	if resp.StatusCode != 200 {
		a.Error = fmt.Sprintf("raw wire: HTTP %d", resp.StatusCode)
		return a
	}
	var d tn.Decision
	if err := json.Unmarshal(rb, &d); err != nil {
		a.Error = "raw wire: " + err.Error()
		return a
	}
	ans, ok := d.Answers[jq.Name]
	if !ok {
		a.Error = "raw wire: no answer"
		return a
	}
	// The verdict rule is applied to the hand-decoded answer exactly as jevx does.
	a.Verdict, a.Model, a.InTokens = verdictOf(jq, ans), d.Model, d.Usage.InputTokens
	b, _ := json.Marshal(ans)
	_ = json.Unmarshal(b, &a.Answer)
	a.Answer["type"] = ans.AnswerType()
	return a
}

func loadImage(rel string) (string, string) {
	b, err := os.ReadFile(filepath.Join("..", "..", rel))
	if err != nil {
		die("image: %v", err)
	}
	mime := "image/png"
	if strings.HasSuffix(rel, ".jpg") || strings.HasSuffix(rel, ".jpeg") {
		mime = "image/jpeg"
	}
	return mime, base64.StdEncoding.EncodeToString(b)
}

// maxDelta compares the numbers of two answers: noul, score, confidence and
// every probability.
func maxDelta(a, b map[string]any) float64 {
	if a == nil || b == nil {
		return math.NaN()
	}
	m := 0.0
	num := func(k string) {
		x, ok1 := a[k].(float64)
		y, ok2 := b[k].(float64)
		if ok1 && ok2 {
			m = math.Max(m, math.Abs(x-y))
		}
	}
	num("noul")
	num("score")
	num("confidence")
	pa, _ := a["probabilities"].(map[string]any)
	pb, _ := b["probabilities"].(map[string]any)
	for k, v := range pa {
		x, _ := v.(float64)
		y, _ := pb[k].(float64)
		m = math.Max(m, math.Abs(x-y))
	}
	return m
}

// ------------------------------------------------------------------ joint

type jointFixture struct {
	Questions map[string]json.RawMessage `json:"questions"`
	States    []string                   `json:"states"`
}

type jointRow struct {
	State        string             `json:"state"`
	Joint        map[string]any     `json:"joint"`
	JointRepeat  map[string]any     `json:"jointRepeat"`
	Single       map[string]any     `json:"single"`
	DeltaSplit   map[string]float64 `json:"deltaJointVsSingle"`
	DeltaRepeat  map[string]float64 `json:"deltaJointVsRepeat"`
	VerdictSplit map[string]bool    `json:"verdictSame"`
	LatencyMs    map[string]int64   `json:"latencyMs"`
}

type jointReport struct {
	Rows         []jointRow `json:"rows"`
	MaxSplit     float64    `json:"maxDeltaJointVsSingle"`
	MaxRepeat    float64    `json:"maxDeltaJointVsRepeat"`
	VerdictFlips int        `json:"verdictFlips"`
	Floor        float64    `json:"floor"`
	WithinFloor  bool       `json:"withinFloor"`
	InputTokens  int        `json:"inputTokens"`
	Calls        int        `json:"calls"`
}

func joint(cl *tn.Classifier) jointReport {
	b, err := os.ReadFile("fixture/joint.json")
	if err != nil {
		die("%v", err)
	}
	var fx jointFixture
	if err := json.Unmarshal(b, &fx); err != nil {
		die("%v", err)
	}
	var jqs []tn.JudgeQuestion
	names := make([]string, 0, len(fx.Questions))
	for k := range fx.Questions {
		names = append(names, k)
	}
	sort.Strings(names)
	for _, k := range names {
		var q struct {
			Type         string          `json:"type"`
			Instructions string          `json:"instructions"`
			Criteria     json.RawMessage `json:"criteria"`
		}
		_ = json.Unmarshal(fx.Questions[k], &q)
		switch q.Type {
		case "noul":
			jqs = append(jqs, tn.Noul(k, q.Instructions))
		case "choice":
			var m map[string]string
			_ = json.Unmarshal(q.Criteria, &m)
			jqs = append(jqs, tn.Choice(k, q.Instructions, m))
		case "score":
			var l []string
			_ = json.Unmarshal(q.Criteria, &l)
			jqs = append(jqs, tn.Score(k, q.Instructions, l...))
		}
	}
	rep := jointReport{Floor: 0.05}
	ctx := context.Background()
	ask := func(state string, qs ...tn.JudgeQuestion) (tn.Decision, int64) {
		t0 := time.Now()
		d, err := cl.State(state).Evaluate(ctx, qs...)
		if err != nil {
			die("evaluate: %v", err)
		}
		m, _ := tn.Questions(qs...)
		if err := tn.ValidateDecision(m, d); err != nil {
			die("invalid reply: %v", err)
		}
		rep.Calls++
		rep.InputTokens += d.Usage.InputTokens
		return d, time.Since(t0).Milliseconds()
	}
	asMap := func(a tn.DecisionAnswer) map[string]any {
		var m map[string]any
		b, _ := json.Marshal(a)
		_ = json.Unmarshal(b, &m)
		return m
	}
	for _, s := range fx.States {
		r := jointRow{State: s, Joint: map[string]any{}, JointRepeat: map[string]any{}, Single: map[string]any{},
			DeltaSplit: map[string]float64{}, DeltaRepeat: map[string]float64{}, VerdictSplit: map[string]bool{},
			LatencyMs: map[string]int64{}}
		dj, lj := ask(s, jqs...)
		r.LatencyMs["joint"] = lj
		for _, q := range jqs {
			ds, ls := ask(s, q)
			r.LatencyMs[q.Name] = ls
			r.Single[q.Name] = asMap(ds.Answers[q.Name])
			r.Joint[q.Name] = asMap(dj.Answers[q.Name])
			r.DeltaSplit[q.Name] = maxDelta(r.Joint[q.Name].(map[string]any), r.Single[q.Name].(map[string]any))
			same := verdictOf(q, dj.Answers[q.Name]) == verdictOf(q, ds.Answers[q.Name])
			r.VerdictSplit[q.Name] = same
			if !same {
				rep.VerdictFlips++
			}
			rep.MaxSplit = math.Max(rep.MaxSplit, r.DeltaSplit[q.Name])
		}
		dr, _ := ask(s, jqs...)
		for _, q := range jqs {
			r.JointRepeat[q.Name] = asMap(dr.Answers[q.Name])
			r.DeltaRepeat[q.Name] = maxDelta(r.Joint[q.Name].(map[string]any), r.JointRepeat[q.Name].(map[string]any))
			rep.MaxRepeat = math.Max(rep.MaxRepeat, r.DeltaRepeat[q.Name])
		}
		fmt.Printf("%-60.60s split=%v repeat=%v\n", s, fmtD(r.DeltaSplit), fmtD(r.DeltaRepeat))
		rep.Rows = append(rep.Rows, r)
	}
	rep.WithinFloor = rep.MaxSplit <= rep.Floor
	fmt.Printf("\nmax|Δ| joint vs single = %.4f, joint vs joint repeat = %.4f, floor %.2f, verdict flips %d, calls %d, input tokens %d\n",
		rep.MaxSplit, rep.MaxRepeat, rep.Floor, rep.VerdictFlips, rep.Calls, rep.InputTokens)
	return rep
}

func fmtD(m map[string]float64) string {
	ks := make([]string, 0, len(m))
	for k := range m {
		ks = append(ks, k)
	}
	sort.Strings(ks)
	var sb strings.Builder
	for _, k := range ks {
		fmt.Fprintf(&sb, "%s:%.3f ", k, m[k])
	}
	return strings.TrimSpace(sb.String())
}

// ------------------------------------------------------------------ wire

// wire points BOTH arms at a local capture server (no network, no key) and
// compares the request bodies they send for every text item. Byte-identical
// requests mean any live verdict difference is the endpoint, not the encoder.
func wire(jevxBin string) {
	var last []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		last, _ = io.ReadAll(r.Body)
		var req struct {
			Questions map[string]struct {
				Type     string          `json:"type"`
				Criteria json.RawMessage `json:"criteria"`
			} `json:"questions"`
		}
		_ = json.Unmarshal(last, &req)
		ans := map[string]any{}
		for k, q := range req.Questions {
			switch q.Type {
			case "noul":
				ans[k] = map[string]any{"type": "noul", "noul": 0.9}
			case "choice":
				var m map[string]string
				_ = json.Unmarshal(q.Criteria, &m)
				ks := []string{}
				for c := range m {
					ks = append(ks, c)
				}
				sort.Strings(ks)
				pr := map[string]float64{}
				for _, c := range ks {
					pr[c] = 0
				}
				pr[ks[0]] = 1
				ans[k] = map[string]any{"type": "choice", "choice": ks[0], "probabilities": pr, "confidence": 1}
			case "score":
				ans[k] = map[string]any{"type": "score", "score": 1, "probabilities": map[string]float64{"0": 0, "1": 1, "2": 0}, "confidence": 1}
			}
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"model": "capture", "answers": ans})
	}))
	defer srv.Close()
	p := profile{URL: srv.URL + "/v1/systemone", Base: srv.URL + "/v1", Model: "capture", Backend: tn.BackendCustom}
	cl := newClassifier(p)
	cfg := jevxConfig(p, "capture")
	defer os.Remove(cfg)
	same, canon, n := 0, 0, 0
	for _, it := range readItems() {
		if it.Image != "" {
			continue
		}
		n++
		viaJevx(jevxBin, cfg, "capture", it)
		old := last
		viaToolnexus(cl, it.In, question(it))
		neu := last
		var a, b any
		_ = json.Unmarshal(old, &a)
		_ = json.Unmarshal(neu, &b)
		ca, _ := json.Marshal(a)
		cb, _ := json.Marshal(b)
		switch {
		case bytes.Equal(old, neu):
			same++
			canon++
		case bytes.Equal(ca, cb):
			canon++
			fmt.Printf("%-26s same JSON, different bytes\n  jevx: %s\n  tn:   %s\n", it.ID, old, neu)
		default:
			fmt.Printf("%-26s DIFFERENT\n  jevx: %s\n  tn:   %s\n", it.ID, old, neu)
		}
	}
	fmt.Printf("\n%d/%d byte-identical, %d/%d same JSON\n", same, n, canon, n)
}
