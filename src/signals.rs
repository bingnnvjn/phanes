//! SIGINT/SIGTERM handling: a shared stop flag the play/bench loops poll.

use std::sync::atomic::{AtomicBool, Ordering};

static STOP: AtomicBool = AtomicBool::new(false);

extern "C" fn on_signal(_signal: libc::c_int) {
    STOP.store(true, Ordering::SeqCst);
}

/// Install SIGINT/SIGTERM handlers that set the stop flag. Safe to call once.
pub fn install_signal_handlers() {
    unsafe {
        libc::signal(libc::SIGINT, on_signal as *const () as libc::sighandler_t);
        libc::signal(libc::SIGTERM, on_signal as *const () as libc::sighandler_t);
    }
}

pub fn stop_requested() -> bool {
    STOP.load(Ordering::SeqCst)
}
