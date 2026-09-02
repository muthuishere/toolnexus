package toolnexus

import (
	"fmt"
	"log"
	"strings"
)

// ---------------------------------------------------------------------------
// §8A Content-part emission, and the tool-result relocation rule.
//
// Emission lives here, in message assembly — adapters.go is tool-SCHEMA only.
// The canonical transcript keeps parts in their §1B shape; this file turns them
// into provider blocks on the way out, and the OpenAI synthetic user message it
// may add is an ADAPTER ARTIFACT: it never enters the transcript, the
// ConversationStore, or Translate output.
// ---------------------------------------------------------------------------

// OnUnsupportedPart modes (ClientOptions.OnUnsupportedPart). Empty ⇒ the
// provenance rule: an attached part errors, a tool-derived part degrades.
const (
	UnsupportedPartAsError = "error"
	UnsupportedPartAsText  = "text"
)

// allowedBlocks is the POSITIVE ALLOWLIST (§8A). An encoded block is asserted
// against it before the request is sent, because an unknown block type sent
// upstream returns HTTP 200 with the content silently discarded — the exact bug
// this change exists to remove.
var allowedBlocks = map[ClientStyle]map[string]bool{
	StyleOpenAI:    {"text": true, "image_url": true, "file": true, "input_audio": true},
	StyleAnthropic: {"text": true, "image": true, "document": true},
}

// audioFormat maps an audio mime type to OpenAI's input_audio `format`.
func audioFormat(mime string) string {
	switch mime {
	case "audio/mpeg", "audio/mp3":
		return "mp3"
	case "audio/wav", "audio/x-wav", "audio/wave":
		return "wav"
	}
	return strings.TrimPrefix(mime, "audio/")
}

// encodePart maps one §1B part to the style's block, or reports an
// *UnsupportedPartError when the style defines no shape for it.
func encodePart(style ClientStyle, p ContentPart) (map[string]any, error) {
	unsupported := func() (map[string]any, error) {
		return nil, &UnsupportedPartError{PartType: p.Type, MimeType: p.MimeType, Style: style}
	}
	var block map[string]any
	switch style {
	case StyleAnthropic:
		switch p.Type {
		case PartText:
			block = map[string]any{"type": "text", "text": p.Text}
		case PartImage:
			if p.Data != "" {
				block = map[string]any{"type": "image", "source": map[string]any{"type": "base64", "media_type": p.MimeType, "data": p.Data}}
			} else {
				block = map[string]any{"type": "image", "source": map[string]any{"type": "url", "url": p.URL}}
			}
		case PartFile:
			if p.Data != "" {
				block = map[string]any{"type": "document", "source": map[string]any{"type": "base64", "media_type": p.MimeType, "data": p.Data}}
			} else {
				block = map[string]any{"type": "document", "source": map[string]any{"type": "url", "url": p.URL}}
			}
		default: // audio — Anthropic defines no audio block.
			return unsupported()
		}
	default: // openai (Chat Completions)
		switch p.Type {
		case PartText:
			block = map[string]any{"type": "text", "text": p.Text}
		case PartImage:
			url := p.URL
			if p.Data != "" {
				url = p.dataURL()
			}
			block = map[string]any{"type": "image_url", "image_url": map[string]any{"url": url}}
		case PartFile:
			// Chat Completions has no URL form for `file`, and file_data REQUIRES
			// the data:<mime>;base64, prefix (a bare base64 string is a 400).
			if p.Data == "" {
				return unsupported()
			}
			name := p.Name
			if name == "" {
				name = "file"
			}
			block = map[string]any{"type": "file", "file": map[string]any{"filename": name, "file_data": p.dataURL()}}
		default: // audio
			if p.Data == "" {
				return unsupported() // no URL form for input_audio
			}
			block = map[string]any{"type": "input_audio", "input_audio": map[string]any{"data": p.Data, "format": audioFormat(p.MimeType)}}
		}
	}
	// The positive allowlist: a block that is not a named shape for this style
	// never reaches the wire.
	t, _ := block["type"].(string)
	if !allowedBlocks[styleKey(style)][t] {
		return nil, fmt.Errorf("toolnexus: block type %q is not in the %q allowlist for a %q part", t, string(style), p.Type)
	}
	return block, nil
}

func styleKey(style ClientStyle) ClientStyle {
	if style == StyleAnthropic {
		return StyleAnthropic
	}
	return StyleOpenAI
}

// unsupportedPlaceholder names a part that could not be represented, without
// ever rendering its bytes: the canonical §8A form
// "[unsupported <type> part (<mimeType>, <bytes> bytes)]".
func unsupportedPlaceholder(p ContentPart) map[string]any {
	return map[string]any{
		"type": "text",
		"text": fmt.Sprintf("[unsupported %s part (%s, %d bytes)]", p.Type, p.MimeType, p.Bytes()),
	}
}

