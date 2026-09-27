package shop

import (
	"testing"
)

func TestValidateLine_Valid(t *testing.T) {
	tests := []struct {
		name string
		line Line
		want error
	}{
		{
			name: "normal valid product",
			line: Line{SKU: "ABC123", Price: 1999, Qty: 1},
			want: nil,
		},
		{
			name: "valid multiple quantity",
			line: Line{SKU: "XYZ789", Price: 500, Qty: 10},
			want: nil,
		},
		{
			name: "valid maximum quantity",
			line: Line{SKU: "MAXQTY", Price: 100, Qty: 99},
			want: nil,
		},
		{
			name: "valid trimmed SKU",
			line: Line{SKU: " TRIMSKU ", Price: 2500, Qty: 2},
			want: nil,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := ValidateLine(tt.line)
			if got != tt.want {
				t.Errorf("ValidateLine(%+v) = %v; want %v", tt.line, got, tt.want)
			}
		})
	}
}
