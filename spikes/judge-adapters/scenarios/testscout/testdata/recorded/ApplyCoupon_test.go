package shop

import (
	"testing"
)

func TestApplyCoupon_TrailingSpace(t *testing.T) {
	total := Money(10000) // $100.00
	tests := []struct {
		name      string
		code      string
		wantTotal Money
		wantErr   bool
	}{
		{
			name:      "Valid code without trailing space",
			code:      "SAVE10",
			wantTotal: Money(9000), // 10% off $100.00 = $90.00
			wantErr:   false,
		},
		{
			name:      "Pasted code with trailing space",
			code:      "SAVE10 ",
			wantTotal: Money(9000), // Expect 10% off, ignoring trailing space
			wantErr:   false,
		},
		{
			name:      "Empty code means no discount",
			code:      "",
			wantTotal: total,
			wantErr:   false,
		},
		{
			name:      "Invalid coupon prefix",
			code:      "DISCOUNT10",
			wantTotal: 0,
			wantErr:   true,
		},
		{
			name:      "Out of range percentage",
			code:      "SAVE0",
			wantTotal: 0,
			wantErr:   true,
		},
		{
			name:      "Out of range percentage above 50",
			code:      "SAVE99",
			wantTotal: 0,
			wantErr:   true,
		},
		{
			name:      "Non-integer discount",
			code:      "SAVE1O", // letter O instead of zero
			wantTotal: 0,
			wantErr:   true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := ApplyCoupon(total, tt.code)
			if (err != nil) != tt.wantErr {
				t.Fatalf("ApplyCoupon(%q) error = %v, wantErr %v", tt.code, err, tt.wantErr)
			}
			if !tt.wantErr && got != tt.wantTotal {
				t.Errorf("ApplyCoupon(%q) = %s, want %s", tt.code, got.String(), tt.wantTotal.String())
			}
		})
	}
}
