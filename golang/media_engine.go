// The shared API base of the media clients (SPEC §7G.5, §7G.8): one job per
// verb call carries the deadline, headers and origin rule; every request
// follows the redirect rules; gather turns provider results into MediaFiles.

package toolnexus

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime/debug"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"
)

// mediaJob is one verb call against an HTTP provider.
type mediaJob struct {
	ctx       context.Context
	verb      string
	client    string
	http      *http.Client
	base      string            // base URL, trailing "/" trimmed
	headers   map[string]string // sent only to the base URL's origin
	deadline  time.Time
	timeoutMs int
	jobID     string
	started   time.Time
	progress  func(MediaProgress)
	cfg       MediaConfig
	gatherDef string // default gather mode
}

func newMediaJob(ctx context.Context, client, verb string, cfg MediaConfig, call MediaCall, base string, headers map[string]string, timeoutMs int, gatherDef string) *mediaJob {
	now := time.Now()
	deadline := now.Add(time.Duration(timeoutMs) * time.Millisecond)
	if !call.Deadline.IsZero() && call.Deadline.Before(deadline) {
		deadline = call.Deadline
	}
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) { // D12: ctx.deadline joins the effective deadline
		deadline = d
	}
	progress := call.Progress
	if progress == nil {
		progress = cfg.Loop.OnProgress
	}
	return &mediaJob{ctx: ctx, verb: verb, client: client, http: cfg.httpClient(),
		base: strings.TrimRight(base, "/"), headers: headers, deadline: deadline, timeoutMs: timeoutMs,
		started: now, progress: progress, cfg: cfg, gatherDef: gatherDef}
}

func (j *mediaJob) report(status string) {
	if j.progress != nil {
		j.progress(MediaProgress{Verb: j.verb, Client: j.client, JobID: j.jobID, Status: status,
			ElapsedMs: time.Since(j.started).Milliseconds()})
	}
}

func (j *mediaJob) timedOut() *MediaError {
	if j.jobID != "" {
		return &MediaError{Message: fmt.Sprintf("job %s timed out after %dms", j.jobID, j.timeoutMs), Status: "timeout", JobID: j.jobID}
	}
	return mediaErr("timeout", fmt.Sprintf("%s: timed out after %dms", j.verb, j.timeoutMs))
}

// ctxErr maps the caller's context ending: an expired deadline is timeout,
// only an explicit cancel is cancelled (D12).
func (j *mediaJob) ctxErr() error {
	if errors.Is(j.ctx.Err(), context.DeadlineExceeded) {
		return j.timedOut()
	}
	return mediaCancelled(j.ctx.Err())
}

func (j *mediaJob) fail(msg string) *MediaError {
	return &MediaError{Message: msg, Status: "failed", JobID: j.jobID}
}

// wait sleeps intervalMs, or returns the cancellation error.
func (j *mediaJob) wait(intervalMs int) error {
	t := time.NewTimer(time.Duration(intervalMs) * time.Millisecond)
	defer t.Stop()
	select {
	case <-j.ctx.Done():
		return j.ctxErr()
	case <-t.C:
		return nil
	}
}

type mediaResp struct {
	status int
	header http.Header
	raw    []byte
}

var errTooManyRedirects = errors.New("too many redirects")

// errMediaTooBig marks a body read past the cap.
var errMediaTooBig = errors.New("too big")

