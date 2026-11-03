// Example 10 — GitHub's hosted MCP server (speaks 2026-07-28) with OAuth.
//
// GitHub supports neither DCR nor CIMD, so you register an OAuth App once
// (github.com/settings/developers -> New OAuth App, callback URL http://127.0.0.1/callback,
// enable "expiring user tokens" if you want refresh) and pass its client id + secret.
//
//	sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- go run ./examples/10-github
//
// Credentials are read from the environment and never printed; neither is any token.
// BROWSER=chrome-agent opens the consent page in the chrome-agent profile (used by the test);
// otherwise the system browser opens.
package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"

	mcp "toolnexus/spikes/mcp2026"
)

func main() {
	id, secret := os.Getenv("GITHUB_MCP_CLIENT_ID"), os.Getenv("GITHUB_MCP_CLIENT_SECRET")
	if id == "" || secret == "" {
		fmt.Println("skipped: run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET -- ...")
		return
	}

	login := mcp.LoopbackAuthorizer{} // system browser + http://127.0.0.1:<random>/callback
	if os.Getenv("BROWSER") == "chrome-agent" {
		login.Open = func(u string) error { return exec.Command("chrome-agent", "goto", u).Run() }
	}

	c := mcp.NewClient("https://api.githubcopilot.com/mcp/")
	c.Auth = &mcp.OAuth{
		ClientID:     id,
		ClientSecret: secret, // GitHub OAuth Apps are confidential clients (client_secret_basic)
		Authorizer:   login,
	}

	tools, err := c.Tools()
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("tools listed:", len(tools) > 0)

	res, err := c.CallTool("get_me", mcp.Obj{}, nil) // read-only: who am I
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println("get_me:", res["resultType"], "| isError:", res["isError"] == true) // content withheld on purpose
}
