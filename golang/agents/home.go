// Agent home — personas (SPEC §7E), the persona archetype over the shipped §7D
// runtime. Three additions, all riding shipped seams (the soul is the OnSpawn/
// session-start injection point via Def.Soul, the six verbs, the injectable
// Clock) — NO runtime changes:
//
//   1. FromDir(dir) — the directory IS the agent: the ordered bootstrap files
//      (AGENTS/SOUL/IDENTITY/USER/TOOLS/HEARTBEAT/MEMORY.md) compose the soul as
//      "## <file>" sections, each read with a 2 MB cap. Composition happens once,
//      at session start; the soul is fixed for the run (frozen snapshot — the
//      cache-stability rule, for free).
//   2. MemoryTool(dir) — one add|replace|remove tool over MEMORY.md / USER.md.
//      All actions write to DISK. It NEVER mutates the live session's prompt:
//      persisted memory loads at the START of the next session (the frozen
//      snapshot keeps a long-lived persona cache-stable). A replace/remove of a
//      substring that is absent is a loud isError. Omittable per persona.
//   3. StartAgent(agent, …) — a heartbeat: each interval posts a tick to the
//      agent's OWN inbox (the unsolicited rail — ticks coalesce) and, when idle,
//      wakes it with a "read HEARTBEAT.md and act, else HEARTBEAT_OK" prompt. A
//      HEARTBEAT_OK reply stays silent (only a substantive reply reaches onBeat).
//      All timing goes through the runtime's injectable Clock (fixtures run on a
//      virtual clock). Inbound channels are the HOST's job — deliver external
//      events by calling Post/Wake.
//
// Higher patterns are recipes over the above, no new surface: dream/consolidation
// = a StartAgent whose HEARTBEAT.md folds notes into MEMORY.md via the memory
// tool; a channel assistant = the host's inbound handler calling Post/Wake.
package agents

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	tn "github.com/muthuishere/toolnexus/golang"
)

// BootstrapOrder is the canonical discovery order (openclaw convention —
// identity first, memory last). Each present file is injected into the soul as a
// "## <filename>" section, in this order.
var BootstrapOrder = []string{
	"AGENTS.md", "SOUL.md", "IDENTITY.md", "USER.md", "TOOLS.md", "HEARTBEAT.md", "MEMORY.md",
}

// maxBootstrapBytes caps each bootstrap file; a larger file is truncated with a
// notice (the on-disk file is untouched).
const maxBootstrapBytes = 2 * 1024 * 1024

// HeartbeatOK is the sentinel a persona replies with when a beat needs no action;
// such a reply is silent (no report reaches onBeat).
const HeartbeatOK = "HEARTBEAT_OK"

// truncationNotice is appended to a bootstrap file that exceeds the byte cap.
const truncationNotice = "\n[truncated: exceeds 2 MB bootstrap cap]"

// readCapped reads a bootstrap file with the byte cap; ok=false when absent.
func readCapped(p string) (string, bool) {
	b, err := os.ReadFile(p)
	if err != nil {
		return "", false
	}
	if len(b) > maxBootstrapBytes {
		return string(b[:maxBootstrapBytes]) + truncationNotice, true
	}
	return string(b), true
}

// ComposeSoul discovers the bootstrap files under dir and composes them into one
// soul string (the frozen snapshot), returning the composed soul and the ordered
// list of files that were found. Absent files are skipped.
func ComposeSoul(dir string) (soul string, found []string) {
	var sections []string
	for _, f := range BootstrapOrder {
		if body, ok := readCapped(filepath.Join(dir, f)); ok {
			sections = append(sections, "## "+f+"\n\n"+strings.TrimSpace(body))
			found = append(found, f)
		}
	}
	return strings.Join(sections, "\n\n"), found
}