// request sends one request (with the client headers only when the target is
// on the base origin, and only when withHeaders), follows redirects per §7G.8,
// and reads the body up to limit bytes (<=0 ⇒ unbounded). It does not check
// the status.
func (j *mediaJob) request(method, target string, body []byte, contentType string, withHeaders bool, limit int64) (mediaResp, error) {
	ctx, cancel := context.WithDeadline(j.ctx, j.deadline)
	defer cancel()
	failed := func(err error) error {
		if j.ctx.Err() != nil {
			return j.ctxErr() // cancellation stays the native error
		}
		if ctx.Err() != nil {
			return j.timedOut()
		}
		if errors.Is(err, errTooManyRedirects) {
			return j.fail(j.verb + ": too many redirects")
		}
		return j.fail(fmt.Sprintf("%s: %v", j.verb, err))
	}
	baseOrigin := ""
	if bu, err := url.Parse(j.base); err == nil {
		baseOrigin = mediaOrigin(bu)
	}
	c := *j.http
	inner := j.http.CheckRedirect
	c.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	var via []*http.Request
	var prev *http.Response
	var r *http.Response
	for {
		var rd io.Reader
		if body != nil {
			rd = bytes.NewReader(body)
		}
		req, err := http.NewRequestWithContext(ctx, method, target, rd)
		if err != nil {
			return mediaResp{}, failed(err)
		}
		if body != nil && contentType != "" {
			req.Header.Set("Content-Type", contentType)
		}
		if withHeaders && mediaOrigin(req.URL) == baseOrigin {
			for k, v := range j.headers {
				req.Header.Set(k, v)
			}
		}
		if req.Header.Get("User-Agent") == "" { // D23
			req.Header.Set("User-Agent", MediaUserAgent())
		}
		if len(via) > 0 && inner != nil {
			if err := inner(req, via); err != nil {
				if err == http.ErrUseLastResponse {
					r = prev
					break
				}
				prev.Body.Close()
				return mediaResp{}, failed(err)
			}
		}
		if prev != nil {
			_, _ = io.Copy(io.Discard, io.LimitReader(prev.Body, 1<<16))
			prev.Body.Close()
		}
		resp, err := c.Do(req)
		if err != nil {
			return mediaResp{}, failed(err)
		}
		loc := resp.Header.Get("Location")
		isRedirect := false
		switch resp.StatusCode {
		case 301, 302, 303, 307, 308:
			isRedirect = loc != ""
		}
		if !isRedirect {
			r = resp
			break
		}
		if len(via) >= 10 {
			resp.Body.Close()
			return mediaResp{}, failed(errTooManyRedirects)
		}
		next, err := req.URL.Parse(loc)
		if err != nil {
			resp.Body.Close()
			return mediaResp{}, failed(err)
		}
		if resp.StatusCode <= 303 && method != http.MethodGet && method != http.MethodHead {
			method, body = http.MethodGet, nil
		}
		prev = resp
		via = append(via, req)
		target = next.String()
	}
	defer r.Body.Close()
	var rd io.Reader = r.Body
	if limit > 0 {
		rd = io.LimitReader(r.Body, limit+1)
	}
	raw, err := io.ReadAll(rd)
	if err != nil {
		return mediaResp{}, failed(err)
	}
	if limit > 0 && int64(len(raw)) > limit {
		return mediaResp{status: r.StatusCode, header: r.Header}, errMediaTooBig
	}
	return mediaResp{status: r.StatusCode, header: r.Header, raw: raw}, nil
}

// check applies the §7G.2 unavailable mapping and the non-2xx rule.
func (j *mediaJob) check(r mediaResp) error {
	if r.status >= 200 && r.status <= 299 {
		return nil
	}
	body := capMediaBody(r.raw)
	if r.status == 402 || (r.status == 429 && mediaQuotaWords(string(r.raw))) {
		return &MediaError{Message: fmt.Sprintf("%s: HTTP %d: %s", j.client, r.status, body), Status: "unavailable", JobID: j.jobID}
	}
	return j.fail(fmt.Sprintf("HTTP %d: %s", r.status, body))
}

func mediaQuotaWords(s string) bool {
	s = strings.ToLower(s)
	for _, w := range []string{"quota", "credit", "billing", "balance"} {
		if strings.Contains(s, w) {
			return true
		}
	}
	return false
}

// url joins a path onto the base URL; an absolute URL passes through.
func (j *mediaJob) url(p string) string {
	if strings.HasPrefix(p, "http://") || strings.HasPrefix(p, "https://") {
		return p
	}
	return j.base + p
}

// call sends an API request (client headers on the base origin) and checks it.
func (j *mediaJob) call(method, p string, body []byte, contentType string) (mediaResp, error) {
	r, err := j.request(method, j.url(p), body, contentType, true, 0)
	if err != nil {
		return r, err
	}
	return r, j.check(r)
}

// jsonCall sends a JSON body (nil ⇒ no body) and parses a JSON response.
func (j *mediaJob) jsonCall(method, p string, body any) (any, mediaResp, error) {
	var payload []byte
	ct := ""
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, mediaResp{}, j.fail(fmt.Sprintf("%s: %v", j.verb, err))
		}
		payload, ct = b, "application/json"
	}
	r, err := j.call(method, p, payload, ct)
	if err != nil {
		return nil, r, err
	}
	out, err := j.parseJSON(r.raw)
	return out, r, err
}

func (j *mediaJob) parseJSON(raw []byte) (any, error) {
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.UseNumber()
	var out any
	if err := dec.Decode(&out); err != nil || dec.More() {
		return nil, j.fail(j.verb + ": response is not JSON")
	}
	return out, nil
}

