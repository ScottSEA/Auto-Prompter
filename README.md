# Auto-Prompter

Android-first Compose Multiplatform teleprompter with a Kotlin/Wasm browser preview.

**This repository is the architecture tracer bullet, not a finished product.** It exists to
prove the module layout and the "shared core owns alignment, platform adapters depend inward"
design end to end: a minimal speech-follow engine in shared code, exercised by a diagnostic
Compose screen that runs unchanged on Android and in the browser.

## Modules

| Module               | Type                              | Responsibility |
|----------------------|-----------------------------------|----------------|
| `:core`              | KMP library (jvm, android, wasmJs) | Immutable script/hypothesis domain, the canonical serializable `ScriptDocument` format, the pure document-editor reducer, the public `ScriptFollower` follow interface, the pure `reducePromptSession` session state machine, the `DocumentStore` seam, and its `InMemoryDocumentStore` reference adapter. Depends on no adapter. |
| `:storeContractTest` | KMP test-support library (jvm, android, wasmJs) | Owns the nine reusable `DocumentStore` contract behaviors as ordinary functions so every adapter runs the exact same assertions. Test-only support; nothing production depends on it. |
| `:roomStore`         | KMP library (jvm, androidLibrary) | Durable Room 3 / SQLite `RoomDocumentStore` adapter for the `DocumentStore` seam, plus its `jvm()` / Android factory functions. Depends inward on `:core`; keeps Room/KSP codegen out of `:core` and `:ui`. |
| `:webStore`          | KMP library (wasmJs) | Durable browser `IndexedDbDocumentStore` adapter for the `DocumentStore` seam over **IndexedDB** (via `com.juul.indexeddb`), plus its suspend `openIndexedDbDocumentStore(...)` factory. Depends inward on `:core`; keeps IndexedDB/JS interop out of `:core` and `:ui`. |
| `:ui`                | KMP + Compose library (android, wasmJs) | Shared Compose tracer screen with a diagnostic document-editor section. Dispatches all intent through `:core`'s reducers and takes an injected `DocumentStore`; holds no alignment, mode, editing, or store-construction logic of its own. |
| `:androidApp`        | Android application                | Android launcher (`MainActivity`) that builds the Room-backed store and hosts the shared screen. |
| `:webApp`            | Kotlin/Wasm Compose executable     | Browser composition root that opens the durable IndexedDB store and serves the shared screen. |

Adapters (`:androidApp`, `:webApp`) depend inward on `:ui` -> `:core`; `:androidApp` also
depends on `:roomStore` -> `:core`, and `:webApp` depends on `:webStore` -> `:core`. `:roomStore`
and `:webStore` depend only on `:core`; `:core` never depends outward on Room or IndexedDB.
`:storeContractTest` is consumed only by adapter test source sets.

### Why this shape

The initial `:core` / `:ui` / launcher split remains intact. Two modules were added only where a
real seam justified them: `:roomStore` isolates Room/KSP/native SQLite, `:webStore` isolates the
IndexedDB/JS interop, while
`:storeContractTest` lets every adapter reuse one behavioral contract without shipping test code
inside production core. `:core` adds a `jvm()` target purely so shared domain tests run fast
off-device (`:core:jvmTest`); its shipped targets are `androidLibrary` and `wasmJs`.

## What the follow engine does today

`scriptFollower(script)` is a **deterministic local aligner**. For each recognizer hypothesis it
fits the spoken tokens to the script inside a bounded window around committed progress, scoring
exact matches against ad-lib insertions (extra spoken words) and skipped script words, and biasing
toward the speaker's current location. It handles: continuing a new utterance from committed
progress; short ad-libs that should not stall progress; skipped script words when surrounding
tokens corroborate the jump; a phrase repeated later in the script (resolving to the nearest
forward occurrence, never backwards); revised/shorter partials, which never regress committed
progress; and withholding a broad jump when evidence is thin -- a lone matched token is trusted
only when it sits exactly at committed progress, and candidate alignments are ranked so a
well-supported multi-token chain wins over a higher-scoring but unsupported lone match.

