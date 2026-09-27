// Package shop is the tiny target testscout scouts. ApplyCoupon carries the
// checkout-500 bug: a coupon code with a trailing space fails to parse.
package shop

import (
	"errors"
	"fmt"
	"math"
	"strconv"
	"strings"
)

// Money is an amount in cents.
type Money int64

// Coupon is a percentage-off code such as "SAVE10".
type Coupon struct{ code string }

// Code returns the coupon code.
func (c Coupon) Code() string { return c.code }

// String formats cents as dollars, e.g. 1999 -> "$19.99".
func (m Money) String() string { return fmt.Sprintf("$%d.%02d", m/100, m%100) }

// percentOf is internal glue: the number after the SAVE prefix.
func percentOf(code string) (int, error) {
	return strconv.Atoi(strings.TrimPrefix(code, "SAVE")) // BUG: code is not trimmed
}

// ApplyCoupon returns total after the coupon. An empty code is no discount.
func ApplyCoupon(total Money, code string) (Money, error) {
	if code == "" {
		return total, nil
	}
	if !strings.HasPrefix(code, "SAVE") {
		return 0, errors.New("unknown coupon")
	}
	pct, err := percentOf(code)
	if err != nil {
		return 0, fmt.Errorf("bad coupon %q: %w", code, err)
	}
	if pct <= 0 || pct > 50 {
		return 0, errors.New("coupon out of range")
	}
	return total - RoundCents(float64(total)*float64(pct)/100), nil
}

// RoundCents rounds a fractional cent amount half away from zero.
func RoundCents(c float64) Money { return Money(math.Round(c)) }