// ---- multipart ----

type mediaPart struct {
	name, value     string
	filename, ctype string
	data            []byte
	file            bool
}

func (j *mediaJob) multipartCall(p string, parts []mediaPart, providerOptions map[string]any) (any, error) {
	parts = applyMultipartOptions(parts, providerOptions)
	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	for _, mp := range parts {
		if !mp.file {
			_ = w.WriteField(mp.name, mp.value)
			continue
		}
		h := textproto.MIMEHeader{}
		h.Set("Content-Disposition", fmt.Sprintf(`form-data; name="%s"; filename="%s"`, mpEscape(mp.name), mpEscape(mp.filename)))
		h.Set("Content-Type", mp.ctype)
		pw, _ := w.CreatePart(h)
		_, _ = pw.Write(mp.data)
	}
	_ = w.Close()
	r, err := j.call(http.MethodPost, p, buf.Bytes(), w.FormDataContentType())
	if err != nil {
		return nil, err
	}
	return j.parseJSON(r.raw)
}

func mpEscape(s string) string {
	return strings.NewReplacer("\\", "\\\\", `"`, "\\\"").Replace(s)
}

// applyMultipartOptions: providerOptions add or replace text fields (sorted keys).
func applyMultipartOptions(parts []mediaPart, opts map[string]any) []mediaPart {
	if len(opts) == 0 {
		return parts
	}
	keys := mediaSortedKeys(opts)
	out := append([]mediaPart(nil), parts...)
	for _, k := range keys {
		v := opts[k]
		s, ok := v.(string)
		if !ok {
			b, _ := json.Marshal(v)
			s = string(b)
		}
		replaced := false
		for i := range out {
			if !out[i].file && out[i].name == k {
				out[i].value, replaced = s, true
			}
		}
		if !replaced {
			idx := len(out)
			for i := range out {
				if out[i].file {
					idx = i
					break
				}
			}
			out = append(out[:idx], append([]mediaPart{{name: k, value: s}}, out[idx:]...)...)
		}
	}
	return out
}

// ---- inputs (§7G.1 Input references) ----

// readInput resolves an http(s) URL (GET, no client headers, deadline, maxBytes),
// a file:// URL or a local path to bytes + upload filename + part content type.
func (j *mediaJob) readInput(value string) (mediaPart, error) {
	cant := func(reason any) error {
		return j.fail(fmt.Sprintf("%s: cannot read %q: %v", j.verb, value, reason))
	}
	var data []byte
	var name string
	switch {
	case strings.HasPrefix(value, "http://") || strings.HasPrefix(value, "https://"):
		u, err := url.Parse(value)
		if err != nil {
			return mediaPart{}, cant(err)
		}
		name = lastSegment(u.Path)
		r, err := j.request(http.MethodGet, value, nil, "", false, j.maxBytes())
		if err == errMediaTooBig {
			return mediaPart{}, cant(fmt.Sprintf("file exceeds gather.maxBytes (%d bytes)", j.maxBytes())) // D14
		}
		if err != nil {
			var me *MediaError
			if errors.As(err, &me) && (me.Status == "cancelled" || me.Status == "timeout") {
				return mediaPart{}, err
			}
			return mediaPart{}, cant(err)
		}
		if r.status < 200 || r.status > 299 {
			return mediaPart{}, cant(fmt.Sprintf("HTTP %d", r.status))
		}
		data = r.raw
	default:
		p := value
		if strings.HasPrefix(value, "file://") {
			if u, err := url.Parse(value); err == nil {
				p = u.Path
			} else {
				p = strings.TrimPrefix(value, "file://")
			}
		}
		if fi, err := os.Stat(p); err == nil && fi.Mode().IsRegular() && fi.Size() > j.maxBytes() {
			return mediaPart{}, cant(fmt.Sprintf("file exceeds gather.maxBytes (%d bytes)", j.maxBytes())) // D14, before reading
		}
		b, err := os.ReadFile(p)
		if err != nil {
			return mediaPart{}, cant(err)
		}
		if int64(len(b)) > j.maxBytes() {
			return mediaPart{}, cant(fmt.Sprintf("file exceeds gather.maxBytes (%d bytes)", j.maxBytes())) // D14
		}
		data, name = b, filepath.Base(p)
	}
	if name == "" || name == "." || name == "/" {
		name = "input"
	}
	ct := "application/octet-stream"
	if mt, ok := mimeFromExt(name); ok {
		ct = mt
	}
	return mediaPart{filename: name, ctype: ct, data: data, file: true}, nil
}

