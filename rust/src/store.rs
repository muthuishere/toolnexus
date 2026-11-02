//! Conversation memory (SPEC §8): a [`ConversationStore`] keyed by conversation
//! id, plus the in-memory default and a durable file-backed store.

use std::collections::HashMap;
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

use serde_json::{Value, json};

/// Where `ask(id, …)` keeps transcripts. `get` of an unknown id is `Ok(None)`.
pub trait ConversationStore: Send + Sync {
    fn get(&self, id: &str) -> Result<Option<Vec<Value>>, String>;
    fn save(&self, id: &str, messages: &[Value]) -> Result<(), String>;
    /// Every conversation, most recently updated first (ties by id).
    fn list(&self) -> Result<Vec<ConversationInfo>, String> {
        Ok(Vec::new())
    }
    fn delete(&self, _id: &str) -> Result<(), String> {
        Ok(())
    }
}

/// One stored conversation.
#[derive(Debug, Clone, PartialEq)]
pub struct ConversationInfo {
    pub id: String,
    pub updated_at: SystemTime,
}

fn sort_infos(out: &mut [ConversationInfo]) {
    out.sort_by(|a, b| {
        b.updated_at
            .cmp(&a.updated_at)
            .then_with(|| a.id.cmp(&b.id))
    });
}

/// The default store: process memory, copies on get/save.
#[derive(Debug, Default)]
pub struct InMemoryConversationStore {
    inner: Mutex<HashMap<String, (Vec<Value>, SystemTime)>>,
}

impl InMemoryConversationStore {
    pub fn new() -> Self {
        Self::default()
    }
}

impl ConversationStore for InMemoryConversationStore {
    fn get(&self, id: &str) -> Result<Option<Vec<Value>>, String> {
        let g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        Ok(g.get(id).map(|(m, _)| m.clone()))
    }
    fn save(&self, id: &str, messages: &[Value]) -> Result<(), String> {
        let mut g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        g.insert(id.to_owned(), (messages.to_vec(), SystemTime::now()));
        Ok(())
    }
    fn list(&self) -> Result<Vec<ConversationInfo>, String> {
        let g = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let mut out: Vec<ConversationInfo> = g
            .iter()
            .map(|(id, (_, t))| ConversationInfo {
                id: id.clone(),
                updated_at: *t,
            })
            .collect();
        sort_infos(&mut out);
        Ok(out)
    }
    fn delete(&self, id: &str) -> Result<(), String> {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(id);
        Ok(())
    }
}

/// Durability of a [`FileConversationStore`] save.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum FileStoreSync {
    /// fsync the file and the directory (default).
    #[default]
    Full,
    /// fsync the file only.
    Fsync,
    /// No fsync (atomic rename only).
    None,
}

const FILE_VERSION: i64 = 1;

/// One JSON file per conversation (`<root>/<id>.json`, `{version:1,messages}`),
/// written atomically (temp file + rename). Ids are `[A-Za-z0-9._-]{1,128}`.
#[derive(Debug)]
pub struct FileConversationStore {
    root: PathBuf,
    sync: FileStoreSync,
    locks: Mutex<HashMap<String, Arc<Mutex<()>>>>,
}

fn valid_id(id: &str) -> bool {
    id != "."
        && id != ".."
        && !id.is_empty()
        && id.len() <= 128
        && id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'.' || b == b'_' || b == b'-')
}

impl FileConversationStore {
    pub fn new(root: impl Into<PathBuf>) -> Result<Self, String> {
        Self::with_sync(root, FileStoreSync::Full)
    }

    pub fn with_sync(root: impl Into<PathBuf>, sync: FileStoreSync) -> Result<Self, String> {
        let root = root.into();
        if root.as_os_str().is_empty() || root.to_string_lossy().trim().is_empty() {
            return Err("toolnexus: FileConversationStore: root is required".into());
        }
        Ok(Self {
            root,
            sync,
            locks: Mutex::new(HashMap::new()),
        })
    }

