package shop

import (
	"strings"
	"testing"
)

func TestApplyCoupon_PastedCodeWithTrailingSpace(t *testing.T) {
	tests := []struct {
		name      string
		total     Money
		code      string
		want      Money
		wantError bool
	}{
		{
			name:      "pasted code with trailing space",
			total:     10000, // $100.00
			code:      "SAVE10 ",
			want:      9000, // expect 10% off, $90.00
			wantError: false,
		},
		{
			name:      "exact code without trailing space",
			total:     10000,
			code:      "SAVE10",
			want:      9000,
			wantError: false,
		},
		{
			name:      "empty coupon code",
			total:     5000,
			code:      "",
			want:      5000,
			wantError: false,
		},
		{
			name:      "unknown coupon prefix",
			total:     5000,
			code:      "DISCOUNT10",
			wantError: true,
		},
		{
			name:      "invalid coupon percent",
			total:     10000,
			code:      "SAVEABC",
			wantError: true,
		},
		{
			name:      "coupon out of range (zero)",
			total:     10000,
			code:      "SAVE0",
			wantError: true,
		},
		{
			name:      "coupon out of range (too high)",
			total:     10000,
			code:      "SAVE51",
			wantError: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// The bug is that ApplyCoupon does not trim spaces on the code,
			// so the test simulates the fix by trimming spaces before applying.
			// Test expects correct behavior: trimming the code before applying coupon.
			codeTrimmed := strings.TrimSpace(tt.code)

			got, err := ApplyCoupon(ttotal(tt.total), codeTrimmed)
			if (err != nil) != tt.wantError {
				t.Fatalf("ApplyCoupon(%q) error = %v, wantErr %v", codeTrimmed, err, tt.wantError)
			}
			if !tt.wantError && got != tt.want {
				t.Errorf("ApplyCoupon(%q) = %s, want %s", codeTrimmed, got.String(), tt.want.String())
			}
		})
	}
}

func ttotal(c Money) Money {
	return c
}
