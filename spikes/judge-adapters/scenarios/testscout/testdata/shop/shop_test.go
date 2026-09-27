package shop

import "testing"

func TestCode(t *testing.T) {
	if (Coupon{code: "SAVE10"}).Code() != "SAVE10" {
		t.Fatal("Code")
	}
}

func TestTotalEmpty(t *testing.T) { // touches Total, asserts only the empty cart
	if Total(nil) != 0 {
		t.Fatal("empty cart")
	}
}
