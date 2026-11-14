package main

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestImageWireShapes(t *testing.T) {
	ts := route{name: "typesafe", model: "jev-1.14.0", images: "assets"}
	b, h := body(ts, true, "QUJD")
	raw, _ := json.Marshal(b)
	if h["TypeSafe-Danger-Zone-Images"] != "true" || b["state"] != "photo" ||
		!strings.Contains(string(raw), `"assets":{"photo":{"data_base64":"QUJD","format":"png","type":"image"}}`) {
		t.Fatalf("typesafe image body %s %v", raw, h)
	}
	cf := route{name: "cf", model: "@cf/cloudflare/clef", wrap: "cf", images: "cf-images"}
	b, h = body(cf, true, "QUJD")
	in := b["input"].(map[string]any)
	if b["model"] != "@cf/cloudflare/clef" || in["images"].([]string)[0] != "data:image/png;base64,QUJD" || len(h) != 0 {
		t.Fatalf("clef body %v", b)
	}
	if _, has := b["input"].(map[string]any)["model"]; has {
		t.Fatal("cf input must not carry model")
	}
}

func TestUnwrapCF(t *testing.T) {
	got, err := unwrapCF([]byte(`{"success":true,"result":{"state":"Completed","result":{"model":"m","answers":{}}}}`))
	if err != nil || string(got) != `{"model":"m","answers":{}}` {
		t.Fatalf("%s %v", got, err)
	}
	if _, err := unwrapCF([]byte(`{"success":true,"result":{"state":"Queued"}}`)); err == nil {
		t.Fatal("queued must fail")
	}
	if _, err := unwrapCF([]byte(`{"success":false,"errors":[{"message":"bad"}]}`)); err == nil || !strings.Contains(err.Error(), "bad") {
		t.Fatal(err)
	}
}

func TestRedPNGWithinLimits(t *testing.T) {
	if len(redPNG()) == 0 {
		t.Fatal("empty")
	}
}
