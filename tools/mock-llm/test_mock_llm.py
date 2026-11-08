"""Self-test for the mock model server: pins each reply shape to the shared fixtures
under examples/, so the mock cannot drift from the wires the ports speak.

    python3 -m unittest tools/mock-llm/test_mock_llm.py
"""
import json
import sys
import unittest
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))
import mock_llm  # noqa: E402


def post(base, path, body, headers=None):
    req = urllib.request.Request(base + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


class MockTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv, cls.base = mock_llm.start(port=0)

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def test_systemone_matches_fixture_shape(self):
        fx = json.loads((ROOT / "examples/judge/base.json").read_text())
        _, raw = post(self.base, "/systemone", fx["request"])
        got = json.loads(raw)
        want = fx["response"]
        self.assertEqual(set(got), set(want))
        for k, a in want["answers"].items():
            g = got["answers"][k]
            self.assertEqual(set(g), set(a), k)
            if "probabilities" in g:
                self.assertEqual(set(g["probabilities"]), set(a["probabilities"]), k)
                self.assertAlmostEqual(sum(g["probabilities"].values()), 1.0)

    def test_decisions_matches_fixture_reply_shape(self):
        # the openai-decisions fixture is on the #157 branch; the reply shape it pins:
        want_keys = {"predicate": {"type", "name", "probability"},
                     "choice": {"type", "name", "choice", "confidence", "probabilities"},
                     "score": {"type", "name", "score", "confidence", "probabilities"}}
        body = {"input": "The package arrived with a broken screen.", "model": "gpt-6-luna", "questions": [
            {"instructions": "Damaged?", "type": "predicate"},
            {"choices": [{"description": "refunds", "value": "billing"}, {"value": "other"},
                         {"description": "parcels", "value": "shipping"}], "instructions": "Team?", "type": "choice"},
            {"instructions": "Upset?", "levels": [{"label": "calm"}, {"label": "annoyed"}, {"label": "furious"}],
             "type": "score"}]}
        got = json.loads(post(self.base, "/decisions", body)[1])
        self.assertEqual(got["model"], "gpt-6-luna")
        for a in got["answers"]:
            self.assertEqual(set(a), want_keys[a["type"]])
        self.assertEqual([p["value"] for p in got["answers"][1]["probabilities"]], ["billing", "other", "shipping"])
        self.assertEqual([p["label"] for p in got["answers"][2]["probabilities"]], ["calm", "annoyed", "furious"])
        # same state + instructions => same pick as the systemone wire
        s1 = json.loads(post(self.base, "/systemone", {"state": body["input"], "questions": {"t": {
            "type": "choice", "instructions": "Team?", "criteria": {"billing": "", "other": "", "shipping": ""}}}})[1])
        self.assertEqual(s1["answers"]["t"]["choice"], got["answers"][1]["choice"])

    def test_media_style_declares_every_canonical_verb(self):
        verbs = [v["verb"] for v in json.loads((ROOT / "conformance/media/verbs.json").read_text())["verbs"]]
        style = mock_llm.media_style(self.base)
        self.assertEqual(sorted(style["verbs"]), sorted(verbs))
        for v in json.loads((ROOT / "conformance/media/verbs.json").read_text())["verbs"]:
            self.assertEqual(set(style["verbs"][v["verb"]]["args"]), {a["name"] for a in v["args"]}, v["verb"])

    def test_image_fixture_is_the_shared_png(self):
        self.assertEqual(mock_llm.PNG, (ROOT / "examples/media/fixture.png").read_bytes())

    def test_fault_header_and_retry_after(self):
        code, _ = post(self.base, "/chat/completions", {}, {"X-Mock-Fault": "503"})
        self.assertEqual(code, 503)

    def test_a2a_agent_submit_then_poll(self):
        origin = self.base[:-3]
        with urllib.request.urlopen(origin + "/.well-known/agent-card.json") as r:
            card = json.loads(r.read())
        self.assertEqual(card["url"], origin + "/a2a")
        t = json.loads(post(origin, "/a2a", {"jsonrpc": "2.0", "id": 1, "method": "SendMessage", "params": {
            "message": {"role": "user", "messageId": "m", "parts": [{"kind": "text", "text": "hi"}]}}})[1])["result"]
        states = [json.loads(post(origin, "/a2a", {"jsonrpc": "2.0", "id": 2, "method": "GetTask",
                                                  "params": {"id": t["id"]}})[1])["result"] for _ in range(2)]
        self.assertEqual([s["status"]["state"] for s in states], ["working", "completed"])
        self.assertEqual(states[1]["artifacts"][0]["parts"][0]["text"], "A2A mock done: hi")

    def test_http_tool_endpoints(self):
        self.assertEqual(json.loads(post(self.base, "/tools/add", {"a": 2, "b": 3})[1]), {"sum": 5})

    def _stdio(self, args, lines):
        import subprocess
        p = subprocess.run([sys.executable, str(HERE / "mock_llm.py"), *args], text=True, capture_output=True,
                           input="".join(json.dumps(m) + "\n" for m in lines), timeout=20)
        return [json.loads(x) for x in p.stdout.splitlines() if x.strip()]

    def test_mcp_stdio_both_paths(self):
        meta = {"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}}
        out = self._stdio(["--mcp"], [{"jsonrpc": "2.0", "id": 1, "method": "server/discover"},
                                      {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                       "params": {"name": "add", "arguments": {"a": 2, "b": 3}, **meta}}])
        self.assertIn("2026-07-28", out[0]["result"]["supportedVersions"])
        self.assertEqual(out[1]["result"]["content"][0]["text"], "5")
        self.assertEqual(out[1]["result"]["resultType"], "complete")
        old = self._stdio(["--mcp", "--legacy"], [{"jsonrpc": "2.0", "id": 1, "method": "server/discover"},
                                                  {"jsonrpc": "2.0", "id": 2, "method": "initialize", "params": {}}])
        self.assertEqual(old[0]["error"]["code"], -32601)
        self.assertEqual(old[1]["result"]["serverInfo"]["name"], "mock-mcp")

    def test_mcp_http(self):
        import mock_mcp
        srv, url = mock_mcp.start_http()
        try:
            origin, path = url.rsplit("/", 1)
            r = json.loads(post(origin, "/" + path, {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})[1])
            self.assertEqual([t["name"] for t in r["result"]["tools"]], ["add", "echo"])
        finally:
            srv.shutdown()

    def test_cli_and_acp(self):
        import subprocess
        cli = lambda inp: json.loads(subprocess.run([sys.executable, str(HERE / "mock_llm.py"), "--cli"], text=True,
                                                    input=inp, capture_output=True, timeout=20).stdout)
        self.assertEqual(cli("add please")["tool_calls"][0]["arguments"], {"a": 19, "b": 23})
        self.assertEqual(cli("[tool add] 42\n"), {"content": "The sum is 42."})
        out = self._stdio(["--acp"], [
            {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
            {"jsonrpc": "2.0", "id": 2, "method": "session/new", "params": {"cwd": "/", "mcpServers": []}},
            {"jsonrpc": "2.0", "id": 3, "method": "session/prompt",
             "params": {"sessionId": "fake-1", "prompt": [{"type": "text", "text": "hello"}]}},
            {"jsonrpc": "2.0", "id": 4, "method": "session/prompt",
             "params": {"sessionId": "fake-1", "prompt": [{"type": "text", "text": "[wait]"}]}},
            {"jsonrpc": "2.0", "method": "session/cancel", "params": {"sessionId": "fake-1"}}])
        text = "".join(m["params"]["update"]["content"]["text"] for m in out
                       if m.get("method") == "session/update")
        self.assertEqual(text, "Mock ACP reply to: helloWorking...")
        stops = {m["id"]: m["result"]["stopReason"] for m in out if m.get("id") in (3, 4)}
        self.assertEqual(stops, {3: "end_turn", 4: "cancelled"})

    def test_fake_media_cli_grok_and_codex(self):
        import subprocess
        import tempfile
        d = tempfile.mkdtemp()
        run = lambda *a, env=None: subprocess.run([sys.executable, str(HERE / "fake_media_cli.py"), *a],
                                                  capture_output=True, text=True, timeout=20, env=env)
        g = run("--as", "grok", "-p", "Generate exactly one short video: x", "--cwd", d,
                "--output-format", "streaming-json", "--yolo", "--no-auto-update")
        self.assertEqual(g.returncode, 0)
        ev = [json.loads(x) for x in g.stdout.splitlines() if x.startswith("{")]
        self.assertEqual([e["type"] for e in ev][-1], "end")
        self.assertEqual(set(ev[-1]) >= {"stopReason", "sessionId", "usage", "total_cost_usd", "total_cost_usd_ticks"}, True)
        paths = [e["rawOutput"]["path"] for e in ev if e["type"] == "tool_call_update"]
        self.assertEqual(open(paths[0], "rb").read(), mock_llm.PNG)
        self.assertTrue(paths[1].endswith("/videos/1.mp4") and Path(paths[1]).stat().st_size > 0)
        f = run("--as", "grok", "-p", "[fail]", "--cwd", d)
        self.assertEqual((f.returncode, json.loads(f.stdout.splitlines()[-1])["type"]), (1, "error"))
        import os
        c = run("--as", "codex", "exec", "--json", "--skip-git-repo-check", "-s", "workspace-write", "draw",
                env=dict(os.environ, CODEX_HOME=d + "/ch"))
        ev = [json.loads(x) for x in c.stdout.splitlines()]
        self.assertEqual([e["type"] for e in ev], ["thread.started", "turn.started", "item.completed", "turn.completed"])
        self.assertEqual(open(ev[2]["item"]["text"], "rb").read(), mock_llm.PNG)
        cf = run("--as", "codex", "exec", "--json", "[fail]")
        self.assertEqual((cf.returncode, json.loads(cf.stdout.splitlines()[-1])["type"]), (1, "turn.failed"))


if __name__ == "__main__":
    unittest.main()