    fn path(&self, id: &str) -> Result<PathBuf, String> {
        if !valid_id(id) {
            return Err(format!(
                "toolnexus: FileConversationStore: invalid conversation id {id:?}"
            ));
        }
        Ok(self.root.join(format!("{id}.json")))
    }

    fn lock_for(&self, id: &str) -> Arc<Mutex<()>> {
        self.locks
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .entry(id.to_owned())
            .or_default()
            .clone()
    }
}

fn rand_hex() -> String {
    use std::hash::{BuildHasher, Hasher};
    let mut h = std::collections::hash_map::RandomState::new().build_hasher();
    h.write_u128(
        SystemTime::now()
            .duration_since(SystemTime::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0),
    );
    format!("{:016x}", h.finish())
}

impl ConversationStore for FileConversationStore {
    fn get(&self, id: &str) -> Result<Option<Vec<Value>>, String> {
        let p = self.path(id)?;
        let data = match std::fs::read(&p) {
            Ok(d) => d,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => {
                return Err(format!(
                    "toolnexus: FileConversationStore: read {id:?}: {e}"
                ));
            }
        };
        let body: Value = serde_json::from_slice(&data).map_err(|e| {
            format!("toolnexus: FileConversationStore: corrupt conversation {id:?}: {e}")
        })?;
        if body.get("version").and_then(Value::as_i64) != Some(FILE_VERSION) {
            return Err(format!(
                "toolnexus: FileConversationStore: conversation {id:?} has unsupported version"
            ));
        }
        Ok(Some(
            body.get("messages")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default(),
        ))
    }

    fn save(&self, id: &str, messages: &[Value]) -> Result<(), String> {
        let p = self.path(id)?;
        let data = serde_json::to_vec(&json!({"version": FILE_VERSION, "messages": messages}))
            .map_err(|e| format!("toolnexus: FileConversationStore: encode {id:?}: {e}"))?;
        let lock = self.lock_for(id);
        let _g = lock.lock().unwrap_or_else(|e| e.into_inner());
        std::fs::create_dir_all(&self.root)
            .map_err(|e| format!("toolnexus: FileConversationStore: create root: {e}"))?;
        let tmp = self.root.join(format!(".{id}.{}.tmp", rand_hex()));
        let write = || -> std::io::Result<()> {
            let mut f = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&tmp)?;
            f.write_all(&data)?;
            match self.sync {
                FileStoreSync::Full => f.sync_all()?,
                FileStoreSync::Fsync => f.sync_data()?,
                FileStoreSync::None => {}
            }
            drop(f);
            std::fs::rename(&tmp, &p)
        };
        if let Err(e) = write() {
            let _ = std::fs::remove_file(&tmp);
            return Err(format!(
                "toolnexus: FileConversationStore: write {id:?}: {e}"
            ));
        }
        if self.sync == FileStoreSync::Full
            && let Ok(d) = std::fs::File::open(&self.root)
        {
            let _ = d.sync_all();
        }
        Ok(())
    }

    fn list(&self) -> Result<Vec<ConversationInfo>, String> {
        let rd = match std::fs::read_dir(&self.root) {
            Ok(r) => r,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(e) => return Err(format!("toolnexus: FileConversationStore: list: {e}")),
        };
        let mut out = Vec::new();
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            let Ok(ft) = e.file_type() else { continue };
            if !ft.is_file() {
                continue;
            }
            let Some(id) = name.strip_suffix(".json") else {
                continue;
            };
            if !valid_id(id) {
                continue;
            }
            let Ok(meta) = e.metadata() else { continue };
            out.push(ConversationInfo {
                id: id.to_owned(),
                updated_at: meta.modified().unwrap_or(SystemTime::UNIX_EPOCH),
            });
        }
        sort_infos(&mut out);
        Ok(out)
    }

    fn delete(&self, id: &str) -> Result<(), String> {
        let p = self.path(id)?;
        let lock = self.lock_for(id);
        let _g = lock.lock().unwrap_or_else(|e| e.into_inner());
        match std::fs::remove_file(&p) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(format!(
                "toolnexus: FileConversationStore: delete {id:?}: {e}"
            )),
        }
    }
}
