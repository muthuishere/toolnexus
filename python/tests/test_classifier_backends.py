"""add-classifier-backends (SPEC §8B "Backends, images, cache and validation").

Every fixture under examples/judge/backends/ is exercised here. Hermetic: a fake
System One endpoint on 127.0.0.1, no network, no real key.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable

import pytest

from toolnexus import (
    CLASSIFIER_BACKENDS,
    DEFAULT_THRESHOLDS,
    Bands,
    ChoiceQuestion,
    ClassifierError,
    ClassifierUnsupported,
    MemoryCache,
    NoulCriteria,
    NoulQuestion,
    RawAnswer,
    RawQuestion,
    ScoreQuestion,
    Thresholds,
    chunk_questions,
    classifier_decision_cache_key,
    classifier_model_cache_key,
    create_classifier,
    noul,
    raw,
    validate_decision,
)
from toolnexus.classifier import _decision_from_wire, classifier_key_env
from toolnexus.content import ContentPart, text

FIX = Path(__file__).resolve().parents[2] / "examples" / "judge"
NOUL_REPLY = '{"model":"jev-1.14.0","answers":{"q":{"type":"noul","noul":0.91}},"usage":{"input_tokens":3,"output_tokens":1}}'


def load(name: str) -> Any:
    return json.loads((FIX / "backends" / name).read_text())


def fixture_questions(m: dict[str, dict[str, Any]]) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for k, w in m.items():
        ins = w.get("instructions", "")
        t = w["type"]
        if t == "noul":
            c = w.get("criteria")
            out[k] = NoulQuestion(ins, NoulCriteria(c["true"], c["false"]) if c else None)
        elif t == "choice":
            out[k] = ChoiceQuestion(ins, dict(w["criteria"]))
        elif t == "score":
            out[k] = ScoreQuestion(ins, list(w["criteria"]))
        else:
            out[k] = RawQuestion(t, ins, w.get("criteria"))
    return out


def fixture_state(v: Any) -> Any:
    if isinstance(v, dict):
        if isinstance(v.get("$image"), dict):
            im = v["$image"]
            return ContentPart(type="image", mimeType=im["mimeType"], data=im["data"])
        if isinstance(v.get("$text"), str):
            return text(v["$text"])
        return {k: fixture_state(e) for k, e in v.items()}
    if isinstance(v, list):
        return [fixture_state(e) for e in v]
    return v


class FakeSystemOne:
    def __init__(self, reply: Callable[[int, str], tuple[int, str]]) -> None:
        self.reply = reply
        self.paths: list[str] = []
        self.bodies: list[str] = []
        self.headers: list[dict[str, str]] = []
        self.lock = threading.Lock()
        fake = self

        class H(BaseHTTPRequestHandler):
            def do_POST(self) -> None:  # noqa: N802
                body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
                with fake.lock:
                    fake.paths.append(self.path)
                    fake.bodies.append(body)
                    fake.headers.append({k.lower(): v for k, v in self.headers.items()})
                    n = len(fake.bodies)
                st, out = fake.reply(n, body)
                data = out.encode()
                self.send_response(st)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *a: Any) -> None:
                pass

        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = f"http://127.0.0.1:{self.srv.server_address[1]}"
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def close(self) -> None:
        self.srv.shutdown()
        self.srv.server_close()


@pytest.fixture
def fake():
    made: list[FakeSystemOne] = []

    def make(reply: Callable[[int, str], tuple[int, str]]) -> FakeSystemOne:
        f = FakeSystemOne(reply)
        made.append(f)
        return f

    yield make
    for f in made:
        f.close()


def no_retry(_info: Any) -> str:
    return "fail"


# --------------------------------------------------------------------------- presets


def test_backend_presets_fixture() -> None:
    fx = load("presets.json")
    assert set(CLASSIFIER_BACKENDS) == set(fx["backends"])
    for name, want in fx["backends"].items():
        p = CLASSIFIER_BACKENDS[name]
        assert p.base_url == (want["baseUrl"] or "")
        assert (p.model, p.api_key_env, p.wire, p.images_header) == (
            want["model"], want["apiKeyEnv"], want["wire"], want["imagesHeader"]
        )
        for m, img in (want["supportsImages"] or {}).items():
            assert p.supports is not None and p.supports(m).images is img, (name, m)
        if want["supportsImages"] is None:
            assert p.supports is None
    with pytest.raises(ClassifierError) as e:
        create_classifier(backend="nope")  # type: ignore[arg-type]
    assert str(e.value) == fx["unknownBackendError"]
    with pytest.raises(ClassifierError) as e:
        create_classifier(backend="custom", base_url="http://x")
    assert str(e.value) == fx["customError"]
    with pytest.raises(ClassifierError) as e:
        create_classifier(backend="custom", supports={"images": True})
    assert str(e.value) == fx["customError"]
    create_classifier(backend="custom", base_url="http://x", supports={"images": False})
    # The custom check is systemone-only: other styles never touch the wire.
    create_classifier(backend="custom", style="custom", evaluate=lambda s, q: None)
    # openrouter default moved to 1.14 (PD-4).
    assert create_classifier(backend="openrouter").model == "typesafe/jev-1.14"


# --------------------------------------------------------------------------- images


@pytest.mark.parametrize("case", load("images.json")["cases"], ids=lambda c: c["name"])
async def test_images_fixture(case: dict[str, Any], fake, monkeypatch) -> None:
    fx = load("images.json")
    qs = fixture_questions(fx["questions"])
    cf = case["backend"] == "cloudflare"
    f = fake(lambda n, b: (200, '{"success":true,"result":' + NOUL_REPLY + "}" if cf else NOUL_REPLY))
    exp = case["expect"]
    base = f.url + "/v1"
    if cf:
        base = f.url + "/accounts/${TN_TEST_ACCOUNT}/ai/run"
        monkeypatch.setenv("TN_TEST_ACCOUNT", "acct123")
        if exp.get("url"):
            assert exp["url"] == CLASSIFIER_BACKENDS["cloudflare"].base_url
    c = create_classifier(
        backend=case["backend"], base_url=base, model=case.get("model"), supports=case.get("supports"),
        on_error=no_retry,
    )
    st = fixture_state(case["state"])
    if exp.get("unsupported"):
        with pytest.raises(ClassifierUnsupported) as e:
            await c.evaluate(st, qs)
        assert str(e.value) == exp["error"]
        assert e.value.feature == "images" and e.value.backend == case["backend"]
    elif exp.get("errorPrefix"):
        with pytest.raises(ClassifierError) as e:
            await c.evaluate(st, qs)
        assert str(e.value).startswith(exp["errorPrefix"])
        assert not isinstance(e.value, ClassifierUnsupported)
    else:
        await c.evaluate(st, qs)
        assert f.bodies[0] == exp["body"]
        assert (f.headers[0].get("typesafe-danger-zone-images") == "true") is exp["imagesHeader"]
        if cf:
            assert f.paths[0] == "/accounts/acct123/ai/run"
        else:
            assert f.paths[0] == "/v1/systemone"
        return
    assert f.bodies == []


async def test_other_styles_get_parts_verbatim() -> None:
    seen: list[Any] = []

    def ev(st: Any, qs: Any):
        seen.append(st)
        return _decision_from_wire({"answers": {"q": {"type": "noul", "noul": 0.5}}})

    part = ContentPart(type="image", mimeType="image/gif", data="AAAA")
    c = create_classifier(style="custom", backend="vercel", evaluate=ev)
    await c.evaluate({"p": part}, {"q": NoulQuestion("x")})
    assert seen[0]["p"] is part


async def test_url_image_and_audio_part_fail_before_request(fake) -> None:
    f = fake(lambda n, b: (200, NOUL_REPLY))
    c = create_classifier(base_url=f.url)
    with pytest.raises(ClassifierError, match="inline data"):
        await c.evaluate({"p": ContentPart(type="image", mimeType="image/png", url="https://x/y.png")}, {"q": NoulQuestion("x")})
    with pytest.raises(ClassifierError, match="audio parts are not supported"):
        await c.evaluate([ContentPart(type="audio", mimeType="audio/wav", data="AAAA")], {"q": NoulQuestion("x")})
    assert f.bodies == []


def test_request_image_total_limit() -> None:
    from toolnexus.classifier import _check_images, _LiftedImage
    import base64

    png = bytearray(b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR" + (300).to_bytes(4, "big") * 2)
    png += bytes(12 * 1024 * 1024)
    d = base64.b64encode(bytes(png)).decode()
    imgs = [_LiftedImage("a", "image/png", d), _LiftedImage("b", "image/png", d), _LiftedImage("c", "image/png", d)]
    with pytest.raises(ClassifierError, match='^classifier: image "c": request images total'):
        _check_images(imgs)


# --------------------------------------------------------------------------- cloudflare


@pytest.mark.parametrize("case", load("cloudflare-replies.json")["cases"], ids=lambda c: c["name"])
async def test_cloudflare_replies_fixture(case: dict[str, Any], fake) -> None:
    qs = fixture_questions(load("cloudflare-replies.json")["questions"])
    f = fake(lambda n, b: (200, json.dumps(case["reply"])))
    c = create_classifier(backend="cloudflare", base_url=f.url)
    exp = case["expect"]
    if "error" in exp:
        with pytest.raises(ClassifierError) as e:
            await c.evaluate("x", qs)
        assert str(e.value) == exp["error"]
        return
    d = await c.evaluate("x", qs)
    assert d.model == exp["model"] and d.noul("q").noul == exp["noul"]
    assert f.paths[0] == "/"


async def test_cloudflare_error_keeps_account_template(fake, monkeypatch) -> None:
    f = fake(lambda n, b: (500, "boom"))
    monkeypatch.setenv("TN_TEST_ACCOUNT", "secretacct")
    c = create_classifier(backend="cloudflare", base_url=f.url + "/accounts/${TN_TEST_ACCOUNT}/ai/run", on_error=no_retry)
    with pytest.raises(ClassifierError) as e:
        await c.evaluate("x", {"q": NoulQuestion("y")})
    assert "secretacct" not in str(e.value) and "${TN_TEST_ACCOUNT}" in str(e.value)
    assert f.paths[0] == "/accounts/secretacct/ai/run"


# --------------------------------------------------------------------------- key routing (#155)


@pytest.mark.parametrize("case", load("key-routing.json")["captureCases"], ids=lambda c: c["name"])
async def test_key_routing_capture_fixture(case: dict[str, Any], fake, monkeypatch) -> None:
    for k, v in case.get("env", {}).items():
        monkeypatch.setenv(k, v)
    qs = fixture_questions(load("key-routing.json")["questions"])

    def reply(n: int, b: str) -> tuple[int, str]:
        if case["backend"] == "cloudflare":
            return 200, '{"success":true,"result":' + NOUL_REPLY + "}"
        return 200, NOUL_REPLY

    f = fake(reply)
    kw: dict[str, Any] = {}
    if case["backend"] == "custom":
        kw["supports"] = {"images": False}
    c = create_classifier(
        backend=case["backend"], base_url=f.url + "/v1", api_key_env=case.get("apiKeyEnv"),
        headers=case.get("headers"), retries=0, **kw,
    )
    await c.evaluate("x", qs)
    assert f.headers
    want = case["expectAuthorization"]
    for h in f.headers:
        assert h.get("authorization") == want, case["name"]


def test_key_routing_host_fixture() -> None:
    for c in load("key-routing.json")["hostCases"]:
        assert classifier_key_env(c["backend"], c["baseUrl"]) == c["expectKeyEnv"], c


# --------------------------------------------------------------------------- cache


def test_cache_keys_fixture() -> None:
    for c in load("cache-keys.json")["cases"]:
        qs = fixture_questions(c["questions"])
        assert classifier_model_cache_key(c["endpoint"], c["model"]) == c["modelKey"], c["name"]
        assert (
            classifier_decision_cache_key(c["endpoint"], c["served"], c["state"], c.get("assets"), qs)
            == c["decisionKey"]
        ), c["name"]
        assert hashlib.sha256(c["decisionKeyBytes"].encode()).hexdigest() == c["decisionKey"]


async def test_state_builder_and_cache(fake) -> None:
    f = fake(lambda n, b: (200, NOUL_REPLY))
    events: list[dict[str, Any]] = []
    c = create_classifier(base_url=f.url, cache=MemoryCache(3600, 100), on_metric=events.append)
    q = noul("q", "Is the object red?")
    d1 = await c.state("a red mug").evaluate(q)
    assert not d1.cached
    d2 = await c.state("a red mug").evaluate(q)
    assert d2.cached and len(f.bodies) == 1
    assert events[1]["cached"] is True and events[1]["questions"] == 1 and events[1]["questions_hash"]
    await c.state("a blue mug").evaluate(q)
    assert len(f.bodies) == 2
    with pytest.raises(ClassifierError, match="duplicate"):
        await c.state("x").evaluate(q, q)


async def test_async_cache_is_awaited(fake) -> None:
    f = fake(lambda n, b: (200, NOUL_REPLY))

    class AsyncCache:
        def __init__(self) -> None:
            self.m: dict[str, str] = {}

        async def get(self, k: str):
            return self.m.get(k)

        async def set(self, k: str, v: str) -> None:
            self.m[k] = v

    cache = AsyncCache()
    c = create_classifier(base_url=f.url, cache=cache)
    await c.state("s").evaluate(noul("q", "x"))
    d = await c.state("s").evaluate(noul("q", "x"))
    assert d.cached and len(f.bodies) == 1 and len(cache.m) == 2


async def test_invalid_replies_are_not_cached(fake) -> None:
    bad = '{"model":"m","answers":{"q":{"type":"noul","noul":1.5}}}'
    f = fake(lambda n, b: (200, bad))
    c = create_classifier(base_url=f.url, cache=MemoryCache())
    for _ in range(2):  # plain evaluate: returned as before, never cached
        await c.evaluate("s", {"q": NoulQuestion("x")})
    assert len(f.bodies) == 2
    with pytest.raises(ClassifierError) as e:
        await c.state("s").evaluate(noul("q", "x"))
    assert str(e.value).startswith('classifier: invalid decision: answer "q"')


async def test_cache_follows_served_model(fake) -> None:
    served = {"m": "jev-1.13.0"}
    f = fake(lambda n, b: (200, '{"model":"%s","answers":{"q":{"type":"noul","noul":0.5}}}' % served["m"]))
    cache = MemoryCache()
    c = create_classifier(base_url=f.url, cache=cache)
    await c.state("s").evaluate(noul("q", "x"))
    assert cache.get(classifier_model_cache_key(f.url, "jev-latest")) == "jev-1.13.0"
    served["m"] = "jev-1.14.0"
    await c.state("other").evaluate(noul("q", "x"))
    d = await c.state("s").evaluate(noul("q", "x"))
    assert not d.cached and len(f.bodies) == 3 and d.model == "jev-1.14.0"


def test_memory_cache_ttl_and_max() -> None:
    m = MemoryCache(ttl=10, max=2)
    now = [0.0]
    m._now = lambda: now[0]
    m.set("a", "1")
    m.set("b", "2")
    m.set("c", "3")
    assert m.get("a") is None and m.get("b") == "2" and m.get("c") == "3"
    now[0] = 11
    assert m.get("b") is None


# --------------------------------------------------------------------------- batch


async def test_states_batch_per_item(fake) -> None:
    f = fake(lambda n, b: (400, '{"error":"nope"}') if '"state":"bad"' in b else (200, NOUL_REPLY))
    c = create_classifier(base_url=f.url)
    rs = await c.states("a", "bad", "c").evaluate(noul("q", "Is the object red?"))
    assert len(rs) == 3
    assert rs[0].error is None and rs[2].error is None and rs[1].error is not None
    assert rs[0].decision is not None and rs[0].decision.model == "jev-1.14.0" and rs[1].decision is None
    assert await c.states().evaluate(noul("q", "x")) == []
    with pytest.raises(ClassifierError, match="duplicate"):
        await c.states("a").evaluate(noul("q", "x"), noul("q", "y"))


async def test_states_batch_bounded_to_16() -> None:
    live = {"n": 0, "max": 0}

    async def ev(st: Any, qs: Any):
        live["n"] += 1
        live["max"] = max(live["max"], live["n"])
        await asyncio.sleep(0.01)
        live["n"] -= 1
        return _decision_from_wire({"answers": {"q": {"type": "noul", "noul": 0.5}}})

    c = create_classifier(style="custom", evaluate=ev)
    rs = await c.states(*range(40)).evaluate(noul("q", "x"))
    assert len(rs) == 40 and all(r.decision for r in rs) and live["max"] == 16


# --------------------------------------------------------------------------- V1 / T1 / C1


def test_validate_cases_fixture() -> None:
    for c in load("validate-cases.json")["cases"]:
        d = _decision_from_wire(c["response"])
        err = validate_decision(fixture_questions(c["questions"]), d)
        assert (err is None) is c["valid"], (c["name"], err)
        if err is not None:
            assert str(err).startswith("classifier: invalid decision: "), c["name"]
            assert f'"{c["key"]}"' in str(err), (c["name"], err)


def _bands_band(b: Bands, a: Any) -> str:
    from toolnexus.judge import _read

    r = _read(a, b)
    if r.band is not None:
        return r.band
    return "yes" if r.sure else "uncertain"


def test_thresholds_fixture() -> None:
    fx = load("thresholds-cases.json")
    dflt = fx["default"]
    assert DEFAULT_THRESHOLDS == Thresholds(dflt["yes"], dflt["no"], dflt["minConfidence"])
    for c in fx["cases"]:
        a = _decision_from_wire({"answers": {"a": c["answer"]}}).answers["a"]
        th = DEFAULT_THRESHOLDS
        if c.get("thresholds"):
            t = c["thresholds"]
            th = Thresholds(t["yes"], t["no"], t["minConfidence"])
        assert th.band(a) == c["band"], c["name"]
        assert _bands_band(Bands(), a) == c["bandsBand"], c["name"]


def test_chunk_questions() -> None:
    qs = {"c": NoulQuestion("c"), "a": NoulQuestion("a"), "b": NoulQuestion("b")}
    ch = chunk_questions(qs, 2)
    assert [list(x) for x in ch] == [["a", "b"], ["c"]]
    with pytest.raises(ClassifierError):
        chunk_questions(qs, 0)


# --------------------------------------------------------------------------- raw + metric


async def test_raw_question_and_answer(fake) -> None:
    f = fake(lambda n, b: (200, '{"model":"jev-1.14.0","answers":{"spot":{"type":"box","box":[1,2,3,4]}}}'))
    c = create_classifier(base_url=f.url)
    d = await c.state("s").evaluate(raw("spot", "box", "Where is the mug?"))
    assert f.bodies[0] == '{"model":"jev-latest","questions":{"spot":{"instructions":"Where is the mug?","type":"box"}},"state":"s"}'
    ra = d.raw("spot")
    assert isinstance(ra, RawAnswer) and ra.type == "box" and ra.value["box"] == [1, 2, 3, 4]
    with pytest.raises(ClassifierError, match="needs a type"):
        await c.evaluate("s", {"x": RawQuestion("", "y")})


async def test_metric_hash_matches_fixture() -> None:
    fx = json.loads((FIX / "base.json").read_text())
    events: list[dict[str, Any]] = []
    c = create_classifier(
        style="custom", model=fx["request"]["model"], on_metric=events.append,
        evaluate=lambda s, q: _decision_from_wire({"model": "m", "answers": {}}),
    )
    await c.evaluate(fx["request"]["state"], fixture_questions(fx["request"]["questions"]))
    ev = events[-1]
    assert ev["questions_hash"] == fx["canonicalSha256"] and ev["questions"] == 3 and ev["cached"] is False
    with pytest.raises(ClassifierError):
        await c.evaluate("s", {"x": RawQuestion("", "y")})
    assert events[-1]["status"] == "error" and events[-1]["questions"] == 1 and events[-1]["questions_hash"]


# --------------------------------------------------------------------------- openai decisions (#157)

_DEC = load("openai-decisions.json")


def _dec_ok_reply() -> str:
    return json.dumps(_DEC["replyCases"][0]["reply"])


@pytest.mark.parametrize("case", _DEC["requestCases"], ids=lambda c: c["name"])
async def test_openai_decisions_request_fixture(case: dict[str, Any], fake) -> None:
    f = fake(lambda n, b: (200, _dec_ok_reply()))
    kw: dict[str, Any] = {"model": case["model"]} if case.get("model") else {}
    c = create_classifier(backend="openai", base_url=f.url + "/v1", on_error=no_retry, **kw)
    await c.evaluate(fixture_state(case["state"]), fixture_questions(_DEC["questions"]))
    assert f.bodies[0] == case["expect"]["body"]
    assert f.paths[0] == "/v1/decisions"
    assert "typesafe-danger-zone-images" not in f.headers[0]


@pytest.mark.parametrize("case", _DEC["replyCases"], ids=lambda c: c["name"])
async def test_openai_decisions_reply_fixture(case: dict[str, Any], fake) -> None:
    from toolnexus.classifier import _decisions_reply

    qs = fixture_questions(_DEC["questions"])
    exp = case["expect"]
    if exp.get("normalized"):
        assert _decisions_reply(case["reply"], qs).decode() == exp["normalized"]
    f = fake(lambda n, b: (200, json.dumps(case["reply"])))
    c = create_classifier(backend="openai", base_url=f.url, on_error=no_retry)
    if "error" in exp:
        with pytest.raises(ClassifierError) as e:
            await c.evaluate("x", qs)
        assert str(e.value) == exp["error"]
        return
    d = await c.evaluate("x", qs)
    assert d.model == exp["model"] and d.calibrated is exp["calibrated"]
    for k, v in exp.get("noul", {}).items():
        assert d.noul(k).noul == v
    for k, v in exp.get("choice", {}).items():
        assert d.choice(k).choice == v
    for k, v in exp.get("score", {}).items():
        assert d.score(k).score == v


@pytest.mark.parametrize("case", _DEC["errorCases"], ids=lambda c: c["name"])
async def test_openai_decisions_error_fixture(case: dict[str, Any], fake) -> None:
    f = fake(lambda n, b: (200, _dec_ok_reply()))
    c = create_classifier(backend="openai", base_url=f.url, on_error=no_retry)
    with pytest.raises(ClassifierError) as e:
        await c.evaluate("x", fixture_questions(case["questions"]))
    assert str(e.value) == case["expect"]["error"]
    assert isinstance(e.value, ClassifierUnsupported) is bool(case["expect"].get("unsupported"))
    assert f.bodies == []


@pytest.mark.parametrize("case", _DEC["captureCases"], ids=lambda c: c["name"])
async def test_openai_decisions_capture_fixture(case: dict[str, Any], fake, monkeypatch) -> None:
    for k, v in case.get("env", {}).items():
        monkeypatch.setenv(k, v)
    f = fake(lambda n, b: (200, _dec_ok_reply()))
    c = create_classifier(
        backend="openai", base_url=f.url + "/v1", api_key_env=case.get("apiKeyEnv"), on_error=no_retry
    )
    await c.evaluate("x", fixture_questions(_DEC["questions"]))
    assert f.headers[0].get("authorization") == case["expectAuthorization"]