func lastSegment(p string) string {
	if i := strings.LastIndex(p, "/"); i >= 0 {
		return p[i+1:]
	}
	return p
}

// ---- gather (§7G.5) ----

var mediaSeq atomic.Int64

func (j *mediaJob) mode() string {
	if m := j.cfg.Gather.Mode; m != "" {
		return m
	}
	return j.gatherDef
}

func (j *mediaJob) maxBytes() int64 { return gatherMaxBytes(j.cfg.Gather) }

func gatherMaxBytes(g MediaGather) int64 {
	if g.MaxBytes > 0 {
		return g.MaxBytes
	}
	return 104857600
}

func (j *mediaJob) tooBig() *MediaError {
	return j.fail(fmt.Sprintf("%s: file exceeds gather.maxBytes (%d bytes)", j.verb, j.maxBytes()))
}

// fromBytes: bytes, base64 and data: URLs — saved in url/download mode, data in inline.
func (j *mediaJob) fromBytes(kind string, data []byte, mime string) (MediaFile, error) {
	return gatherBytes(j.cfg.Gather, j.mode(), j.client, j.verb, kind, data, mime, "", j.fail)
}

// gatherBytes saves or inlines data. ext, when non-empty, is the saved file's
// extension (a URL's own, §7G.12 D22); else the mime table decides.
func gatherBytes(g MediaGather, mode, client, verb, kind string, data []byte, mime, ext string, fail func(string) *MediaError) (MediaFile, error) {
	f := MediaFile{Type: mediaPartType(kind), MimeType: mime}
	if int64(len(data)) > gatherMaxBytes(g) {
		return f, fail(fmt.Sprintf("%s: file exceeds gather.maxBytes (%d bytes)", verb, gatherMaxBytes(g)))
	}
	if mode == "inline" {
		f.Data = base64.StdEncoding.EncodeToString(data)
		return f, nil
	}
	if ext == "" {
		ext = extForMime(mime)
	}
	dir := g.Dir
	if dir == "" {
		dir = filepath.Join(os.TempDir(), "toolnexus-media")
	}
	abs, err := filepath.Abs(dir)
	if err != nil {
		return f, fail(fmt.Sprintf("%s: %v", verb, err))
	}
	if err := os.MkdirAll(abs, 0o755); err != nil {
		return f, fail(fmt.Sprintf("%s: %v", verb, err))
	}
	p := filepath.Join(abs, fmt.Sprintf("%s-%s-%d-%d%s", client, verb, time.Now().UnixMilli(), mediaSeq.Add(1), ext))
	if slug := mediaSlug(g.Name); slug != "" {
		p = mediaFreeName(abs, slug, ext)
	}
	if err := os.WriteFile(p, data, 0o644); err != nil {
		return f, fail(fmt.Sprintf("%s: %v", verb, err))
	}
	f.Path, f.URL = p, "file://"+p
	return f, nil
}

func (j *mediaJob) fromBase64(kind, b64, mime string) (MediaFile, error) {
	data, err := base64.StdEncoding.DecodeString(b64)
	if err != nil {
		return MediaFile{}, j.fail(fmt.Sprintf("%s: bad base64 result: %v", j.verb, err))
	}
	return j.fromBytes(kind, data, mime)
}

func (j *mediaJob) fromDataURL(kind, s string) (MediaFile, error) {
	head, body, ok := strings.Cut(strings.TrimPrefix(s, "data:"), ",")
	if !ok {
		return MediaFile{}, j.fail(j.verb + ": bad data URL")
	}
	mime := strings.SplitN(head, ";", 2)[0]
	if mime == "" {
		mime = defaultMime(kind)
	}
	var data []byte
	var err error
	if strings.HasSuffix(head, ";base64") {
		data, err = base64.StdEncoding.DecodeString(body)
	} else {
		var s string
		s, err = url.PathUnescape(body)
		data = []byte(s)
	}
	if err != nil {
		return MediaFile{}, j.fail(fmt.Sprintf("%s: bad data URL: %v", j.verb, err))
	}
	return j.fromBytes(kind, data, mime)
}

