// MCP — INBOUND, the 2026-07-28 wire on MCPHandler (SPEC §7C.2, ADR 0040).
//
// Opt-in: list "2026-07-28" in MCPHandlerConfig.ProtocolVersions. A POST is
// served on this profile when the version is accepted and the request is a
// 2026 request (mcp2026Route). The profile is stateless: no initialize, no
// Mcp-Session-Id, no server→client request. Every hook keeps its meaning:
// Authenticate (after header validation), ListTools, BeforeCall, OnCall,
// resources, prompts, plus ListSkills for the SEP-2640 skills extension. A
// confirm decision and a tool suspension (§10 Pending) leave as an MRTR
// `input_required` result; the client's retry carries inputResponses and the
// echoed requestState.

package toolnexus

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

// MCP 2026-07-28 wire constants (SPEC §2.1).
const (
	// MCPProtocol2026 is the MCP 2026-07-28 wire version, used by both the client
	// (§2.1) and the handler (§7C.2). The canonical name; McpProtocolVersion is a
	// deprecated alias of it.
	MCPProtocol2026 = "2026-07-28"
	// mcpMetaVersion, mcpMetaClientCaps, mcpMetaServerInfo: shared with the client, see mcp2026.go.
	MCPSkillsExtension  = "io.modelcontextprotocol/skills"
	mcpCodeHeaderMismat = -32020
	mcpCodeUnsupported  = -32022
)

// MCPSkill is one skill published through the skills extension (SEP-2640).
// Files are paths relative to the skill directory (slash-separated) and MUST
// include "SKILL.md". LoadMCPSkills builds these from a skills directory.
type MCPSkill struct {
	Name        string
	Frontmatter map[string]any
	Files       []MCPSkillFile
}

// MCPSkillFile is one text file of a skill.
type MCPSkillFile struct {
	Path string
	Text string
}

// MCPAuthError is an Authenticate failure with an explicit HTTP status and
// challenge (MCPServerAuth returns it). Status 0 ⇒ 401. On any wire the
// handler answers Status with WWW-Authenticate = Challenge (a non-empty
// WWWAuthenticate hook result wins) and the body {"error":"unauthorized"}.
type MCPAuthError struct {
	Status    int
	Challenge string
	Reason    string // never sent on the wire
}

func (e *MCPAuthError) Error() string {
	if e.Reason != "" {
		return e.Reason
	}
	return "unauthorized"
}

type mcp26Err struct {
	status int
	code   int
	msg    string
	data   any
}

// mcp2026Route reports whether this POST is served on the 2026-07-28 profile.
func (h *MCPHTTPHandler) mcp2026Route(r *http.Request, method string, meta map[string]any) bool {
	if !mcpHasVersion(h.versions, MCPProtocol2026) {
		return false
	}
	legacy := false
	for _, v := range h.versions {
		if v != MCPProtocol2026 {
			legacy = true
		}
	}
	if !legacy {
		return true
	}
	if method == "initialize" {
		return false
	}
	hv := r.Header.Get("MCP-Protocol-Version")
	if hv == MCPProtocol2026 {
		return true
	}
	if hv != "" && mcpHasVersion(h.versions, hv) {
		return false
	}
	_, metaV := meta[mcpMetaVersion]
	return method == "server/discover" || r.Header.Get("Mcp-Method") != "" || metaV
}

// serve2026 answers one POST on the 2026 profile. body was already read.
func (h *MCPHTTPHandler) serve2026(ctx context.Context, w http.ResponseWriter, r *http.Request, body []byte, msg mcpRPCMessage, meta map[string]any) {
	if msg.Method == "" || len(msg.ID) == 0 || string(msg.ID) == "null" {
		w.WriteHeader(http.StatusAccepted) // notification (or a stray response): acknowledged
		return
	}
	var params map[string]any
	_ = json.Unmarshal(msg.Params, &params)
	if e := mcp26Validate(r, msg.Method, params, meta); e != nil {
		writeMcp26(w, e.status, msg.ID, nil, e)
		return
	}
	if h.cfg.Authenticate != nil {
		id, err := h.cfg.Authenticate(r)
		if err != nil {
			h.writeAuthFailure(w, r, err)
			return
		}
		ctx = context.WithValue(ctx, mcpIdentityKey, id)
	}
	if meta != nil {
		ctx = context.WithValue(ctx, mcpMetaKey, meta)
	}
	res, e := h.dispatch2026(ctx, msg.Method, msg.Params, params, meta)
	status := http.StatusOK
	if e != nil {
		status = e.status
		if status == 0 {
			status = http.StatusOK
		}
	}
	writeMcp26(w, status, msg.ID, res, e)
}

