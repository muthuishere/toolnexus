// MCP 2026-07-28 server source: discover-first connect, tools/list → Tool,
// tools/call result mapping (same as §2.F), MRTR input_required → §10 (§2.5),
// OAuth pause → §10, tool search (§2.8) and the skills extension (§2.7).

package toolnexus

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode"
)

// mcp26Server is one server connected on the 2026 path.
type mcp26Server struct {
	name    string
	cfg     ServerConfig
	c       *mcp26Client
	waitFor func(Request) (Answer, error)
	timeout time.Duration

	mu     sync.Mutex
	paused map[string]*mcp26CallState // §10 Request.ID → paused tools/call

	toolMu     sync.Mutex
	toolCache  []jsonObj
	toolExpiry time.Time // zero = cached for the life of the load

	discovery *mcp26Discovery
	skillDir  string          // temp dir holding downloaded remote skills ("" = none)
	disabled  map[string]bool // toolkit DisableTools (final names)
}

// mcp26CallState is a tools/call between MRTR rounds or paused on §10.
type mcp26CallState struct {
	tool      string
	args      jsonObj
	responses jsonObj // inputResponses for the next send
	state     any     // requestState echoed verbatim
	round     int
	reqs      jsonObj  // this round's inputRequests
	keys      []string // inputRequests still to answer (sorted)
	curKey    string   // key a paused input Request answers
	auth      bool     // paused on OAuth (Request kind authorization from §2A)
}

type mcpConnectOpts struct {
	waitFor    func(Request) (Answer, error)
	tokenStore TokenStore
	openURL    func(string) error
	disabled   map[string]bool
}

// connect2026 probes a server with server/discover. fallback=true ⇒ the caller
// uses the frozen SDK path (§2.2a); a non-nil err with fallback=false is a load
// failure. On fallback the returned auth engine (may be nil) carries any token
// obtained during the probe.
func connect2026(ctx context.Context, name string, cfg ServerConfig, o mcpConnectOpts) (s *mcp26Server, auth *mcpOAuth, fallback bool, err error) {
	timeout := cfg.timeout()
	c := &mcp26Client{url: cfg.URL, headers: cfg.Headers}
	if cfg.isRemote() {
		c.auth = newMcpOAuth(cfg.Auth, o.tokenStore, o.openURL, o.waitFor)
		auth = c.auth
	} else {
		if len(cfg.Command) == 0 {
			return nil, nil, false, fmt.Errorf("local server %q has no command", name)
		}
		st, sErr := startMcp26Stdio(cfg)
		if sErr != nil {
			return nil, nil, false, sErr
		}
		c.stdio = st
	}
	probe := timeout
	if c.stdio != nil && probe > 5*time.Second {
		probe = 5 * time.Second
	}
	pctx, cancel := context.WithTimeout(ctx, probe)
	disc, dErr := c.discover(pctx)
	cancel()
	if dErr != nil && c.stdio != nil && errors.Is(dErr, context.DeadlineExceeded) && ctx.Err() == nil {
		dErr = errMcpStdioNoReply
	}
	if dErr != nil {
		if c.stdio != nil {
			c.stdio.kill() // the frozen path starts its own process
		}
		if ctx.Err() != nil {
			return nil, nil, false, ctx.Err()
		}
		if discoverFallback(dErr) {
			return nil, auth, true, nil
		}
		return nil, nil, false, dErr
	}
	s = &mcp26Server{name: name, cfg: cfg, c: c, waitFor: o.waitFor, timeout: timeout, paused: map[string]*mcp26CallState{}, disabled: o.disabled, discovery: disc}
	return s, nil, false, nil
}

func (s *mcp26Server) close() {
	s.c.close()
	if s.skillDir != "" {
		_ = os.RemoveAll(s.skillDir)
	}
}

