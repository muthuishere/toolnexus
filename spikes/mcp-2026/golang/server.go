package mcp2026

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strings"
)

type fixture struct {
	Server    obj    `json:"server"`
	Tools     []obj  `json:"tools"`
	SkillsDir string `json:"skillsDir"`
}

type skillFile struct {
	uri  string
	data []byte
}

type skill struct {
	uri         string
	frontmatter obj
	files       []skillFile // sorted by uri; includes SKILL.md
}

// Server is a stateless MCP 2026-07-28 server over POST /mcp.
type Server struct {
	fx     fixture
	skills []skill     // sorted by uri
	Auth   *ServerAuth // nil = unprotected (the default; cross-port checks run without auth)
	// code-defined tools (checked before the fixture tools); register with Tool before serving.
	handlers map[string]ToolFunc
	tools    []obj // fixture + code tools, sorted by name
}

// ToolFunc handles one tool call; ctx carries the Principal (PrincipalFrom). Return a string
// (text result), an obj with "content" (a full CallToolResult), or any JSON value (structured).
type ToolFunc func(ctx context.Context, args obj) (any, error)

// MaxBodyBytes caps a request body; larger requests get 413.
const MaxBodyBytes = 4 << 20

// NewServer returns an empty server: add tools with Tool, protect it with Auth.
func NewServer() *Server { return &Server{} }

// Tool registers a code-defined tool (listed in tools/list). Not safe once serving.
func (s *Server) Tool(name, description string, h ToolFunc) *Server {
	if s.handlers == nil {
		s.handlers = map[string]ToolFunc{}
	}
	s.handlers[name] = h
	s.tools = slices.DeleteFunc(s.tools, func(t obj) bool { return t["name"] == name })
	s.tools = append(s.tools, obj{"name": name, "description": description, "inputSchema": obj{"type": "object"}})
	sort.Slice(s.tools, func(i, j int) bool { return s.tools[i]["name"].(string) < s.tools[j]["name"].(string) })
	return s
}

func LoadServer(fixturePath string) (*Server, error) {
	raw, err := os.ReadFile(fixturePath)
	if err != nil {
		return nil, err
	}
	s := &Server{}
	if err := json.Unmarshal(raw, &s.fx); err != nil {
		return nil, err
	}
	for _, t := range s.fx.Tools {
		if _, ok := t["name"].(string); !ok {
			return nil, fmt.Errorf("fixture tool without a name: %v", t)
		}
	}
	s.tools = append(s.tools, s.fx.Tools...)
	sort.Slice(s.tools, func(i, j int) bool { return s.tools[i]["name"].(string) < s.tools[j]["name"].(string) })
	root := filepath.Join(filepath.Dir(fixturePath), s.fx.SkillsDir)
	err = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() || d.Name() != "SKILL.md" {
			return err
		}
		sk, err := loadSkill(filepath.Dir(p))
		if err == nil {
			s.skills = append(s.skills, sk)
		}
		return err
	})
	sort.Slice(s.skills, func(i, j int) bool { return s.skills[i].uri < s.skills[j].uri })
	return s, err
}

func loadSkill(dir string) (skill, error) {
	md, err := os.ReadFile(filepath.Join(dir, "SKILL.md"))
	if err != nil {
		return skill{}, err
	}
	fm := parseFrontmatter(string(md))
	name, _ := fm["name"].(string)
	if name == "" || name != filepath.Base(dir) {
		return skill{}, fmt.Errorf("skill %s: name must match dir", dir)
	}
	sk := skill{uri: "skill://" + name + "/SKILL.md", frontmatter: fm}
	err = filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		rel, _ := filepath.Rel(dir, p)
		b, err := os.ReadFile(p)
		sk.files = append(sk.files, skillFile{"skill://" + name + "/" + filepath.ToSlash(rel), b})
		return err
	})
	sort.Slice(sk.files, func(i, j int) bool { return sk.files[i].uri < sk.files[j].uri })
	return sk, err
}

// parseFrontmatter handles the flat `key: value` YAML subset the fixture skills use.
func parseFrontmatter(md string) obj {
	fm := obj{}
	md = strings.ReplaceAll(md, "\r\n", "\n")
	if !strings.HasPrefix(md, "---\n") {
		return fm
	}
	end := strings.Index(md[4:], "\n---")
	if end < 0 {
		return fm
	}
	for _, line := range strings.Split(md[4:4+end], "\n") {
		k, v, ok := strings.Cut(line, ":")
		if ok && !strings.HasPrefix(line, " ") {
			fm[strings.TrimSpace(k)] = strings.Trim(strings.TrimSpace(v), `"'`)
		}
	}
	return fm
}

