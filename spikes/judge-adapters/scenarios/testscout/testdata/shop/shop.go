// Package shop is the tiny target testscout scouts. ApplyCoupon carries the
// checkout-500 bug: a pasted coupon code with a trailing space fails to parse.
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

// Line is one cart line: a unit price and a quantity.
type Line struct {
	SKU   string
	Price Money
	Qty   int
}

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

// Total sums price x quantity over every line of the cart.
func Total(lines []Line) Money {
	var sum Money
	for _, l := range lines {
		sum += l.Price * Money(l.Qty)
	}
	return sum
}

// ValidateLine returns the error message shown to the shopper, or nil.
func ValidateLine(l Line) error {
	if strings.TrimSpace(l.SKU) == "" {
		return errors.New("please pick a product")
	}
	if l.Qty < 1 || l.Qty > 99 {
		return fmt.Errorf("quantity must be between 1 and 99, got %d", l.Qty)
	}
	if l.Price < 0 {
		return errors.New("price cannot be negative")
	}
	return nil
}
