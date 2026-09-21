package main

import (
	"context"
	"encoding/json"
	"fmt"
	"math/rand"
	"strings"
	"sync/atomic"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

var emptyTK *tn.Toolkit

// Run panics on a nil toolkit, so hand it an explicit empty one.
func emptyToolkit() *tn.Toolkit {
	if emptyTK == nil {
		emptyTK, _ = tn.CreateToolkit(context.Background(), tn.Options{Builtins: false})
	}
	return emptyTK
}

type Player interface {
	Name() string
	Pick(g Grid, opts []Move, turn int) (string, string, error) // dir, why
	Stats() string
}

// ---------- 1. corner heuristic: pure code, zero models. The honest baseline.
type Heuristic struct{}

func (Heuristic) Name() string { return "heuristic" }
func (Heuristic) Stats() string { return "0 model calls" }
func (Heuristic) Pick(g Grid, opts []Move, turn int) (string, string, error) {
	order := map[string]int{"left": 0, "up": 1, "down": 2, "right": 3}
	best, bi := opts[0], 99
	for _, o := range opts {
		if order[o.Dir] < bi {
			best, bi = o, order[o.Dir]
		}
	}
	return best.Dir, "fixed priority left>up>down>right", nil
}

// ---------- 2. JUDGE ONLY: Jev picks every move. Fast doer, no planner.
type JudgePlayer struct {
	C        *Classifier
	Strategy string
}

func (*JudgePlayer) Name() string { return "judge-only" }
func (p *JudgePlayer) Stats() string {
	n := atomic.LoadInt64(&p.C.Calls)
	if n == 0 {
		return "0 calls"
	}
	return fmt.Sprintf("%d judge calls, %.0fms avg, $%.5f",
		n, float64(p.C.Nanos)/float64(n)/1e6, float64(p.C.CostMicros)/1e6)
}
func (p *JudgePlayer) Pick(g Grid, opts []Move, turn int) (string, string, error) {
	if len(opts) == 1 {
		return opts[0].Dir, "only legal move (no call)", nil // code first: never pay for a forced move
	}
	strat := p.Strategy
	if strat == "" {
		strat = "Keep the largest tile pinned in one corner and keep the board orderly."
	}
	crit := map[string]string{}
	for _, o := range opts {
		crit[o.Dir] = o.Describe()
	}
	d, err := p.C.Evaluate(
		map[string]any{"grid": g, "turn": turn},
		map[string]any{"move": choiceOver("STRATEGY: "+strat+" Which move best follows the strategy?", crit)})
	if err != nil {
		return opts[0].Dir, "judge error, fell back: " + err.Error(), nil // fail-open
	}
	a, err := d.Choice("move")
	if err != nil {
		return opts[0].Dir, "parse error, fell back", nil
	}
	return a.Choice, fmt.Sprintf("judge %.2f", a.Confidence), nil
}

// ---------- 3. LLM ONLY: the big model picks every move. Slow, expensive.
type LLMPlayer struct {
	Client *tn.Client
	Calls  int64
	Nanos  int64
}

func (*LLMPlayer) Name() string { return "llm-only" }
func (p *LLMPlayer) Stats() string {
	n := atomic.LoadInt64(&p.Calls)
	if n == 0 {
		return "0 calls"
	}
	return fmt.Sprintf("%d LLM calls, %.0fms avg", n, float64(p.Nanos)/float64(n)/1e6)
}
func (p *LLMPlayer) Pick(g Grid, opts []Move, turn int) (string, string, error) {
	if len(opts) == 1 {
		return opts[0].Dir, "only legal move (no call)", nil
	}
	var b strings.Builder
	b.WriteString("2048 board:\n" + g.String() + "\nLegal moves:\n")
	for _, o := range opts {
		b.WriteString("  " + o.Dir + ": " + o.Describe() + "\n")
	}
	b.WriteString("\nReply with ONLY one word: the direction.")
	start := time.Now()
	r, err := p.Client.Run(context.Background(), b.String(), nil)
	atomic.AddInt64(&p.Calls, 1)
	atomic.AddInt64(&p.Nanos, int64(time.Since(start)))
	if err != nil {
		return opts[0].Dir, "llm error, fell back", nil
	}
	pick := strings.ToLower(strings.TrimSpace(r.Text))
	for _, o := range opts {
		if strings.Contains(pick, o.Dir) {
			return o.Dir, "llm", nil
		}
	}
	return opts[0].Dir, "llm gave junk (" + trunc(pick, 30) + "), fell back", nil
}

// ---------- 4. HYBRID: the big model writes the STRATEGY in prose every N moves;
// the judge applies it every move. System Two plans, System One acts.
type Hybrid struct {
	Judge    *JudgePlayer
	Client   *tn.Client
	Every    int
	LLMCalls int64
	LLMNanos int64
	current  string
}

func (*Hybrid) Name() string { return "hybrid" }
func (p *Hybrid) Stats() string {
	n := atomic.LoadInt64(&p.LLMCalls)
	jn := atomic.LoadInt64(&p.Judge.C.Calls)
	s := fmt.Sprintf("%d LLM re-plans", n)
	if n > 0 {
		s += fmt.Sprintf(" (%.0fms avg)", float64(p.LLMNanos)/float64(n)/1e6)
	}
	if jn > 0 {
		s += fmt.Sprintf(", %d judge calls (%.0fms avg, $%.5f)",
			jn, float64(p.Judge.C.Nanos)/float64(jn)/1e6, float64(p.Judge.C.CostMicros)/1e6)
	}
	return s
}
func (p *Hybrid) Plan() string { return p.current }
func (p *Hybrid) Pick(g Grid, opts []Move, turn int) (string, string, error) {
	if turn%p.Every == 0 || p.current == "" {
		prompt := "You are coaching a 2048 player that can read prose but cannot do arithmetic.\n" +
			"Board:\n" + g.String() + "\nWrite ONE or TWO sentences of strategy for the next " +
			fmt.Sprint(p.Every) + " moves. Be concrete about which corner to pin and which direction to avoid. No preamble."
		start := time.Now()
		r, err := p.Client.Run(context.Background(), prompt, nil)
		atomic.AddInt64(&p.LLMCalls, 1)
		atomic.AddInt64(&p.LLMNanos, int64(time.Since(start)))
		if err == nil && strings.TrimSpace(r.Text) != "" {
			p.current = strings.TrimSpace(r.Text)
		}
	}
	p.Judge.Strategy = p.current
	d, why, err := p.Judge.Pick(g, opts, turn)
	return d, why, err
}

func trunc(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}

// ---------- the game loop, identical for every player
type Result struct {
	Player  string   `json:"player"`
	Score   int      `json:"score"`
	MaxTile int      `json:"maxTile"`
	Moves   int      `json:"moves"`
	WallMs  int64    `json:"wallMs"`
	Stats   string   `json:"stats"`
	Frames  []Frame  `json:"frames"`
	Plans   []string `json:"plans,omitempty"`
}
type Frame struct {
	Grid  Grid   `json:"grid"`
	Dir   string `json:"dir"`
	Why   string `json:"why"`
	Score int    `json:"score"`
}

func Play(p Player, seed int64, maxMoves int, verbose bool) Result {
	rng := rand.New(rand.NewSource(seed))
	var g Grid
	g.spawn(rng)
	g.spawn(rng)
	score, moves := 0, 0
	start := time.Now()
	res := Result{Player: p.Name()}
	for moves < maxMoves {
		opts := g.Options()
		if len(opts) == 0 {
			break
		}
		dir, why, _ := p.Pick(g, opts, moves)
		var chosen *Move
		for i := range opts {
			if opts[i].Dir == dir {
				chosen = &opts[i]
			}
		}
		if chosen == nil {
			chosen = &opts[0]
			why += " [illegal, corrected]"
		}
		g = chosen.Grid
		score += chosen.Score
		g.spawn(rng)
		moves++
		res.Frames = append(res.Frames, Frame{Grid: g, Dir: chosen.Dir, Why: why, Score: score})
		if h, ok := p.(*Hybrid); ok && (moves-1)%h.Every == 0 {
			res.Plans = append(res.Plans, fmt.Sprintf("move %d: %s", moves, h.Plan()))
		}
		if verbose {
			mx, _, _ := g.max()
			fmt.Printf("\r%-10s move %3d  score %6d  max %5d  %-28s", p.Name(), moves, score, mx, trunc(why, 28))
		}
	}
	mx, _, _ := g.max()
	res.Score, res.MaxTile, res.Moves = score, mx, moves
	res.WallMs = time.Since(start).Milliseconds()
	res.Stats = p.Stats()
	if verbose {
		fmt.Println()
	}
	return res
}

func (r Result) JSON() string { b, _ := json.Marshal(r); return string(b) }