// MemoryTool is the file-backed `memory` builtin (SPEC §7E / §4A note): NOT one
// of the default process-stateless builtins — it exists only for a home-wired
// persona. One tool, three actions (add/replace/remove) over MEMORY.md (self,
// default) and USER.md (user). All actions write to disk; the write does NOT
// change the current session's prompt (frozen snapshot — it loads next session).
// A replace/remove whose substring is absent returns a loud isError.
func MemoryTool(dir string) tn.Tool {
	fileFor := func(target string) string {
		if target == "user" {
			return filepath.Join(dir, "USER.md")
		}
		return filepath.Join(dir, "MEMORY.md")
	}
	return tn.Tool{
		Name: "memory",
		Description: "Persist durable memory. action=add appends an entry; replace swaps an " +
			"existing substring; remove deletes one. target=self (MEMORY.md, default) or user " +
			"(USER.md). Writes persist to disk and load at the START of your next session — they " +
			"do NOT change your current context.",
		InputSchema: tn.JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"action": map[string]any{"type": "string", "enum": []any{"add", "replace", "remove"}},
				"target": map[string]any{"type": "string", "enum": []any{"self", "user"}},
				"text":   map[string]any{"type": "string", "description": "For add: the entry. For replace/remove: the existing text."},
				"with":   map[string]any{"type": "string", "description": "For replace: the replacement text."},
			},
			"required": []any{"action", "text"},
		},
		Source: tn.SourceCustom,
		Execute: func(args map[string]any, _ *tn.ToolContext) (tn.ToolResult, error) {
			target, _ := args["target"].(string)
			file := fileFor(target)
			action, _ := args["action"].(string)
			text, _ := args["text"].(string)
			curB, _ := os.ReadFile(file)
			cur := string(curB)
			var next string
			switch action {
			case "add":
				// trimEnd(cur) + "\n- text\n", then trimStart — matches the JS reference.
				next = strings.TrimLeft(strings.TrimRight(cur, " \t\r\n")+"\n- "+text+"\n", " \t\r\n")
			case "replace":
				if !strings.Contains(cur, text) {
					return tn.ToolResult{Output: "not found: " + text, IsError: true}, nil
				}
				with, _ := args["with"].(string)
				next = strings.Replace(cur, text, with, 1)
			case "remove":
				if !strings.Contains(cur, text) {
					return tn.ToolResult{Output: "not found: " + text, IsError: true}, nil
				}
				next = strings.Replace(cur, text, "", 1)
			default:
				return tn.ToolResult{Output: "unknown action: " + action, IsError: true}, nil
			}
			if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
				return tn.ToolResult{Output: err.Error(), IsError: true}, nil
			}
			if err := os.WriteFile(file, []byte(next), 0o644); err != nil {
				return tn.ToolResult{Output: err.Error(), IsError: true}, nil
			}
			return tn.ToolResult{Output: fmt.Sprintf("ok (%s → %s); loads next session", action, filepath.Base(file))}, nil
		},
	}
}

// FromDirOptions tunes FromDir.
type FromDirOptions struct {
	// Does overrides the routing description (default "persona agent from <dir>").
	Does string
	// Name overrides the agent name (default filepath.Base(dir)).
	Name string
	// Model overrides the model id (default "inherit").
	Model string
	// Tools are extra tools beyond the memory builtin.
	Tools []tn.Tool
	// Memory gates the memory builtin: nil ⇒ enabled (default). Point it at a
	// false to omit the memory tool (a read-only persona).
	Memory *bool
}

// FromDir builds a persona Agent from a directory: it composes the bootstrap
// files into the soul (frozen snapshot) and wires the memory tool over the same
// directory (unless disabled). The returned Agent runs like any other — Run for
// a one-shot, StartAgent for a heartbeat loop.
func FromDir(dir string, opts FromDirOptions) *Agent {
	soul, _ := ComposeSoul(dir)
	name := opts.Name
	if name == "" {
		name = filepath.Base(dir)
	}
	does := opts.Does
	if does == "" {
		does = "persona agent from " + dir
	}
	model := opts.Model
	if model == "" {
		model = "inherit"
	}
	tools := append([]tn.Tool(nil), opts.Tools...)
	if opts.Memory == nil || *opts.Memory {
		tools = append(tools, MemoryTool(dir))
	}
	return New(name, Spec{Does: does, Soul: soul, Model: model, Tools: tools})
}

