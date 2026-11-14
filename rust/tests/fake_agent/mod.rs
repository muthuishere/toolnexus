//! Locates the scripted fake ACP agent (examples/fake_acp_agent.rs) for the ACP tests.
//!
//! Cargo used to build examples next to the test binary (`target/<profile>/examples`). Newer
//! cargo (1.100, the build-dir layout) moves test binaries under `target/<profile>/build/...`,
//! so that guess no longer holds. Try it first, then fall back to building the example once.

use std::path::PathBuf;
use std::sync::OnceLock;

fn exe_name() -> &'static str {
    if cfg!(windows) {
        "fake_acp_agent.exe"
    } else {
        "fake_acp_agent"
    }
}

pub fn fake() -> PathBuf {
    static PATH: OnceLock<PathBuf> = OnceLock::new();
    PATH.get_or_init(|| {
        let mut p = std::env::current_exe().unwrap();
        p.pop();
        if p.ends_with("deps") {
            p.pop();
        }
        let beside = p.join("examples").join(exe_name());
        if beside.exists() {
            return beside;
        }
        let manifest = env!("CARGO_MANIFEST_DIR");
        let target = PathBuf::from(manifest)
            .join("target")
            .join("fake-acp-agent");
        let status = std::process::Command::new(env!("CARGO"))
            .args([
                "build",
                "--quiet",
                "--example",
                "fake_acp_agent",
                "--manifest-path",
            ])
            .arg(PathBuf::from(manifest).join("Cargo.toml"))
            .arg("--target-dir")
            .arg(&target)
            .status()
            .expect("run cargo build for the fake agent");
        assert!(status.success(), "building the fake agent failed");
        let built = target.join("debug").join("examples").join(exe_name());
        assert!(built.exists(), "fake agent not built at {built:?}");
        built
    })
    .clone()
}