It is deliberately **not** yet the confidence-weighted, timing-aware, rare-token recovery engine
with acquire/retain hysteresis described in the architecture proposal. Those, along with
stable-vs-tentative token handling and fuzzy/phonetic matching, remain later milestones; the scope
is named accordingly in `ScriptFollower.kt`. The aligner runs a
bounded O(H*W) dynamic program (H hypothesis tokens, W the fixed local window), using rolling
primitive arrays with no per-candidate allocation, so cost stays flat per update regardless of
script length.

## The prompt-session reducer

Session behavior lives in shared code as a pure state machine, not in ad-hoc UI mutations.
`PromptSessionState` is immutable and carries the `Script`, the committed follow position, and an
explicit `FollowMode` (`Following` or `ManualHold`). `reducePromptSession(state, action)` is a pure
top-level reducer over a sealed `PromptSessionAction` vocabulary that models domain intent rather
than hardware keys, so a presentation remote, a touch gesture, and a keyboard all map onto the same
commands. This milestone's actions are: `SpeechHeard` (advance via `scriptFollower` while
following), `SeekTo` (jump to an exact token position and hold), `ResumeFollowing`, `NudgeForward`
/ `NudgeBackward` (one-token presentation-remote steps that hold), `ToggleFollow`, and `Reset`.
Following ignores speech while held, resume continues from the manually chosen anchor, nudges stay
within `0..tokenCount`, and an out-of-range `SeekTo` fails fast with an informative
`IllegalArgumentException` rather than silently clamping. The shared tracer screen drives every
transition through this reducer and shows the current mode, so the UI cannot drift from the engine.

The shared tracer screen exercises both layers through named scenarios (continuation, ad-lib
insertion, skipped words, repeated phrase), and each scenario now originates from a canonical
`ScriptDocument` (see below) rather than a raw string. 91 core behavior tests in `:core:jvmTest`
pin these behaviors: 16 aligner tests plus the state bounds, 8 reducer tests covering following,
manual hold, resume, nudges, toggle, reset, and seek-bounds enforcement, 21 document tests
covering construction/validation, plain-text import, JSON round trip and error handling, and
document-to-script conversion, 21 document-editor tests (see below), 9 `DocumentStore` contract
tests, and 16 document-library reducer tests (both see below). 21 shared UI-model tests
in `:ui:jvmTest` pin the Reset button's reducer wiring, that scenario selection carries the
expected document identity/title and starts prompting from the document's converted `Script`,
the diagnostic editor flow (editing marks the draft dirty, applying a valid draft restarts
prompting from the edited document, an invalid draft is not applicable), and the reference-store
save/select/load/delete flow: a save creates generation 1 and only then returns the editor to
clean, a second save advances to generation 2, a stale save conflicts and leaves the editor
dirty with a typed store conflict, delete removes the entry, and loading a saved entry restarts
the editor and prompt under a fresh session.

## The canonical script document

`ScriptDocument` is the first slice of the durable, portable document format that shared code owns.
It is immutable, serialized through a private pinned wire DTO, and carries an explicit `schemaVersion`, a stable
`DocumentId`, a non-blank `title`, and an ordered list of `ScriptBlock`s. Each block has a stable
`BlockId`, a `ScriptBlockKind` (`Paragraph` or `Heading` in this slice), and non-blank `text`.
`DocumentId` and `BlockId` are inline value classes that reject blank values. The current schema is
the named constant `CURRENT_SCHEMA_VERSION` (**1**). Construction fails fast with an informative
`IllegalArgumentException` on an unsupported schema version, a blank title, an empty block list,
duplicate block IDs, or blank block text, so an invalid document can never be built in memory.
The document takes defensive block snapshots, preventing a caller's mutable list from changing an
already validated document.

`importPlainText(id, title, text, blockId)` is a pure, deterministic importer. The caller supplies
the `DocumentId`, the title, and a `(index) -> BlockId` function so tests and platforms own ID
generation. It normalizes CRLF/CR to LF, treats one or more blank lines as a paragraph separator,
joins wrapped non-blank lines within a paragraph with single spaces, trims outer whitespace,
preserves paragraph order, and produces `Paragraph` blocks only. Blank input is rejected explicitly.

