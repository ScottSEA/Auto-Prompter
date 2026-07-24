# Auto-Prompter

Android-first Compose Multiplatform teleprompter with a Kotlin/Wasm browser preview.

**This repository is the architecture tracer bullet, not a finished product.** It exists to
prove the module layout and the "shared core owns alignment, platform adapters depend inward"
design end to end: a minimal speech-follow engine in shared code, exercised by a diagnostic
Compose screen that runs unchanged on Android and in the browser.

## Modules

| Module        | Type                              | Responsibility |
|---------------|-----------------------------------|----------------|
| `:core`       | KMP library (jvm, android, wasmJs) | Immutable script/hypothesis domain, the canonical serializable `ScriptDocument` format, the pure document-editor reducer, the public `ScriptFollower` follow interface, and the pure `reducePromptSession` session state machine. |
| `:ui`         | KMP + Compose library (android, wasmJs) | Shared Compose tracer screen with a diagnostic document-editor section. Dispatches all intent through `:core`'s reducers; holds no alignment, mode, or editing logic of its own. |
| `:androidApp` | Android application                | Android launcher (`MainActivity`) hosting the shared screen. |
| `:webApp`     | Kotlin/Wasm Compose executable     | Browser composition root serving the shared screen. |

Adapters (`:androidApp`, `:webApp`) depend inward on `:ui` -> `:core`; nothing depends outward.

### Why this shape

The four-module `:core` / `:ui` / `:androidApp` / `:webApp` split is the preferred shape and it
builds cleanly under the current toolchain, so no deeper collapse was needed. `:core` adds a
`jvm()` target purely so the shared domain tests run fast off-device (`:core:jvmTest`); the shipped
targets are `androidLibrary` and `wasmJs`.

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
`ScriptDocument` (see below) rather than a raw string. 66 core behavior tests in `:core:jvmTest`
pin these behaviors: 16 aligner tests plus the state bounds, 8 reducer tests covering following,
manual hold, resume, nudges, toggle, reset, and seek-bounds enforcement, 21 document tests
covering construction/validation, plain-text import, JSON round trip and error handling, and
document-to-script conversion, and 21 document-editor tests (see below). 12 shared UI-model tests
in `:ui:jvmTest` pin the Reset button's reducer wiring, that scenario selection carries the
expected document identity/title and starts prompting from the document's converted `Script`, and
the diagnostic editor flow: editing marks the draft dirty, applying a valid draft restarts
prompting from the edited document, an invalid draft is not applicable, and mark-saved returns the
editor to clean.

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

**This is the in-memory model and its plain-text/JSON conversions only.** Persistence, files,
IndexedDB, Drive sync, and Markdown/DOCX/PDF import-export are explicitly *not* implemented yet;
they arrive with their own storage and sync milestones.

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
`document.toScript()`, preserving the editor draft), and a `Mark saved` control that acknowledges
the current generation to demonstrate the clean state. This section is **shared Compose for
diagnostics only**; per the architecture the production web editor remains a DOM island, and this
is not persistence. Scenario selection replaces editor, document, and session together from the
same canonical document; Reset resets prompting only and leaves the editor draft untouched.

**Non-goals in this slice:** no persistence, autosave, undo/redo history, rich text, or the eventual
DOM editor island -- and no schema v2. The pinned schema-v1 wire contract and `ScriptDocument`'s
immutable-block guarantee are unchanged.

## Toolchain

Versions come from the official Kotlin/KMP-App-Template baseline (commit `63ff248c`): Kotlin
2.3.21, Compose Multiplatform 1.11.0, AGP 9.0.1, Gradle 9.3.1, compile/target SDK 36, minSdk 26.
The canonical document format uses `kotlinx-serialization-json` **1.11.0** (the latest stable
release, built against the Kotlin 2.3.x line per its published tooling metadata); the serialization
compiler plugin ships with Kotlin, so it is pinned to the Kotlin version in the version catalog.
Requires JDK 17 and an Android SDK; set its location in `local.properties`:

```
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

The Gradle wrapper downloads its pinned distribution on first run.

## Build / test / run

Run from the repository root (`./gradlew` on Unix, `.\gradlew.bat` on Windows).

```bash
# Shared core and UI-model behavior tests (fast, off-device)
./gradlew :core:jvmTest :ui:jvmTest

# Android debug APK -> androidApp/build/outputs/apk/debug/
./gradlew :androidApp:assembleDebug

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
