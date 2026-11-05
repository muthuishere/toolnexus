// Command spike runs the MCP 2026-07-28 spike server, or the cross-port checks against any server.
package main

import (
	"fmt"
	"net"
	"net/http"
	"os"
	"time"

	mcp "toolnexus/spikes/mcp2026"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Println("usage: run.sh server | client <url> | selftest")
		os.Exit(2)
	}
	switch os.Args[1] {
	case "server":
		port := os.Getenv("PORT")
		if port == "" {
			port = "7103"
		}
		s := mcp.FixtureServer()
		fmt.Printf("listening on http://127.0.0.1:%s/mcp\n", port)
		hs := &http.Server{Addr: "127.0.0.1:" + port, Handler: s, ReadHeaderTimeout: 10 * time.Second,
			ReadTimeout: 30 * time.Second, WriteTimeout: 60 * time.Second, IdleTimeout: 120 * time.Second}
		if err := hs.ListenAndServe(); err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(1)
		}
	case "client":
		url := "http://127.0.0.1:7103/mcp"
		if len(os.Args) > 2 {
			url = os.Args[2]
		}
		if !mcp.RunChecks(url) {
			os.Exit(1)
		}
	case "selftest":
		ln, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			panic(err)
		}
		go http.Serve(ln, mcp.FixtureServer())
		if !mcp.RunChecks("http://" + ln.Addr().String() + "/mcp") {
			os.Exit(1)
		}
	}
}
