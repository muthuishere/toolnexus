;; GENERATED from conformance/cli-styles/ — do not edit by hand. The seven
;; built-in CLI styles and the one-shot preamble (SPEC §8C, ADR 0037), embedded
;; as their EXACT file text so every host carries byte-identical data with no
;; resource loading. toolnexus.cli-test holds the drift test against
;; conformance/; regenerate this file when a style changes there.
(ns toolnexus.cli-styles)

(def preamble
  "conformance/cli-styles/preamble.txt, byte for byte."
  "You are the language model behind a tool-calling client. The client executes tools; you never do.
Do not run commands, read or edit files, or use any tool of your own.
The REQUEST below is the complete conversation in OpenAI chat-completions format: \"messages\" holds every message so far, including earlier tool calls and their results; \"tools\" lists the only tools you may call.
Reply with exactly one JSON object and nothing else: no prose, no markdown fences.
To give the final answer: {\"content\": \"<answer>\"}
To call tools: {\"tool_calls\": [{\"id\": \"<unique id>\", \"type\": \"function\", \"function\": {\"name\": \"<tool name>\", \"arguments\": \"<JSON-encoded arguments>\"}}]}
Never both. Use tool results already in \"messages\" instead of calling the same tool again.
")

(def style-json
  "Style name -> the exact text of conformance/cli-styles/<name>.json."
  {"claude"
   "{
  \"name\": \"claude\",
  \"args\": [
    \"-p\",
    \"--tools\",
    \"\",
    \"--output-format\",
    \"text\",
    [
      \"--model\",
      \"{{model}}\"
    ]
  ],
  \"prompt\": \"stdin\",
  \"answer\": \"stdout\",
  \"verified\": \"claude 2.1.318 --help, 2026-11-04\",
  \"notes\": \"--tools \\\"\\\" disables Claude Code's own tools so the client executes them. Prompt on stdin: no argv length limit. ACP: the claude binary has no ACP mode; set ACPCommand to an ACP adapter (verified: npx -y @agentclientprotocol/claude-agent-acp@0.100.0).\"
}
"
   "codex"
   "{
  \"name\": \"codex\",
  \"args\": [
    \"exec\",
    \"--skip-git-repo-check\",
    \"--ephemeral\",
    \"--color\",
    \"never\",
    \"--sandbox\",
    \"read-only\",
    \"--output-last-message\",
    \"{{out}}\",
    [
      \"--model\",
      \"{{model}}\"
    ],
    \"-\"
  ],
  \"prompt\": \"stdin\",
  \"answer\": \"out\",
  \"verified\": \"codex-cli 0.160.0 exec --help, 2026-11-04\",
  \"notes\": \"'-' reads the prompt from stdin; the final message comes from --output-last-message, stdout carries progress. ACP: none (codex-acp 0.16.0 cannot reach current account models), so codex always uses the one-shot CLI.\"
}
"
   "copilot"
   "{
  \"name\": \"copilot\",
  \"args\": [
    \"-p\",
    \"{{prompt}}\",
    \"--silent\",
    \"--no-color\",
    [
      \"--model\",
      \"{{model}}\"
    ]
  ],
  \"prompt\": \"arg\",
  \"answer\": \"stdout\",
  \"verified\": \"GitHub Copilot CLI 1.0.98 --help, 2026-11-04\",
  \"notes\": \"Prompt as one argv element: Linux caps a single argument at 128 KiB (MAX_ARG_STRLEN).\",
  \"acp\": {
    \"args\": [
      \"--acp\"
    ],
    \"verified\": \"GitHub Copilot CLI 1.0.105 --acp, 2026-11-04\",
    \"notes\": \"Advertises mcpCapabilities.http; titles permission requests with the bare tool name.\"
  }
}
"
   "opencode"
   "{
  \"name\": \"opencode\",
  \"args\": [
    \"run\",
    [
      \"--model\",
      \"{{model}}\"
    ],
    \"{{prompt}}\"
  ],
  \"prompt\": \"arg\",
  \"answer\": \"stdout\",
  \"verified\": \"opencode v2.0.23 run --help, 2026-11-04\",
  \"notes\": \"Model is provider/model. Prompt as one argv element (128 KiB per-argument cap on Linux).\",
  \"acp\": {
    \"args\": [
      \"acp\"
    ],
    \"verified\": \"opencode v2.0.23 acp, 2026-11-04\",
    \"notes\": \"Model is provider/model.\"
  }
}
"
   "devin"
   "{
  \"name\": \"devin\",
  \"args\": [
    \"-p\",
    \"--prompt-file\",
    \"{{file}}\",
    [
      \"--model\",
      \"{{model}}\"
    ]
  ],
  \"prompt\": \"file\",
  \"answer\": \"stdout\",
  \"verified\": \"devin 2026.8.18 --help, 2026-11-04\",
  \"notes\": \"--prompt-file avoids argv limits. The default --permission-mode auto approves only read-only tools.\",
  \"acp\": {
    \"args\": [
      \"acp\"
    ],
    \"projectMcpFile\": \".mcp.json\",
    \"verified\": \"devin 2026.8.18 acp, 2026-11-04\",
    \"notes\": \"Ignores session/new mcpServers: the session runs in a fresh temp cwd holding .mcp.json with the toolkit server. Its MCP tools sit behind a lookup tool: a prompt that does not name the MCP server 'toolnexus' may never find them (live 2026-11-04: 0/3 without, 3/3 with), so systemHint names it on every ACP turn.\",
    \"systemHint\": \"Your tools include those of the MCP server named \\\"toolnexus\\\". When the request needs a tool, look up the toolnexus MCP server and use its tools.\"
  }
}
"
   "gemini"
   "{
  \"name\": \"gemini\",
  \"args\": [
    \"-p\",
    \"{{prompt}}\",
    [
      \"--model\",
      \"{{model}}\"
    ]
  ],
  \"prompt\": \"arg\",
  \"answer\": \"stdout\",
  \"verified\": \"UNVERIFIED: gemini is not installed on the spike machine\",
  \"notes\": \"From the Gemini CLI docs: -p/--prompt is non-interactive mode, -m/--model picks the model.\",
  \"acp\": {
    \"args\": [
      \"--acp\",
      \"--skip-trust\"
    ],
    \"verified\": \"gemini-cli 0.66.0 --acp --skip-trust, 2026-11-04 (ACP only; the one-shot args are UNVERIFIED)\",
    \"notes\": \"Without --skip-trust gemini drops every session MCP server in an untrusted folder.\"
  }
}
"
   "goose"
   "{
  \"name\": \"goose\",
  \"args\": [
    \"run\",
    \"--no-session\",
    \"--text\",
    \"{{prompt}}\"
  ],
  \"prompt\": \"arg\",
  \"answer\": \"stdout\",
  \"verified\": \"UNVERIFIED: goose is not installed on the spike machine\",
  \"notes\": \"From the goose docs: 'goose run --text' is the one-shot mode; the model comes from goose's own config (GOOSE_MODEL), so this style takes no model.\",
  \"acp\": {
    \"args\": [
      \"acp\"
    ],
    \"verified\": \"UNVERIFIED: goose is not installed on the verifying machine\",
    \"notes\": \"From the goose docs: 'goose acp' runs goose as an ACP agent.\"
  }
}
"})