`encodeScriptDocument(document)` and `decodeScriptDocument(json)` are the external format seam. JSON
is configured intentionally: unknown keys are rejected and defaults are always written, so an
evolved or corrupt payload surfaces as an error instead of being silently dropped and every encoded
document requires its `schemaVersion`. Schema-v1 field and block-kind names are pinned explicitly,
and a golden JSON test guards the exact wire contract against accidental refactors. There is no
unknown-schema fallback -- malformed JSON
surfaces as a serialization error, and an unsupported schema version surfaces as an
`IllegalArgumentException` naming the rejected and supported versions.

`ScriptDocument.toScript()` derives the follower `Script` from the blocks in presentation order,
joining them with a single space. Heading text is included because headings are read aloud by
default in this milestone. Derived token indexes are computed on demand and are deliberately never
stored on the document. The tracer, Android, and web paths all start their session from
`startPromptSession(document.toScript())`, sharing this one seam.

**This is the in-memory model and its plain-text/JSON conversions only.** The document *format*
carries no persistence of its own; durable storage lives behind the `DocumentStore` seam (Room on
Android, IndexedDB on web). Files, Drive sync, and Markdown/DOCX/PDF import-export are explicitly
*not* implemented yet; they arrive with their own storage and sync milestones.

## The document editor reducer

`ScriptDocument` can never hold a blank title, no blocks, or blank block text -- but a person
editing *must* be able to clear a title or a block while typing. So the editor slice
(`com.scottsea.autoprompter.core.document.editor`) adds a realistic editing seam that does **not**
force every keystroke through `ScriptDocument` construction. `EditorState` is an immutable snapshot
that identifies the source document (`schemaVersion`, `documentId`), carries an editable `title`
and ordered editable `blocks` (each a `BlockDraft` with a stable non-blank `BlockId`, a
`ScriptBlockKind`, and possibly-blank `text`), an `EditorSessionId`, and tracks an `editGeneration`
and a `savedGeneration`. Draft title and block text may be transiently blank and the block list may be
empty; those are surfaced as derived, typed `EditorValidationIssue`s (`BlankTitle`, `NoBlocks`,
`BlankBlockText(id)`) rather than thrown. `isDirty` and `issues` are derived, not stored, and the
state defensively snapshots its block list so neither a caller's source list nor an exposed
`blocks` read can mutate it. Block IDs are guaranteed unique.

`startDocumentEditor(document)` and `reduceDocumentEditor(state, action)` are the pure entry
points. `EditorAction` is a sealed vocabulary of semantic intents -- `ChangeTitle`,
`ChangeBlockText`, `ChangeBlockKind`, `InsertBlock`, `DeleteBlock`, `MoveBlock`, `SaveAcknowledged`,
`ReplaceFromDocument` -- never keystrokes; adapters translate their inputs into these. An
*effective* edit advances `editGeneration` by exactly one; a true no-op (e.g. re-typing the same
text, or `MoveBlock` to the same resting index) returns the same state with no advance. `MoveBlock`
targets the block's final resting index in `0..lastIndex`. The `Long.MAX_VALUE` generation ceiling
is guarded explicitly. External invalid inputs fail fast with an informative
`IllegalArgumentException`: an unknown block ID, a duplicate inserted ID, an insert index outside
`0..size`, a move target outside `0..lastIndex`, or a save token from another document/editor session.
Deleting the last block is *not* an error -- it is a legal, incomplete draft that reports a
`NoBlocks` issue.

`documentForSave(state)` is the validation seam: it materializes the canonical `ScriptDocument` when the
draft validates, or throws `InvalidEditorDocumentException` exposing the exact typed `issues` list
-- never a null or silent fallback. `saveCandidate(state)` issues a non-forgeable `SaveToken` scoped
to the document, editor session, and current generation, paired with that validated document.
`SaveAcknowledged(token)` accepts only such an issued token for the current document/editor lifetime
and advances `savedGeneration` monotonically. An invalid draft cannot issue a token, a late
completion from a replaced editor is rejected, and a stale acknowledgement from the same editor
cannot make newer edits clean. Opening or replacing a document requires a fresh `EditorSessionId`;
the tracer increments its editor-session serial even when the same scenario is reopened.