// listTools fetches tools/list, cached for its ttlMs (no hint ⇒ once per load).
func (s *mcp26Server) listTools(ctx context.Context) ([]jsonObj, error) {
	s.toolMu.Lock()
	defer s.toolMu.Unlock()
	if s.toolCache != nil && (s.toolExpiry.IsZero() || time.Now().Before(s.toolExpiry)) {
		return s.toolCache, nil
	}
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	r, rawRes, err := s.c.callRaw(ctx, "tools/list", nil)
	if err != nil {
		return nil, err
	}
	var rawTools struct {
		Tools []struct {
			InputSchema json.RawMessage `json:"inputSchema"`
		} `json:"tools"`
	}
	_ = json.Unmarshal(rawRes, &rawTools)
	tools := []jsonObj{}
	raw, _ := r["tools"].([]any)
	for i, t := range raw {
		if m, ok := t.(map[string]any); ok {
			if _, ok := m["name"].(string); ok {
				if i < len(rawTools.Tools) && len(rawTools.Tools[i].InputSchema) > 0 {
					m["__raw_schema"] = rawTools.Tools[i].InputSchema
				}
				tools = append(tools, m)
			}
		}
	}
	s.toolCache, s.toolExpiry = tools, time.Time{}
	if ttl, _ := r["ttlMs"].(float64); ttl > 0 {
		s.toolExpiry = time.Now().Add(time.Duration(ttl) * time.Millisecond)
	}
	return tools, nil
}

// tools builds this server's Tools: every listed tool (filtered by cfg.Tools),
// or — with toolSearch — the two meta-tools.
func (s *mcp26Server) tools(ctx context.Context) ([]Tool, error) {
	defs, err := s.listTools(ctx)
	if err != nil {
		return nil, err
	}
	defs = s.allowed(defs)
	if s.cfg.ToolSearch {
		return s.searchTools(), nil
	}
	out := make([]Tool, 0, len(defs))
	for _, d := range defs {
		tool := d["name"].(string)
		desc, _ := d["description"].(string)
		out = append(out, Tool{
			Name:        Sanitize(s.name) + "_" + Sanitize(tool),
			Description: desc,
			InputSchema: buildInputSchemaJSON(d),
			Source:      SourceMCP,
			Hints:       mcpHintsJSON(d["annotations"]),
			Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
				return s.execute(tool, args, tctx), nil
			},
		})
	}
	return out, nil
}

// allowed applies the per-server `tools` filter and the toolkit DisableTools
// (by final name) — before tool search, so hidden tools cannot be found or called.
func (s *mcp26Server) allowed(defs []jsonObj) []jsonObj {
	defs = applyNameFilter(s.name, defs, func(d jsonObj) string { return d["name"].(string) }, s.cfg.Tools)
	if len(s.disabled) == 0 {
		return defs
	}
	out := make([]jsonObj, 0, len(defs))
	for _, d := range defs {
		if !s.disabled[Sanitize(s.name)+"_"+Sanitize(d["name"].(string))] {
			out = append(out, d)
		}
	}
	return out
}

func (s *mcp26Server) toolInfos(ctx context.Context) ([]ToolInfo, error) {
	defs, err := s.listTools(ctx)
	if err != nil {
		return nil, err
	}
	infos := make([]ToolInfo, 0, len(defs))
	for _, d := range defs {
		desc, _ := d["description"].(string)
		infos = append(infos, ToolInfo{Name: d["name"].(string), Description: desc, InputSchema: buildInputSchemaJSON(d)})
	}
	return infos, nil
}

var mrtrSeq atomic.Uint64

func (s *mcp26Server) errResult(msg string) ToolResult {
	return ToolResult{Output: msg, IsError: true, Metadata: map[string]any{"server": s.name}}
}

