package shop

import (
	"testing"
)

func TestValidateLine_Valid(t *testing.T) {
	tests := []struct {
		name    string
		line    Line
		wantErr bool
		errMsg  string
	}{
		{
			name: "Typical valid line",
			line: Line{SKU: "ABC123", Price: 1999, Qty: 2},
		},
		{
			name: "SKU with spaces inside but trimmed valid",
			line: Line{SKU: "  ABC123  ", Price: 1000, Qty: 1},
		},
		{
			name: "Qty minimum 1",
			line: Line{SKU: "X1", Price: 50, Qty: 1},
		},
		{
			name: "Qty maximum 99",
			line: Line{SKU: "X2", Price: 50, Qty: 99},
		},
		{
			name: "Price zero allowed",
			line: Line{SKU: "FREE", Price: 0, Qty: 1},
		},
	}

	for _, tt := range tests {
		tt := tt
		t.Run(tt.name, func(t *testing.T) {
			err := ValidateLine(tt.line)
			if (err != nil) != tt.wantErr {
				t.Fatalf("ValidateLine() error = %v, wantErr %v", err, tt.wantErr)
			}
			if err != nil && tt.errMsg != "" && err.Error() != tt.errMsg {
				t.Errorf("ValidateLine() error message = %q, wanted %q", err.Error(), tt.errMsg)
			}
		})
	}
}