// fromURL: url mode keeps it; download/inline GET it with no client headers.
func (j *mediaJob) fromURL(kind, u string) (MediaFile, error) {
	mime := mediaMime(kind, u)
	if j.mode() == "url" {
		return MediaFile{Type: mediaPartType(kind), MimeType: mime, URL: u}, nil
	}
	r, err := j.request(http.MethodGet, u, nil, "", false, j.maxBytes())
	if err == errMediaTooBig {
		return MediaFile{}, j.fail(fmt.Sprintf("%s: download failed: file exceeds gather.maxBytes (%d bytes)", j.verb, j.maxBytes())) // D14
	}
	if err != nil {
		return MediaFile{}, err
	}
	if r.status < 200 || r.status > 299 {
		return MediaFile{}, j.fail(fmt.Sprintf("%s: download failed: HTTP %d", j.verb, r.status))
	}
	if ct := strings.TrimSpace(strings.SplitN(r.header.Get("Content-Type"), ";", 2)[0]); ct != "" && !strings.EqualFold(ct, "application/octet-stream") {
		mime = strings.ToLower(ct)
	}
	return gatherBytes(j.cfg.Gather, j.mode(), j.client, j.verb, kind, r.raw, mime, extForURL(u), j.fail)
}

// ---- helpers ----

func mediaOrigin(u *url.URL) string {
	port := u.Port()
	if port == "" {
		switch strings.ToLower(u.Scheme) {
		case "https":
			port = "443"
		case "http":
			port = "80"
		}
	}
	return strings.ToLower(u.Scheme) + "://" + strings.ToLower(u.Hostname()) + ":" + port
}

// capRunes keeps the first n code points, appending "…" when it truncates.
func capRunes(s string, n int) string {
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	i := 0
	for pos := range s {
		if i == n {
			return s[:pos] + "…"
		}
		i++
	}
	return s
}

// capMediaBody keeps the first 1000 code points of an echoed error body (§7G.8).
func capMediaBody(raw []byte) string { return capRunes(string(raw), 1000) }

// percentEncode encodes everything but RFC 3986 unreserved characters as %XX
// (uppercase) of its UTF-8 bytes (§7G.8).
func percentEncode(s string) string {
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~' {
			b.WriteByte(c)
		} else {
			fmt.Fprintf(&b, "%%%02X", c)
		}
	}
	return b.String()
}

func mediaPartType(kind string) string {
	switch kind {
	case "image", "audio":
		return kind
	}
	return "file"
}

var mediaMimes = map[string]string{
	"png": "image/png", "jpg": "image/jpeg", "jpeg": "image/jpeg", "webp": "image/webp", "gif": "image/gif",
	"mp4": "video/mp4", "webm": "video/webm", "mov": "video/quicktime",
	"mp3": "audio/mpeg", "wav": "audio/wav", "ogg": "audio/ogg", "m4a": "audio/mp4", "flac": "audio/flac",
}

var mediaExts = map[string]string{
	"image/png": ".png", "image/jpeg": ".jpg", "image/webp": ".webp", "image/gif": ".gif",
	"video/mp4": ".mp4", "video/webm": ".webm", "video/quicktime": ".mov",
	"audio/mpeg": ".mp3", "audio/wav": ".wav", "audio/x-wav": ".wav", "audio/wave": ".wav", "audio/ogg": ".ogg",
	"audio/mp4": ".m4a", "audio/flac": ".flac", "audio/aac": ".aac", "text/plain": ".txt",
}

var urlExtRe = regexp.MustCompile(`^\.[A-Za-z0-9]{1,5}$`)

// extForURL is the URL path's own extension (last segment, lowercased), or "" (§7G.12 D22).
func extForURL(u string) string {
	if pu, err := url.Parse(u); err == nil && pu.Scheme != "data" {
		last := pu.Path[strings.LastIndex(pu.Path, "/")+1:]
		if dot := strings.LastIndex(last, "."); dot >= 0 && urlExtRe.MatchString(last[dot:]) {
			return strings.ToLower(last[dot:])
		}
	}
	return ""
}

func extForMime(mime string) string {
	if e, ok := mediaExts[mime]; ok {
		return e
	}
	return ".bin"
}

// mimeFromExt: the extension table on a file name or path (case-insensitive).
func mimeFromExt(name string) (string, bool) {
	last := name[strings.LastIndex(name, "/")+1:]
	if dot := strings.LastIndex(last, "."); dot >= 0 {
		if mt, ok := mediaMimes[strings.ToLower(last[dot+1:])]; ok {
			return mt, true
		}
	}
	return "", false
}