func (s *Server) serverInfo() obj { return obj{MetaServerInfo: s.fx.Server} }

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if s.Auth != nil && r.Method == http.MethodGet && s.Auth.serveMetadata(w, r) {
		return
	}
	if r.URL.Path != "/mcp" {
		http.NotFound(w, r)
		return
	}
	if r.Method != http.MethodPost {
		w.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, MaxBodyBytes))
	if err != nil {
		writeRPC(w, http.StatusRequestEntityTooLarge, nil, nil, &rpcError{Code: -32600, Message: "Request body too large or unreadable"})
		return
	}
	var req struct {
		ID     any    `json:"id"`
		Method string `json:"method"`
		Params obj    `json:"params"`
	}
	if err := json.Unmarshal(body, &req); err != nil {
		writeRPC(w, http.StatusBadRequest, nil, nil, &rpcError{Code: -32700, Message: "Parse error"})
		return
	}
	if req.ID == nil { // notification: none defined in core; accept
		w.WriteHeader(http.StatusAccepted)
		return
	}
	if req.Params == nil {
		req.Params = obj{}
	}
	if status, e := s.validate(r, req.Method, req.Params); e != nil {
		writeRPC(w, status, req.ID, nil, e)
		return
	}
	ctx := r.Context()
	if s.Auth != nil {
		var ok bool
		if ctx, ok = s.Auth.check(w, r, req.Method, req.Params); !ok {
			return
		}
	}
	res, e := s.dispatch(ctx, req.Method, req.Params)
	status := http.StatusOK
	if e != nil && e.Code == CodeMethodNotFound {
		status = http.StatusNotFound
	}
	writeRPC(w, status, req.ID, res, e)
}

func (s *Server) validate(r *http.Request, method string, p obj) (int, *rpcError) {
	mismatch := func(msg string) (int, *rpcError) {
		return http.StatusBadRequest, &rpcError{Code: CodeHeaderMismatch, Message: "Header mismatch: " + msg}
	}
	hv := r.Header.Get("MCP-Protocol-Version")
	if hv == "" {
		return mismatch("MCP-Protocol-Version header missing")
	}
	if hm := r.Header.Get("Mcp-Method"); hm != method {
		return mismatch(fmt.Sprintf("Mcp-Method header value '%s' does not match body value '%s'", hm, method))
	}
	if method == "tools/call" || method == "resources/read" || method == "prompts/get" {
		want, _ := p["name"].(string)
		if method == "resources/read" {
			want, _ = p["uri"].(string)
		}
		got, ok := decodeHeader(r.Header.Get("Mcp-Name"))
		if !ok || got != want {
			return mismatch(fmt.Sprintf("Mcp-Name header value '%s' does not match body value '%s'", got, want))
		}
	}
	meta, _ := p["_meta"].(map[string]any)
	mv, _ := meta[MetaVersion].(string)
	if mv != hv {
		return mismatch(fmt.Sprintf("MCP-Protocol-Version header value '%s' does not match _meta value '%s'", hv, mv))
	}
	if mv != ProtocolVersion {
		return http.StatusBadRequest, &rpcError{Code: CodeUnsupportedProtocol, Message: "Unsupported protocol version",
			Data: obj{"supported": []string{ProtocolVersion}, "requested": mv}}
	}
	return 0, nil
}

func decodeHeader(v string) (string, bool) {
	if strings.HasPrefix(v, "=?base64?") && strings.HasSuffix(v, "?=") && len(v) >= 11 {
		b, err := base64.StdEncoding.DecodeString(v[9 : len(v)-2])
		return string(b), err == nil
	}
	return v, v != ""
}

