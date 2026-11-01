package toolnexus

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

// ConversationInfo is one entry of a store listing: the conversation id and
// when it was last saved. (§8 Conversation memory)
type ConversationInfo struct {
	ID        string
	UpdatedAt time.Time
}

// sortConversationInfos orders newest first, ties by id ascending.
func sortConversationInfos(out []ConversationInfo) {
	sort.Slice(out, func(i, j int) bool {
		if !out[i].UpdatedAt.Equal(out[j].UpdatedAt) {
			return out[i].UpdatedAt.After(out[j].UpdatedAt)
		}
		return out[i].ID < out[j].ID
	})
}

// List returns every stored conversation, newest first (ties by id).
func (s *InMemoryConversationStore) List() ([]ConversationInfo, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]ConversationInfo, 0, len(s.msgs))
	for id := range s.msgs {
		out = append(out, ConversationInfo{ID: id, UpdatedAt: s.updated[id]})
	}
	sortConversationInfos(out)
	return out, nil
}

// Delete removes id; deleting a missing id is not an error.
func (s *InMemoryConversationStore) Delete(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.msgs, id)
	delete(s.updated, id)
	return nil
}

var conversationIDRe = regexp.MustCompile(`^[A-Za-z0-9._-]{1,128}$`)

func validConversationID(id string) bool {
	return id != "." && id != ".." && conversationIDRe.MatchString(id)
}

const fileConversationVersion = 1

// FileConversationStore is a durable ConversationStore: one file
// <root>/<id>.json per conversation holding {"version":1,"messages":[...]}.
// Save writes a temp file (0600), fsyncs it and renames it over the old file, so
// a reader sees the old or the new transcript, never a torn one (power-loss
// durability of the rename is best-effort: the parent dir is fsynced where the
// OS allows). Same-id saves are serialized within a process; across processes
// the last rename wins. Ids must match ^[A-Za-z0-9._-]{1,128}$ and not be "." or
// ".."; every operation rejects anything else. Mirrors js FileConversationStore.
type FileConversationStore struct {
	root  string
	sync  FileStoreSync
	mu    sync.Mutex
	locks map[string]*sync.Mutex
}

// FileStoreSync is how hard Save pushes a write to stable storage before the
// rename. Every mode keeps the temp-then-rename swap, so a process crash never
// leaves a torn file; they differ only in what survives power loss.
type FileStoreSync string

const (
	// SyncFull (default) asks for the strongest flush the OS offers: on macOS
	// F_FULLFSYNC (through the drive cache), elsewhere fsync; then the
	// directory is fsynced best-effort.
	SyncFull FileStoreSync = "full"
	// SyncFsync is a plain fsync(2): on macOS it reaches the drive but not
	// through its cache; elsewhere the same as SyncFull. No directory fsync.
	SyncFsync FileStoreSync = "fsync"
	// SyncNone skips every flush: the newest saves may be lost on power loss,
	// but never torn.
	SyncNone FileStoreSync = "none"
)

// FileStoreOptions configures NewFileConversationStoreWith.
type FileStoreOptions struct {
	// Sync is the durability mode; "" means SyncFull.
	Sync FileStoreSync
}

// NewFileConversationStore builds a store rooted at root. root is required (no
// default home directory); it is created with mode 0700 on first save.
func NewFileConversationStore(root string) (*FileConversationStore, error) {
	return NewFileConversationStoreWith(root, FileStoreOptions{})
}

// NewFileConversationStoreWith is NewFileConversationStore with options. An
// unknown Sync mode is an error.
func NewFileConversationStoreWith(root string, opts FileStoreOptions) (*FileConversationStore, error) {
	if strings.TrimSpace(root) == "" {
		return nil, errors.New("toolnexus: FileConversationStore: root is required")
	}
	mode := opts.Sync
	if mode == "" {
		mode = SyncFull
	}
	switch mode {
	case SyncFull, SyncFsync, SyncNone:
	default:
		return nil, fmt.Errorf("toolnexus: FileConversationStore: unknown sync mode %q", opts.Sync)
	}
	return &FileConversationStore{root: root, sync: mode, locks: map[string]*sync.Mutex{}}, nil
}

func (s *FileConversationStore) path(id string) (string, error) {
	if !validConversationID(id) {
		return "", fmt.Errorf("toolnexus: FileConversationStore: invalid conversation id %q", id)
	}
	return filepath.Join(s.root, id+".json"), nil
}

