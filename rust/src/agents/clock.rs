//! The runtime's injectable time source (SPEC §7D "Lifecycle & runtime
//! obligations"): every timer the runtime owns — wait timeouts, the graceful
//! close bound, `max_wall` budget checks, the persona heartbeat — reads this,
//! never the wall clock directly. Fixtures run on a virtual clock.

use std::time::{Duration, Instant};

use crate::client::BoxFuture;

/// A time source: `now`, and a future that completes once `d` has elapsed.
pub trait Clock: Send + Sync {
    fn now(&self) -> Instant;
    fn after(&self, d: Duration) -> BoxFuture<()>;
}

/// The default real clock (tokio timers).
#[derive(Debug, Clone, Copy, Default)]
pub struct SystemClock;

impl Clock for SystemClock {
    fn now(&self) -> Instant {
        Instant::now()
    }
    fn after(&self, d: Duration) -> BoxFuture<()> {
        Box::pin(tokio::time::sleep(d))
    }
}
