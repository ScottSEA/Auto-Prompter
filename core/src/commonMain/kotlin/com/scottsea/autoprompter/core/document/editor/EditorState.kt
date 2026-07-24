package com.scottsea.autoprompter.core.document.editor

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.CURRENT_SCHEMA_VERSION
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import kotlin.jvm.JvmInline

/**
 * The shared document-editor reducer.
 *
 * This slice adds a realistic editing seam on top of the canonical [ScriptDocument]. Unlike
 * [ScriptDocument] -- which can never hold a blank title, no blocks, or blank block text -- an
 * editor draft is deliberately allowed to be *transiently* incomplete, because a user must be able
 * to clear a title or a block while typing. So a draft is NOT forced through [ScriptDocument]
 * construction on every keystroke: instead it carries mutable-looking-but-immutable draft values,
 * reports what is wrong as typed [EditorValidationIssue]s, and only materializes a canonical
 * [ScriptDocument] at an explicit save seam ([documentForSave] / [saveCandidate]).
 *
 * Everything here is a pure value: [startDocumentEditor] and [reduceDocumentEditor] never mutate,
 * and every list is defensively snapshotted so neither a caller's source list nor an exposed draft
 * list can reach back into editor state.
 *
 * Non-goals in this slice: no persistence, autosave, undo/redo history, rich text, or the eventual
 * DOM editor island. See the README.
 */

/**
 * One editable block. Unlike [ScriptBlock], a draft's [text] may be *temporarily* blank while the
 * user is typing; the stable [id] is still a non-blank [BlockId] and the [kind] is always set.
 */
data class BlockDraft(
    val id: BlockId,
    val kind: ScriptBlockKind,
    val text: String,
)

/** Builds a [BlockDraft] paragraph with a caller-supplied stable [id]. */
fun paragraphDraft(id: BlockId, text: String): BlockDraft =
    BlockDraft(id = id, kind = ScriptBlockKind.Paragraph, text = text)

/**
 * Identifies one editor lifetime. Callers must supply a fresh value whenever a document is opened
 * or replaced so delayed save completions cannot affect a later editor instance.
 */
@JvmInline
value class EditorSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "EditorSessionId must not be blank." }
    }
}

/**
 * A typed problem that keeps a draft from being a valid [ScriptDocument]. These are surfaced as
 * derived behavior ([EditorState.issues]) rather than thrown, because a draft is allowed to be
 * incomplete mid-edit. They are raised as an explicit failure only at the save seam.
 */
sealed interface EditorValidationIssue {
    /** The draft [EditorState.title] is blank. */
    data object BlankTitle : EditorValidationIssue

    /** The draft has no blocks at all (e.g. the last block was just deleted). */
    data object NoBlocks : EditorValidationIssue

    /** The block identified by [id] has blank text. */
    data class BlankBlockText(val id: BlockId) : EditorValidationIssue
}

/**
 * Thrown by the save seam ([documentForSave] / [saveCandidate]) when the draft is not a valid
 * document. It never silently falls back: the exact typed [issues] are exposed for the caller.
 */
class InvalidEditorDocumentException(
    val issues: List<EditorValidationIssue>,
) : IllegalStateException("Editor draft is not a valid document: $issues")

/**
 * An immutable snapshot of an in-progress document edit.
 *
 * It identifies one editor lifetime ([sessionId]) and its source document ([schemaVersion],
 * [documentId]), then carries the editable [title] and ordered editable [blocks].
 * [editGeneration] increases by exactly one per *effective* edit (a true no-op never advances it);
 * [savedGeneration] tracks the last acknowledged save and never regresses. [isDirty] and [issues]
 * are derived, not stored.
 *
 * The constructor is [internal]: the public creation seam is [startDocumentEditor], and every other
 * state is produced by [reduceDocumentEditor]. It still enforces the invariants that must hold for
 * *any* reachable state: a supported schema, non-negative generations, `savedGeneration <=
 * editGeneration`, and unique block IDs. It does NOT require a non-blank title, a non-empty block
 * list, or non-blank block text -- those are draft-legal and reported as [issues].
 */
