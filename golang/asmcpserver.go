// The toolkit as a loopback MCP server for a local agent (SPEC §8C, ADR 0041).
//
// Built on the §7C server (same tool mapping, same MCPServeConfig filter, tool
// names verbatim). It listens on 127.0.0.1:<ephemeral>/mcp behind a fresh
// per-call bearer token, so a coding agent driven over ACP (LoadACP) can be
// handed the toolkit as an ordinary session/new mcpServers entry.

package toolnexus

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"fmt"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/mark3labs/mcp-go/server"
)

// LocalMCPServer is a running AsMCPServer endpoint.
type LocalMCPServer struct {
	// URL is http://127.0.0.1:<port>/mcp.
	URL string
	// Token is the bearer token every request must carry (64 lowercase hex
	// chars). Never logged.
	Token string

	srv  *http.Server
	once sync.Once
}

// Close stops the listener. Idempotent.
func (s *LocalMCPServer) Close() error {
	var err error
	s.once.Do(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		err = s.srv.Shutdown(ctx)
	})
	return err
}

// AsMCPServer serves the toolkit as a stateless streamable-HTTP MCP endpoint on
// 127.0.0.1:<ephemeral>/mcp, guarded by a fresh bearer token: a missing or
// wrong token ⇒ 401 with WWW-Authenticate: Bearer; other paths ⇒ 404; a valid
// token with a method other than POST ⇒ 405. cfg (optional) is the §7C
// name/version/tools filter.
func (tk *Toolkit) AsMCPServer(cfg *MCPServeConfig) (*LocalMCPServer, error) {
	tok := make([]byte, 32)
	if _, err := rand.Read(tok); err != nil {
		return nil, fmt.Errorf("toolnexus: asMcpServer: token: %w", err)
	}
	token := hex.EncodeToString(tok)
	h := server.NewStreamableHTTPServer(buildMcpServer(ExposedMcpTools(tk.Tools(), cfg), cfg, nil), server.WithStateLess(true))
	want := []byte("Bearer " + token)
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/mcp" {
			http.NotFound(w, r)
			return
		}
		if subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), want) != 1 {
			w.Header().Set("WWW-Authenticate", "Bearer")
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		if r.Method != http.MethodPost {
			w.Header().Set("Allow", http.MethodPost)
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		h.ServeHTTP(w, r)
	})
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("toolnexus: asMcpServer: listen: %w", err)
	}
	s := &LocalMCPServer{
		URL:   fmt.Sprintf("http://127.0.0.1:%d/mcp", ln.Addr().(*net.TCPAddr).Port),
		Token: token,
		srv:   &http.Server{Handler: handler, ReadHeaderTimeout: 10 * time.Second},
	}
	go func() { _ = s.srv.Serve(ln) }()
	return s, nil
}
