// Agent home — personas (SPEC §7E), the persona archetype over the shipped §7D
// runtime. Three additions, all riding shipped seams (the soul is the OnSpawn/
// session-start injection point via Def.Soul, the six verbs, the injectable
// Clock) — NO runtime changes:
//
//  1. FromDir(dir) — the directory IS the agent: the ordered bootstrap files
//     (AGENTS/SOUL/IDENTITY/USER/TOOLS/HEARTBEAT/MEMORY.md) compose the soul as
//     "## <file>" sections, each read with a 2 MB cap. Composition happens once,
//     at session start; the soul is fixed for the run (frozen snapshot — the
//     cache-stability rule, for free).
//  2. MemoryTool(dir) — one add|replace|remove tool over MEMORY.md / USER.md.
//     All actions write to DISK. It NEVER mutates the live session's prompt:
//     persisted memory loads at the START of the next session (the frozen
//     snapshot keeps a long-lived persona cache-stable). A replace/remove of a
//     substring that is absent is a loud isError. Omittable per persona.
//  3. StartAgent(agent, …) — a heartbeat: each interval posts a tick to the
//     agent's OWN inbox (the unsolicited rail — ticks coalesce) and, when idle,
//     wakes it with a "read HEARTBEAT.md and act, else HEARTBEAT_OK" prompt. A
//     HEARTBEAT_OK reply stays silent (only a substantive reply reaches onBeat).
//     All timing goes through the runtime's injectable Clock (fixtures run on a
//     virtual clock). Inbound channels are the HOST's job — deliver external
//     events by calling Post/Wake.
//
// The memory hierarchy (all opt-in, absent ⇒ byte-identical): a MEMORY.md index
// cap, a notes tier (memory target note:<slug>, read, list) and the
// ground-truth tier, tn.TranscriptSearchTool. Dreaming ships as one preset,
// Dreamer (a plain agent over memory + transcript_search), plus the FromDir
// Dream option that runs it on the persona's runtime. A channel assistant = the
// host's inbound handler calling Post/Wake.
package agents

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

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

// IndexCap is the MEMORY.md index cap (SPEC §7E "Memory hierarchy"): whole
// lines, bounded by line count and UTF-8 bytes. A zero field takes the preset's
// value; a negative one is a construction error.
type IndexCap struct {
	MaxLines int
	MaxBytes int
}

// MemoryIndexCapPreset is the preset index cap: 200 lines / 25600 bytes.
var MemoryIndexCapPreset = IndexCap{MaxLines: 200, MaxBytes: 25600}

// ComposeSoulOptions tunes ComposeSoulWith. The zero value is byte-identical to
// ComposeSoul.
type ComposeSoulOptions struct {
	// MemoryIndexCap caps the injected MEMORY.md (after the 2 MB read cap, before
	// the section trim). nil ⇒ off. &IndexCap{} ⇒ the preset.
	MemoryIndexCap *IndexCap
	// Notes selects the truncation notice that points at note:<slug>.
	Notes bool
}

func resolveIndexCap(c *IndexCap) (*IndexCap, error) {
	if c == nil {
		return nil, nil
	}
	r := *c
	if r.MaxLines == 0 {
		r.MaxLines = MemoryIndexCapPreset.MaxLines
	}
	if r.MaxBytes == 0 {
		r.MaxBytes = MemoryIndexCapPreset.MaxBytes
	}
	if r.MaxLines < 1 || r.MaxBytes < 1 {
		return nil, fmt.Errorf("memoryIndexCap: maxLines and maxBytes must be integers >= 1")
	}
	return &r, nil
}

// MemoryIndexNotice is the exact notice appended to a capped MEMORY.md.
func MemoryIndexNotice(maxLines, maxBytes int, notes bool) string {
	tail := " (keep entries short)."
	if notes {
		tail = `; move detail into notes (memory target "note:<slug>").`
	}
	return fmt.Sprintf("\n\n[MEMORY.md index truncated at %d lines / %d bytes. Keep MEMORY.md a short index%s]", maxLines, maxBytes, tail)
}

// cutUTF8 cuts s at the last whole code point whose UTF-8 end is <= max bytes.
func cutUTF8(s string, max int) string {
	end := 0
	for i, r := range s {
		n := utf8.RuneLen(r)
		if n < 0 {
			n = 1
		}
		if i+n > max {
			break
		}
		end = i + n
	}
	return s[:end]
}

