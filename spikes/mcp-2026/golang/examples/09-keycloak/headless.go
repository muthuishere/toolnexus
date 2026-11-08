package main

// headlessLogin is TEST HARNESS, not something an app needs: it plays the person at the browser.
// It follows the authorization URL, submits Keycloak's login form (and a consent page if shown),
// stops at the 127.0.0.1 redirect and delivers it to the client's loopback listener.

import (
	"fmt"
	"html"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"regexp"
	"strings"
	"time"
)

var (
	loginForm = regexp.MustCompile(`<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"`)
	anyForm   = regexp.MustCompile(`<form[^>]*action="([^"]+)"`)
)

func headlessLogin(user, password string) func(string) error {
	jar, _ := cookiejar.New(nil)
	hc := &http.Client{Jar: jar, Timeout: 15 * time.Second,
		CheckRedirect: func(req *http.Request, _ []*http.Request) error {
			if req.URL.Hostname() == "127.0.0.1" {
				return http.ErrUseLastResponse
			}
			return nil
		}}
	return func(authURL string) error {
		resp, err := hc.Get(authURL)
		if err != nil {
			return err
		}
		body, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		for page := 0; resp.StatusCode == 200 && page < 3; page++ {
			var action string
			var form url.Values
			if m := loginForm.FindSubmatch(body); m != nil {
				action, form = string(m[1]), url.Values{"username": {user}, "password": {password}, "credentialId": {""}}
			} else if m := anyForm.FindSubmatch(body); m != nil && strings.Contains(string(body), `name="accept"`) {
				action, form = string(m[1]), url.Values{"accept": {"Yes"}}
			} else {
				break
			}
			target, err := resp.Request.URL.Parse(html.UnescapeString(action))
			if err != nil {
				return err
			}
			if resp, err = hc.PostForm(target.String(), form); err != nil {
				return err
			}
			body, _ = io.ReadAll(resp.Body)
			resp.Body.Close()
		}
		if resp.StatusCode != http.StatusFound {
			return fmt.Errorf("login did not redirect back (HTTP %d)", resp.StatusCode)
		}
		cb, err := http.Get(resp.Header.Get("Location")) // hand the callback to the loopback listener
		if err != nil {
			return err
		}
		return cb.Body.Close()
	}
}