21 tests in `:core:jvmTest` pin start/copy, blank-tolerant title/block edits with typed issues,
generation no-op-vs-effective semantics, block kind change, insert/delete/move (including bounds and
the `NoBlocks`-on-empty rule), the save-seam round trip and typed rejection, the
dirty/stale/session-scoped acknowledgement rules, defensive aliasing, and the generation-overflow guard.

The tracer hosts a **clearly labelled diagnostic editor section** over this reducer: title and
first-block text fields plus deterministic controls to append a paragraph, remove or move a block,
`Apply to prompt` (enabled only when the draft validates -- it obtains the validated document
through the shared save seam, replaces `TracerModel.document`, and restarts `PromptSession` from
`document.toScript()`, preserving the editor draft), and a `Save to library` control that persists
the validated document through the shared `DocumentStore` (see below) and, **only after the store
confirms**, acknowledges the current generation so the editor returns to clean. A companion
reference-library section lists saved entries with their generations and offers `Select`, `Load`
(restart editor + prompt from the saved snapshot under a fresh `EditorSessionId`), and
`Delete selected`. This section is **shared Compose for diagnostics only**; per the architecture
the production web editor remains a DOM island. The injected `DocumentStore` behind it is durable on
both platforms (Room on Android, IndexedDB on web).
Scenario selection replaces editor, document, and session together from the same canonical
document while preserving the store-backed library state; Reset resets prompting only and leaves
the editor draft untouched.

## The shared document store