// partsOf coerces a canonical `content` / `parts` value into §1B parts. It
// accepts the native []ContentPart and the []any-of-maps form a transcript
// takes after a ConversationStore round trip, and refuses anything whose
// entries are not the four part types (so provider blocks are not mistaken for
// parts).
func partsOf(v any) ([]ContentPart, bool) {
	switch t := v.(type) {
	case []ContentPart:
		return t, true
	case []any:
		if len(t) == 0 {
			return nil, false
		}
		out := make([]ContentPart, 0, len(t))
		for _, e := range t {
			if p, ok := e.(ContentPart); ok {
				out = append(out, p)
				continue
			}
			m, ok := asJSONMap(e)
			if !ok {
				return nil, false
			}
			kind, _ := m["type"].(string)
			switch kind {
			case PartText, PartImage, PartFile, PartAudio:
			default:
				return nil, false
			}
			str := func(k string) string { s, _ := m[k].(string); return s }
			out = append(out, ContentPart{
				Type: kind, Text: str("text"), MimeType: str("mimeType"),
				Data: str("data"), URL: str("url"), Name: str("name"),
			})
		}
		return out, true
	}
	return nil, false
}

// partEmitter carries the per-request emission policy: the style, the size cap,
// the unsupported-part override, and the warn-once latch.
type partEmitter struct {
	style    ClientStyle
	maxBytes int
	mode     string
	warned   *bool
}

func (c *Client) emitter() partEmitter {
	warned := false
	return partEmitter{style: styleKey(c.opts.Style), maxBytes: c.opts.MaxPartBytes, mode: c.opts.OnUnsupportedPart, warned: &warned}
}

// blocks encodes parts for the wire. attached says whether the CALLER supplied
// them (§8A provenance): an attached part the style cannot represent, or one
// over MaxPartBytes, is a typed error before any HTTP call; a tool/MCP-derived
// one degrades to a text placeholder and warns at most once, because failing a
// caller's run because a server volunteered an oversized or unrepresentable
// part would be a regression.
//
// MaxPartBytes is enforced HERE, at request assembly, over every part
// regardless of provenance (§1B) — not only at the edge constructors, because
// an MCP-derived part never passed through one, and a limit a tool-supplied
// image can walk around is not a limit. The constructor check (content.go) is
// a fast-fail convenience for attached parts; this is the guarantee.
func (e partEmitter) blocks(parts []ContentPart, attached bool) ([]any, error) {
	out := make([]any, 0, len(parts))
	for _, p := range parts {
		if err := p.Validate(0); err != nil { // construction validity only; size below
			return nil, err
		}
		if e.maxBytes > 0 {
			if n := p.Bytes(); n > e.maxBytes {
				sizeErr := &PartError{Kind: "size", Msg: fmt.Sprintf("part is %d decoded bytes, over the %d byte limit", n, e.maxBytes)}
				block, err := e.degrade(p, sizeErr, attached)
				if err != nil {
					return nil, err
				}
				out = append(out, block)
				continue
			}
		}
		block, err := encodePart(e.style, p)
		if err == nil {
			out = append(out, block)
			continue
		}
		degraded, err := e.degrade(p, err, attached)
		if err != nil {
			return nil, err
		}
		out = append(out, degraded)
	}
	return out, nil
}

// degrade applies the shared provenance rule (§1B / §8A) to a violation that is
// either an unrepresentable part or an oversize one: an ATTACHED part is a
// typed error, a TOOL-DERIVED one degrades to the canonical placeholder and
// warns at most once. OnUnsupportedPart overrides the split uniformly for
// both kinds of violation.
func (e partEmitter) degrade(p ContentPart, err error, attached bool) (map[string]any, error) {
	var strict bool
	switch e.mode {
	case UnsupportedPartAsError:
		strict = true
	case UnsupportedPartAsText:
		strict = false
	default:
		strict = attached
	}
	if strict {
		return nil, err
	}
	if !*e.warned {
		*e.warned = true
		log.Printf("[toolnexus] %v — replaced with a text placeholder", err)
	}
	return unsupportedPlaceholder(p), nil
}

// splitParts separates text parts from the rest, preserving order in each group.
func splitParts(parts []ContentPart) (text, other []ContentPart) {
	for _, p := range parts {
		if p.nonText() {
			other = append(other, p)
		} else {
			text = append(text, p)
		}
	}
	return
}

// emitMessages renders the canonical transcript for the wire. Returns the
// messages to send; the input slice and its maps are never mutated.
func (c *Client) emitMessages(messages []any) ([]any, error) {
	if !anyParts(messages) {
		return messages, nil // byte-identical: the string path never allocates
	}
	e := c.emitter()
	if e.style == StyleAnthropic {
		return e.emitAnthropic(messages)
	}
	return e.emitOpenAI(messages)
}

// anyParts reports whether the transcript carries anything needing emission.
// A transcript of plain string contents short-circuits, so the pre-0.17 wire
// bytes are untouched.
func anyParts(messages []any) bool {
	for _, m := range messages {
		mm, ok := m.(map[string]any)
		if !ok {
			continue
		}
		if _, ok := mm["parts"]; ok {
			return true
		}
		if _, ok := partsOf(mm["content"]); ok {
			return true
		}
		if blocks, ok := mm["content"].([]any); ok {
			for _, b := range blocks {
				if bm, ok := b.(map[string]any); ok {
					if _, has := bm["parts"]; has {
						return true
					}
				}
			}
		}
	}
	return false
}

