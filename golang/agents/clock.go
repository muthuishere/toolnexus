package agents

import "time"

// Clock is the runtime's injectable time source (SPEC §7D "Lifecycle & runtime
// obligations"): every timer, timeout, and deadline the runtime owns — Wait
// timeouts, the graceful-close Shutdown bound, MaxWall budget checks — reads
// this interface, never the wall clock directly. Fixtures run on a virtual
// clock so timing behavior is deterministic and byte-comparable across ports;
// production uses the default real clock.
type Clock interface {
	// Now returns the current instant.
	Now() time.Time
	// After returns a channel that fires once d has elapsed.
	After(d time.Duration) <-chan time.Time
}

// systemClock is the default real Clock.
type systemClock struct{}

func (systemClock) Now() time.Time                         { return time.Now() }
func (systemClock) After(d time.Duration) <-chan time.Time { return time.After(d) }