// ApplyIndexCap applies the index cap to MEMORY.md's (already 2 MB-capped)
// content, exactly as SPEC §7E pins it: within both bounds ⇒ unchanged; else the
// first MaxLines lines, trailing whole lines dropped until the "\n"-joined bytes
// fit (a lone over-long line is cut at a code-point boundary), plus the notice.
func ApplyIndexCap(content string, c IndexCap, notes bool) string {
	lines := strings.Split(content, "\n")
	if strings.HasSuffix(content, "\n") {
		lines = lines[:len(lines)-1]
	}
	if len(lines) <= c.MaxLines && len(strings.Join(lines, "\n")) <= c.MaxBytes {
		return content
	}
	kept := lines[:min(len(lines), c.MaxLines)]
	for len(kept) > 1 && len(strings.Join(kept, "\n")) > c.MaxBytes {
		kept = kept[:len(kept)-1]
	}
	text := strings.Join(kept, "\n")
	if len(text) > c.MaxBytes {
		text = cutUTF8(text, c.MaxBytes)
	}
	return text + MemoryIndexNotice(c.MaxLines, c.MaxBytes, notes)
}

// ComposeSoul discovers the bootstrap files under dir and composes them into one
// soul string (the frozen snapshot), returning the composed soul and the ordered
// list of files that were found. Absent files are skipped.
func ComposeSoul(dir string) (soul string, found []string) {
	soul, found, _ = ComposeSoulWith(dir, ComposeSoulOptions{})
	return soul, found
}

// ComposeSoulWith is ComposeSoul with the memory-hierarchy options (SPEC §7E).
// It errors only on an invalid MemoryIndexCap.
func ComposeSoulWith(dir string, opts ComposeSoulOptions) (soul string, found []string, err error) {
	c, err := resolveIndexCap(opts.MemoryIndexCap)
	if err != nil {
		return "", nil, err
	}
	var sections []string
	for _, f := range BootstrapOrder {
		if body, ok := readCapped(filepath.Join(dir, f)); ok {
			if c != nil && f == "MEMORY.md" {
				body = ApplyIndexCap(body, *c, opts.Notes)
			}
			sections = append(sections, "## "+f+"\n\n"+strings.TrimSpace(body))
			found = append(found, f)
		}
	}
	return strings.Join(sections, "\n\n"), found, nil
}

// memoryDescription is the notes-off memory tool description (SPEC §7E).
const memoryDescription = "Persist durable memory. action=add appends an entry; replace swaps an " +
	"existing substring (with); remove deletes one. target=self (MEMORY.md, " +
	"default) or user (USER.md). Writes persist to disk and load at the START of " +
	"your NEXT session — they do NOT change your current context."

// MemoryNotesDescription is the notes-on memory tool description (SPEC §7E).
const MemoryNotesDescription = "Persist durable memory. MEMORY.md is a short index loaded at the start of every " +
	"session; put detail in notes (target note:<slug>, stored in memory/<slug>.md, never " +
	"loaded automatically) and point to them from the index. Record what will change a " +
	"future decision. action=add appends an entry; replace swaps the first occurrence of " +
	"an existing substring (with); remove deletes it; read returns a file; list lists the " +
	"notes. target=self (MEMORY.md, default), user (USER.md) or note:<slug> (slug: " +
	"lowercase letters, digits, '.', '_', '-'). Writes persist to disk and load at the " +
	"START of your NEXT session — they do NOT change your current context."