// execute runs tools/call with MRTR + OAuth pause routed through §10. A
// re-execution carrying tctx.Answer for a paused call resumes that exact round.
func (s *mcp26Server) execute(tool string, args map[string]any, tctx *ToolContext) ToolResult {
	parent := context.Background()
	timeout := s.timeout
	var answer *Answer
	if tctx != nil {
		if tctx.Ctx != nil {
			parent = tctx.Ctx
		}
		if tctx.Timeout > 0 {
			timeout = time.Duration(tctx.Timeout) * time.Millisecond
		}
		answer = tctx.Answer
	}
	var st *mcp26CallState
	if answer != nil {
		s.mu.Lock()
		st = s.paused[answer.ID]
		delete(s.paused, answer.ID)
		s.mu.Unlock()
		if st == nil && strings.HasPrefix(answer.ID, "auth-") {
			return s.errResult(fmt.Sprintf("unknown pending authorization %q", answer.ID))
		}
	}
	if st == nil {
		if args == nil {
			args = map[string]any{}
		}
		st = &mcp26CallState{tool: tool, args: args}
		answer = nil
	}
	if answer != nil {
		if st.auth {
			cb, err := callbackFromAnswer(*answer)
			if err == nil {
				actx, acancel := context.WithTimeout(parent, timeout)
				err = s.c.auth.resume(actx, answer.ID, cb)
				acancel()
			}
			if err != nil {
				return s.errResult(err.Error())
			}
		} else {
			st.responses[st.curKey] = answerToInputResponse(*answer)
		}
		st.auth = false
	}
	for st.round < 8 {
		for len(st.keys) > 0 {
			k := st.keys[0]
			st.keys = st.keys[1:]
			req, err := inputRequestToRequest(st.reqs[k])
			if err != nil {
				return s.errResult(fmt.Sprintf("tools/call %s: %v", st.tool, err))
			}
			if s.waitFor == nil {
				st.curKey = k
				s.park(req.ID, st)
				return Pending(req)
			}
			ans, err := s.waitFor(req)
			if err != nil {
				return s.errResult(err.Error())
			}
			st.responses[k] = answerToInputResponse(ans)
		}
		p := jsonObj{"name": st.tool, "arguments": st.args}
		if st.responses != nil {
			p["inputResponses"] = st.responses
		}
		if st.state != nil {
			p["requestState"] = st.state
		}
		ctx, cancel := context.WithTimeout(parent, timeout)
		res, err := s.c.call(ctx, "tools/call", p)
		cancel()
		var ap *mcpAuthPending
		if errors.As(err, &ap) {
			// Rejected before dispatch: the server has NOT run this round. Resume sends it once.
			st.auth = true
			s.park(ap.Request.ID, st)
			return Pending(ap.Request)
		}
		if err != nil {
			if errors.Is(err, context.DeadlineExceeded) && parent.Err() == nil {
				return s.errResult(fmt.Sprintf("%v after %dms", errMcpIdleTimeout, timeout.Milliseconds()))
			}
			return s.errResult(err.Error())
		}
		switch res["resultType"] {
		case "complete":
			return mapCallResult(s.name, res)
		case "input_required":
			st.state = res["requestState"]
			st.responses = jsonObj{}
			st.reqs, _ = res["inputRequests"].(map[string]any)
			st.keys = st.keys[:0]
			for k := range st.reqs {
				st.keys = append(st.keys, k)
			}
			sort.Strings(st.keys)
			st.round++
		default:
			return s.errResult(fmt.Sprintf("tools/call %s: unknown resultType %v", st.tool, res["resultType"]))
		}
	}
	return s.errResult(fmt.Sprintf("tools/call %s: too many input_required rounds", st.tool))
}

func (s *mcp26Server) park(id string, st *mcp26CallState) {
	s.mu.Lock()
	s.paused[id] = st
	s.mu.Unlock()
}

// inputRequestToRequest maps one MRTR input request onto a §10 Request (§2.5).
func inputRequestToRequest(v any) (Request, error) {
	r, ok := v.(map[string]any)
	if !ok {
		return Request{}, errors.New("malformed input request")
	}
	if m, _ := r["method"].(string); m != "elicitation/create" {
		return Request{}, fmt.Errorf("unsupported input request method %q", m)
	}
	p, _ := r["params"].(map[string]any)
	msg, _ := p["message"].(string)
	req := Request{ID: "mrtr-" + strconv.FormatInt(time.Now().UnixMilli(), 36) + "-" + strconv.FormatUint(mrtrSeq.Add(1), 10), Prompt: msg}
	if p["mode"] == "url" {
		req.Kind = "authorization"
		req.URL, _ = p["url"].(string)
		return req, nil
	}
	req.Kind = "input"
	if sc, ok := p["requestedSchema"]; ok && sc != nil {
		req.Data = map[string]any{"schema": sc}
	}
	return req, nil
}

// answerToInputResponse is the §2.F elicitation mapping: ok⇒accept; declined⇒decline; else cancel.
func answerToInputResponse(a Answer) jsonObj {
	if a.Ok {
		content := a.Data
		if content == nil {
			content = map[string]any{}
		}
		return jsonObj{"action": "accept", "content": content}
	}
	if a.Reason == "declined" {
		return jsonObj{"action": "decline"}
	}
	return jsonObj{"action": "cancel"}
}

