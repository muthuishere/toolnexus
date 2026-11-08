package toolnexus

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"regexp"
	"strings"
)

// TranscriptSearchDescription is the transcript_search tool's description
// (SPEC §7E "Memory hierarchy"), byte-identical in every port.
const TranscriptSearchDescription = "Search past conversation transcripts — the ground truth of what actually happened. " +
	"Case-insensitive substring match over message text. Returns one line per matching " +
	"message: <conversationId>#<index> <role>: <snippet>. Read-only."

// ListableConversationStore is a ConversationStore that can enumerate its
// conversations — what TranscriptSearchTool requires. Both shipped stores
// (InMemoryConversationStore, FileConversationStore) implement it.
type ListableConversationStore interface {
	ConversationStore
	List() ([]ConversationInfo, error)
}

// TranscriptSearchOptions tunes TranscriptSearchTool. Zero values take the
// defaults; a negative MaxResults/SnippetChars is a construction error.
type TranscriptSearchOptions struct {
	// MaxResults is the most hits per call and the ceiling for the limit input (default 20).
	MaxResults int
	// SnippetChars is the snippet length in Unicode code points (default 200).
	SnippetChars int
	// Name is the tool name (default "transcript_search").
	Name string
}

var transcriptWS = regexp.MustCompile(`[ \t\n\r\f\v]+`)

// normalizeTranscript collapses every run of ASCII whitespace to one space and trims.
func normalizeTranscript(s string) string {
	s = transcriptWS.ReplaceAllString(s, " ")
	s = strings.TrimPrefix(s, " ")
	return strings.TrimSuffix(s, " ")
}

// asciiFold lowercases A–Z only — never Unicode lowercasing (it changes lengths).
func asciiFold(r []rune) []rune {
	out := make([]rune, len(r))
	for i, c := range r {
		if c >= 'A' && c <= 'Z' {
			c += 'a' - 'A'
		}
		out[i] = c
	}
	return out
}

// compactJSON renders v as compact JSON without HTML escaping (JSON.stringify
// shape), object keys sorted by code point at every depth (§7E). A Go map
// already encodes sorted; a json.RawMessage is decoded (numbers kept verbatim)
// and re-encoded so its stored key order does not leak through.
func compactJSON(v any) string {
	if v == nil {
		return "{}"
	}
	if raw, ok := v.(json.RawMessage); ok {
		dec := json.NewDecoder(bytes.NewReader(raw))
		dec.UseNumber()
		var decoded any
		if err := dec.Decode(&decoded); err != nil {
			return string(raw)
		}
		v = decoded
	}
	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(v); err != nil {
		return ""
	}
	return unescapeLineSeparators(strings.TrimSuffix(buf.String(), "\n"))
}

// unescapeLineSeparators undoes encoding/json's unconditional \u2028 / \u2029
// escapes (SetEscapeHTML does not cover them) so they are emitted as-is (§7E).
// An escape preceded by an odd run of backslashes is a literal "\\u2028" text
// and is left alone.
func unescapeLineSeparators(s string) string {
	if !strings.Contains(s, `\u202`) {
		return s
	}
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == '\\' && i+5 < len(s) && s[i+1] == 'u' && s[i+2:i+5] == "202" && (s[i+5] == '8' || s[i+5] == '9') {
			if s[i+5] == '8' {
				b.WriteRune('\u2028')
			} else {
				b.WriteRune('\u2029')
			}
			i += 5
			continue
		}
		if s[i] == '\\' && i+1 < len(s) {
			b.WriteByte(s[i])
			b.WriteByte(s[i+1])
			i++
			continue
		}
		b.WriteByte(s[i])
	}
	return b.String()
}

// generic turns a typed value into its JSON-generic form (map[string]any / []any).
func generic(v any) any {
	switch v.(type) {
	case nil, string, map[string]any, []any, json.RawMessage:
		return v
	}
	b, err := json.Marshal(v)
	if err != nil {
		return nil
	}
	var out any
	if json.Unmarshal(b, &out) != nil {
		return nil
	}
	return out
}

func transcriptParts(content any) []string {
	switch c := generic(content).(type) {
	case string:
		return []string{c}
	case []any:
		var out []string
		for _, p := range c {
			part, ok := generic(p).(map[string]any)
			if !ok {
				continue
			}
			switch part["type"] {
			case "text":
				if s, ok := part["text"].(string); ok {
					out = append(out, s)
				}
			case "tool_use":
				out = append(out, compactJSON(part["input"]))
			case "tool_result":
				out = append(out, transcriptParts(part["content"])...)
			}
		}
		return out
	}
	return nil
}

// TranscriptMessageText is the searchable text of one stored message (SPEC §7E):
// string content, text parts, tool_use input as compact JSON, tool_result
// content (recursively), then each OpenAI tool call's arguments string — joined
// by "\n", then whitespace-normalized. Images contribute nothing.
func TranscriptMessageText(msg any) string {
	m, ok := generic(msg).(map[string]any)
	if !ok {
		return ""
	}
	pieces := transcriptParts(m["content"])
	if calls, ok := generic(m["tool_calls"]).([]any); ok {
		for _, tc := range calls {
			if call, ok := generic(tc).(map[string]any); ok {
				if fn, ok := generic(call["function"]).(map[string]any); ok {
					if a, ok := fn["arguments"].(string); ok {
						pieces = append(pieces, a)
					}
				}
			}
		}
	}
	return normalizeTranscript(strings.Join(pieces, "\n"))
}

