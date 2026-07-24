package com.scottsea.autoprompter.core.document.editor

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument

/**
 * The public, sealed vocabulary of semantic editor intents. Each is a domain-level action (change
 * title, change a block, insert/delete/move a block, acknowledge a save, reload a document), never
 * a keystroke or a UI event; adapters translate their inputs into these.
 */
sealed interface EditorAction {
    /** Replace the draft title with [raw]; [raw] may be blank while typing. */
    data class ChangeTitle(val raw: String) : EditorAction

    /** Replace the text of the block [id] with [raw]; [raw] may be blank while typing. */
    data class ChangeBlockText(val id: BlockId, val raw: String) : EditorAction

    /** Replace the [kind] of the block [id], preserving its id and text. */
    data class ChangeBlockKind(val id: BlockId, val kind: ScriptBlockKind) : EditorAction

    /** Insert [block] at [index] (0..size, so size means append), using its caller-supplied id. */
    data class InsertBlock(val index: Int, val block: BlockDraft) : EditorAction

    /** Delete the block [id]. Deleting the last block is allowed and yields a NoBlocks issue. */
    data class DeleteBlock(val id: BlockId) : EditorAction

    /**
     * Move the block [id] so it comes to rest at [targetIndex]. [targetIndex] is the block's final
     * position in the resulting list and must be in `0..lastIndex`.
     */
    data class MoveBlock(val id: BlockId, val targetIndex: Int) : EditorAction

    /** Acknowledge completion of the valid, session-scoped save represented by [token]. */
    data class SaveAcknowledged(val token: SaveToken) : EditorAction

    /** Reload the editor from [document] under a fresh [sessionId]: generation 0 and clean. */
    data class ReplaceFromDocument(
        val document: ScriptDocument,
        val sessionId: EditorSessionId,
    ) : EditorAction
}

/**
 * Pure top-level transition: given [state] and a semantic [action], returns the next
 * [EditorState]. It never mutates and holds no hidden state.
 *
 * Generation contract: an *effective* change advances [EditorState.editGeneration] by exactly one;
 * a true no-op returns the same state (no advance). [EditorAction.SaveAcknowledged] moves
 * [EditorState.savedGeneration] only, never the edit generation.
 *
 * External invalid inputs fail fast with an informative [IllegalArgumentException]: an unknown
 * block id, a duplicate inserted id, an out-of-range insert/move index, or a save token from a
 * different editor session/document. A delete that empties the block list is NOT an error -- an
 * empty draft is legal and surfaces as an [EditorValidationIssue.NoBlocks].
 */
fun reduceDocumentEditor(state: EditorState, action: EditorAction): EditorState =
    when (action) {
        is EditorAction.ChangeTitle -> applyEdit(state, newTitle = action.raw)
        is EditorAction.ChangeBlockText -> changeBlockText(state, action.id, action.raw)
        is EditorAction.ChangeBlockKind -> changeBlockKind(state, action.id, action.kind)
        is EditorAction.InsertBlock -> insertBlock(state, action.index, action.block)
        is EditorAction.DeleteBlock -> deleteBlock(state, action.id)
        is EditorAction.MoveBlock -> moveBlock(state, action.id, action.targetIndex)
        is EditorAction.SaveAcknowledged -> saveAcknowledged(state, action.token)
        is EditorAction.ReplaceFromDocument -> startDocumentEditor(action.document, action.sessionId)
    }

/**
 * Applies a candidate title/blocks to [state]. If nothing actually changed it returns [state]
 * unchanged (no generation advance); otherwise it advances the edit generation exactly once.
 */
private fun applyEdit(
    state: EditorState,
    newTitle: String = state.title,
    newBlocks: List<BlockDraft> = state.draftBlocks,
): EditorState {
    if (newTitle == state.title && newBlocks == state.draftBlocks) return state
    return state.with(
        title = newTitle,
        blocks = newBlocks,
        editGeneration = nextGeneration(state.editGeneration),
    )
}

/** The next edit generation, guarding the [Long.MAX_VALUE] ceiling explicitly. */
private fun nextGeneration(current: Long): Long {
    check(current < Long.MAX_VALUE) {
        "Editor edit generation overflow: cannot advance beyond Long.MAX_VALUE."
    }
    return current + 1
}

private fun changeBlockText(state: EditorState, id: BlockId, raw: String): EditorState {
    val index = requireBlockIndex(state, id)
    val updated = state.draftBlocks.mapIndexed { i, block ->
        if (i == index) block.copy(text = raw) else block
    }
    return applyEdit(state, newBlocks = updated)
}

private fun changeBlockKind(state: EditorState, id: BlockId, kind: ScriptBlockKind): EditorState {
    val index = requireBlockIndex(state, id)
    val updated = state.draftBlocks.mapIndexed { i, block ->
        if (i == index) block.copy(kind = kind) else block
    }
    return applyEdit(state, newBlocks = updated)
}

private fun insertBlock(state: EditorState, index: Int, block: BlockDraft): EditorState {
    val size = state.draftBlocks.size
    require(index in 0..size) { "Insert index $index out of range 0..$size." }
    require(state.draftBlocks.none { it.id == block.id }) {
        "Cannot insert duplicate block ID ${block.id.value}."
    }
    val updated = state.draftBlocks.toMutableList().apply { add(index, block) }
    return applyEdit(state, newBlocks = updated)
}

private fun deleteBlock(state: EditorState, id: BlockId): EditorState {
    val index = requireBlockIndex(state, id)
    val updated = state.draftBlocks.toMutableList().apply { removeAt(index) }
    return applyEdit(state, newBlocks = updated)
}

private fun moveBlock(state: EditorState, id: BlockId, targetIndex: Int): EditorState {
    val from = requireBlockIndex(state, id)
    val lastIndex = state.draftBlocks.lastIndex
    require(targetIndex in 0..lastIndex) {
        "Move target index $targetIndex out of range 0..$lastIndex."
    }
    if (from == targetIndex) return state
    val updated = state.draftBlocks.toMutableList().apply {
        val moved = removeAt(from)
        add(targetIndex, moved)
    }
    return applyEdit(state, newBlocks = updated)
}

private fun saveAcknowledged(state: EditorState, token: SaveToken): EditorState {
    require(token.sessionId == state.sessionId) {
        "Save token belongs to editor session ${token.sessionId.value}, not ${state.sessionId.value}."
    }
    require(token.documentId == state.documentId) {
        "Save token belongs to document ${token.documentId.value}, not ${state.documentId.value}."
    }
    val generation = token.generation
    require(generation <= state.editGeneration) {
        "Save generation $generation is in the future; current edit generation is " +
            "${state.editGeneration}."
    }
    val advanced = maxOf(state.savedGeneration, generation)
    if (advanced == state.savedGeneration) return state
    return state.with(savedGeneration = advanced)
}

/** Resolves the index of block [id], failing fast with [IllegalArgumentException] if absent. */
private fun requireBlockIndex(state: EditorState, id: BlockId): Int {
    val index = state.draftBlocks.indexOfFirst { it.id == id }
    require(index >= 0) { "Unknown block ID ${id.value}." }
    return index
}
