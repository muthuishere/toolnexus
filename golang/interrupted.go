package toolnexus

import "context"

// Interrupted turns are saved (SPEC §8): a stateful run (Ask / StreamWithID with
// an id) that ends in an error or a cancellation still saves the prior
// transcript, the user prompt and every complete message. Each assistant tool
// call left without a result first gets a synthetic {isError:true,
// output:"interrupted: the turn ended before this tool returned. ..."} result in the active dialect's shape, so the saved
// transcript stays valid. The original error is still returned / emitted.

// interruptedOutput is the output of the synthetic result sealed onto a tool
// call that never finished.
const interruptedOutput = "interrupted: the turn ended before this tool returned. Whether its side effects happened is unknown. Do not re-run it automatically; check first."

// partialTranscript receives the transcript of a run that ended in an error.
type partialTranscript struct {
	set      bool
	messages []any
}

type partialSinkKey struct{}

func withPartialSink(ctx context.Context, sink *partialTranscript) context.Context {
	if ctx == nil {
		ctx = context.Background()
	}
	return context.WithValue(ctx, partialSinkKey{}, sink)
}

// keepPartial seals the transcript of a failed run and hands a copy to the
// stateful caller's sink (if any). It returns the sealed transcript.
func keepPartial(ctx context.Context, messages []any) []any {
	messages = sealInterrupted(messages)
	if sink, ok := ctx.Value(partialSinkKey{}).(*partialTranscript); ok && sink != nil {
		sink.set = true
		sink.messages = append([]any(nil), messages...)
	}
	return messages
}

// savePartial persists an interrupted turn under id. A failure before the loop
// built any message still saves the prior transcript plus the user prompt. A
// save error is dropped: the run's own error is the one the caller gets.
func (c *Client) savePartial(id string, history []any, prompt string, tk *Toolkit, sink *partialTranscript) {
	msgs := sink.messages
	if !sink.set {
		msgs = append([]any(nil), history...)
		if len(history) == 0 && c.opts.Style != StyleAnthropic {
			if sys := c.system(tk); sys != "" {
				msgs = append(msgs, map[string]any{"role": "system", "content": sys})
			}
		}
		msgs = append(msgs, userMessage(prompt))
	}
	_ = c.store.Save(id, msgs)
}

// appendFinishedBlocks appends the finished anthropic tool_result blocks of an
// aborted turn as one user message (nil slots are calls that never finished).
func appendFinishedBlocks(messages []any, results []any) []any {
	var blocks []any
	for _, r := range results {
		if r != nil {
			blocks = append(blocks, r)
		}
	}
	if len(blocks) == 0 {
		return messages
	}
	return append(messages, map[string]any{"role": "user", "content": blocks})
}

// sealInterrupted appends an "interrupted" error result for every tool call of
// the last assistant message that has no result — openai: a role:"tool"
// message; anthropic: a tool_result block (is_error) in the following user
// message.
func sealInterrupted(messages []any) []any {
	last := -1
	for i := len(messages) - 1; i >= 0; i-- {
		if m, ok := messages[i].(map[string]any); ok && m["role"] == "assistant" {
			last = i
			break
		}
	}
	if last < 0 {
		return messages
	}
	asst := messages[last].(map[string]any)
	// openai dialect
	if calls := anySlice(asst["tool_calls"]); len(calls) > 0 {
		have := map[string]bool{}
		for _, m := range messages[last+1:] {
			if mm, ok := m.(map[string]any); ok && mm["role"] == "tool" {
				if id, ok := mm["tool_call_id"].(string); ok {
					have[id] = true
				}
			}
		}
		for _, raw := range calls {
			tc, _ := raw.(map[string]any)
			id, _ := tc["id"].(string)
			if id == "" || have[id] {
				continue
			}
			messages = append(messages, map[string]any{"role": "tool", "tool_call_id": id, "content": interruptedOutput})
		}
		return messages
	}
	// anthropic dialect
	var ids []string
	for _, raw := range anySlice(asst["content"]) {
		if b, ok := raw.(map[string]any); ok && b["type"] == "tool_use" {
			if id, ok := b["id"].(string); ok && id != "" {
				ids = append(ids, id)
			}
		}
	}
	if len(ids) == 0 {
		return messages
	}
	var blocks []any
	have := map[string]bool{}
	hasUser := false
	if last+1 < len(messages) {
		if um, ok := messages[last+1].(map[string]any); ok && um["role"] == "user" {
			if bs := anySlice(um["content"]); bs != nil {
				hasUser = true
				blocks = append(blocks, bs...)
				for _, raw := range bs {
					if b, ok := raw.(map[string]any); ok && b["type"] == "tool_result" {
						if id, ok := b["tool_use_id"].(string); ok {
							have[id] = true
						}
					}
				}
			}
		}
	}
	added := false
	for _, id := range ids {
		if !have[id] {
			blocks = append(blocks, map[string]any{"type": "tool_result", "tool_use_id": id, "content": interruptedOutput, "is_error": true})
			added = true
		}
	}
	if !added {
		return messages
	}
	user := map[string]any{"role": "user", "content": blocks}
	if hasUser {
		out := append([]any(nil), messages...)
		out[last+1] = user
		return out
	}
	return append(messages, user)
}

// anySlice views a transcript list as []any (JSON-decoded or built in-loop).
func anySlice(v any) []any {
	switch t := v.(type) {
	case []any:
		return t
	case []map[string]any:
		out := make([]any, len(t))
		for i, m := range t {
			out[i] = m
		}
		return out
	}
	return nil
}
