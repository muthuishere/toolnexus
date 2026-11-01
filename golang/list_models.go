package toolnexus

// ListModels (go-agent-cli ask #9, golang only for now): the models a provider
// offers, for model switching. One GET, no retries.

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strings"
)

// ModelInfo is one model a provider lists.
type ModelInfo struct {
	ID string `json:"id"`
	// Name is the provider's display name, when it gives one.
	Name string `json:"name,omitempty"`
	// ContextLength is the context window in tokens when the provider reports it
	// (OpenRouter does; OpenAI and Anthropic do not) — 0 = unknown.
	ContextLength int `json:"contextLength,omitempty"`
}

// ListModels lists the models at opts.BaseURL using opts.Style, the same key
// rules as the client (APIKey, APIKeyEnv, or the host's own env var) and
// opts.HTTPClient. openai style: GET {base}/models. anthropic style:
// GET {base}/v1/models, following pagination. Sorted by ID.
func ListModels(ctx context.Context, opts ClientOptions) ([]ModelInfo, error) {
	if ctx == nil {
		ctx = context.Background()
	}
	c := CreateClient(opts)
	key, err := c.resolveKey()
	if err != nil {
		return nil, err
	}
	base := strings.TrimRight(opts.BaseURL, "/")
	var out []ModelInfo
	if opts.Style == StyleAnthropic {
		endpoint := base + "/v1/models"
		if strings.HasSuffix(base, "/v1") {
			endpoint = base + "/models"
		}
		after := ""
		for page := 0; page < 100; page++ {
			u := endpoint + "?limit=1000"
			if after != "" {
				u += "&after_id=" + url.QueryEscape(after)
			}
			var body struct {
				Data []struct {
					ID          string `json:"id"`
					DisplayName string `json:"display_name"`
				} `json:"data"`
				HasMore bool   `json:"has_more"`
				LastID  string `json:"last_id"`
			}
			if err := c.getJSON(ctx, u, map[string]string{"x-api-key": key, "anthropic-version": "2023-06-01"}, &body); err != nil {
				return nil, err
			}
			for _, m := range body.Data {
				out = append(out, ModelInfo{ID: m.ID, Name: m.DisplayName})
			}
			if !body.HasMore || body.LastID == "" {
				break
			}
			after = body.LastID
		}
	} else {
		var body struct {
			Data []struct {
				ID            string `json:"id"`
				Name          string `json:"name"`
				ContextLength int    `json:"context_length"`
			} `json:"data"`
		}
		if err := c.getJSON(ctx, base+"/models", map[string]string{"Authorization": "Bearer " + key}, &body); err != nil {
			return nil, err
		}
		for _, m := range body.Data {
			out = append(out, ModelInfo{ID: m.ID, Name: m.Name, ContextLength: m.ContextLength})
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

func (c *Client) getJSON(ctx context.Context, endpoint string, headers map[string]string, into any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return err
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 32<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode/100 != 2 {
		msg := string(raw)
		if len(msg) > 300 {
			msg = msg[:300]
		}
		return fmt.Errorf("list models: HTTP %d: %s", resp.StatusCode, msg)
	}
	return json.Unmarshal(raw, into)
}