// noteSlugRe is a valid note slug: lowercase, no path separators (and no "..").
var noteSlugRe = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,63}$`)

func validSlug(s string) bool { return noteSlugRe.MatchString(s) && !strings.Contains(s, "..") }

// MemoryToolOptions tunes MemoryToolWith.
type MemoryToolOptions struct {
	// Notes turns on the notes tier: note:<slug> targets plus read/list. Off ⇒
	// the tool is byte-identical to MemoryTool.
	Notes bool
}

// pathLocks serializes read-modify-write per resolved absolute path within this
// process, so two memory tools over one home never lose an entry (SPEC §7E).
var pathLocks sync.Map // string → *sync.Mutex

func lockPath(p string) func() {
	key, err := filepath.Abs(p)
	if err != nil {
		key = p
	}
	if dir, err := filepath.EvalSymlinks(filepath.Dir(key)); err == nil {
		key = filepath.Join(dir, filepath.Base(key))
	}
	m, _ := pathLocks.LoadOrStore(key, &sync.Mutex{})
	mu := m.(*sync.Mutex)
	mu.Lock()
	return mu.Unlock
}

// rmw is one read-modify-write over the FULL file (never the capped view),
// under the per-path lock. A non-nil result is the error to return.
func rmw(file, action, text, with string) *tn.ToolResult {
	unlock := lockPath(file)
	defer unlock()
	curB, _ := os.ReadFile(file)
	cur := string(curB)
	var next string
	switch action {
	case "add":
		// trimEnd(cur) + "\n- text\n", then trimStart — matches the JS reference.
		next = strings.TrimLeft(strings.TrimRight(cur, " \t\r\n")+"\n- "+text+"\n", " \t\r\n")
	case "replace", "remove":
		if !strings.Contains(cur, text) {
			return &tn.ToolResult{Output: "not found: " + text, IsError: true}
		}
		next = strings.Replace(cur, text, with, 1) // first occurrence only (§7E)
	default:
		return &tn.ToolResult{Output: "unknown action: " + action, IsError: true}
	}
	if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
		return &tn.ToolResult{Output: err.Error(), IsError: true}
	}
	if err := writeFileAtomic(file, []byte(next)); err != nil {
		return &tn.ToolResult{Output: err.Error(), IsError: true}
	}
	return nil
}

func memoryProps(notes bool) map[string]any {
	action := []any{"add", "replace", "remove"}
	target := map[string]any{"type": "string", "enum": []any{"self", "user"}, "description": "self=MEMORY.md (default), user=USER.md"}
	if notes {
		action = append(action, "read", "list")
		target = map[string]any{"type": "string", "description": "self=MEMORY.md (default), user=USER.md, note:<slug>=memory/<slug>.md"}
	}
	return map[string]any{
		"action": map[string]any{"type": "string", "enum": action},
		"target": target,
		"text":   map[string]any{"type": "string", "description": "For add: the entry. For replace/remove: the existing text."},
		"with":   map[string]any{"type": "string", "description": "For replace: the replacement text."},
	}
}

// MemoryTool is the file-backed `memory` builtin (SPEC §7E / §4A note): NOT one
// of the default process-stateless builtins — it exists only for a home-wired
// persona. One tool, three actions (add/replace/remove) over MEMORY.md (self,
// default) and USER.md (user). replace/remove act on the FIRST occurrence. All
// actions write to disk (atomically, serialized per file); the write does NOT
// change the current session's prompt (frozen snapshot — it loads next session).
// A replace/remove whose substring is absent returns a loud isError.
func MemoryTool(dir string) tn.Tool {
	return MemoryToolWith(dir, MemoryToolOptions{})
}

// MemoryToolWith is MemoryTool with options. Notes on adds target note:<slug>
// (<dir>/memory/<slug>.md, never injected into the soul) and the read/list
// actions (SPEC §7E "Memory hierarchy").
func MemoryToolWith(dir string, opts MemoryToolOptions) tn.Tool {
	if opts.Notes {
		return notesMemoryTool(dir)
	}
	return tn.Tool{
		Name:        "memory",
		Description: memoryDescription,
		InputSchema: tn.JSONSchema{
			"type":       "object",
			"properties": memoryProps(false),
			"required":   []any{"action", "text"},
		},
		Source: tn.SourceCustom,
		Execute: func(args map[string]any, _ *tn.ToolContext) (tn.ToolResult, error) {
			target, _ := args["target"].(string)
			file := filepath.Join(dir, "MEMORY.md")
			if target == "user" {
				file = filepath.Join(dir, "USER.md")
			}
			action, _ := args["action"].(string)
			text, _ := args["text"].(string)
			with, _ := args["with"].(string)
			if action == "remove" {
				with = ""
			}
			if r := rmw(file, action, text, with); r != nil {
				return *r, nil
			}
			return tn.ToolResult{Output: fmt.Sprintf("ok (%s → %s); loads next session", action, filepath.Base(file))}, nil
		},
	}
}

func notesMemoryTool(dir string) tn.Tool {
	notesDir := filepath.Join(dir, "memory")
	return tn.Tool{
		Name:        "memory",
		Description: MemoryNotesDescription,
		InputSchema: tn.JSONSchema{
			"type":       "object",
			"properties": memoryProps(true),
			"required":   []any{"action"},
		},
		Source: tn.SourceCustom,
		Execute: func(args map[string]any, _ *tn.ToolContext) (tn.ToolResult, error) {
			action := fmt.Sprint(args["action"])
			if action == "list" {
				return tn.ToolResult{Output: listNotes(notesDir)}, nil
			}
			target := "self"
			if t, present := args["target"]; present && t != nil {
				target = fmt.Sprint(t)
			}
			var file, label string
			switch {
			case target == "self":
				file, label = filepath.Join(dir, "MEMORY.md"), "MEMORY.md"
			case target == "user":
				file, label = filepath.Join(dir, "USER.md"), "USER.md"
			case strings.HasPrefix(target, "note:"):
				slug := strings.TrimPrefix(target, "note:")
				if !validSlug(slug) {
					return tn.ToolResult{Output: "invalid note slug: " + slug, IsError: true}, nil
				}
				file, label = filepath.Join(notesDir, slug+".md"), "memory/"+slug+".md"
			default:
				return tn.ToolResult{Output: "unknown target: " + target, IsError: true}, nil
			}
			if action == "read" {
				body, ok := readCapped(file)
				if !ok {
					return tn.ToolResult{Output: "not found: " + target, IsError: true}, nil
				}
				return tn.ToolResult{Output: body}, nil
			}
			if action != "add" && action != "replace" && action != "remove" {
				return tn.ToolResult{Output: "unknown action: " + action, IsError: true}, nil
			}
			text, ok := args["text"].(string)
			if !ok {
				return tn.ToolResult{Output: "text is required", IsError: true}, nil
			}
			with := ""
			if action == "replace" {
				if w, present := args["with"]; present && w != nil {
					with = fmt.Sprint(w)
				}
			}
			if r := rmw(file, action, text, with); r != nil {
				return *r, nil
			}
			if strings.HasPrefix(label, "memory/") {
				return tn.ToolResult{Output: fmt.Sprintf("ok (%s → %s)", action, label)}, nil
			}
			return tn.ToolResult{Output: fmt.Sprintf("ok (%s → %s); loads next session", action, label)}, nil
		},
	}
}

// listNotes is the `list` output: "<slug>\t<bytes>" per note, slug-ascending
// by explicit sort (never directory order); "(no notes)" when none.
func listNotes(notesDir string) string {
	entries, _ := os.ReadDir(notesDir)
	type row struct {
		slug string
		size int64
	}
	var rows []row
	for _, e := range entries {
		name := e.Name()
		if !strings.HasSuffix(name, ".md") {
			continue
		}
		slug := strings.TrimSuffix(name, ".md")
		if !validSlug(slug) {
			continue
		}
		fi, err := os.Stat(filepath.Join(notesDir, name))
		if err != nil || !fi.Mode().IsRegular() {
			continue
		}
		rows = append(rows, row{slug, fi.Size()})
	}
	if len(rows) == 0 {
		return "(no notes)"
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].slug < rows[j].slug })
	out := make([]string, len(rows))
	for i, r := range rows {
		out[i] = fmt.Sprintf("%s\t%d", r.slug, r.size)
	}
	return strings.Join(out, "\n")
}

// writeFileAtomic writes data to a temp file in the same directory, flushes it
// (fsync) and renames it over p, so a crash mid-write never leaves a partial
// memory file. An existing file's permission bits are kept.
func writeFileAtomic(p string, data []byte) error {
	// OpenFile with 0o666 (not CreateTemp's 0o600) so a new file gets 0666 &^ umask,
	// exactly as os.WriteFile would; O_EXCL guards the unique name.
	name := filepath.Join(filepath.Dir(p), fmt.Sprintf(".%s.tmp-%d-%d", filepath.Base(p), os.Getpid(), time.Now().UnixNano()))
	tmp, err := os.OpenFile(name, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o666)
	if err != nil {
		return err
	}
	_, err = tmp.Write(data)
	if err == nil {
		err = tmp.Sync()
	}
	// Keep an existing file's permission bits (the rename would otherwise give it the temp's).
	if fi, serr := os.Stat(p); err == nil && serr == nil && runtime.GOOS != "windows" {
		err = tmp.Chmod(fi.Mode().Perm())
	}
	if cerr := tmp.Close(); err == nil {
		err = cerr
	}
	if err == nil {
		err = os.Rename(name, p)
	}
	if err != nil {
		_ = os.Remove(name)
	}
	return err
}

// DreamOptions schedules a dreamer next to a FromDir persona (SPEC §7E "Dreaming").
type DreamOptions struct {
	// EveryMs is the dream interval in milliseconds (must be > 0).
	EveryMs int64
	// Store holds the transcripts to verify memory against; it must implement List.
	Store tn.ConversationStore
	// Instructions is host prose appended after DreamPrompt (and DREAM.md).
	Instructions string
}

type dreamConfig struct {
	dreamer *Agent
	everyMs int64
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
	// MemoryIndexCap caps the injected MEMORY.md (&IndexCap{} ⇒ 200 lines /
	// 25600 bytes). nil ⇒ off.
	MemoryIndexCap *IndexCap
	// Notes turns on the notes tier of the memory tool (note:<slug>, read, list).
	Notes bool
	// Dream, when set, runs a Dreamer over the same dir on the same runtime
	// every EveryMs once the persona is started with StartAgent. nil ⇒ off.
	Dream *DreamOptions
}

// FromDir builds a persona Agent from a directory: it composes the bootstrap
// files into the soul (frozen snapshot) and wires the memory tool over the same
// directory (unless disabled). The returned Agent runs like any other — Run for
// a one-shot, StartAgent for a heartbeat loop.
//
// FromDir panics on an invalid configuration (a MemoryIndexCap field < 0, a
// Dream with EveryMs <= 0 or a store without List) — a programmer error.
func FromDir(dir string, opts FromDirOptions) *Agent {
	soul, _, err := ComposeSoulWith(dir, ComposeSoulOptions{MemoryIndexCap: opts.MemoryIndexCap, Notes: opts.Notes})
	if err != nil {
		panic(err)
	}
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
		tools = append(tools, MemoryToolWith(dir, MemoryToolOptions{Notes: opts.Notes}))
	}
	persona := New(name, Spec{Does: does, Soul: soul, Model: model, Tools: tools})
	if opts.Dream != nil {
		if opts.Dream.EveryMs <= 0 {
			panic("fromDir: dream.everyMs must be > 0")
		}
		d, err := Dreamer(dir, DreamerOptions{Store: opts.Dream.Store, Instructions: opts.Dream.Instructions, Name: name + "-dreamer"})
		if err != nil {
			panic(err)
		}
		persona.dream = &dreamConfig{dreamer: d, everyMs: opts.Dream.EveryMs}
	}
	return persona
}

// DreamPrompt is what a dream pass is FOR and the order it works in (SPEC §7E
// "Dreaming"), byte-identical in every port. It becomes the dreamer's
// "## HEARTBEAT.md" section, so the ordinary HeartbeatPrompt wake drives a pass
// and HEARTBEAT_OK keeps an idle pass silent.
const DreamPrompt = "You are dreaming: an offline pass over your own memory. Memory exists so that a future session decides better. MEMORY.md is a short index loaded at the start of every session; notes (memory target \"note:<slug>\") hold the detail; the transcripts (transcript_search) are the ground truth of what actually happened.\n" +
	"Work in this order:\n" +
	"1. Verify: read MEMORY.md and the notes it points to (memory read, memory list) and check each claim against the transcripts. Fix a claim the transcripts contradict; remove one they no longer support.\n" +
	"2. Demote: move detail out of MEMORY.md into a note and leave a one-line [[slug]] pointer, so the index stays short.\n" +
	"3. Merge: fold duplicate or conflicting notes into one.\n" +
	"4. Repair links: point every [[slug]] at an existing note or create it; give every note an index line.\n" +
	"5. Log: if you changed anything, append exactly one entry with memory add, target \"note:log\", text starting \"dream: \" and naming every change and why.\n" +
	"If nothing needs changing, write nothing and reply HEARTBEAT_OK."

// DreamerOptions tunes Dreamer.
type DreamerOptions struct {
	// Store holds the transcripts to verify memory against — must implement List. Required.
	Store tn.ConversationStore
	// Instructions is host prose appended after DreamPrompt (and DREAM.md, if present).
	Instructions string
	// Name defaults to "<basename(dir)>-dreamer".
	Name string
	// Model defaults to "inherit" — the persona's client (use a cheap model there).
	Model string
}

// Dreamer is the built-in dreamer preset (SPEC §7E "Dreaming"): a plain Agent
// whose tools are EXACTLY memory (notes on, over dir) and transcript_search
// (over Store) — never the persona's own tools. Its soul is one "## HEARTBEAT.md"
// section holding DreamPrompt, then DREAM.md from dir (trimmed, if non-empty),
// then Instructions (trimmed, if non-empty), joined by blank lines. Schedule it
// with StartAgent or run one pass with Run(opts, HeartbeatPrompt).
func Dreamer(dir string, opts DreamerOptions) (*Agent, error) {
	if opts.Store == nil {
		return nil, fmt.Errorf("dreamer: store is required")
	}
	search, err := tn.TranscriptSearchTool(opts.Store, tn.TranscriptSearchOptions{})
	if err != nil {
		return nil, err
	}
	parts := []string{DreamPrompt}
	if body, ok := readCapped(filepath.Join(dir, "DREAM.md")); ok {
		if t := strings.TrimSpace(body); t != "" {
			parts = append(parts, t)
		}
	}
	if t := strings.TrimSpace(opts.Instructions); t != "" {
		parts = append(parts, t)
	}
	name := opts.Name
	if name == "" {
		name = filepath.Base(dir) + "-dreamer"
	}
	model := opts.Model
	if model == "" {
		model = "inherit"
	}
	return New(name, Spec{
		Does:  "dreams over the memory in " + dir,
		Soul:  "## HEARTBEAT.md\n\n" + strings.Join(parts, "\n\n"),
		Model: model,
		Tools: []tn.Tool{MemoryToolWith(dir, MemoryToolOptions{Notes: true}), search},
	}), nil
}

// HeartbeatPrompt is the wake prompt of every heartbeat.
const HeartbeatPrompt = "Heartbeat. Read your HEARTBEAT.md section and follow it. If nothing needs " +
	"attention, reply " + HeartbeatOK + "."

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
//
// A persona built by FromDir with Dream also gets its dreamer registered on the
// SAME runtime, spawned under the root and given its own heartbeat; dreamer
// replies never reach OnBeat or Beats. Stop stops both.
func StartAgent(a *Agent, opts StartOptions) (*StartedAgent, error) {
	ro := opts.Options
	ro.Registry = a.Registry()
	if a.dream != nil {
		for k, v := range a.dream.dreamer.Registry() {
			if _, taken := ro.Registry[k]; !taken {
				ro.Registry[k] = v
			}
		}
	}
	rt := NewRuntime(ro)
	h, err := rt.Spawn(rt.Root, a.Name, nil)
	if err != nil {
		return nil, err
	}
	sa := &StartedAgent{rt: rt, handle: h, stopCh: make(chan struct{}), beat: make(chan struct{}, 1)}
	sa.heartbeat(h, opts.EveryMs, func(text string) {
		sa.mu.Lock()
		sa.beats = append(sa.beats, text)
		sa.mu.Unlock()
		if opts.OnBeat != nil {
			opts.OnBeat(text)
		}
	}, sa.pulse)
	if a.dream != nil {
		// The dreamer's replies are housekeeping — what it did is in memory/log.md.
		dh, err := rt.Spawn(rt.Root, a.dream.dreamer.Name, nil)
		if err != nil {
			sa.Stop()
			return nil, err
		}
		sa.heartbeat(dh, a.dream.everyMs, func(string) {}, func() {})
	}
	return sa, nil
}

// heartbeat runs one self-rescheduling heartbeat loop on h until Stop.
func (s *StartedAgent) heartbeat(h *Handle, everyMs int64, onText func(string), pulse func()) {
	every := time.Duration(everyMs) * time.Millisecond
	clock := s.rt.clock // the injectable seam — real clock in prod, virtual in fixtures
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		for {
			select {
			case <-s.stopCh:
				return
			case <-clock.After(every):
			}
			// Unsolicited rail: the tick lands in the inbox (coalesces); a wake at
			// idle drains the whole inbox as ONE turn.
			s.rt.Post(h, InboxItem{From: "clock", Channel: "timer", Text: "tick"})
			if s.rt.StateOf(h) == StateIdle && s.rt.Wake(h, HeartbeatPrompt).Ok {
				s.wg.Add(1)
				go func() {
					defer s.wg.Done()
					r := s.rt.Wait(h, 0)
					if !r.IsError && !strings.Contains(r.Text, HeartbeatOK) {
						onText(r.Text)
					}
					pulse()
				}()
			} else {
				pulse()
			}
		}
	}()
}

// Stop ends the heartbeat loop and closes the runtime (stop-all at the root).
func (s *StartedAgent) Stop() {
	close(s.stopCh)
	s.wg.Wait()
	s.rt.Close(s.rt.Root, nil)
}