class EditorState internal constructor(
    val sessionId: EditorSessionId,
    val schemaVersion: Int,
    val documentId: DocumentId,
    val title: String,
    blocks: List<BlockDraft>,
    val editGeneration: Long,
    val savedGeneration: Long,
) {
    /** Defensive snapshot: neither the caller's source list nor an exposed [blocks] read aliases it. */
    private val blockSnapshot: List<BlockDraft> = blocks.toList()

    /** Editable blocks in order. Each read returns a fresh copy so the state cannot be mutated. */
    val blocks: List<BlockDraft> get() = blockSnapshot.toList()

    /** Internal, non-copying view for the reducer in this package (never exposed to callers). */
    internal val draftBlocks: List<BlockDraft> get() = blockSnapshot

    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported editor schema version $schemaVersion; this build supports " +
                "version $CURRENT_SCHEMA_VERSION."
        }
        require(editGeneration >= 0) { "editGeneration cannot be negative: $editGeneration." }
        require(savedGeneration >= 0) { "savedGeneration cannot be negative: $savedGeneration." }
        require(savedGeneration <= editGeneration) {
            "savedGeneration $savedGeneration cannot exceed editGeneration $editGeneration."
        }
        val ids = blockSnapshot.map { it.id }
        require(ids.toSet().size == ids.size) { "Editor block IDs must be unique." }
    }

    /** True while the draft has effective edits not yet acknowledged as saved. */
    val isDirty: Boolean get() = editGeneration != savedGeneration

    /**
     * The typed problems that currently keep this draft from being a valid document, in a stable
     * order: blank title first, then no-blocks, then each blank block by [BlockId] in draft order.
     * An empty list means [documentForSave] would succeed.
     */
    val issues: List<EditorValidationIssue>
        get() = buildList {
            if (title.isBlank()) add(EditorValidationIssue.BlankTitle)
            if (blockSnapshot.isEmpty()) add(EditorValidationIssue.NoBlocks)
            for (block in blockSnapshot) {
                if (block.text.isBlank()) add(EditorValidationIssue.BlankBlockText(block.id))
            }
        }

    /** Produces a new state, reusing this state's fields where an argument is omitted. */
    internal fun with(
        title: String = this.title,
        blocks: List<BlockDraft> = this.blockSnapshot,
        editGeneration: Long = this.editGeneration,
        savedGeneration: Long = this.savedGeneration,
    ): EditorState = EditorState(
        sessionId = sessionId,
        schemaVersion = schemaVersion,
        documentId = documentId,
        title = title,
        blocks = blocks,
        editGeneration = editGeneration,
        savedGeneration = savedGeneration,
    )

    override fun equals(other: Any?): Boolean =
        other is EditorState &&
            sessionId == other.sessionId &&
            schemaVersion == other.schemaVersion &&
            documentId == other.documentId &&
            title == other.title &&
            blockSnapshot == other.blockSnapshot &&
            editGeneration == other.editGeneration &&
            savedGeneration == other.savedGeneration

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + schemaVersion
        result = 31 * result + documentId.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + blockSnapshot.hashCode()
        result = 31 * result + editGeneration.hashCode()
        result = 31 * result + savedGeneration.hashCode()
        return result
    }

    override fun toString(): String =
        "EditorState(sessionId=$sessionId, documentId=$documentId, title=$title, blocks=$blockSnapshot, " +
            "editGeneration=$editGeneration, savedGeneration=$savedGeneration)"
}

/**
 * An opaque proof that a valid save candidate was issued for one editor session and generation.
 * Its constructor is internal so external callers can only obtain tokens through [saveCandidate].
 */
class SaveToken internal constructor(
    val sessionId: EditorSessionId,
    val documentId: DocumentId,
    val generation: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is SaveToken &&
            sessionId == other.sessionId &&
            documentId == other.documentId &&
            generation == other.generation

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + generation.hashCode()
        return result
    }

    override fun toString(): String =
        "SaveToken(sessionId=$sessionId, documentId=$documentId, generation=$generation)"
}

/** A validated, non-forgeable save unit pairing an issued [token] with its canonical [document]. */
class SaveCandidate internal constructor(
    val token: SaveToken,
    val document: ScriptDocument,
) {
    val generation: Long get() = token.generation
}

/**
 * Starts a fresh editor from [document]: title/blocks copied, generation 0, clean, no issues.
 * [sessionId] must be unique for this editor lifetime.
 */
fun startDocumentEditor(document: ScriptDocument, sessionId: EditorSessionId): EditorState =
    EditorState(
        sessionId = sessionId,
        schemaVersion = document.schemaVersion,
        documentId = document.id,
        title = document.title,
        blocks = document.blocks.map { BlockDraft(it.id, it.kind, it.text) },
        editGeneration = 0L,
        savedGeneration = 0L,
    )

/** Internal convenience for same-module tests; production callers supply a unique session ID. */
internal fun startDocumentEditor(document: ScriptDocument): EditorState =
    startDocumentEditor(document, EditorSessionId("internal-${document.id.value}"))

/**
 * Materializes the canonical [ScriptDocument] for [state] when the draft is valid, or fails with an
 * [InvalidEditorDocumentException] that exposes the typed [EditorState.issues]. It never returns a
 * null or a silent fallback document.
 */
fun documentForSave(state: EditorState): ScriptDocument {
    val issues = state.issues
    if (issues.isNotEmpty()) throw InvalidEditorDocumentException(issues)
    return ScriptDocument(
        schemaVersion = state.schemaVersion,
        id = state.documentId,
        title = state.title,
        blocks = state.draftBlocks.map { ScriptBlock(it.id, it.kind, it.text) },
    )
}

/**
 * The save seam: a [SaveCandidate] pairing the current [EditorState.editGeneration] with the
 * validated document. Fails via [documentForSave] when the draft is invalid.
 */
fun saveCandidate(state: EditorState): SaveCandidate =
    SaveCandidate(
        token = SaveToken(
            sessionId = state.sessionId,
            documentId = state.documentId,
            generation = state.editGeneration,
        ),
        document = documentForSave(state),
    )
