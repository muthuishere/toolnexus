package toolnexus

import (
	"encoding/base64"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// ---------------------------------------------------------------------------
// §1B ContentPart — the one multimodal model, identical in every port.
// ---------------------------------------------------------------------------

// Part type discriminators (§1B). The union is exactly these four.
const (
	PartText  = "text"
	PartImage = "image"
	PartFile  = "file"
	PartAudio = "audio"
)

// ContentPart is one piece of message or tool-result content (§1B).
//
// A non-text part carries a MimeType plus EXACTLY ONE of Data (standard base64,
// padded, no line breaks) or URL. Both, or neither, is a construction error. A
// part NEVER holds a filesystem path: a path does not survive a persisted and
// replayed transcript, nor the MCP / A2A process boundary — the edge
// constructors (File, FileHandle, FSFile, Reader, Bytes, URLPart) read and
// encode at construction so the path never enters the part.
//
// Modeled as a struct with a string discriminator and omitempty optionals, the
// same shape as A2APart and StreamEvent, so it marshals with the stdlib and
// needs no custom UnmarshalJSON.
type ContentPart struct {
	Type     string `json:"type"`
	Text     string `json:"text,omitempty"`
	MimeType string `json:"mimeType,omitempty"`
	Data     string `json:"data,omitempty"`
	URL      string `json:"url,omitempty"`
	Name     string `json:"name,omitempty"`

	// err is the deferred construction error (the text/template, sql.Rows idiom):
	// an edge constructor stays a one-expression call argument and RunParts
	// surfaces the first bad part. Unexported, so it never serialises and never
	// crosses a process boundary.
	err error
}

// PartError is the typed construction/validation error for a content part (§1B).
type PartError struct {
	// Kind is a stable machine-readable reason: "both", "neither", "extension",
	// "size", "read", "dataurl", "mime".
	Kind string
	Msg  string
}

func (e *PartError) Error() string { return "toolnexus: " + e.Msg }

func partErr(kind, format string, a ...any) ContentPart {
	return ContentPart{err: &PartError{Kind: kind, Msg: fmt.Sprintf(format, a...)}}
}

// UnsupportedPartError is raised when a part cannot be represented by the
// selected provider style (§8A). It never carries the part's bytes.
type UnsupportedPartError struct {
	PartType string
	MimeType string
	Style    ClientStyle
}

func (e *UnsupportedPartError) Error() string {
	mime := e.MimeType
	if mime == "" {
		mime = "(no mime type)"
	}
	return fmt.Sprintf("toolnexus: the %q style has no block for a %q part (%s)", string(e.Style), e.PartType, mime)
}

// DefaultMaxPartBytes, when > 0, is the default value for ClientOptions.
// MaxPartBytes (§1B): the DECODED byte length a part may carry, measured and
// enforced at request assembly over EVERY part regardless of provenance — an
// MCP-derived part never passes through an edge constructor, so a limit only
// checked there is not a limit. Going over follows the same provenance rule
// as an unsupported part: an attached part errors, a tool-derived one degrades
// to the placeholder with a warn-once (emit.go). The edge constructors also
// check this value as a fast-fail convenience with a better message; that
// check is not the guarantee. 0 (the default) means no limit.
var DefaultMaxPartBytes int

// Err returns the deferred construction error of a part, or nil.
func (p ContentPart) Err() error { return p.err }

// Bytes is the DECODED byte length a part carries (0 for a text or url part).
func (p ContentPart) Bytes() int {
	if p.Data == "" {
		return 0
	}
	raw, err := base64.StdEncoding.DecodeString(p.Data)
	if err != nil {
		return 0
	}
	return len(raw)
}

// EstimatedTokens is the byte-derived per-part token estimate (§1B):
// max(85, floor(decodedBytes/750)) for a non-text part. Deliberately NOT the
// length of the mimeType string, which would score a 5 MB image at ~3 tokens
// and make it uncompactable.
func (p ContentPart) EstimatedTokens() int {
	if p.Type == PartText {
		return len(p.Text) / 4
	}
	n := p.Bytes() / 750
	if n < 85 {
		n = 85
	}
	return n
}

// String renders a part as {type, mimeType, bytes} — its Data is NEVER printed.
// This is what logs and §9 events show.
func (p ContentPart) String() string {
	if p.Type == PartText {
		return fmt.Sprintf("{type:text, chars:%d}", len(p.Text))
	}
	if p.URL != "" {
		return fmt.Sprintf("{type:%s, mimeType:%s, url}", p.Type, p.MimeType)
	}
	return fmt.Sprintf("{type:%s, mimeType:%s, bytes:%d}", p.Type, p.MimeType, p.Bytes())
}

// Validate reports the part's construction error, the data/url exclusivity rule
// and (when maxBytes > 0) the decoded size cap.
func (p ContentPart) Validate(maxBytes int) error {
	if p.err != nil {
		return p.err
	}
	if p.Type == PartText {
		return nil
	}
	switch {
	case p.Data != "" && p.URL != "":
		return &PartError{Kind: "both", Msg: fmt.Sprintf("a %q part carries both data and url — exactly one is allowed", p.Type)}
	case p.Data == "" && p.URL == "":
		return &PartError{Kind: "neither", Msg: fmt.Sprintf("a %q part carries neither data nor url — exactly one is required", p.Type)}
	case p.MimeType == "":
		return &PartError{Kind: "mime", Msg: fmt.Sprintf("a %q part needs a mimeType", p.Type)}
	}
	if maxBytes > 0 {
		if n := p.Bytes(); n > maxBytes {
			return &PartError{Kind: "size", Msg: fmt.Sprintf("part is %d decoded bytes, over the %d byte limit", n, maxBytes)}
		}
	}
	return nil
}

// ---------------------------------------------------------------------------
// The fixed media extension table (§6 read / §1B edge constructors).
// Shared by every port. No sniffing, no platform mime database — /etc/mime.types
// varies per machine and would break cross-port parity.
// ---------------------------------------------------------------------------

type mediaEntry struct {
	mime     string
	partType string
}

var mediaTable = map[string]mediaEntry{
	"png":  {"image/png", PartImage},
	"jpg":  {"image/jpeg", PartImage},
	"jpeg": {"image/jpeg", PartImage},
	"gif":  {"image/gif", PartImage},
	"webp": {"image/webp", PartImage},
	"pdf":  {"application/pdf", PartFile},
	"mp3":  {"audio/mpeg", PartAudio},
	"wav":  {"audio/wav", PartAudio},
}

// lookupMedia resolves a path's extension through the fixed table.
func lookupMedia(path string) (mediaEntry, bool) {
	ext := strings.ToLower(strings.TrimPrefix(filepath.Ext(path), "."))
	e, ok := mediaTable[ext]
	return e, ok
}

// partTypeForMime maps a mime type to its part type: image/* ⇒ image,
// audio/* ⇒ audio, everything else ⇒ file.
func partTypeForMime(mime string) string {
	switch {
	case strings.HasPrefix(mime, "image/"):
		return PartImage
	case strings.HasPrefix(mime, "audio/"):
		return PartAudio
	default:
		return PartFile
	}
}

// ---------------------------------------------------------------------------
// Edge constructors — they normalise, so a path or raw bytes never enter a part.
// ---------------------------------------------------------------------------

// Text is a text content part.
func Text(s string) ContentPart { return ContentPart{Type: PartText, Text: s} }

// File builds a part from a filesystem path: the bytes are read and base64d NOW
// and the mime type comes from the fixed extension table. An unknown extension
// is a typed error naming it. The error is deferred onto the returned part and
// surfaced by RunParts, so this stays usable as a one-expression argument.
func File(path string) ContentPart {
	e, ok := lookupMedia(path)
	if !ok {
		return unknownExtension(path)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return partErr("read", "cannot read %s: %v", path, err)
	}
	p := encodeBytes(raw, e.mime, e.partType)
	if p.err == nil && p.Type == PartFile {
		p.Name = filepath.Base(path)
	}
	return p
}

// Reader builds a part from any io.Reader with an explicit mime type. The
// reader is drained EAGERLY here — a part must never hold an unread stream,
// which would not survive a persisted transcript any better than a path does.
//
// The reader is NOT closed: it belongs to the caller, who closes it (or not) on
// their own terms. A read failure is deferred onto the returned part and
// surfaced by RunParts, like File's.
func Reader(r io.Reader, mimeType string) ContentPart {
	if mimeType == "" {
		return partErr("mime", "Reader needs an explicit mimeType")
	}
	raw, err := drain(r)
	if err != nil {
		return partErr("read", "cannot read the reader: %v", err)
	}
	return encodeBytes(raw, mimeType, partTypeForMime(mimeType))
}

// FileHandle builds a part from an open file the caller already holds — an
// *os.File, or anything else satisfying fs.File. The bytes are read NOW and the
// mime type comes from the fixed extension table applied to the file's own name
// (fs.File.Stat), so an *os.File opened on shot.png needs no mimeType argument.
// When the name carries no usable extension, use Reader with an explicit mime.
//
// The file is NOT closed: it belongs to the caller. Errors are deferred onto the
// returned part and surfaced by RunParts.
func FileHandle(f fs.File) ContentPart {
	if f == nil {
		return partErr("read", "FileHandle needs an open file, got nil")
	}
	info, err := f.Stat()
	if err != nil {
		return partErr("read", "cannot stat the file: %v", err)
	}
	name := info.Name()
	e, ok := lookupMedia(name)
	if !ok {
		return unknownExtension(name)
	}
	raw, err := drain(f)
	if err != nil {
		return partErr("read", "cannot read %s: %v", name, err)
	}
	p := encodeBytes(raw, e.mime, e.partType)
	if p.err == nil && p.Type == PartFile {
		p.Name = filepath.Base(name)
	}
	return p
}

// FSFile builds a part from a name inside an fs.FS — an embed.FS, an os.DirFS,
// a testing fstest.MapFS. This is how a Go program ships a fixture image inside
// its own binary, so it is a first-class source rather than a conversion the
// caller writes by hand. Same rules as File: read now, base64 now, mime from the
// fixed extension table, error deferred onto the part.
func FSFile(fsys fs.FS, name string) ContentPart {
	if fsys == nil {
		return partErr("read", "FSFile needs a file system, got nil")
	}
	e, ok := lookupMedia(name)
	if !ok {
		return unknownExtension(name)
	}
	raw, err := fs.ReadFile(fsys, name)
	if err != nil {
		return partErr("read", "cannot read %s: %v", name, err)
	}
	p := encodeBytes(raw, e.mime, e.partType)
	if p.err == nil && p.Type == PartFile {
		p.Name = filepath.Base(name)
	}
	return p
}

// drain reads a source fully. When DefaultMaxPartBytes is set it reads one byte
// past the limit and stops, so an oversize stream still fast-fails in
// encodeBytes without the whole thing being pulled into memory first.
func drain(r io.Reader) ([]byte, error) {
	if DefaultMaxPartBytes > 0 {
		return io.ReadAll(io.LimitReader(r, int64(DefaultMaxPartBytes)+1))
	}
	return io.ReadAll(r)
}

// unknownExtension is the shared typed error for a name the fixed media table
// does not cover — identical wording whatever the source was.
func unknownExtension(name string) ContentPart {
	ext := strings.ToLower(strings.TrimPrefix(filepath.Ext(name), "."))
	if ext == "" {
		return partErr("extension", "%s has no extension — pass explicit bytes with a mimeType instead", name)
	}
	return partErr("extension", "unknown media extension %q — pass explicit bytes with a mimeType instead", ext)
}

// Bytes builds a part from native bytes with an explicit mime type; the caller
// never pays the hand-base64 tax.
func Bytes(raw []byte, mimeType string) ContentPart {
	if mimeType == "" {
		return partErr("mime", "Bytes needs an explicit mimeType")
	}
	return encodeBytes(raw, mimeType, partTypeForMime(mimeType))
}

func encodeBytes(raw []byte, mime, partType string) ContentPart {
	if DefaultMaxPartBytes > 0 && len(raw) > DefaultMaxPartBytes {
		return partErr("size", "part is %d decoded bytes, over the %d byte limit", len(raw), DefaultMaxPartBytes)
	}
	return ContentPart{Type: partType, MimeType: mime, Data: base64.StdEncoding.EncodeToString(raw)}
}

// URLPart builds a part from a URL. A `data:<mime>;base64,<b64>` URL is parsed
// into {mimeType, data} at construction — two spellings of the same bytes must
// not diverge downstream — and every other URL is retained as url. mimeType may
// be empty for a data: URL (it is read from the URL).
func URLPart(u, mimeType string) ContentPart {
	if strings.HasPrefix(u, "data:") {
		mime, b64, err := parseDataURL(u)
		if err != nil {
			return ContentPart{err: err}
		}
		raw, decErr := base64.StdEncoding.DecodeString(b64)
		if decErr != nil {
			return partErr("dataurl", "data: URL payload is not standard base64: %v", decErr)
		}
		return encodeBytes(raw, mime, partTypeForMime(mime))
	}
	if mimeType == "" {
		return partErr("mime", "a url part needs an explicit mimeType")
	}
	return ContentPart{Type: partTypeForMime(mimeType), MimeType: mimeType, URL: u}
}

func parseDataURL(u string) (mime, b64 string, err error) {
	rest := strings.TrimPrefix(u, "data:")
	comma := strings.Index(rest, ",")
	if comma < 0 {
		return "", "", &PartError{Kind: "dataurl", Msg: "malformed data: URL — no comma"}
	}
	meta, payload := rest[:comma], rest[comma+1:]
	if !strings.Contains(meta, ";base64") {
		return "", "", &PartError{Kind: "dataurl", Msg: "only base64 data: URLs are supported"}
	}
	mime = strings.TrimSuffix(meta, ";base64")
	if mime == "" {
		return "", "", &PartError{Kind: "dataurl", Msg: "data: URL carries no mime type"}
	}
	return mime, payload, nil
}

// dataURL renders a part's bytes back as a data: URL (the shape OpenAI's
// image_url and file_data both require).
func (p ContentPart) dataURL() string {
	return "data:" + p.MimeType + ";base64," + p.Data
}

// nonText reports whether a part needs a native block (i.e. is not plain text).
func (p ContentPart) nonText() bool { return p.Type != PartText }

// describePart is the canonical §8A one-liner used where a part must be named
// but never rendered — an image-only tool result, an unsupported-part
// placeholder: "<type> (<mimeType>, <bytes> bytes)". <bytes> is the DECODED
// byte count; a part carrying a url instead of data renders it as 0.
func describePart(p ContentPart) string {
	return fmt.Sprintf("%s (%s, %d bytes)", p.Type, p.MimeType, p.Bytes())
}