The persistence seam is an asynchronous, optimistic-concurrency `DocumentStore`
(`com.scottsea.autoprompter.core.document.store`). Android implements it durably over **Room /
SQLite** (see [The durable Room store](#the-durable-room-store)); the **web** implements it durably
over **IndexedDB** (see [The durable web store](#the-durable-web-store)). `:core` ships the
interface, the reusable contract test
suite (now in `:storeContractTest`), and one **in-memory reference adapter** that proves the
semantics those production adapters must satisfy and still backs every off-device test.
**The reference adapter is not persistence: it holds everything in process memory and
loses all documents when the process ends -- it exists only to specify and test the contract.**

Concurrency is optimistic and explicit, never nullable magic. Each document ID carries a
**monotonic `StoreGeneration`** (an inline `Long`, rejecting negatives): the first successful
mutation for an ID is generation 1, and every later successful save or delete increments it by
exactly one. Generations are monotonic **across delete/recreate**: deleting exposes a *tombstone*
generation, and recreating resumes at tombstone + 1. Callers pin their intent with a sealed
`SavePrecondition` -- `MustBeMissing` (a fresh create) or `Matches(generation)` (an update of a
known live generation). The store answers reads and conflicts with a sealed `DocumentState` that
distinguishes never-created and missing-after-delete from live: `Live(snapshot)` or
`Missing(id, lastGeneration)`, where a null `lastGeneration` means never created and a non-null one
is the tombstone. `save` and `delete` return sealed `SaveOutcome`/`DeleteOutcome` values --
`Saved`/`Deleted` or `Conflict(current)` exposing the current `DocumentState`. **Expected optimistic
conflicts never throw**; only genuinely invalid input (guarded by the value types) does.

This is what makes the seam **ABA-safe**: create gen 1, delete (tombstone gen 2), recreate
(gen 3) -- a stale writer still holding `Matches(1)` conflicts instead of silently clobbering the
recreated document. The store also owns **defensive snapshots**: neither the stored blocks nor the
returned summary list can be mutated by a caller to affect stored state.

The reference adapter (`InMemoryDocumentStore`) is concurrency-safe and deterministic with **no
global singleton**: a single `Mutex` serializes every suspend operation, it keeps live records plus
the last generation per ID (so tombstones survive delete), `list()` returns a fresh immutable list
of live summaries ordered deterministically by title then ID, and a failed precondition mutates and
increments nothing. There is no silent fallback or broad catch. 9 contract behaviors run against it
through the reusable functional contract in the **`:storeContractTest`** module (a functional
runner, not an inheritance framework, so the Room adapter and the IndexedDB adapter
reuse the exact same assertions without duplicating them): never-created reads, first create at
gen 1, `MustBeMissing` conflict on a live doc, `Matches` update vs stale-conflict, delete +
tombstone vs stale/missing delete, ABA-protected recreate, independent per-ID sequences with
deterministic ordering, defensive aliasing, and a concurrent same-precondition race where exactly
one save wins and one conflicts.

## The document-library reducer

A pure `DocumentLibrary` reducer (`com.scottsea.autoprompter.core.document.library`) folds observed
store outcomes into UI state without performing any suspend effect itself -- the UI/effect layer
calls the store and dispatches the results. `DocumentLibraryState` carries a `LibraryStatus`
(`Loading`/`Ready`/`Failed`), the deterministic live summaries, the selected document ID, a per-ID
map of loaded snapshots, and the latest typed store conflict (a `DocumentState`). `reduceDocumentLibrary`
installs a listing with a valid selection (falling back explicitly when the selected ID is gone),
fails an unknown selection explicitly, upserts and selects on save success, removes and selects a
deterministic fallback on delete success, preserves current UI data while exposing the typed
conflict on a save/delete conflict, and clears the conflict on demand. State snapshots its public
collections defensively and rejects duplicate summary IDs. 16 tests pin these behaviors.

Async diagnostic callbacks transform the latest `TracerModel` rather than replacing it with the
model captured when an operation started. A late successful save always updates the library, but
only acknowledges the editor when its issued token still matches the current editor session;
scenario changes and newer edits are therefore not overwritten by delayed results. Late saves also
preserve any library selection made after the save started, and loads are applied only while the
editor session, generation, requested selected document, and requested stored generation still match.

**Non-goals in this slice:** no autosave, undo/redo history, rich text, the eventual
DOM editor island, or schema v2. The pinned schema-v1 wire contract and `ScriptDocument`'s
immutable-block guarantee are unchanged. Both production platforms are now durable: Android over
Room/SQLite and web over IndexedDB.

## The durable Room store

`:roomStore` ships `RoomDocumentStore` -- the first real durable adapter for the `DocumentStore`
seam, backed by **Room 3** over **SQLite**. It satisfies the nine-behavior contract exactly (run
against real temporary on-disk SQLite databases on the JVM, one fresh database per behavior) and
adds durability, so Android now survives process restart. Web has its own durable adapter over
IndexedDB (see [The durable web store](#the-durable-web-store)).

**Versions (official, verified 2026-07-24):** Room `androidx.room3:room3-runtime` / `room3-compiler`
**3.0.0** (the first stable Room 3 line, released 2026-07-01; the new `androidx.room3` namespace is
KSP-only, coroutines-first, and requires an explicit `SQLiteDriver` --
<https://developer.android.com/jetpack/androidx/releases/room3>). SQLite `androidx.sqlite:sqlite-bundled`
**2.7.0**, using `BundledSQLiteDriver` for consistent behavior across JVM host and Android. KSP
`com.google.devtools.ksp` **2.3.10** (KSP2), the published plugin build for Kotlin 2.3.21, proven by
the codegen compiling on both the `jvm` and `android` targets. The Room schema is exported by the
`androidx.room3` Gradle plugin **3.0.0**.

**Schema / table strategy.** One table, `document_rows`, holds **one row per `DocumentId`** and
retains both live state and tombstones so generations stay monotonic across delete/recreate. Columns:
`id` (`TEXT` primary key), `generation` (`INTEGER`), `deleted` (`INTEGER` tombstone flag), `title`
(`TEXT`, non-null for a live row, null for a tombstone), and `payload` (`TEXT`, the canonical
**schema-v1 JSON** `ScriptDocument`, non-null for a live row, null for a tombstone). Document blocks
are deliberately **not** normalized into child tables in this slice: the canonical JSON is the durable
payload seam, so the document schema and its migrations are owned once by `kotlinx-serialization` and
not duplicated in SQL. Row mapping is strict -- a live row must have a non-null payload and title, and
its generation must be positive, its decoded `ScriptDocument.id` and title must match the row
columns, and a tombstone must retain neither title nor payload. Malformed/unsupported payloads and
any other inconsistent row surface an explicit `CorruptDocumentRowException` before reads or
mutations rather than disappearing, defaulting, or being overwritten.

**Transaction / generation mapping.** Every `save` and `delete` runs its compare-and-set inside a
single Room `withWriteTransaction`, so the precondition check and the row write are atomic; Room
serializes writers on the one bundled connection, which makes the concurrent same-precondition race
deterministic (one `Saved`, one `Conflict`). A live save upserts `(deleted=false, title, payload)`;
a delete upserts a tombstone `(deleted=true, title=null, payload=null)` at the next generation rather
than deleting the row, preserving generation history. `generation` is monotonic exactly like the
in-memory store: first mutation is gen 1, each later mutation `+1`. Overflow is **guarded before
mutating** -- a mutation at `Long.MAX_VALUE` throws and the transaction rolls back, leaving the row
untouched. Expected optimistic conflicts return the current `DocumentState.Live`/`.Missing` as data
and never throw; only genuinely invalid/corrupt database state throws.

**Schema location & migration rule.** The exported schema is checked into version control at
`roomStore/schemas/com.scottsea.autoprompter.roomstore.DocumentDatabase/1.json` (version 1). This
directory is **not** gitignored: the JSON is the source-of-truth history that a future schema bump
diffs against. Any change to the Room entities must bump `DocumentDatabase`'s version and check in the
new exported JSON alongside a migration; the current slice is v1 only, with no migrations.

**Contract reuse.** `:roomStore`'s JVM tests call the same nine functions from `:storeContractTest`
that `:core` uses for the in-memory adapter -- no behavior is re-specified. On top of them the module
adds Room-specific durability tests (close/reopen the same file preserves the live document and its
generation; reopen preserves a tombstone and recreate advances past it), corruption tests (null or
malformed payload, null/mismatched title, payload-id/row-id mismatch, invalid tombstone data, and
generation zero each throw explicitly, exercised through a test-only raw-row seeding seam that does
not weaken the public API), and generation-overflow tests
(save and delete at `Long.MAX_VALUE` throw without altering the row). `RoomDocumentStore` owns its
database and exposes an explicit `close()` (`AutoCloseable`); the database and DAO never leak through
the `DocumentStore` interface. The module currently has 21 JVM tests: the 9 shared contract
behaviors plus smoke, durability, corruption, and generation-overflow coverage.

**Android durability & lifecycle.** `MainActivity` builds one Room-backed store from the
**application** context (`createRoomDocumentStore(applicationContext)`), injects it into `TracerApp`,
and closes it after `ComponentActivity.onDestroy()` has disposed Compose and canceled its UI
coroutines. The store is Activity-owned and reopened after a configuration change, which is safe
because the SQLite file is the durable source of truth and Room's compare-and-set is atomic; an
Application-scoped owner was not needed for this slice.

**Web durability & lifecycle.** `webApp`'s `main` opens one IndexedDB-backed store
(`openIndexedDbDocumentStore()`), waits for the async open in a retained page-lifetime coroutine
scope (never `GlobalScope`), and injects it into `TracerApp`. The connection is kept open for the
lifetime of the page. If the open fails the app renders a **fatal bootstrap error** instead of
silently substituting an in-memory store -- a memory fallback would falsely advertise durability and
discard the user's work on the next reload. The app also observes connection liveness: a cross-tab
schema upgrade, browser force-close, or failed store operation replaces the tracer with a clear
reload-required error instead of leaving dead controls. See [The durable web store](#the-durable-web-store).

## The durable web store

`:webStore` ships `IndexedDbDocumentStore` -- the durable web adapter for the `DocumentStore` seam,
backed by the browser's **IndexedDB**. It satisfies the same nine-behavior contract exactly (run in
a **real headless Chromium**, one fresh uniquely-named database per behavior) and adds
browser-specific durability, corruption, generation-overflow, and concurrency tests. This is the web
analogue of the Room store: reloading the page, or restarting the browser, preserves saved
documents and their generations.

**Dependency (official, verified 2026-07-24):** direct IndexedDB access via
`com.juul.indexeddb:core:0.12.0` (Apache-2.0;
<https://github.com/JuulLabs/indexeddb>). Version 0.12.0 is the first release with Kotlin/Wasm
support and coroutine transaction wrappers, built for the Kotlin 2.3.x line this project uses. We
chose direct IndexedDB over a Room 3 `WebWorkerSQLiteDriver` because Room 3 3.0.0 still ships **no
default web worker** -- it would require authoring and maintaining an app-supplied SQLite Wasm/OPFS
worker, which is disproportionate for a single-object-store key/value schema. The Juul/JS/IndexedDB
types never leak through the `DocumentStore` interface; `kotlin.js.ExperimentalWasmJsInterop` is
opted in only on the interop source that needs it, not project-wide.

**Schema.** Database version 1 with a single object store `document_rows`, keyed by the string `id`
(one JS object row per `DocumentId`). Row fields: `id` (string), `generation` (**decimal string**),
`deleted` (boolean), `title` (nullable string), `payload` (nullable string). The generation is
stored as a **decimal string, never a `Long` or JS number**: the Juul README warns that `Long`
emits an unsupported `bigint` across the JS boundary, and a JS `number` silently loses integer
precision above 2^53. Strings round-trip exactly. Live/tombstone invariants match Room: a live row
has a positive generation, a non-null title, and a non-null schema-v1 payload whose decoded id and
title match the row; a tombstone has null title and null payload. A corrupt row throws a dedicated
`WebStoreCorruptionException` **before** any read or mutation completes.

**Atomic protocol.** `save` and `delete` perform their get -> check-precondition -> put inside a
single IndexedDB **readwrite transaction** on the `document_rows` store. IndexedDB serializes
overlapping readwrite transactions for the same store, so a concurrent same-precondition race yields
exactly one winner and one typed `Conflict` -- with no lost update. Encoding, decoding, and
validation run **synchronously** inside the transaction; the code never calls an arbitrary suspend
function between the transaction-scoped operations, because an unrelated suspension can let the
transaction auto-commit and raise `TransactionInactiveError`. Only Juul's transaction-scoped suspend
operations are awaited inside the lambda. First mutation is generation 1, each success `+1`,
tombstones are retained, and recreate resumes at tombstone + 1. Generation strings are parsed with
strict canonical `Long` parsing: non-numeric, non-canonical (`"007"`, `"+1"`, `" 1"`, `"1.0"`),
negative, zero, and out-of-range values are rejected as corruption, and `Long.MAX_VALUE` is checked
before every write so overflow fails without altering the row. `list()` returns only live rows,
validates tombstones before filtering them, decodes every live row, orders deterministically by title
then id, and returns a fresh defensive list.

**Lifecycle.** `IndexedDbDocumentStore` implements `DocumentStore` and `AutoCloseable`. Because
opening and migrating IndexedDB are asynchronous, construction goes through the suspend factory
`openIndexedDbDocumentStore(name: String = DEFAULT_WEB_DATABASE_NAME)`. The version-1 upgrade
(`oldVersion < 1`) creates the object store and key path; unsupported future database versions are
rejected through normal IndexedDB version handling, with no destructive fallback. A test-only
database-deletion helper supports isolated tests; cleanup attempts every close/delete and rethrows
the first failure so blocked isolation cannot pass unnoticed. The web app uses the fixed default
database name and observes `isOpenFlow`; a version-change/close event transitions bootstrap to a
reload-required error.

**Contract reuse.** `:webStore`'s browser tests call the same nine functions from
`:storeContractTest` that `:core` and `:roomStore` use -- no behavior is re-specified. To let the
IndexedDB adapter open asynchronously, the contract's store-factory type is a **suspend** function;
the in-memory and Room adapters pass their existing non-suspend constructors unchanged, and the
production `DocumentStore` interface is untouched. On top of the nine shared behaviors the module
adds browser-specific tests: close/reopen the same database preserves the live document and its
generation; a tombstone survives reopen and recreate advances past it; malformed payload,
id/title mismatch, invalid tombstone, generation `0`, and non-numeric/overflow generation each throw
before any read or mutation; a `Long.MAX_VALUE` mutation fails without altering the row; and two
**independently opened** store instances on the same database satisfy the concurrent compare-and-set
(proving serialization holds across connections, not just within one instance). Every test uses a
unique database name and closes then `deleteDatabase`es it afterwards -- including on failure -- so
no test pollutes another and no open connection blocks deletion. The module has 30 browser tests: 9
shared contract behaviors plus smoke (1), durability (2), corruption (9), generation-overflow (2),
concurrency (1), pure row-mapping (4), cleanup-failure (1), and cross-version lifecycle (1) coverage.

**Browser compatibility & storage caveats.** IndexedDB is available in all current evergreen
browsers. Stored data is durable but **origin-scoped** and subject to the browser's storage
eviction under storage pressure or when the user clears site data; it is not a server-side backup.
Private/incognito sessions may clear IndexedDB when the session ends. These are properties of
browser storage, not of this adapter.

## Toolchain

Versions come from the official Kotlin/KMP-App-Template baseline (commit `63ff248c`): Kotlin
2.3.21, Compose Multiplatform 1.11.0, AGP 9.0.1, Gradle 9.3.1, compile/target SDK 36, minSdk 26.
The canonical document format uses `kotlinx-serialization-json` **1.11.0** (the latest stable
release, built against the Kotlin 2.3.x line per its published tooling metadata); the serialization
compiler plugin ships with Kotlin, so it is pinned to the Kotlin version in the version catalog.
The shared store seam and its tests use `kotlinx-coroutines` **1.11.0** -- the latest stable release
on Maven Central (`<release>1.11.0</release>`, published 2026-05-07), whose multiplatform artifacts
publish the `wasmJs` klibs this project needs and whose Kotlin-2.x stable library metadata is
forward-consumable by the 2.3.21 compiler. `kotlinx-coroutines-core` is a `commonMain` dependency of
both `:core` and `:ui`; `kotlinx-coroutines-test` is a `commonTest` dependency used to drive the
suspend store operations deterministically under `runTest`. Requires JDK 17 and an Android SDK; set
its location in `local.properties`:

```
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

The Gradle wrapper downloads its pinned distribution on first run.

## Build / test / run

Run from the repository root (`./gradlew` on Unix, `.\gradlew.bat` on Windows).

```bash
# Shared core and UI-model behavior tests (fast, off-device)
./gradlew :core:jvmTest :ui:jvmTest

# Durable Room store: 9 reused contract behaviors on real temp SQLite DBs,
# plus reopen/corruption/overflow tests (off-device, JVM)
./gradlew :roomStore:jvmTest

# Durable web store: the same 9 reused contract behaviors plus browser-specific
# durability/corruption/overflow/concurrency/lifecycle tests, in real headless Chromium (30 tests)
./gradlew :webStore:wasmJsBrowserTest

# Android debug APK -> androidApp/build/outputs/apk/debug/
./gradlew :androidApp:assembleDebug

# Android lint (report -> androidApp/build/reports/lint-results-debug.html)
./gradlew :androidApp:lintDebug

# Deployable web preview -> webApp/build/dist/wasmJs/productionExecutable/
./gradlew :webApp:wasmJsBrowserDistribution

# Live web preview at http://localhost:8080 (Ctrl+C to stop)
./gradlew :webApp:wasmJsBrowserDevelopmentRun
```

Open `webApp/build/dist/wasmJs/productionExecutable/index.html` through any static file server
(it will not run from a `file://` URL because it loads a wasm module). The preview requires a
modern browser with WasmGC support.

### Web build note

`:webApp:wasmJsBrowserDistribution` bundles with webpack, which Kotlin fetches through Yarn from
the public npm registry. On a network that blocks `registry.yarnpkg.com` / `registry.npmjs.org`,
point Yarn at a reachable mirror in `~/.yarnrc` (`registry "<mirror-url>"`) or pre-populate a
`yarn-offline-mirror`. The Android and core builds do not need npm.

## Conventions

Functional and immutable: data classes, pure top-level functions, small effect-free transforms;
no DI framework. Package root `com.scottsea.autoprompter`. ASCII source.