func indexRunes(hay, needle []rune) int {
outer:
	for i := 0; i+len(needle) <= len(hay); i++ {
		for j := range needle {
			if hay[i+j] != needle[j] {
				continue outer
			}
		}
		return i
	}
	return -1
}

func transcriptSnippet(cps []rune, at, qlen, size int) string {
	if len(cps) <= size {
		return string(cps)
	}
	start := at + qlen/2 - size/2
	start = max(0, min(start, len(cps)-size))
	end := start + size
	s := string(cps[start:end])
	if start > 0 {
		s = "…" + s
	}
	if end < len(cps) {
		s += "…"
	}
	return s
}

// intArg reports v as an integer when it is one (JSON numbers decode as float64).
func intArg(v any) (int, bool) {
	switch n := v.(type) {
	case int:
		return n, true
	case int64:
		return int(n), true
	case float64:
		if n == math.Trunc(n) && !math.IsInf(n, 0) {
			return int(n), true
		}
	case json.Number:
		if i, err := n.Int64(); err == nil {
			return int(i), true
		}
	}
	return 0, false
}

// TranscriptSearchTool is a read-only Tool over a ConversationStore (SPEC §7E
// "Memory hierarchy" → the ground-truth tier). It errors unless the store
// implements List. Input {query, conversationId?, limit?}; output one line per
// hit, "<conversationId>#<index> <role>: <snippet>", "no matches" when none, and
// a final "(+<k> more)" when hits exceed the limit. Never writes.
func TranscriptSearchTool(store ConversationStore, opts TranscriptSearchOptions) (Tool, error) {
	listable, ok := store.(ListableConversationStore)
	if store == nil || !ok {
		return Tool{}, errors.New("transcriptSearchTool: the store must implement list()")
	}
	maxResults, snippetChars := opts.MaxResults, opts.SnippetChars
	if maxResults == 0 {
		maxResults = 20
	}
	if snippetChars == 0 {
		snippetChars = 200
	}
	if maxResults < 1 {
		return Tool{}, errors.New("transcriptSearchTool: maxResults must be an integer >= 1")
	}
	if snippetChars < 1 {
		return Tool{}, errors.New("transcriptSearchTool: snippetChars must be an integer >= 1")
	}
	name := opts.Name
	if name == "" {
		name = "transcript_search"
	}
	return Tool{
		Name:        name,
		Description: TranscriptSearchDescription,
		InputSchema: JSONSchema{
			"type": "object",
			"properties": map[string]any{
				"query":          map[string]any{"type": "string", "description": "Text to find (case-insensitive substring)."},
				"conversationId": map[string]any{"type": "string", "description": "Search only this conversation."},
				"limit":          map[string]any{"type": "integer", "description": fmt.Sprintf("Most hits to return (default and max %d).", maxResults)},
			},
			"required": []any{"query"},
		},
		Source: SourceCustom,
		Execute: func(args map[string]any, _ *ToolContext) (ToolResult, error) {
			q, _ := args["query"].(string)
			query := normalizeTranscript(q)
			if query == "" {
				return ToolResult{Output: "query is required", IsError: true}, nil
			}
			limit := maxResults
			if raw, present := args["limit"]; present {
				n, ok := intArg(raw)
				if !ok || n < 1 {
					return ToolResult{Output: "limit must be an integer >= 1", IsError: true}, nil
				}
				limit = min(n, maxResults)
			}
			var ids []string
			if id, ok := args["conversationId"].(string); ok {
				ids = []string{id}
			} else {
				infos, err := listable.List()
				if err != nil {
					return ToolResult{Output: err.Error(), IsError: true}, nil
				}
				for _, e := range infos {
					ids = append(ids, e.ID)
				}
			}
			needle := asciiFold([]rune(query))
			var hits []string
			total := 0
			for _, id := range ids {
				msgs, err := listable.Get(id)
				if err != nil {
					return ToolResult{Output: err.Error(), IsError: true}, nil
				}
				for index, msg := range msgs {
					cps := []rune(TranscriptMessageText(msg))
					at := indexRunes(asciiFold(cps), needle)
					if at < 0 {
						continue
					}
					total++
					if len(hits) < limit {
						role := "unknown"
						if m, ok := generic(msg).(map[string]any); ok {
							if r, ok := m["role"].(string); ok {
								role = r
							}
						}
						hits = append(hits, fmt.Sprintf("%s#%d %s: %s", id, index, role, transcriptSnippet(cps, at, len(needle), snippetChars)))
					}
				}
			}
			if total == 0 {
				return ToolResult{Output: "no matches"}, nil
			}
			if total > len(hits) {
				hits = append(hits, fmt.Sprintf("(+%d more)", total-len(hits)))
			}
			return ToolResult{Output: strings.Join(hits, "\n")}, nil
		},
	}, nil
}
