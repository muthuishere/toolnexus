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


if __name__ == "__main__":
    unittest.main()
