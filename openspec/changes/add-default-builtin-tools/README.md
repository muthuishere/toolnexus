# add-default-builtin-tools

Ship a default-on built-in toolset (`read` / `write` / `execute` / `grep` / `glob`) so a fresh
Toolkit can act on the local filesystem and shell without the caller wiring up native tools first —
confined to a root directory, disable-able, and byte-identical across all five ports.
