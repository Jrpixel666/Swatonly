//! Process hardening, run once when the library loads.

use std::fs;

/// No core dumps, and no ptrace attach from other processes of the same user (debuggers,
/// memory scrapers). Root can still attach; nothing in userspace can stop that.
pub fn harden() {
  // SAFETY: plain syscalls with constant, valid arguments.
  unsafe {
    libc::prctl(libc::PR_SET_DUMPABLE, 0, 0, 0, 0);
    let none = libc::rlimit { rlim_cur: 0, rlim_max: 0 };
    libc::setrlimit(libc::RLIMIT_CORE, &none);
  }
}

/// Signs that someone is instrumenting this process.
pub fn report() -> serde_json::Value {
  let status = fs::read_to_string("/proc/self/status").unwrap_or_default();
  let tracer = status
    .lines()
    .find_map(|l| l.strip_prefix("TracerPid:"))
    .and_then(|v| v.trim().parse::<u32>().ok())
    .unwrap_or(0);
  let maps = fs::read_to_string("/proc/self/maps").unwrap_or_default().to_ascii_lowercase();
  let hooked = ["frida", "gadget", "xposed", "substrate", "lsposed", "edxp"]
    .iter()
    .any(|needle| maps.contains(needle));
  // SAFETY: prctl(PR_GET_DUMPABLE) takes no pointers.
  let dumpable = unsafe { libc::prctl(libc::PR_GET_DUMPABLE, 0, 0, 0, 0) };
  serde_json::json!({ "traced": tracer != 0, "hooked": hooked, "dumpable": dumpable != 0 })
}