func copyMap(m map[string]any) map[string]any {
	out := make(map[string]any, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

// emitOpenAI applies the RELOCATION RULE: the openai `tool` role rejects an
// image (a hard 400), so a tool message carries its output plus TEXT parts
// only, and all non-text parts from all tool results answering one assistant
// turn move, in tool-call order, into ONE synthetic user message emitted right
// after the last tool message.
func (e partEmitter) emitOpenAI(messages []any) ([]any, error) {
	out := make([]any, 0, len(messages)+1)
	var relocated []any // pending synthetic-user blocks for the current tool run

	flush := func() {
		if len(relocated) > 0 {
			out = append(out, map[string]any{"role": "user", "content": relocated})
			relocated = nil
		}
	}

	for _, raw := range messages {
		m, ok := raw.(map[string]any)
		if !ok {
			flush()
			out = append(out, raw)
			continue
		}
		role, _ := m["role"].(string)
		if role != "tool" {
			flush()
		}
		parts, hasParts := partsOf(m["content"])
		switch {
		case role == "tool":
			msg := copyMap(m)
			delete(msg, "parts")
			delete(msg, "name") // the label carrier, not a wire field
			tparts, _ := partsOf(m["parts"])
			text, other := splitParts(tparts)
			if len(text) > 0 {
				blocks, err := e.blocks(append([]ContentPart{Text(asString(m["content"]))}, text...), false)
				if err != nil {
					return nil, err
				}
				msg["content"] = blocks
			}
			out = append(out, msg)
			if len(other) > 0 {
				id, _ := m["tool_call_id"].(string)
				name, _ := m["name"].(string)
				relocated = append(relocated, map[string]any{
					"type": "text",
					"text": fmt.Sprintf("Output of tool %s (%s):", name, id),
				})
				blocks, err := e.blocks(other, false)
				if err != nil {
					return nil, err
				}
				relocated = append(relocated, blocks...)
			}
		case hasParts:
			blocks, err := e.blocks(parts, true)
			if err != nil {
				return nil, err
			}
			msg := copyMap(m)
			msg["content"] = blocks
			out = append(out, msg)
		default:
			out = append(out, raw)
		}
	}
	flush()
	return out, nil
}

// emitAnthropic emits tool-result parts NATIVELY, as blocks inside
// tool_result.content keyed to the tool_use_id — no relocation, so the
// association, the cache breakpoints, and "this is tool output" all survive.
func (e partEmitter) emitAnthropic(messages []any) ([]any, error) {
	out := make([]any, 0, len(messages))
	for _, raw := range messages {
		m, ok := raw.(map[string]any)
		if !ok {
			out = append(out, raw)
			continue
		}
		if parts, ok := partsOf(m["content"]); ok {
			blocks, err := e.blocks(parts, true)
			if err != nil {
				return nil, err
			}
			msg := copyMap(m)
			msg["content"] = blocks
			out = append(out, msg)
			continue
		}
		inner, ok := m["content"].([]any)
		if !ok {
			out = append(out, raw)
			continue
		}
		changed := false
		newInner := make([]any, 0, len(inner))
		for _, b := range inner {
			bm, ok := b.(map[string]any)
			if !ok {
				newInner = append(newInner, b)
				continue
			}
			tparts, has := partsOf(bm["parts"])
			if !has || len(tparts) == 0 {
				if _, stray := bm["parts"]; stray {
					nb := copyMap(bm)
					delete(nb, "parts")
					delete(nb, "name")
					newInner = append(newInner, nb)
					changed = true
					continue
				}
				newInner = append(newInner, b)
				continue
			}
			changed = true
			nb := copyMap(bm)
			delete(nb, "parts")
			delete(nb, "name") // the relocation label carrier; anthropic emits natively
			lead := []ContentPart{}
			if s := asString(bm["content"]); s != "" {
				lead = append(lead, Text(s))
			}
			blocks, err := e.blocks(append(lead, tparts...), false)
			if err != nil {
				return nil, err
			}
			nb["content"] = blocks
			newInner = append(newInner, nb)
		}
		if !changed {
			out = append(out, raw)
			continue
		}
		msg := copyMap(m)
		msg["content"] = newInner
		out = append(out, msg)
	}
	return out, nil
}

func asString(v any) string {
	s, _ := v.(string)
	return s
}

// userMessage seeds the transcript's user turn. Given a string the assembled
// message is byte-identical to a pre-0.17 port; given parts, content is the §1B
// list and the caller's ordering is preserved (ordering is semantic to a model).
func userMessage(prompt any) map[string]any {
	return map[string]any{"role": "user", "content": prompt}
}

// withToolParts attaches a tool result's §1B parts to its transcript entry, and
// the tool name that labels them after relocation. Both keys are stripped at
// emission, and neither is added when a result carries no parts — so a text-only
// tool result stays byte-identical to pre-0.17.
func withToolParts(entry map[string]any, name string, parts []ContentPart) map[string]any {
	if len(parts) == 0 {
		return entry
	}
	entry["parts"] = parts
	entry["name"] = name
	return entry
}