func (s *Server) dispatch(ctx context.Context, method string, p obj) (obj, *rpcError) {
	switch method {
	case "server/discover":
		return obj{"resultType": "complete", "supportedVersions": []string{ProtocolVersion},
			"capabilities": obj{"tools": obj{}, "resources": obj{}, "extensions": obj{SkillsExt: obj{}}},
			"_meta":        s.serverInfo(), "ttlMs": 3600000, "cacheScope": "public"}, nil
	case "tools/list":
		return obj{"resultType": "complete", "tools": s.tools, "ttlMs": 60000, "cacheScope": "public", "_meta": s.serverInfo()}, nil
	case "tools/call":
		return s.callTool(ctx, p)
	case "skills/list":
		list := []obj{}
		for _, sk := range s.skills {
			list = append(list, sk.entry())
		}
		return obj{"resultType": "complete", "skills": list, "ttlMs": 60000, "cacheScope": "public", "_meta": s.serverInfo()}, nil
	case "skills/get":
		uri, _ := p["uri"].(string)
		if _, _, err := parseSkillURI(uri); err != nil {
			return nil, &rpcError{Code: CodeInvalidParams, Message: "Invalid skill URI: " + err.Error()}
		}
		for _, sk := range s.skills {
			if sk.uri == uri {
				return obj{"resultType": "complete", "skill": sk.entry(), "_meta": s.serverInfo()}, nil
			}
		}
		return nil, &rpcError{Code: CodeInvalidParams, Message: "Unknown skill: " + uri}
	case "resources/read":
		uri, _ := p["uri"].(string)
		if _, _, err := parseSkillURI(uri); err != nil {
			return nil, &rpcError{Code: CodeInvalidParams, Message: "Invalid resource URI: " + err.Error()}
		}
		for _, sk := range s.skills {
			for _, f := range sk.files {
				if f.uri == uri {
					mime := "text/plain"
					if strings.HasSuffix(uri, ".md") {
						mime = "text/markdown"
					}
					return obj{"resultType": "complete", "contents": []obj{{"uri": uri, "mimeType": mime, "text": string(f.data)}},
						"ttlMs": 60000, "cacheScope": "public", "_meta": s.serverInfo()}, nil
				}
			}
		}
		return nil, &rpcError{Code: CodeInvalidParams, Message: "Resource not found: " + uri}
	}
	return nil, &rpcError{Code: CodeMethodNotFound, Message: "Method not found: " + method}
}

func (sk skill) entry() obj {
	res := []obj{}
	for _, f := range sk.files {
		res = append(res, obj{"uri": f.uri, "digest": fmt.Sprintf("sha256:%x", sha256.Sum256(f.data)), "size": len(f.data)})
	}
	return obj{"uri": sk.uri, "frontmatter": sk.frontmatter, "resources": res}
}

func (s *Server) callTool(ctx context.Context, p obj) (obj, *rpcError) {
	name, _ := p["name"].(string)
	args, _ := p["arguments"].(map[string]any)
	if h := s.handlers[name]; h != nil {
		v, err := h(ctx, args)
		if err != nil {
			return obj{"resultType": "complete", "content": []obj{{"type": "text", "text": err.Error()}}, "isError": true}, nil
		}
		switch r := v.(type) {
		case string:
			return obj{"resultType": "complete", "content": []obj{{"type": "text", "text": r}}, "isError": false}, nil
		case obj:
			if _, full := r["content"]; full {
				if r["resultType"] == nil {
					r["resultType"] = "complete"
				}
				return r, nil
			}
		}
		b, err := json.Marshal(v)
		if err != nil {
			return nil, &rpcError{Code: -32603, Message: "tool result is not JSON: " + err.Error()}
		}
		return obj{"resultType": "complete", "content": []obj{{"type": "text", "text": string(b)}}, "structuredContent": v, "isError": false}, nil
	}
	text := func(t string, structured any) obj {
		r := obj{"resultType": "complete", "content": []obj{{"type": "text", "text": t}}, "isError": false, "_meta": s.serverInfo()}
		if structured != nil {
			r["structuredContent"] = structured
		}
		return r
	}
	switch name {
	case "echo":
		t, _ := args["text"].(string)
		return text(t, obj{"text": t}), nil
	case "greet":
		if resp, _ := p["inputResponses"].(map[string]any); resp != nil {
			if nr, _ := resp["name"].(map[string]any); nr != nil && nr["action"] == "accept" {
				if c, _ := nr["content"].(map[string]any); c != nil {
					if n, _ := c["name"].(string); n != "" {
						return text("Hello, "+n+"!", obj{"greeting": "Hello, " + n + "!"}), nil
					}
				}
			}
		}
		// Missing/declined answer: ask (again), per MRTR error-handling guidance.
		return obj{"resultType": "input_required", "_meta": s.serverInfo(), "inputRequests": obj{
			"name": obj{"method": "elicitation/create", "params": obj{
				"mode": "form", "message": "What is your name?",
				"requestedSchema": obj{"type": "object", "properties": obj{"name": obj{"type": "string"}}, "required": []string{"name"}},
			}},
		}}, nil
	}
	return nil, &rpcError{Code: CodeInvalidParams, Message: "Unknown tool: " + name}
}

func writeRPC(w http.ResponseWriter, status int, id any, result obj, e *rpcError) {
	msg := obj{"jsonrpc": "2.0", "id": id}
	if e != nil {
		msg["error"] = e
	} else {
		msg["result"] = result
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(msg)
}