// ---------------------------------------------------------------------------
// Result mapping over raw JSON — identical to the §2.F SDK-path mapping.
// ---------------------------------------------------------------------------

func mapCallResult(server string, res jsonObj) ToolResult {
	meta := map[string]any{"server": server}
	content, _ := res["content"].([]any)
	var texts, errTexts []string
	var parts []ContentPart
	for _, x := range content {
		c, _ := x.(map[string]any)
		str := func(k string) string { v, _ := c[k].(string); return v }
		switch str("type") {
		case "text":
			texts = append(texts, str("text"))
			if strings.TrimSpace(str("text")) != "" {
				errTexts = append(errTexts, str("text"))
			}
		case "image":
			parts = append(parts, ContentPart{Type: PartImage, MimeType: str("mimeType"), Data: str("data")})
		case "audio":
			parts = append(parts, ContentPart{Type: PartAudio, MimeType: str("mimeType"), Data: str("data")})
		case "resource_link":
			parts = append(parts, ContentPart{Type: PartFile, MimeType: str("mimeType"), URL: str("uri"), Name: str("name")})
		case "resource":
			r, _ := c["resource"].(map[string]any)
			if t, ok := r["text"].(string); ok {
				texts = append(texts, t)
			} else if b, ok := r["blob"].(string); ok {
				mt, _ := r["mimeType"].(string)
				uri, _ := r["uri"].(string)
				parts = append(parts, ContentPart{Type: PartFile, MimeType: mt, Data: b, Name: uri})
			}
		}
	}
	if isErr, _ := res["isError"].(bool); isErr {
		out := strings.Join(errTexts, "\n\n")
		if len(errTexts) == 0 {
			out = "MCP tool returned an error"
		}
		return ToolResult{Output: out, IsError: true, Parts: parts, Metadata: meta}
	}
	if sc, ok := res["structuredContent"]; ok && sc != nil {
		b, err := json.Marshal(sc)
		if err != nil {
			return ToolResult{Output: err.Error(), IsError: true, Metadata: meta}
		}
		return ToolResult{Output: string(b), Parts: parts, Metadata: meta}
	}
	out := strings.Join(texts, "\n")
	if out == "" && len(parts) > 0 {
		out = describeParts(parts)
	}
	return ToolResult{Output: out, Parts: parts, Metadata: meta}
}

// buildInputSchemaJSON mirrors buildInputSchema for a raw tools/list entry.
func buildInputSchemaJSON(def jsonObj) JSONSchema {
	in, _ := def["inputSchema"].(map[string]any)
	schema := JSONSchema{}
	if d, ok := in["$defs"].(map[string]any); ok && len(d) > 0 {
		schema["$defs"] = d
	}
	if req := stringList(in["required"]); len(req) > 0 {
		schema["required"] = req
	}
	props, _ := in["properties"].(map[string]any)
	if props == nil {
		props = map[string]any{}
	}
	schema["type"] = "object"
	schema["properties"] = props
	schema["additionalProperties"] = false
	return schema
}

func mcpHintsJSON(v any) *ToolHints {
	a, _ := v.(map[string]any)
	ro, hasRO := a["readOnlyHint"].(bool)
	de, hasDe := a["destructiveHint"].(bool)
	ow, hasOW := a["openWorldHint"].(bool)
	if !hasRO && !hasDe && !hasOW {
		return nil
	}
	h := ToolHints{Destructive: true, OpenWorld: true}
	if hasRO {
		h.ReadOnly = ro
	}
	if hasDe {
		h.Destructive = de
	}
	if hasOW {
		h.OpenWorld = ow
	}
	if h.ReadOnly {
		h.Destructive = false
	}
	return &h
}

// ---------------------------------------------------------------------------
// Tool search (§2.8).
// ---------------------------------------------------------------------------

