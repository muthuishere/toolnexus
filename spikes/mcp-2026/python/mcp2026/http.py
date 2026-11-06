"""Tiny stdlib HTTP layer: a client (http.client) and a threaded WSGI server (wsgiref), both TLS-capable.

The client trusts any DevAuthServer started in this process (dev only), so a zero-config OAuth client
and ServerAuth(issuer=as_.issuer) need no TLS wiring in dev and tests.
"""
from __future__ import annotations

import base64
import http.client
import json
import os
import re
import socketserver
import ssl
import tempfile
import threading
from dataclasses import dataclass, field
from typing import Callable, Iterable
from urllib.parse import urljoin, urlsplit
from wsgiref.simple_server import WSGIRequestHandler, WSGIServer

DEFAULT_TIMEOUT = 30.0  # seconds; a hung server cannot hang the caller
MAX_BODY_BYTES = 4 << 20

_dev_lock = threading.Lock()
_dev_pems: list[str] = []
_dev_ctx: ssl.SSLContext | None = None


def trust_dev_cert(pem: str) -> None:
    """Dev only: default clients in this process now trust this certificate."""
    global _dev_ctx
    with _dev_lock:
        _dev_pems.append(pem)
        ctx = ssl.create_default_context()
        for p in _dev_pems:
            ctx.load_verify_locations(cadata=p)
        _dev_ctx = ctx


def default_ssl_context() -> ssl.SSLContext:
    with _dev_lock:
        return _dev_ctx or ssl.create_default_context()


@dataclass
class Response:
    status: int
    headers: http.client.HTTPMessage
    body: bytes
    url: str

    def header(self, name: str) -> str:
        return self.headers.get(name, "") or ""

    def json(self):
        return json.loads(self.body)


@dataclass
class HttpClient:
    """A minimal HTTP client. `headers` are added to every request (e.g. a static Authorization)."""

    timeout: float = DEFAULT_TIMEOUT
    ssl_context: ssl.SSLContext | None = None  # None = system trust + in-process dev certs
    headers: dict[str, str] = field(default_factory=dict)
    follow_redirects: bool = False

    def request(self, method: str, url: str, body: bytes | str | None = None,
                headers: dict[str, str] | None = None) -> Response:
        for _ in range(10):
            resp = self._once(method, url, body, headers)
            loc = resp.header("Location")
            if not (self.follow_redirects and resp.status in (301, 302, 303, 307, 308) and loc):
                return resp
            url = urljoin(url, loc)
            if resp.status in (301, 302, 303):
                method, body = "GET", None
        raise OSError("too many redirects")

    def get(self, url: str, headers: dict[str, str] | None = None) -> Response:
        return self.request("GET", url, headers=headers)

    def _once(self, method, url, body, headers) -> Response:
        u = urlsplit(url)
        if u.scheme == "https":
            conn = http.client.HTTPSConnection(u.hostname, u.port, timeout=self.timeout,
                                               context=self.ssl_context or default_ssl_context())
        elif u.scheme == "http":
            conn = http.client.HTTPConnection(u.hostname, u.port, timeout=self.timeout)
        else:
            raise ValueError(f"unsupported URL scheme: {url}")
        h = {**self.headers, **(headers or {})}
        if isinstance(body, str):
            body = body.encode()
        try:
            conn.request(method, (u.path or "/") + (f"?{u.query}" if u.query else ""), body=body, headers=h)
            r = conn.getresponse()
            data = r.read()
            return Response(r.status, r.headers, data, url)
        finally:
            conn.close()


_shared = HttpClient()


def default_http() -> HttpClient:
    return _shared


# ---------------------------------------------------------------- server side


class Request:
    """A parsed WSGI request (what Server / ServerAuth / web handlers look at)."""

    def __init__(self, environ: dict):
        self.environ = environ
        self.method = environ.get("REQUEST_METHOD", "GET")
        self.path = environ.get("PATH_INFO", "") or "/"
        self.query = environ.get("QUERY_STRING", "")
        self.scheme = environ.get("wsgi.url_scheme", "http")
        self.host = environ.get("HTTP_HOST") or f"{environ.get('SERVER_NAME')}:{environ.get('SERVER_PORT')}"
        self._body: bytes | None = None

    def header(self, name: str) -> str:
        key = name.upper().replace("-", "_")
        if key in ("CONTENT_TYPE", "CONTENT_LENGTH"):
            return self.environ.get(key, "") or ""
        return self.environ.get("HTTP_" + key, "") or ""

    def has_header(self, name: str) -> bool:
        return ("HTTP_" + name.upper().replace("-", "_")) in self.environ

    @property
    def url(self) -> str:
        return f"{self.scheme}://{self.host}{self.path}" + (f"?{self.query}" if self.query else "")

    def body(self, limit: int = MAX_BODY_BYTES) -> bytes:
        """Reads the body; raises ValueError when it is larger than limit."""
        if self._body is None:
            n = int(self.environ.get("CONTENT_LENGTH") or 0)
            if n > limit:
                raise ValueError("body too large")
            self._body = self.environ["wsgi.input"].read(n) if n else b""
        return self._body


