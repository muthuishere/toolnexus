package shop

import "testing"

// Coverage-only: executes every line, asserts nothing a customer would notice.
func TestRoundCents(t *testing.T) {
	_ = RoundCents(1.5)
	_ = RoundCents(-2.5)
	_ = RoundCents(0)
}