// writeAuthFailure answers an Authenticate error (both wires).
func (h *MCPHTTPHandler) writeAuthFailure(w http.ResponseWriter, r *http.Request, err error) {
	challenge := ""
	if h.cfg.WWWAuthenticate != nil {
		challenge = h.cfg.WWWAuthenticate(r, err)
	}
	var ae *MCPAuthError
	if errors.As(err, &ae) {
		if challenge == "" {
			challenge = ae.Challenge
		}
		if challenge != "" {
			w.Header().Set("WWW-Authenticate", challenge)
		}
		status := ae.Status
		if status == 0 {
			status = http.StatusUnauthorized
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
		return
	}
	if challenge != "" {
		w.Header().Set("WWW-Authenticate", challenge)
	}
	http.Error(w, "Unauthorized", http.StatusUnauthorized)
}

// mcp26Validate applies SPEC §7C.2 header validation, in order.
func mcp26Validate(r *http.Request, method string, p map[string]any, meta map[string]any) *mcp26Err {
	mismatch := func(m string) *mcp26Err {
		return &mcp26Err{status: http.StatusBadRequest, code: mcpCodeHeaderMismat, msg: "Header mismatch: " + m}
	}
	hv := r.Header.Get("MCP-Protocol-Version")
	if hv == "" {
		return mismatch("MCP-Protocol-Version header missing")
	}
	if hm := r.Header.Get("Mcp-Method"); hm != method {
		return mismatch(fmt.Sprintf("Mcp-Method header value '%s' does not match body value '%s'", hm, method))
	}
	if method == "tools/call" || method == "prompts/get" || method == "resources/read" {
		key := "name"
		if method == "resources/read" {
			key = "uri"
		}
		want, _ := p[key].(string)
		got, ok := mcpDecodeHeaderValue(r.Header.Get("Mcp-Name"))
		if !ok || got != want {
			return mismatch(fmt.Sprintf("Mcp-Name header value '%s' does not match body value '%s'", got, want))
		}
	}
	mv, _ := meta[mcpMetaVersion].(string)
	if mv != hv {
		return mismatch(fmt.Sprintf("MCP-Protocol-Version header value '%s' does not match _meta value '%s'", hv, mv))
	}
	if mv != MCPProtocol2026 {
		return &mcp26Err{status: http.StatusBadRequest, code: mcpCodeUnsupported, msg: "Unsupported protocol version",
			data: map[string]any{"supported": []string{MCPProtocol2026}, "requested": mv}}
	}
	return nil
}

// mcpDecodeHeaderValue decodes the `=?base64?…?=` sentinel (SPEC §2.3).
func mcpDecodeHeaderValue(v string) (string, bool) {
	if strings.HasPrefix(v, "=?base64?") && strings.HasSuffix(v, "?=") && len(v) >= 11 {
		b, err := base64.StdEncoding.DecodeString(v[9 : len(v)-2])
		if err != nil {
			return "", false
		}
		return string(b), true
	}
	return v, v != ""
}

func (h *MCPHTTPHandler) info2026(ctx context.Context) MCPServerInfo {
	info := MCPServerInfo{Name: h.cfg.Name, Title: h.cfg.Title, Version: h.cfg.Version, Instructions: h.cfg.Instructions}
	if h.cfg.ServerInfo != nil {
		info = h.cfg.ServerInfo(ctx)
	}
	if info.Name == "" {
		info.Name = mcpServeDefaultName
	}
	if info.Version == "" {
		info.Version = mcpServeDefaultVersion
	}
	return info
}

// complete26 stamps resultType and _meta.serverInfo onto a result.
func (h *MCPHTTPHandler) complete26(ctx context.Context, m map[string]any, resultType string) map[string]any {
	info := h.info2026(ctx)
	meta, _ := m["_meta"].(map[string]any)
	if meta == nil {
		meta = map[string]any{}
	}
	meta[mcpMetaServerInfo] = omitEmpty(map[string]any{"name": info.Name, "title": info.Title, "version": info.Version})
	m["_meta"] = meta
	m["resultType"] = resultType
	return m
}

// cacheScope26 is "private" when results may depend on the caller.
func (h *MCPHTTPHandler) cacheScope26() string {
	if h.cfg.Authenticate != nil {
		return "private"
	}
	return "public"
}

func (h *MCPHTTPHandler) dispatch2026(ctx context.Context, method string, raw json.RawMessage, p map[string]any, meta map[string]any) (map[string]any, *mcp26Err) {
	internal := func(err error) *mcp26Err { return &mcp26Err{code: -32603, msg: err.Error()} }
	invalid := func(m string) *mcp26Err { return &mcp26Err{code: -32602, msg: m} }
	switch method {
	case "server/discover":
		info := h.info2026(ctx)
		caps := map[string]any{"tools": map[string]any{}}
		if h.cfg.ListResources != nil || h.cfg.ListSkills != nil {
			caps["resources"] = map[string]any{}
		}
		if h.cfg.ListPrompts != nil {
			caps["prompts"] = map[string]any{}
		}
		if h.cfg.ListSkills != nil {
			caps["extensions"] = map[string]any{MCPSkillsExtension: map[string]any{}}
		}
		res := map[string]any{"supportedVersions": []string{MCPProtocol2026}, "capabilities": caps,
			"ttlMs": 3600000, "cacheScope": "public"}
		if info.Instructions != "" {
			res["instructions"] = info.Instructions
		}
		return h.complete26(ctx, res, "complete"), nil
	case "ping":
		return h.complete26(ctx, map[string]any{}, "complete"), nil
	case "tools/list":
		tools, err := h.tools(ctx)
		if err != nil {
			return nil, internal(err)
		}
		sorted := append([]Tool(nil), tools...)
		sort.SliceStable(sorted, func(i, j int) bool { return sorted[i].Name < sorted[j].Name })
		out := make([]map[string]any, 0, len(sorted))
		for _, t := range sorted {
			out = append(out, mcpWireTool(t))
		}
		return h.complete26(ctx, map[string]any{"tools": out, "ttlMs": 60000, "cacheScope": h.cacheScope26()}, "complete"), nil
	case "tools/call":
		return h.callTool2026(ctx, p, meta)
	case "skills/list":
		if h.cfg.ListSkills == nil {
			break
		}
		skills, err := h.skills26(ctx)
		if err != nil {
			return nil, internal(err)
		}
		list := make([]map[string]any, 0, len(skills))
		for _, s := range skills {
			list = append(list, map[string]any{"uri": "skill://" + s.Name + "/SKILL.md", "frontmatter": mcpFrontmatter(s)})
		}
		return h.complete26(ctx, map[string]any{"skills": list, "ttlMs": 60000, "cacheScope": h.cacheScope26()}, "complete"), nil
	case "skills/get":
		if h.cfg.ListSkills == nil {
			break
		}
		uri, _ := p["uri"].(string)
		if _, _, err := mcpParseSkillURI(uri); err != nil {
			return nil, invalid("Invalid skill URI: " + err.Error())
		}
		skills, err := h.skills26(ctx)
		if err != nil {
			return nil, internal(err)
		}
		for _, s := range skills {
			if "skill://"+s.Name+"/SKILL.md" == uri {
				return h.complete26(ctx, map[string]any{"skill": mcpSkillEntry(s)}, "complete"), nil
			}
		}
		return nil, invalid("Unknown skill: " + uri)
	case "resources/list":
		if h.cfg.ListResources == nil {
			break
		}
		rs, err := h.cfg.ListResources(ctx)
		if err != nil {
			return nil, internal(err)
		}
		out := make([]map[string]any, 0, len(rs))
		for _, r := range rs {
			out = append(out, omitEmpty(map[string]any{"uri": r.URI, "name": r.Name, "title": r.Title, "description": r.Description, "mimeType": r.MimeType}))
		}
		return h.complete26(ctx, map[string]any{"resources": out}, "complete"), nil
	case "resources/read":
		uri, _ := p["uri"].(string)
		if strings.HasPrefix(uri, "skill://") && h.cfg.ListSkills != nil {
			name, rel, err := mcpParseSkillURI(uri)
			if err != nil {
				return nil, invalid("Invalid resource URI: " + err.Error())
			}
			skills, err := h.skills26(ctx)
			if err != nil {
				return nil, internal(err)
			}
			for _, s := range skills {
				if s.Name != name {
					continue
				}
				for _, f := range s.Files {
					if f.Path == rel {
						mt := "text/plain"
						if strings.HasSuffix(rel, ".md") {
							mt = "text/markdown"
						}
						return h.complete26(ctx, map[string]any{"contents": []map[string]any{{"uri": uri, "mimeType": mt, "text": f.Text}},
							"ttlMs": 60000, "cacheScope": h.cacheScope26()}, "complete"), nil
					}
				}
			}
			return nil, invalid("Resource not found: " + uri)
		}
		if h.cfg.ListResources == nil || h.cfg.ReadResource == nil {
			break
		}
		cs, err := h.cfg.ReadResource(ctx, uri)
		if err != nil {
			return nil, &mcp26Err{code: -32002, msg: err.Error()}
		}
		out := make([]map[string]any, 0, len(cs))
		for _, c := range cs {
			m := omitEmpty(map[string]any{"uri": c.URI, "mimeType": c.MimeType, "blob": c.Blob})
			if c.Blob == "" {
				m["text"] = c.Text
			}
			out = append(out, m)
		}
		return h.complete26(ctx, map[string]any{"contents": out}, "complete"), nil
	case "prompts/list", "prompts/get":
		if h.cfg.ListPrompts == nil {
			break
		}
		// Same wire as the legacy profile; only the envelope differs.
		res, rerr := h.dispatch(ctx, nil, "", mcpRPCMessage{Method: method, Params: raw})
		if rerr != nil {
			return nil, &mcp26Err{code: rerr.Code, msg: rerr.Message}
		}
		m, _ := res.(map[string]any)
		if m == nil {
			m = map[string]any{}
		}
		return h.complete26(ctx, m, "complete"), nil
	}
	return nil, &mcp26Err{status: http.StatusNotFound, code: -32601, msg: "Method not found: " + method}
}

func (h *MCPHTTPHandler) skills26(ctx context.Context) ([]MCPSkill, error) {
	skills, err := h.cfg.ListSkills(ctx)
	if err != nil {
		return nil, err
	}
	out := append([]MCPSkill(nil), skills...)
	sort.SliceStable(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, nil
}

func mcpFrontmatter(s MCPSkill) map[string]any {
	if s.Frontmatter != nil {
		return s.Frontmatter
	}
	return map[string]any{"name": s.Name}
}

func mcpSkillEntry(s MCPSkill) map[string]any {
	files := append([]MCPSkillFile(nil), s.Files...)
	sort.SliceStable(files, func(i, j int) bool { return files[i].Path < files[j].Path })
	res := make([]map[string]any, 0, len(files))
	for _, f := range files {
		res = append(res, map[string]any{"uri": "skill://" + s.Name + "/" + f.Path,
			"digest": fmt.Sprintf("sha256:%x", sha256.Sum256([]byte(f.Text))), "size": len(f.Text)})
	}
	return map[string]any{"uri": "skill://" + s.Name + "/SKILL.md", "frontmatter": mcpFrontmatter(s), "resources": res}
}

// mcpParseSkillURI validates skill://<name>/<path> (SPEC §2.7 containment).
func mcpParseSkillURI(uri string) (name, rel string, err error) {
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
	for _, s := range segs {
		if s == "" || s == "." || s == ".." || strings.Contains(s, ":") {
			return "", "", errors.New("skill URI path escapes the skill (empty, '.', '..' or ':' segment)")
		}
	}
	return segs[0], strings.Join(segs[1:], "/"), nil
}

// mcp26State is the opaque requestState: which tool the round belongs to and
// whether a confirm was already accepted. Base64url of compact JSON; it is not
// integrity-protected (the client could equally answer "accept").
type mcp26State struct {
	Tool      string `json:"tool"`
	Confirmed bool   `json:"confirmed,omitempty"`
}

func encodeState26(s mcp26State) string {
	b, _ := json.Marshal(s)
	return base64.RawURLEncoding.EncodeToString(b)
}

func decodeState26(v any) (mcp26State, bool) {
	str, _ := v.(string)
	b, err := base64.RawURLEncoding.DecodeString(str)
	var m map[string]any // exact keys (a struct would match "TOOL" case-insensitively)
	if err != nil || json.Unmarshal(b, &m) != nil {
		return mcp26State{}, false
	}
	tool, ok := m["tool"].(string)
	if !ok {
		return mcp26State{}, false
	}
	confirmed, _ := m["confirmed"].(bool)
	return mcp26State{Tool: tool, Confirmed: confirmed}, true
}

// inputResponse26 maps one MRTR input response onto a §10 Answer.
func inputResponse26(key string, v any) *Answer {
	m, _ := v.(map[string]any)
	if m == nil {
		return nil
	}
	action, _ := m["action"].(string)
	a := &Answer{ID: key, Ok: action == "accept"}
	if c, ok := m["content"].(map[string]any); ok {
		a.Data = c
	}
	if !a.Ok {
		a.Reason = "cancelled"
		if action == "decline" {
			a.Reason = "declined"
		}
	}
	return a
}

func (h *MCPHTTPHandler) callTool2026(ctx context.Context, p map[string]any, meta map[string]any) (map[string]any, *mcp26Err) {
	name, _ := p["name"].(string)
	tools, err := h.tools(ctx)
	if err != nil {
		return nil, &mcp26Err{code: -32603, msg: err.Error()}
	}
	var tool *Tool
	for i := range tools {
		if tools[i].Name == name {
			tool = &tools[i]
			break
		}
	}
	if tool == nil || tool.Execute == nil {
		return nil, &mcp26Err{code: -32602, msg: "Unknown tool: " + name}
	}
	started := time.Now()
	ev := MCPCallEvent{Name: tool.Name, Source: tool.Source, Decision: MCPAllow, Identity: IdentityFrom(ctx)}
	finish := func(m map[string]any, isErr bool, execErr error) (map[string]any, *mcp26Err) {
		b, _ := json.Marshal(m)
		ev.Ms = time.Since(started).Milliseconds()
		ev.IsError, ev.Err, ev.ResultBytes = isErr, execErr, len(b)
		if h.cfg.OnCall != nil {
			func() {
				defer func() { _ = recover() }()
				h.cfg.OnCall(ev)
			}()
		}
		return m, nil
	}
	result := func(res ToolResult, execErr error) (map[string]any, *mcp26Err) {
		b, _ := json.Marshal(toolResultToMcp(res))
		var m map[string]any
		_ = json.Unmarshal(b, &m)
		return finish(h.complete26(ctx, m, "complete"), res.IsError, execErr)
	}

	rawArgs, hasArgs := p["arguments"]
	args := map[string]any{}
	if hasArgs && rawArgs != nil {
		a, ok := rawArgs.(map[string]any)
		if !ok {
			return result(ToolResult{Output: "invalid arguments: must be an object", IsError: true}, nil)
		}
		args = a
	}
	argsJSON, _ := json.Marshal(args)

	state, hasState := decodeState26(p["requestState"])
	if hasState && state.Tool != name {
		state, hasState = mcp26State{}, false
	}
	responses, _ := p["inputResponses"].(map[string]any)

	if h.cfg.BeforeCall != nil {
		d, err := h.cfg.BeforeCall(ctx, *tool, argsJSON)
		if err != nil {
			d = MCPDecision{Kind: MCPDeny, Message: err.Error()}
		}
		if d.Kind == "" {
			d.Kind = MCPAllow
		}
		ev.Decision = d.Kind
		switch d.Kind {
		case MCPDeny:
			if d.Result != nil {
				return result(*d.Result, nil)
			}
			m := d.Message
			if m == "" {
				m = "denied"
			}
			return result(ToolResult{Output: m, IsError: true}, nil)
		case MCPConfirm:
			if hasState && state.Confirmed {
				break // accepted in an earlier round of this call
			}
			if ans := inputResponse26("confirm", responses["confirm"]); hasState && ans != nil {
				if !ans.Ok {
					return result(ToolResult{Output: "declined", IsError: true}, nil)
				}
				if v, ok := ans.Data["confirm"].(bool); ok && !v {
					return result(ToolResult{Output: "declined", IsError: true}, nil)
				}
				state = mcp26State{Tool: name, Confirmed: true}
				responses = nil // the confirm round is consumed
				break
			}
			if !mcp26CanElicit(meta) {
				if d.Result != nil {
					return result(*d.Result, nil)
				}
				return result(ToolResult{Output: "confirmation required: " + d.Message, IsError: true}, nil)
			}
			msg := d.Message
			if msg == "" {
				msg = "Confirm this action?"
			}
			return finish(h.complete26(ctx, map[string]any{
				"inputRequests": map[string]any{"confirm": map[string]any{"method": "elicitation/create", "params": map[string]any{
					"mode": "form", "message": msg,
					"requestedSchema": map[string]any{"type": "object", "properties": map[string]any{
						"confirm": map[string]any{"type": "boolean", "title": "Confirm", "description": msg}}},
				}}},
				"requestState": encodeState26(mcp26State{Tool: name}),
			}, "input_required"), false, nil)
		}
	}

	tc := &ToolContext{Ctx: ctx}
	if hasState && len(responses) == 1 {
		for k, v := range responses {
			tc.Answer = inputResponse26(k, v)
		}
	}
	res, execErr := tool.Execute(args, tc)
	if execErr != nil {
		res = ToolResult{Output: execErr.Error(), IsError: true}
	}
	if req := PendingOf(res); req != nil && execErr == nil {
		ir := mcp26InputRequest(*req)
		return finish(h.complete26(ctx, map[string]any{
			"inputRequests": map[string]any{req.ID: ir},
			"requestState":  encodeState26(mcp26State{Tool: name, Confirmed: state.Confirmed}),
		}, "input_required"), false, nil)
	}
	return result(res, execErr)
}

// mcp26CanElicit: the request's _meta clientCapabilities declare elicitation.
func mcp26CanElicit(meta map[string]any) bool {
	caps, _ := meta[mcpMetaClientCaps].(map[string]any)
	_, ok := caps["elicitation"]
	return ok
}

// mcp26InputRequest maps a §10 Request onto an MRTR input request: kind
// "authorization" with a URL ⇒ URL-mode elicitation, anything else ⇒ form.
func mcp26InputRequest(r Request) map[string]any {
	if r.Kind == "authorization" && r.URL != "" {
		return map[string]any{"method": "elicitation/create", "params": map[string]any{
			"mode": "url", "message": r.Prompt, "url": r.URL, "elicitationId": r.ID}}
	}
	schema, _ := r.Data["schema"].(map[string]any)
	if s, ok := r.Data["schema"].(JSONSchema); ok {
		schema = s
	}
	if schema == nil {
		schema = map[string]any{"type": "object", "properties": map[string]any{}}
	}
	return map[string]any{"method": "elicitation/create", "params": map[string]any{
		"mode": "form", "message": r.Prompt, "requestedSchema": schema}}
}

func writeMcp26(w http.ResponseWriter, status int, id json.RawMessage, result map[string]any, e *mcp26Err) {
	if id == nil {
		id = json.RawMessage("null")
	}
	msg := map[string]any{"jsonrpc": "2.0", "id": id}
	if e != nil {
		er := map[string]any{"code": e.code, "message": e.msg}
		if e.data != nil {
			er["data"] = e.data
		}
		msg["error"] = er
	} else {
		msg["result"] = result
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(msg)
}

// handlePost2026 reads and routes a POST when the 2026 profile is enabled. It
// returns false (body restored) when the request belongs to the legacy wire.
func (h *MCPHTTPHandler) handlePost2026(ctx context.Context, w http.ResponseWriter, r *http.Request) bool {
	if mt, _, err := mime.ParseMediaType(r.Header.Get("Content-Type")); err != nil || mt != "application/json" {
		return false // the legacy path answers 415 identically
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, h.cfg.MaxBodyBytes+1))
	if err != nil {
		return false
	}
	r.Body = io.NopCloser(bytes.NewReader(body))
	if int64(len(body)) > h.cfg.MaxBodyBytes {
		return false
	}
	trimmed := strings.TrimSpace(string(body))
	var msg mcpRPCMessage
	if strings.HasPrefix(trimmed, "[") || json.Unmarshal(body, &msg) != nil || msg.JSONRPC != "2.0" || !utf8.Valid(body) {
		if !h.only2026() {
			return false
		}
		if strings.HasPrefix(trimmed, "[") {
			writeMcp26(w, http.StatusBadRequest, nil, nil, &mcp26Err{code: -32600, msg: "batch requests are not supported"})
		} else {
			writeMcp26(w, http.StatusBadRequest, nil, nil, &mcp26Err{code: -32700, msg: "Parse error"})
		}
		return true
	}
	var pm struct {
		Meta map[string]any `json:"_meta"`
	}
	_ = json.Unmarshal(msg.Params, &pm)
	if !h.mcp2026Route(r, msg.Method, pm.Meta) {
		return false
	}
	h.serve2026(ctx, w, r, body, msg, pm.Meta)
	return true
}

func (h *MCPHTTPHandler) only2026() bool {
	return len(h.versions) == 1 && h.versions[0] == MCPProtocol2026
}

// LoadMCPSkills reads every skill under dir (any depth, `**/SKILL.md`) for
// MCPHandlerConfig.ListSkills. The frontmatter is {name, description} parsed
// as in §3; a skill without a name is skipped. Files are every regular UTF-8
// text file under the skill directory (binary files are skipped — the wire
// carries text), paths slash-separated, sorted by code point.
func LoadMCPSkills(dir string) ([]MCPSkill, error) {
	var out []MCPSkill
	err := filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || d.Name() != "SKILL.md" {
			return err
		}
		raw, err := os.ReadFile(p)
		if err != nil {
			return err
		}
		fm, _, malformed, _ := parseFrontmatter(string(raw))
		if malformed || fm.Name == "" {
			return nil
		}
		root := filepath.Dir(p)
		sk := MCPSkill{Name: fm.Name, Frontmatter: map[string]any{"name": fm.Name, "description": fm.Description}}
		err = filepath.WalkDir(root, func(fp string, fd os.DirEntry, err error) error {
			if err != nil || !fd.Type().IsRegular() {
				return err
			}
			b, err := os.ReadFile(fp)
			if err != nil || !utf8.Valid(b) {
				return err
			}
			rel, _ := filepath.Rel(root, fp)
			sk.Files = append(sk.Files, MCPSkillFile{Path: filepath.ToSlash(rel), Text: string(b)})
			return nil
		})
		sort.Slice(sk.Files, func(i, j int) bool { return sk.Files[i].Path < sk.Files[j].Path })
		out = append(out, sk)
		return err
	})
	sort.SliceStable(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out, err
}