// StartOptions configures StartAgent. The embedded Options carry the runtime
// wiring (Transport/LLM/Clock/…); StartAgent fills in Registry from the agent.
type StartOptions struct {
	Options
	// EveryMs is the heartbeat interval in milliseconds.
	EveryMs int64
	// OnBeat receives every SUBSTANTIVE beat (HEARTBEAT_OK replies are silent).
	OnBeat func(text string)
}

// StartedAgent is a running persona heartbeat. Stop tears it down.
type StartedAgent struct {
	rt     *Runtime
	handle *Handle
	stopCh chan struct{}
	wg     sync.WaitGroup

	mu    sync.Mutex
	beats []string

	// beat pulses (buffered, coalescing) after each beat cycle completes — the
	// deterministic sync point for a virtual-clock fixture test.
	beat chan struct{}
}

// Beats returns a snapshot of the substantive beats collected so far.
func (s *StartedAgent) Beats() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.beats...)
}

// Handle exposes the persona's runtime handle so a host can deliver inbound
// channel events via Post/Wake (channels are the host's job).
func (s *StartedAgent) Handle() *Handle { return s.handle }

// Runtime exposes the underlying runtime (List/Inspect/Post/Wake).
func (s *StartedAgent) Runtime() *Runtime { return s.rt }

func (s *StartedAgent) pulse() {
	select {
	case s.beat <- struct{}{}:
	default:
	}
}

// StartAgent gives a persona its own clock. Each EveryMs interval it posts a tick
// to the agent's own inbox (coalescing on the unsolicited rail) and, when idle,
// wakes it with the heartbeat prompt; a HEARTBEAT_OK reply stays silent. All
// timing goes through the runtime's injectable Clock. Stop the returned handle to
// tear the loop (and the runtime) down.
func StartAgent(a *Agent, opts StartOptions) (*StartedAgent, error) {
	ro := opts.Options
	ro.Registry = a.Registry()
	rt := NewRuntime(ro)
	h, err := rt.Spawn(rt.Root, a.Name, nil)
	if err != nil {
		return nil, err
	}
	sa := &StartedAgent{rt: rt, handle: h, stopCh: make(chan struct{}), beat: make(chan struct{}, 1)}
	every := time.Duration(opts.EveryMs) * time.Millisecond
	clock := rt.clock // the injectable seam — real clock in prod, virtual in fixtures
	const prompt = "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs " +
		"attention, reply " + HeartbeatOK + "."

	sa.wg.Add(1)
	go func() {
		defer sa.wg.Done()
		for {
			select {
			case <-sa.stopCh:
				return
			case <-clock.After(every):
			}
			// Unsolicited rail: the tick lands in the inbox (coalesces); a wake at
			// idle drains the whole inbox as ONE turn.
			sa.rt.Post(h, InboxItem{From: "clock", Channel: "timer", Text: "tick"})
			if sa.rt.StateOf(h) == StateIdle && sa.rt.Wake(h, prompt).Ok {
				sa.wg.Add(1)
				go func() {
					defer sa.wg.Done()
					r := sa.rt.Wait(h, 0)
					if !r.IsError && !strings.Contains(r.Text, HeartbeatOK) {
						sa.mu.Lock()
						sa.beats = append(sa.beats, r.Text)
						sa.mu.Unlock()
						if opts.OnBeat != nil {
							opts.OnBeat(r.Text)
						}
					}
					sa.pulse()
				}()
			} else {
				sa.pulse()
			}
		}
	}()
	return sa, nil
}

// Stop ends the heartbeat loop and closes the runtime (stop-all at the root).
func (s *StartedAgent) Stop() {
	close(s.stopCh)
	s.wg.Wait()
	s.rt.Close(s.rt.Root, nil)
}
