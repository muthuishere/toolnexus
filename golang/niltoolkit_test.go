package toolnexus

import "testing"

// A nil Toolkit means "no tools" — Run/Ask used to panic on it, although §8 Gap 5
// already defines the empty-tool-list behaviour.
func TestNilToolkitIsEmpty(t *testing.T) {
	var tk *Toolkit
	if got := tk.Tools(); len(got) != 0 {
		t.Fatalf("Tools() on nil = %v", got)
	}
	if got := tk.ToOpenAI(); len(got) != 0 {
		t.Fatalf("ToOpenAI() on nil = %v", got)
	}
	if got := tk.ToAnthropic(); len(got) != 0 {
		t.Fatalf("ToAnthropic() on nil = %v", got)
	}
	if sp := tk.SkillsPrompt(); sp != "" {
		t.Fatalf("SkillsPrompt() on nil = %q", sp)
	}
	if _, ok := tk.Get("anything"); ok {
		t.Fatal("Get() on nil reported a tool")
	}
	r, err := tk.Execute(nil, "anything", nil)
	if err != nil || !r.IsError {
		t.Fatalf("Execute() on nil = %+v, %v", r, err)
	}
	if s := tk.McpStatus(); len(s) != 0 {
		t.Fatalf("McpStatus() on nil = %v", s)
	}
}
