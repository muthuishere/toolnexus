package main

// 2048 engine. Pure, seeded, deterministic — the ARITHMETIC half.
// Jev is never asked to add anything: code computes every consequence,
// and the judge only ever chooses between described outcomes.

import (
	"fmt"
	"math/rand"
	"strings"
)

type Grid [4][4]int

type Move struct {
	Dir    string // up down left right
	Grid   Grid
	Score  int    // points gained
	Merges int    // how many pairs merged
	Opened int    // free cells after the move
	MaxPos string // where the largest tile ends up
	Legal  bool
}

var dirs = []string{"up", "down", "left", "right"}

func slide(row [4]int) ([4]int, int, int) {
	var out [4]int
	n, gained, merges := 0, 0, 0
	var packed []int
	for _, v := range row {
		if v != 0 {
			packed = append(packed, v)
		}
	}
	for i := 0; i < len(packed); i++ {
		if i+1 < len(packed) && packed[i] == packed[i+1] {
			out[n] = packed[i] * 2
			gained += out[n]
			merges++
			i++
		} else {
			out[n] = packed[i]
		}
		n++
	}
	return out, gained, merges
}

func (g Grid) rotate() Grid { // clockwise
	var o Grid
	for r := 0; r < 4; r++ {
		for c := 0; c < 4; c++ {
			o[c][3-r] = g[r][c]
		}
	}
	return o
}

func (g Grid) apply(dir string) (Grid, int, int) {
	n := map[string]int{"left": 0, "up": 1, "right": 2, "down": 3}[dir]
	w := g
	for i := 0; i < n; i++ {
		w = w.rotate()
	}
	gained, merges := 0, 0
	for r := 0; r < 4; r++ {
		row, gg, mm := slide(w[r])
		w[r], gained, merges = row, gained+gg, merges+mm
	}
	for i := 0; i < (4-n)%4; i++ {
		w = w.rotate()
	}
	return w, gained, merges
}

func (g Grid) free() []([2]int) {
	var f [][2]int
	for r := 0; r < 4; r++ {
		for c := 0; c < 4; c++ {
			if g[r][c] == 0 {
				f = append(f, [2]int{r, c})
			}
		}
	}
	return f
}

func (g Grid) max() (int, int, int) {
	m, mr, mc := 0, 0, 0
	for r := 0; r < 4; r++ {
		for c := 0; c < 4; c++ {
			if g[r][c] > m {
				m, mr, mc = g[r][c], r, c
			}
		}
	}
	return m, mr, mc
}

func corner(r, c int) string {
	v := ""
	switch {
	case r == 0 && c == 0:
		v = "the top-left CORNER"
	case r == 0 && c == 3:
		v = "the top-right CORNER"
	case r == 3 && c == 0:
		v = "the bottom-left CORNER"
	case r == 3 && c == 3:
		v = "the bottom-right CORNER"
	case r == 0 || r == 3 || c == 0 || c == 3:
		v = "an edge"
	default:
		v = "the middle (exposed)"
	}
	return v
}

// Options computes every legal move and DESCRIBES its consequence in prose.
// This is the whole trick: the model never computes, it only judges.
func (g Grid) Options() []Move {
	var out []Move
	for _, d := range dirs {
		ng, gained, merges := g.apply(d)
		if ng == g {
			continue
		}
		m, mr, mc := ng.max()
		out = append(out, Move{Dir: d, Grid: ng, Score: gained, Merges: merges,
			Opened: len(ng.free()), MaxPos: corner(mr, mc), Legal: true})
		_ = m
	}
	return out
}

func (m Move) Describe() string {
	mx, _, _ := m.Grid.max()
	parts := []string{}
	if m.Merges == 0 {
		parts = append(parts, "merges nothing")
	} else {
		parts = append(parts, fmt.Sprintf("merges %d pair(s) for +%d points", m.Merges, m.Score))
	}
	parts = append(parts, fmt.Sprintf("leaves the largest tile (%d) in %s", mx, m.MaxPos))
	parts = append(parts, fmt.Sprintf("leaves %d empty cells", m.Opened))
	return strings.Join(parts, ", ")
}

func (g *Grid) spawn(rng *rand.Rand) bool {
	f := g.free()
	if len(f) == 0 {
		return false
	}
	p := f[rng.Intn(len(f))]
	v := 2
	if rng.Float64() < 0.1 {
		v = 4
	}
	g[p[0]][p[1]] = v
	return true
}

func (g Grid) String() string {
	var b strings.Builder
	for r := 0; r < 4; r++ {
		for c := 0; c < 4; c++ {
			if g[r][c] == 0 {
				b.WriteString("     .")
			} else {
				b.WriteString(fmt.Sprintf("%6d", g[r][c]))
			}
		}
		b.WriteString("\n")
	}
	return b.String()
}
