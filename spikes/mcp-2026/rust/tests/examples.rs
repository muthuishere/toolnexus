//! Runs every program under examples/ end to end and checks its exact output, so the docs (which
//! copy code from these programs) cannot rot. 01-08 are hermetic (127.0.0.1 only); 09 needs
//! KEYCLOAK=1 + ../golang/keycloak/docker-compose.yml, 10 needs GITHUB_OAUTH=1 + sec-injected creds.
use std::process::Command;

const HERMETIC: &[(&str, &str)] = &[
    (
        "01-connect-http",
        "supported versions: [2026-07-28]\ntools: echo, greet\necho: hello over HTTP",
    ),
    ("02-connect-stdio", "tool count: 2\necho: hello over stdio"),
    (
        "03-tool-input",
        "server asks (elicitation/create): What is your name?\nresult: Hello, Ada!",
    ),
    (
        "04-skills",
        "skills extension: true\nskill skill://hello-world/SKILL.md: hello-world\nfirst heading: # Hello World Skill\ndownloaded: hello-world/SKILL.md\ndownloaded: hello-world/scripts/greet.sh",
    ),
    (
        "05-tool-search",
        "eager: 100 tools, 9721 bytes of schema\nlazy:  2 tools, 519 bytes of schema\ntop hit: slack_create\nresult: slack_create ran with hi team",
    ),
    (
        "06-oauth-zero-config",
        "echo: hello after login\ngreet: Hello, Ada!\nbrowser logins: 2\ntoken stored for this issuer: true",
    ),
    (
        "07-oauth-web-pause-resume",
        "(a) web_handler: 200 resumed after consent\n(b) paused: authorization - true - has URL: true\n(b) resumed: resumed by hand",
    ),
    (
        "08-serve-protected",
        "no token: 401 | WWW-Authenticate has resource_metadata: true\nmetadata: issuer ok: true | scopes: [mcp:read]\nwhoami: hello dev-user (mcp:read)\ndelete_all after step-up: deleted\nwhoami: hello dev-user (mcp:read mcp:admin)\nverify hook: hello billing-service\nwrong key rejected: true",
    ),
];

/// Builds examples/<name> (cargo test already did; this is a no-op then) and runs it with extra env.
fn run_example(name: &str, env: &[(&str, &str)]) -> String {
    let dir = env!("CARGO_MANIFEST_DIR");
    let st = Command::new(env!("CARGO"))
        .args(["build", "-q", "--example", name])
        .current_dir(dir)
        .status()
        .unwrap();
    assert!(st.success(), "build {name}");
    let out = Command::new(format!("{dir}/target/debug/examples/{name}"))
        .envs(env.iter().copied())
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "run {name}: {}\nstdout:\n{}\nstderr:\n{}",
        out.status,
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
    String::from_utf8_lossy(&out.stdout).trim().to_string()
}

#[test]
fn examples_hermetic() {
    for (name, want) in HERMETIC {
        let got = run_example(name, &[]);
        assert_eq!(&got, want, "{name}: output drifted");
    }
}

#[test]
fn example_keycloak() {
    if std::env::var("KEYCLOAK").as_deref() != Ok("1") {
        eprintln!("skip: set KEYCLOAK=1 with ../golang/keycloak/docker-compose.yml running");
        return;
    }
    let got = run_example("09-keycloak", &[("HEADLESS_LOGIN", "1")]);
    println!("{got}");
    assert!(
        got.contains("whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true")
            && got.contains("greet: Hello, Alice!"),
        "{got}"
    );
}

#[test]
fn example_github() {
    if std::env::var("GITHUB_OAUTH").as_deref() != Ok("1") {
        eprintln!(
            "skip: set GITHUB_OAUTH=1 (run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET)"
        );
        return;
    }
    let got = run_example("10-github", &[("BROWSER", "chrome-agent")]);
    assert_eq!(got, "tools listed: true\nget_me: complete | isError: false");
}