def respond(start_response, status: int, body: bytes | str = b"",
            headers: Iterable[tuple[str, str]] = ()) -> list[bytes]:
    if isinstance(body, str):
        body = body.encode()
    reason = http.client.responses.get(status, "")
    start_response(f"{status} {reason}", [*headers, ("Content-Length", str(len(body)))])
    return [body]


def respond_json(start_response, status: int, value, headers: Iterable[tuple[str, str]] = ()) -> list[bytes]:
    return respond(start_response, status, json.dumps(value), [("Content-Type", "application/json"), *headers])


class _Handler(WSGIRequestHandler):
    def log_message(self, *args):  # quiet
        pass

    def get_environ(self):
        env = super().get_environ()
        if self.server.tls:
            env["HTTPS"] = "on"  # wsgiref derives wsgi.url_scheme from it
        return env

    def setup(self):
        if isinstance(self.request, ssl.SSLSocket):
            self.request.settimeout(DEFAULT_TIMEOUT)
            self.request.do_handshake()  # in the worker thread, not the accept loop
        super().setup()

    def handle(self):
        try:
            super().handle()
        except (ssl.SSLError, ConnectionError, OSError):
            pass


class _Server(socketserver.ThreadingMixIn, WSGIServer):
    daemon_threads = True
    allow_reuse_address = True
    tls = False

    def server_bind(self):  # skip HTTPServer's getfqdn (slow on macOS)
        socketserver.TCPServer.server_bind(self)
        self.server_name, self.server_port = self.server_address[:2]
        self.setup_environ()

    def handle_error(self, request, client_address):  # client went away etc.
        pass


@dataclass
class Running:
    """A server running on a background thread (like Go's httptest.Server)."""

    url: str
    _srv: _Server
    cert_pem: str = ""

    def close(self) -> None:
        self._srv.shutdown()
        self._srv.server_close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def serve(app: Callable, host: str = "127.0.0.1", port: int = 0, tls: bool = False,
          block: bool = False) -> Running:
    """Serves a WSGI app on host:port (0 = random). tls=True uses a fresh self-signed cert for 127.0.0.1
    that every default client in this process trusts (dev/tests only)."""
    srv = _Server((host, port), _Handler)
    srv.set_app(app)
    pem = ""
    if tls:
        pem, key = self_signed_cert()
        d = tempfile.mkdtemp()
        cf, kf = os.path.join(d, "c.pem"), os.path.join(d, "k.pem")
        with open(cf, "w") as f:
            f.write(pem)
        with open(kf, "w") as f:
            f.write(key)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(cf, kf)
        os.remove(cf), os.remove(kf), os.rmdir(d)
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True, do_handshake_on_connect=False)
        srv.tls = True
        trust_dev_cert(pem)
    h, p = srv.server_address[:2]
    run = Running(f"{'https' if tls else 'http'}://{h}:{p}", srv, pem)
    if block:
        srv.serve_forever()
    else:
        threading.Thread(target=srv.serve_forever, daemon=True).start()
    return run


def self_signed_cert() -> tuple[str, str]:
    """(cert PEM, key PEM) for 127.0.0.1 / localhost, valid one day."""
    import datetime
    import ipaddress

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "127.0.0.1")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=5)).not_valid_after(now + datetime.timedelta(days=1))
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
                                                        x509.DNSName("localhost")]), critical=False)
            .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
            .sign(key, hashes.SHA256()))
    return (cert.public_bytes(serialization.Encoding.PEM).decode(),
            key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                              serialization.NoEncryption()).decode())


def b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def b64url_decode(s: str) -> bytes:
    """Strict unpadded base64url (like Go's RawURLEncoding): any other character is an error."""
    if not re.fullmatch(r"[A-Za-z0-9_-]*", s) or len(s) % 4 == 1:
        raise ValueError("bad base64url")
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))
