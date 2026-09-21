## ADDED Requirements

### Requirement: Every capped listing sorts before it truncates

A builtin that walks a filesystem tree and returns a bounded list to the model — `glob`, `grep`,
and anything else that enumerates and truncates — SHALL **collect every candidate, sort by the
path relative to the walk root in plain Unicode code-point order, and only then truncate to the
cap**.

A port SHALL NOT break out of the walk at the cap and sort the survivors. Doing so leaves the
**filesystem** deciding which entries reach the model while the sort merely orders what happened
to be found first, so the same query returns different results on a different machine — and on
some filesystems between two runs of the same one. This is a content defect, not an ordering one,
and it is invisible to a review that inspects the comparator rather than what precedes it.

The rule is not specific to skills: it governs every capped listing that reaches the model, and
each port SHALL audit its builtins for it, not only its skill module.

#### Scenario: The cap selects from the sorted set

- **WHEN** a tree contains more matches than the cap, and a match that sorts first is reached late in the walk
- **THEN** that match appears in the returned list, and a match that sorts last does not
- **AND** the returned set does not depend on the order the filesystem enumerated

#### Scenario: The walk is not broken at the cap

- **WHEN** a capped listing is produced
- **THEN** the walk completes and the truncation happens after the sort, in every port

#### Scenario: The same query returns the same results on any machine

- **WHEN** the same query runs against the same tree on two machines whose filesystems enumerate differently
- **THEN** the returned entries are identical, not merely identically ordered

### Requirement: Builtin listings emit relative, slash-separated paths

A builtin listing returned to the model SHALL emit paths **relative to the walk root**, using `/`
as the separator on every platform, and SHALL sort on the same string it emits.

An absolute path leaks the host's directory layout into model-visible output, and a listing that
sorts on a relative path while emitting an absolute one is ordering by one string and displaying
another.

#### Scenario: Matches are relative to the walk root

- **WHEN** a search builtin returns matching files
- **THEN** each emitted path is relative to the walk root, `/`-separated, and is the string the results were sorted on

#### Scenario: The host's layout does not reach the model

- **WHEN** a listing is produced from an absolute walk root
- **THEN** the emitted entries carry no absolute prefix