func (s *FileConversationStore) lockFor(id string) *sync.Mutex {
	s.mu.Lock()
	defer s.mu.Unlock()
	l, ok := s.locks[id]
	if !ok {
		l = &sync.Mutex{}
		s.locks[id] = l
	}
	return l
}

type fileConversationBody struct {
	Version  *int  `json:"version"`
	Messages []any `json:"messages"`
}

// Get returns the stored transcript, (nil, nil) when absent, and an error when
// the file is unreadable, corrupt, or carries an unknown version.
func (s *FileConversationStore) Get(id string) ([]any, error) {
	p, err := s.path(id)
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(p)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return nil, nil
		}
		return nil, fmt.Errorf("toolnexus: FileConversationStore: read %q: %w", id, err)
	}
	var body fileConversationBody
	if err := json.Unmarshal(data, &body); err != nil {
		return nil, fmt.Errorf("toolnexus: FileConversationStore: corrupt conversation %q: %w", id, err)
	}
	if body.Version == nil || *body.Version != fileConversationVersion {
		return nil, fmt.Errorf("toolnexus: FileConversationStore: conversation %q has unsupported version", id)
	}
	if body.Messages == nil {
		body.Messages = []any{}
	}
	return body.Messages, nil
}

// Save atomically replaces the transcript for id.
func (s *FileConversationStore) Save(id string, messages []any) error {
	p, err := s.path(id)
	if err != nil {
		return err
	}
	if messages == nil {
		messages = []any{}
	}
	data, err := json.Marshal(map[string]any{"version": fileConversationVersion, "messages": messages})
	if err != nil {
		return fmt.Errorf("toolnexus: FileConversationStore: encode %q: %w", id, err)
	}
	l := s.lockFor(id)
	l.Lock()
	defer l.Unlock()
	if err := os.MkdirAll(s.root, 0o700); err != nil {
		return fmt.Errorf("toolnexus: FileConversationStore: create root: %w", err)
	}
	var rnd [8]byte
	if _, err := rand.Read(rnd[:]); err != nil {
		return err
	}
	tmp := filepath.Join(s.root, "."+id+"."+hex.EncodeToString(rnd[:])+".tmp")
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return fmt.Errorf("toolnexus: FileConversationStore: write %q: %w", id, err)
	}
	_, werr := f.Write(data)
	if werr == nil {
		switch s.sync {
		case SyncFull:
			werr = f.Sync()
		case SyncFsync:
			werr = plainFsync(f)
		}
	}
	if cerr := f.Close(); werr == nil {
		werr = cerr
	}
	if werr == nil {
		werr = os.Rename(tmp, p)
	}
	if werr != nil {
		_ = os.Remove(tmp)
		return fmt.Errorf("toolnexus: FileConversationStore: write %q: %w", id, werr)
	}
	// Best-effort: fsync the directory so the rename survives power loss.
	if s.sync != SyncFull {
		return nil
	}
	if d, err := os.Open(s.root); err == nil {
		_ = d.Sync()
		_ = d.Close()
	}
	return nil
}

// List returns {id, updatedAt (file mtime)} for every valid <id>.json, newest
// first, ties by id ascending. It does not parse file bodies; temp files and
// invalid names are skipped. A missing root lists as empty.
func (s *FileConversationStore) List() ([]ConversationInfo, error) {
	entries, err := os.ReadDir(s.root)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return []ConversationInfo{}, nil
		}
		return nil, fmt.Errorf("toolnexus: FileConversationStore: list: %w", err)
	}
	out := []ConversationInfo{}
	for _, e := range entries {
		name := e.Name()
		if !e.Type().IsRegular() || !strings.HasSuffix(name, ".json") {
			continue
		}
		id := strings.TrimSuffix(name, ".json")
		if !validConversationID(id) {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue // removed between ReadDir and Info
		}
		out = append(out, ConversationInfo{ID: id, UpdatedAt: info.ModTime()})
	}
	sortConversationInfos(out)
	return out, nil
}

// Delete removes the conversation; a missing id is not an error.
func (s *FileConversationStore) Delete(id string) error {
	p, err := s.path(id)
	if err != nil {
		return err
	}
	l := s.lockFor(id)
	l.Lock()
	defer l.Unlock()
	if err := os.Remove(p); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("toolnexus: FileConversationStore: delete %q: %w", id, err)
	}
	return nil
}

var _ ConversationStore = (*FileConversationStore)(nil)
