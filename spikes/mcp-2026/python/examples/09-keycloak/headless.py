"""headless_login is TEST HARNESS, not something an app needs: it plays the person at the browser.
It follows the authorization URL, submits Keycloak's login form (and a consent page if shown),
stops at the 127.0.0.1 redirect and delivers it to the client's loopback listener."""
import html
import http.cookiejar
import re
import urllib.error
import urllib.parse
import urllib.request

LOGIN_FORM = re.compile(r'<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"')
ANY_FORM = re.compile(r'<form[^>]*action="([^"]+)"')


class _LocalhostIsSecure(http.cookiejar.DefaultCookiePolicy):
    """Browsers treat http://localhost as a secure context, so Keycloak's Secure cookies are sent there."""

    def return_ok_secure(self, cookie, request):
        return True


class _StopAtLoopback(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).hostname == "127.0.0.1":
            raise urllib.error.HTTPError(newurl, code, "loopback", headers, fp)  # stop: hand it back
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def headless_login(user: str, password: str, deliver: bool = True):
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(_LocalhostIsSecure())),
                                         _StopAtLoopback())

    def fetch(url, form=None):
        data = urllib.parse.urlencode(form).encode() if form is not None else None
        try:
            r = opener.open(url, data, timeout=15)
            return 200, r.geturl(), r.read().decode(), ""
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303):
                return e.code, url, "", e.headers.get("Location", "")
            raise

    def login(auth_url: str):
        status, url, body, loc = fetch(auth_url)
        for _ in range(3):
            if status != 200:
                break
            m = LOGIN_FORM.search(body)
            if m:
                action, form = m.group(1), {"username": user, "password": password, "credentialId": ""}
            elif (m := ANY_FORM.search(body)) and 'name="accept"' in body:
                action, form = m.group(1), {"accept": "Yes"}
            else:
                break
            status, url, body, loc = fetch(urllib.parse.urljoin(url, html.unescape(action)), form)
        if status not in (302, 303) or not loc:
            raise RuntimeError(f"login did not redirect back (HTTP {status})")
        if deliver:
            urllib.request.urlopen(loc, timeout=15).read()  # hand the callback to the loopback listener
        return loc

    return login
