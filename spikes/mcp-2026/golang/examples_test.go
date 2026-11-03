package mcp2026_test

// Runs every program under examples/ end to end and checks its exact output, so the docs
// (which copy code from these programs) cannot rot. 01-08 are hermetic (127.0.0.1 only);
// 09 needs KEYCLOAK=1 + keycloak/docker-compose.yml, 10 needs GITHUB_OAUTH=1 + sec-injected creds.

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var hermeticExamples = map[string]string{
	"01-connect-http": `supported versions: [2026-07-28]
tools: echo, greet
echo: hello over HTTP`,
	"02-connect-stdio": `tool count: 2
echo: hello over stdio`,
	"03-tool-input": `server asks (elicitation/create): What is your name?
result: Hello, Ada!`,
	"04-skills": `skills extension: true
skill skill://hello-world/SKILL.md: hello-world
first heading: # Hello World Skill
downloaded: hello-world/SKILL.md
downloaded: hello-world/scripts/greet.sh`,
	"05-tool-search": `eager: 100 tools, 9721 bytes of schema
lazy:  2 tools, 519 bytes of schema
top hit: slack_create
result: slack_create ran with hi team`,
	"06-oauth-zero-config": `echo: hello after login
greet: Hello, Ada!
browser logins: 2
token stored for this issuer: true`,
	"07-oauth-web-pause-resume": `(a) WebHandler: 200 resumed after consent
(b) paused: authorization - true - has URL: true
(b) resumed: resumed by hand`,
	"08-serve-protected": `no token: 401 | WWW-Authenticate has resource_metadata: true
metadata: issuer ok: true | scopes: [mcp:read]
whoami: hello dev-user (mcp:read)
delete_all after step-up: deleted
whoami: hello dev-user (mcp:read mcp:admin)
verify hook: hello billing-service
wrong key rejected: true`,
}

// runExample builds examples/<name> and runs it with extra env, returning trimmed stdout.
func runExample(t *testing.T, name string, timeout time.Duration, env ...string) string {
	t.Helper()
	bin := filepath.Join(t.TempDir(), name)
	if out, err := exec.Command("go", "build", "-o", bin, "./examples/"+name).CombinedOutput(); err != nil {
		t.Fatalf("build %s: %v\n%s", name, err, out)
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	cmd := exec.CommandContext(ctx, bin)
	cmd.Env = append(os.Environ(), env...)
	var stderr strings.Builder
	cmd.Stderr = &stderr
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("run %s: %v\nstdout:\n%s\nstderr:\n%s", name, err, out, stderr.String())
	}
	return strings.TrimSpace(string(out))
}

func TestExamplesHermetic(t *testing.T) {
	for name, want := range hermeticExamples {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			if got := runExample(t, name, 60*time.Second); got != want {
				t.Fatalf("output drifted\n--- got ---\n%s\n--- want ---\n%s", got, want)
			}
		})
	}
}

func TestExampleKeycloak(t *testing.T) {
	if os.Getenv("KEYCLOAK") != "1" {
		t.Skip("set KEYCLOAK=1 with keycloak/docker-compose.yml running")
	}
	got := runExample(t, "09-keycloak", 90*time.Second, "HEADLESS_LOGIN=1")
	t.Log("\n" + got)
	if !strings.Contains(got, "whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true") || !strings.Contains(got, "greet: Hello, Alice!") {
		t.Fatalf("unexpected output:\n%s", got)
	}
}

func TestExampleGitHub(t *testing.T) {
	if os.Getenv("GITHUB_OAUTH") != "1" {
		t.Skip("set GITHUB_OAUTH=1 (run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET)")
	}
	got := runExample(t, "10-github", 3*time.Minute, "BROWSER=chrome-agent")
	t.Log("\n" + got)
	if got != "tools listed: true\nget_me: complete | isError: false" {
		t.Fatalf("unexpected output:\n%s", got)
	}
}
