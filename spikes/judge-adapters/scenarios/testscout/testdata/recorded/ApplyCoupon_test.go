package shop

import "testing"

// A customer pastes "SAVE10 " from an email. They must get the discount, not a 500.
func TestApplyCouponCustomerCases(t *testing.T) {
	cases := []struct {
		name, code string
		want       Money
		wantErr    bool
	}{
		{"no coupon pays full price", "", 1000, false},
		{"ten percent off", "SAVE10", 900, false},
		{"trailing space from a pasted code", "SAVE10 ", 900, false},
		{"unknown code is rejected", "FREEBIE", 0, true},
		{"over the cap is rejected", "SAVE90", 0, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, err := ApplyCoupon(1000, c.code)
			if (err != nil) != c.wantErr || got != c.want {
				t.Fatalf("ApplyCoupon(1000, %q) = %v, %v; want %v, err=%v", c.code, got, err, c.want, c.wantErr)
			}
		})
	}
}
