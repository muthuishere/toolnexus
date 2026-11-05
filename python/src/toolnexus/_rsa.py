"""Minimal RS256 (RSASSA-PKCS1-v1_5 + SHA-256) on the standard library only.

Used by :class:`~toolnexus.mcp_server_auth.McpServerAuth` (verify) and the DEV-ONLY
authorization server (keygen + sign), so the MCP 2026-07-28 server half adds no runtime
dependency. Verification is the RFC 8017 §8.2.2 encode-and-compare form (no parsing of the
decrypted block), which is the variant immune to Bleichenbacher-style signature forgery.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import secrets

# DER DigestInfo prefix for SHA-256 (RFC 8017 §9.2 note 1).
_SHA256_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")


def b64url(b: bytes) -> str:
    """base64url without padding (JWT segments)."""
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode("ascii")


def b64url_decode(s: str) -> bytes:
    """Strict base64url without padding (Go's ``RawURLEncoding``): padding or a
    character outside the alphabet is an error."""
    if "=" in s:
        raise ValueError("padded base64url")
    return base64.b64decode(s + "=" * (-len(s) % 4), altchars=b"-_", validate=True)


def _emsa(digest: bytes, k: int) -> bytes:
    t = _SHA256_PREFIX + digest
    if k < len(t) + 11:
        raise ValueError("RSA key too short")
    return b"\x00\x01" + b"\xff" * (k - len(t) - 3) + b"\x00" + t


def verify_rs256(n: int, e: int, message: bytes, sig: bytes) -> bool:
    """True when ``sig`` is a valid RS256 signature of ``message`` under (n, e)."""
    k = (n.bit_length() + 7) // 8
    if len(sig) != k:
        return False
    s = int.from_bytes(sig, "big")
    if s >= n:
        return False
    em = pow(s, e, n).to_bytes(k, "big")
    try:
        want = _emsa(hashlib.sha256(message).digest(), k)
    except ValueError:
        return False
    return hmac.compare_digest(em, want)


def _probable_prime(n: int) -> bool:
    if n < 2:
        return False
    for p in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37):
        if n % p == 0:
            return n == p
    d, r = n - 1, 0
    while d % 2 == 0:
        d //= 2
        r += 1
    for _ in range(40):
        a = secrets.randbelow(n - 3) + 2
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(r - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def _prime(bits: int, e: int) -> int:
    while True:
        c = secrets.randbits(bits) | (1 << (bits - 1)) | (1 << (bits - 2)) | 1
        if c % e != 1 and _probable_prime(c):
            return c


class RsaKey:
    """An RSA private key (e = 65537) with CRT signing."""

    def __init__(self, bits: int = 2048) -> None:
        e = 65537
        while True:
            p, q = _prime(bits // 2, e), _prime(bits // 2, e)
            if p == q:
                continue
            n = p * q
            if n.bit_length() != bits:
                continue
            break
        self.n, self.e = n, e
        self._p, self._q = p, q
        self._d = pow(e, -1, (p - 1) * (q - 1))
        self._dp, self._dq = self._d % (p - 1), self._d % (q - 1)
        self._qinv = pow(q, -1, p)

    def sign_rs256(self, message: bytes) -> bytes:
        k = (self.n.bit_length() + 7) // 8
        m = int.from_bytes(_emsa(hashlib.sha256(message).digest(), k), "big")
        m1, m2 = pow(m, self._dp, self._p), pow(m, self._dq, self._q)
        s = m2 + ((self._qinv * (m1 - m2)) % self._p) * self._q
        return s.to_bytes(k, "big")

    def jwk(self, kid: str) -> dict[str, str]:
        def enc(x: int) -> str:
            return b64url(x.to_bytes((x.bit_length() + 7) // 8, "big"))

        return {"kty": "RSA", "kid": kid, "alg": "RS256", "use": "sig", "n": enc(self.n), "e": enc(self.e)}