func (s *mcp26Server) searchTools() []Tool {
	about := s.cfg.Description
	if about == "" {
		about = s.name
	}
	search := Tool{
		Name:        Sanitize(s.name) + "_search_tools",
		Description: "Search the tools of " + about + " by keywords. Returns name, description and inputSchema of the best matches; run one with call_tool.",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{"query": map[string]any{"type": "string"}, "limit": map[string]any{"type": "integer"}}, "required": []string{"query"}},
		Source:      SourceMCP,
		Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			q, _ := args["query"].(string)
			limit := 5
			if l, ok := args["limit"].(float64); ok && l > 0 {
				limit = int(l)
			}
			ctx := context.Background()
			if tctx != nil && tctx.Ctx != nil {
				ctx = tctx.Ctx
			}
			defs, err := s.listTools(ctx)
			if err != nil {
				return s.errResult(err.Error()), nil
			}
			hits := rankTools(s.allowed(defs), q, limit, s.cfg.Description)
			b, _ := json.Marshal(hits)
			return ToolResult{Output: string(b), Metadata: map[string]any{"server": s.name}}, nil
		},
	}
	call := Tool{
		Name:        Sanitize(s.name) + "_call_tool",
		Description: "Run a tool found with search_tools.",
		InputSchema: JSONSchema{"type": "object", "properties": map[string]any{"name": map[string]any{"type": "string"}, "arguments": map[string]any{"type": "object"}}, "required": []string{"name"}},
		Source:      SourceMCP,
		Execute: func(args map[string]any, tctx *ToolContext) (ToolResult, error) {
			n, _ := args["name"].(string)
			a, _ := args["arguments"].(map[string]any)
			ctx := context.Background()
			if tctx != nil && tctx.Ctx != nil {
				ctx = tctx.Ctx
			}
			defs, err := s.listTools(ctx)
			if err != nil {
				return s.errResult(err.Error()), nil
			}
			known := false
			for _, d := range s.allowed(defs) {
				if d["name"] == n {
					known = true
				}
			}
			if !known {
				return s.errResult(fmt.Sprintf("unknown tool %q on %s", n, s.name)), nil
			}
			return s.execute(n, a, tctx), nil
		},
	}
	return []Tool{search, call}
}

// rankTools is BM25 over the tool name (×2, split on _ and camelCase) + description.
// searchHit keeps the pinned key order: name, description, inputSchema, server.
type searchHit struct {
	Name        any             `json:"name"`
	Description any             `json:"description"`
	InputSchema json.RawMessage `json:"inputSchema"`
	Server      string          `json:"server"`
}

func rankTools(tools []jsonObj, query string, limit int, server string) []searchHit {
	docs := make([][]string, len(tools))
	df := map[string]int{}
	total := 0
	for i, t := range tools {
		n, _ := t["name"].(string)
		d, _ := t["description"].(string)
		nt := searchTokens(n)
		docs[i] = append(append(append([]string{}, nt...), nt...), searchTokens(d)...)
		total += len(docs[i])
		seen := map[string]bool{}
		for _, w := range docs[i] {
			if !seen[w] {
				seen[w] = true
				df[w]++
			}
		}
	}
	avg := float64(total) / math.Max(1, float64(len(tools)))
	type hit struct {
		i     int
		score float64
	}
	var hits []hit
	for i, d := range docs {
		tf := map[string]int{}
		for _, w := range d {
			tf[w]++
		}
		sc := 0.0
		for _, q := range searchTokens(query) {
			f := float64(tf[q])
			if f == 0 {
				continue
			}
			idf := math.Log(1 + (float64(len(tools))-float64(df[q])+0.5)/(float64(df[q])+0.5))
			sc += idf * f * 2.2 / (f + 1.2*(0.25+0.75*float64(len(d))/avg))
		}
		if sc > 0 {
			hits = append(hits, hit{i, sc})
		}
	}
	sort.SliceStable(hits, func(a, b int) bool { return hits[a].score > hits[b].score })
	out := []searchHit{}
	for _, h := range hits[:min(limit, len(hits))] {
		t := tools[h.i]
		raw, _ := t["__raw_schema"].(json.RawMessage)
		if raw == nil {
			raw, _ = json.Marshal(t["inputSchema"])
		}
		out = append(out, searchHit{Name: t["name"], Description: t["description"], InputSchema: raw, Server: server})
	}
	return out
}

func searchTokens(s string) []string {
	var out []string
	var cur []rune
	flush := func() {
		if len(cur) > 0 {
			out = append(out, strings.ToLower(string(cur)))
			cur = cur[:0]
		}
	}
	prevLower := false
	for _, r := range s {
		switch {
		case unicode.IsUpper(r) && prevLower:
			flush()
			cur = append(cur, r)
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			cur = append(cur, r)
		default:
			flush()
		}
		prevLower = unicode.IsLower(r)
	}
	flush()
	return out
}