func defaultMime(kind string) string {
	switch kind {
	case "image":
		return "image/png"
	case "audio":
		return "audio/mpeg"
	}
	return "video/mp4"
}

// mediaMime: extension of the URL path (query and fragment stripped), else the kind default.
func mediaMime(kind, u string) string {
	if i := strings.IndexAny(u, "?#"); i >= 0 {
		u = u[:i]
	}
	if i := strings.Index(u, "://"); i >= 0 { // only the path's extension counts
		rest := u[i+3:]
		if j := strings.Index(rest, "/"); j >= 0 {
			u = rest[j:]
		} else {
			u = ""
		}
	}
	if mt, ok := mimeFromExt(u); ok {
		return mt
	}
	return defaultMime(kind)
}

// scalarText renders a string as-is and a number (integral ⇒ integer text).
// Anything else (absent, null, objects, bools) ⇒ ok=false. Empty string ⇒ ok=false.
func scalarText(v any) (string, bool) {
	switch x := v.(type) {
	case string:
		return x, x != ""
	case json.Number:
		return numberText(string(x)), true
	case float64:
		return numberText(strconv.FormatFloat(x, 'f', -1, 64)), true
	case int:
		return strconv.Itoa(x), true
	case int64:
		return strconv.FormatInt(x, 10), true
	}
	return "", false
}

// jobIDText: only a non-empty string or an integral number is a job id.
func jobIDText(v any) (string, bool) {
	switch n := v.(type) {
	case json.Number:
		f, err := strconv.ParseFloat(string(n), 64)
		if err != nil || f != math.Trunc(f) {
			return "", false
		}
	case float64:
		if n != math.Trunc(n) {
			return "", false
		}
	}
	return scalarText(v)
}

func numberText(s string) string {
	if _, err := strconv.ParseInt(s, 10, 64); err == nil {
		return s
	}
	f, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return s
	}
	if f == math.Trunc(f) && math.Abs(f) < 1e18 {
		return strconv.FormatInt(int64(f), 10)
	}
	return strconv.FormatFloat(f, 'g', -1, 64)
}

// argInt reads an integral arg (int, float64, json.Number).
func argInt(v any) (int64, bool) {
	switch x := v.(type) {
	case int:
		return int64(x), true
	case int64:
		return x, true
	case float64:
		return int64(x), x == math.Trunc(x)
	case json.Number:
		f, err := x.Float64()
		return int64(f), err == nil && f == math.Trunc(f)
	}
	return 0, false
}

// argText renders an arg as text (numbers via numberText).
func argText(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	if n, ok := argInt(v); ok {
		return strconv.FormatInt(n, 10)
	}
	if t, ok := scalarText(v); ok {
		return t
	}
	return fmt.Sprint(v)
}

func mediaSortedKeys(m map[string]any) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// mediaVersionFallback is the package version used when the build carries no
// module version (a local checkout, go run inside this repo).
const mediaVersionFallback = "0.23.0"

// MediaUserAgent is the default User-Agent of every media HTTP request
// (§7G.12 D23): "toolnexus/<version> (golang)". A client/style User-Agent header wins.
func MediaUserAgent() string {
	v := mediaVersionFallback
	if bi, ok := debug.ReadBuildInfo(); ok {
		for _, d := range bi.Deps {
			if d.Path == "github.com/muthuishere/toolnexus/golang" && strings.HasPrefix(d.Version, "v") && d.Version != "(devel)" {
				v = strings.TrimPrefix(d.Version, "v")
			}
		}
	}
	return "toolnexus/" + v + " (golang)"
}

// mediaSlug sanitises gather.name (§7G.10b).
func mediaSlug(name string) string {
	b := []byte(name)
	for i, c := range b {
		ok := c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-'
		if !ok {
			b[i] = '-'
		}
	}
	return strings.TrimLeft(string(b), ".-")
}

var mediaNameMu sync.Mutex

// mediaFreeName is the first of <slug><ext>, <slug>-2<ext>, … not yet in dir.
// The file is created empty under a lock so concurrent gathers never collide.
func mediaFreeName(dir, slug, ext string) string {
	mediaNameMu.Lock()
	defer mediaNameMu.Unlock()
	for n := 1; ; n++ {
		name := slug + ext
		if n > 1 {
			name = fmt.Sprintf("%s-%d%s", slug, n, ext)
		}
		p := filepath.Join(dir, name)
		f, err := os.OpenFile(p, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o644)
		if err == nil {
			f.Close()
			return p
		}
	}
}
