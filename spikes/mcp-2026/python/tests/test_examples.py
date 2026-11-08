"""Runs every program under examples/ end to end and checks its exact output, so the docs (which copy
code from these programs) cannot rot. 01-08 are hermetic (127.0.0.1 only); 09 needs KEYCLOAK=1 +
keycloak/docker-compose.yml, 10 needs GITHUB_OAUTH=1 + sec-injected creds."""
import os
import subprocess
import sys

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

HERMETIC = {
    "01-connect-http": """supported versions: [2026-07-28]
tools: echo, greet
echo: hello over HTTP""",
    "02-connect-stdio": """tool count: 2
echo: hello over stdio""",
    "03-tool-input": """server asks (elicitation/create): What is your name?
result: Hello, Ada!""",
    "04-skills": """skills extension: true
skill skill://hello-world/SKILL.md: hello-world
first heading: # Hello World Skill
downloaded: hello-world/SKILL.md
downloaded: hello-world/scripts/greet.sh""",
    "05-tool-search": """eager: 100 tools, 9721 bytes of schema
lazy:  2 tools, 519 bytes of schema
top hit: slack_create
result: slack_create ran with hi team""",
    "06-oauth-zero-config": """echo: hello after login
greet: Hello, Ada!
browser logins: 2
token stored for this issuer: true""",
    "07-oauth-web-pause-resume": """(a) web_handler: 200 resumed after consent
(b) paused: authorization - true - has URL: true
(b) resumed: resumed by hand""",
    "08-serve-protected": """no token: 401 | WWW-Authenticate has resource_metadata: true
metadata: issuer ok: true | scopes: [mcp:read]
whoami: hello dev-user (mcp:read)
delete_all after step-up: deleted
whoami: hello dev-user (mcp:read mcp:admin)
verify hook: hello billing-service
wrong key rejected: true""",
}


def run_example(name: str, timeout: float, **env) -> str:
    p = subprocess.run([sys.executable, os.path.join(ROOT, "examples", name, "main.py")], capture_output=True,
                       text=True, timeout=timeout, env={**os.environ, **env})
    assert p.returncode == 0, f"{name}: exit {p.returncode}\nstdout:\n{p.stdout}\nstderr:\n{p.stderr}"
    return p.stdout.strip()


@pytest.mark.parametrize("name", sorted(HERMETIC))
def test_example_hermetic(name):
    assert run_example(name, 60) == HERMETIC[name]


@pytest.mark.skipif(os.environ.get("KEYCLOAK") != "1", reason="set KEYCLOAK=1 with keycloak/docker-compose.yml running")
def test_example_keycloak():
    got = run_example("09-keycloak", 90, HEADLESS_LOGIN="1")
    print(got)
    assert "whoami: client=toolnexus-mcp scopes=mcp:tools subject-set=true" in got and "greet: Hello, Alice!" in got


@pytest.mark.skipif(os.environ.get("GITHUB_OAUTH") != "1",
                    reason="set GITHUB_OAUTH=1 (run via sec run GITHUB_MCP_CLIENT_ID,GITHUB_MCP_CLIENT_SECRET)")
def test_example_github():
    got = run_example("10-github", 180, BROWSER="chrome-agent")
    print(got)
    assert got == "tools listed: true\nget_me: complete | isError: false"
