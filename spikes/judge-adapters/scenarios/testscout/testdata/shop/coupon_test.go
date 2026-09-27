package shop

import "testing"

func TestCode(t *testing.T) {
	if (Coupon{code: "SAVE10"}).Code() != "SAVE10" {
		t.Fatal("Code")
	}
}