// ---------------------------------------------------------------------------
// Skills extension (§2.7): remote skills are downloaded into a per-load temp
// dir and join the same `skill` tool inventory as local skills.
// ---------------------------------------------------------------------------

// parseSkillURI validates skill://<name>/<path>: percent-decode once, then
// reject a leftover %, \, NUL, ':' and any empty / . / .. segment.
func parseSkillURI(uri string) (name, rel string, err error) {
	rest, ok := strings.CutPrefix(uri, "skill://")
	if !ok {
		return "", "", errors.New("not a skill:// URI")
	}
	dec, err := url.PathUnescape(rest)
	if err != nil {
		return "", "", errors.New("bad percent-encoding")
	}
	if strings.ContainsAny(dec, "%\\\x00") {
		return "", "", errors.New("skill URI has encoded, backslash or NUL characters")
	}
	segs := strings.Split(dec, "/")
	if len(segs) < 2 {
		return "", "", errors.New("skill URI needs skill://<name>/<path>")
	}
	for _, sg := range segs {
		if sg == "" || sg == "." || sg == ".." || strings.Contains(sg, ":") {
			return "", "", errors.New("skill URI path escapes the skill (empty, '.', '..' or ':' segment)")
		}
	}
	return segs[0], strings.Join(segs[1:], "/"), nil
}

// loadSkills lists the server's skills and downloads each into a temp dir.
// A bad skill is skipped (logged); it never fails the server.
func (s *mcp26Server) loadSkills(ctx context.Context) {
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	r, err := s.c.call(ctx, "skills/list", nil)
	if err != nil {
		log.Printf("[toolnexus] MCP server %q skills/list failed: %v", s.name, err)
		return
	}
	list, _ := r["skills"].([]any)
	if len(list) == 0 {
		return
	}
	dir, err := os.MkdirTemp("", "toolnexus-mcp-skills-")
	if err != nil {
		log.Printf("[toolnexus] MCP server %q skills: %v", s.name, err)
		return
	}
	s.skillDir = dir
	for _, x := range list {
		m, _ := x.(map[string]any)
		uri, _ := m["uri"].(string)
		if err := s.downloadSkill(ctx, uri, dir); err != nil {
			log.Printf("[toolnexus] MCP server %q skill %q skipped: %v", s.name, uri, err)
		}
	}
}

func (s *mcp26Server) downloadSkill(ctx context.Context, uri, dir string) error {
	name, _, err := parseSkillURI(uri)
	if err != nil {
		return err
	}
	r, err := s.c.call(ctx, "skills/get", jsonObj{"uri": uri})
	if err != nil {
		return err
	}
	sk, _ := r["skill"].(map[string]any)
	res, _ := sk["resources"].([]any)
	type file struct {
		path string
		data []byte
	}
	var files []file
	for _, x := range res { // validate everything before writing anything
		m, _ := x.(map[string]any)
		fu, _ := m["uri"].(string)
		n, rel, err := parseSkillURI(fu)
		if err != nil || n != name || !filepath.IsLocal(filepath.FromSlash(rel)) {
			return fmt.Errorf("refusing skill resource %q: outside skill %s", fu, name)
		}
		rr, err := s.c.call(ctx, "resources/read", jsonObj{"uri": fu})
		if err != nil {
			return err
		}
		cs, _ := rr["contents"].([]any)
		if len(cs) == 0 {
			return fmt.Errorf("%s: empty resources/read", fu)
		}
		c0, _ := cs[0].(map[string]any)
		t, _ := c0["text"].(string)
		if d, _ := m["digest"].(string); d != "" && d != fmt.Sprintf("sha256:%x", sha256.Sum256([]byte(t))) {
			return fmt.Errorf("%s: digest mismatch", fu)
		}
		files = append(files, file{filepath.Join(dir, name, filepath.FromSlash(rel)), []byte(t)})
	}
	for _, f := range files {
		if err := os.MkdirAll(filepath.Dir(f.path), 0o755); err != nil {
			return err
		}
		if err := os.WriteFile(f.path, f.data, 0o644); err != nil {
			return err
		}
	}
	return nil
}
