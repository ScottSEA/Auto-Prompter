package com.scottsea.autoprompter.core.document.editor

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.CURRENT_SCHEMA_VERSION
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentEditorTest {

    private fun sampleDocument(): ScriptDocument = ScriptDocument(
        id = DocumentId("doc-1"),
        title = "Opening remarks",
        blocks = listOf(
            ScriptBlock(BlockId("b0"), ScriptBlockKind.Heading, "Welcome"),
            ScriptBlock(BlockId("b1"), ScriptBlockKind.Paragraph, "Thank you all for coming."),
            ScriptBlock(BlockId("b2"), ScriptBlockKind.Paragraph, "Let us begin."),
        ),
    )

    private fun start(
        document: ScriptDocument = sampleDocument(),
        session: String = "editor-test",
    ): EditorState = startDocumentEditor(document, EditorSessionId(session))

    // --- Cycle 1: start editor ---

    @Test
    fun startingFromDocumentCopiesTitleAndBlocksAtGenerationZeroCleanNoIssues() {
        val document = sampleDocument()

        val state = startDocumentEditor(document)

        assertEquals(CURRENT_SCHEMA_VERSION, state.schemaVersion)
        assertEquals(DocumentId("doc-1"), state.documentId)
        assertEquals("Opening remarks", state.title)
        assertEquals(
            listOf(BlockId("b0"), BlockId("b1"), BlockId("b2")),
            state.blocks.map { it.id },
        )
        assertEquals(ScriptBlockKind.Heading, state.blocks[0].kind)
        assertEquals("Thank you all for coming.", state.blocks[1].text)
        assertEquals(0L, state.editGeneration)
        assertEquals(0L, state.savedGeneration)
        assertFalse(state.isDirty)
        assertTrue(state.issues.isEmpty())
    }

    // --- Cycle 2: title and block text changes ---

    @Test
    fun changingTitleToBlankReportsIssueAndIncrementsGenerationOnce() {
        val state = startDocumentEditor(sampleDocument())

        val cleared = reduceDocumentEditor(state, EditorAction.ChangeTitle("   "))

        assertEquals("   ", cleared.title)
        assertEquals(1L, cleared.editGeneration)
        assertTrue(cleared.isDirty)
        assertEquals(listOf(EditorValidationIssue.BlankTitle), cleared.issues)
    }

    @Test
    fun effectiveTitleChangeIncrementsButRepeatingTheSameTitleIsANoOp() {
        val state = startDocumentEditor(sampleDocument())

        val renamed = reduceDocumentEditor(state, EditorAction.ChangeTitle("Closing remarks"))
        assertEquals("Closing remarks", renamed.title)
        assertEquals(1L, renamed.editGeneration)

        val again = reduceDocumentEditor(renamed, EditorAction.ChangeTitle("Closing remarks"))
        assertEquals(1L, again.editGeneration)
        assertEquals(renamed, again)
    }

    @Test
    fun changingBlockTextToBlankReportsTypedIssueForThatBlock() {
        val state = startDocumentEditor(sampleDocument())

        val cleared = reduceDocumentEditor(state, EditorAction.ChangeBlockText(BlockId("b1"), ""))

        assertEquals("", cleared.blocks[1].text)
        assertEquals(BlockId("b1"), cleared.blocks[1].id)
        assertEquals(1L, cleared.editGeneration)
        assertTrue(cleared.isDirty)
        assertEquals(listOf(EditorValidationIssue.BlankBlockText(BlockId("b1"))), cleared.issues)
    }

    @Test
    fun repeatingBlockTextIsANoOpButUnknownBlockFails() {
        val state = startDocumentEditor(sampleDocument())

        val same = reduceDocumentEditor(
            state,
            EditorAction.ChangeBlockText(BlockId("b1"), "Thank you all for coming."),
        )
        assertEquals(0L, same.editGeneration)
        assertEquals(state, same)

        assertFailsWith<IllegalArgumentException> {
            reduceDocumentEditor(state, EditorAction.ChangeBlockText(BlockId("missing"), "x"))
        }
    }

    // --- Cycle 3: block kind change ---

    @Test
    fun changingBlockKindPreservesIdAndTextAndIncrementsOnce() {
        val state = startDocumentEditor(sampleDocument())

        val changed = reduceDocumentEditor(
            state,
            EditorAction.ChangeBlockKind(BlockId("b1"), ScriptBlockKind.Heading),
        )
        assertEquals(ScriptBlockKind.Heading, changed.blocks[1].kind)
        assertEquals(BlockId("b1"), changed.blocks[1].id)
        assertEquals("Thank you all for coming.", changed.blocks[1].text)
        assertEquals(1L, changed.editGeneration)

        val again = reduceDocumentEditor(
            changed,
            EditorAction.ChangeBlockKind(BlockId("b1"), ScriptBlockKind.Heading),
        )
        assertEquals(1L, again.editGeneration)
        assertEquals(changed, again)
    }

    // --- Cycle 4: insert ---

    @Test
    fun insertAtStartMiddleEndPreservesCallerIdAndOrder() {
        val state = startDocumentEditor(sampleDocument())

        val atStart = reduceDocumentEditor(
            state,
            EditorAction.InsertBlock(0, paragraphDraft(BlockId("head"), "First!")),
        )
        assertEquals(
            listOf(BlockId("head"), BlockId("b0"), BlockId("b1"), BlockId("b2")),
            atStart.blocks.map { it.id },
        )
        assertEquals(1L, atStart.editGeneration)

        val atMiddle = reduceDocumentEditor(
            state,
            EditorAction.InsertBlock(1, paragraphDraft(BlockId("mid"), "Middle")),
        )
        assertEquals(
            listOf(BlockId("b0"), BlockId("mid"), BlockId("b1"), BlockId("b2")),
            atMiddle.blocks.map { it.id },
        )

        val atEnd = reduceDocumentEditor(
            state,
            EditorAction.InsertBlock(3, paragraphDraft(BlockId("tail"), "Last")),
        )
        assertEquals(
            listOf(BlockId("b0"), BlockId("b1"), BlockId("b2"), BlockId("tail")),
            atEnd.blocks.map { it.id },
        )
        assertEquals("Last", atEnd.blocks.last().text)
    }

    @Test
    fun insertRejectsDuplicateIdAndOutOfRangeIndices() {
        val state = startDocumentEditor(sampleDocument())

        assertFailsWith<IllegalArgumentException> {
            reduceDocumentEditor(state, EditorAction.InsertBlock(1, paragraphDraft(BlockId("b0"), "dup")))
        }
        assertFailsWith<IllegalArgumentException> {
            reduceDocumentEditor(state, EditorAction.InsertBlock(-1, paragraphDraft(BlockId("x"), "x")))
        }
        assertFailsWith<IllegalArgumentException> {
            reduceDocumentEditor(state, EditorAction.InsertBlock(4, paragraphDraft(BlockId("x"), "x")))
        }
    }

    // --- Cycle 5: delete ---

    @Test
    fun deleteByIdPreservesRemainingOrderAndUnknownIdFails() {
        val state = startDocumentEditor(sampleDocument())

        val deleted = reduceDocumentEditor(state, EditorAction.DeleteBlock(BlockId("b1")))
        assertEquals(listOf(BlockId("b0"), BlockId("b2")), deleted.blocks.map { it.id })
        assertEquals(1L, deleted.editGeneration)
        assertTrue(deleted.issues.isEmpty())

        assertFailsWith<IllegalArgumentException> {
            reduceDocumentEditor(state, EditorAction.DeleteBlock(BlockId("missing")))
        }
    }

    @Test
    fun deletingTheLastBlockYieldsNoBlocksIssueNotAnException() {
        val single = ScriptDocument(
            id = DocumentId("doc-solo"),
            title = "Solo",
            blocks = listOf(ScriptBlock(BlockId("only"), ScriptBlockKind.Paragraph, "one")),
        )
        val state = startDocumentEditor(single)

        val emptied = reduceDocumentEditor(state, EditorAction.DeleteBlock(BlockId("only")))

        assertTrue(emptied.blocks.isEmpty())
        assertEquals(1L, emptied.editGeneration)
        assertEquals(listOf(EditorValidationIssue.NoBlocks), emptied.issues)
    }

    // --- Cycle 6: move (targetIndex = final resting index in 0..lastIndex) ---

    @Test
    fun moveHandlesForwardBackwardAndNoOpWithoutSpuriousGeneration() {
        val state = startDocumentEditor(sampleDocument())

        val forward = reduceDocumentEditor(state, EditorAction.MoveBlock(BlockId("b0"), 2))
        assertEquals(listOf(BlockId("b1"), BlockId("b2"), BlockId("b0")), forward.blocks.map { it.id })
        assertEquals(1L, forward.editGeneration)

        val backward = reduceDocumentEditor(state, EditorAction.MoveBlock(BlockId("b2"), 0))
        assertEquals(listOf(BlockId("b2"), BlockId("b0"), BlockId("b1")), backward.blocks.map { it.id })

        val sameSpot = reduceDocumentEditor(state, EditorAction.MoveBlock(BlockId("b1"), 1))
        assertEquals(0L, sameSpot.editGeneration)
        assertEquals(state, sameSpot)
    }

    @Test
    fun moveRejectsUnknownIdAndOutOfRangeTarget() {
        val state = startDocumentEditor(sampleDocument())

        assertFailsWith<IllegalArgumentException> {
            reduceDocumentEditor(state, EditorAction.MoveBlock(BlockId("missing"), 0))
        }
        assertFailsWith<IllegalArgumentException> {
            reduceDocumentEditor(state, EditorAction.MoveBlock(BlockId("b0"), -1))
        }
        assertFailsWith<IllegalArgumentException> {
            reduceDocumentEditor(state, EditorAction.MoveBlock(BlockId("b0"), 3))
        }
    }

    // --- Cycle 7: documentForSave round trip and typed rejection ---

    @Test
    fun documentForSaveRoundTripsValidDraftToCanonicalDocument() {
        val document = sampleDocument()
        val state = startDocumentEditor(document)

        assertEquals(document, documentForSave(state))

        val candidate = saveCandidate(state)
        assertEquals(0L, candidate.generation)
        assertEquals(document, candidate.document)
    }

    @Test
    fun documentForSaveRejectsInvalidDraftWithTypedIssues() {
        val state = startDocumentEditor(sampleDocument())
        val blankTitle = reduceDocumentEditor(state, EditorAction.ChangeTitle(""))
        val alsoBlankBlock = reduceDocumentEditor(
            blankTitle,
            EditorAction.ChangeBlockText(BlockId("b1"), "  "),
        )

        val error = assertFailsWith<InvalidEditorDocumentException> { documentForSave(alsoBlankBlock) }
        assertEquals(
            listOf(
                EditorValidationIssue.BlankTitle,
                EditorValidationIssue.BlankBlockText(BlockId("b1")),
            ),
            error.issues,
        )
        assertFailsWith<InvalidEditorDocumentException> { saveCandidate(alsoBlankBlock) }
    }

    // --- Cycle 8: save acknowledgement ---

    @Test
    fun acknowledgingCurrentGenerationMarksClean() {
        val state = startDocumentEditor(sampleDocument())
        val edited = reduceDocumentEditor(state, EditorAction.ChangeTitle("Renamed"))
        assertTrue(edited.isDirty)
        val candidate = saveCandidate(edited)

        val acknowledged = reduceDocumentEditor(
            edited,
            EditorAction.SaveAcknowledged(candidate.token),
        )
        assertEquals(1L, acknowledged.savedGeneration)
        assertFalse(acknowledged.isDirty)
    }

    @Test
    fun staleAcknowledgementAfterANewerEditLeavesDraftDirtyButAdvancesMonotonically() {
        val state = startDocumentEditor(sampleDocument())
        val gen1 = reduceDocumentEditor(state, EditorAction.ChangeTitle("First"))
        val candidate1 = saveCandidate(gen1)
        val gen2 = reduceDocumentEditor(gen1, EditorAction.ChangeTitle("Second"))

        // Acknowledging the older generation 1 advances savedGeneration from 0 to 1 but the
        // generation-2 edit is still unsaved.
        val stale = reduceDocumentEditor(gen2, EditorAction.SaveAcknowledged(candidate1.token))
        assertEquals(1L, stale.savedGeneration)
        assertEquals(2L, stale.editGeneration)
        assertTrue(stale.isDirty)
    }

    @Test
    fun foreignAcknowledgementsFailAndSavedGenerationNeverRegresses() {
        val state = startDocumentEditor(sampleDocument())
        val gen1 = reduceDocumentEditor(state, EditorAction.ChangeTitle("First"))
        val candidate1 = saveCandidate(gen1)
        val gen2 = reduceDocumentEditor(gen1, EditorAction.ChangeTitle("Second"))
        val candidate2 = saveCandidate(gen2)

        val savedTwo = reduceDocumentEditor(
            gen2,
            EditorAction.SaveAcknowledged(candidate2.token),
        )
        assertEquals(2L, savedTwo.savedGeneration)
        // An older ack must not decrease savedGeneration; it is a no-op.
        val stillTwo = reduceDocumentEditor(
            savedTwo,
            EditorAction.SaveAcknowledged(candidate1.token),
        )
        assertEquals(2L, stillTwo.savedGeneration)
        assertEquals(savedTwo, stillTwo)

        val otherSession = reduceDocumentEditor(
            startDocumentEditor(sampleDocument(), EditorSessionId("other-session")),
            EditorAction.ChangeTitle("Other"),
        )
        val otherToken = saveCandidate(otherSession).token
        assertFailsWith<IllegalArgumentException> {
            reduceDocumentEditor(gen2, EditorAction.SaveAcknowledged(otherToken))
        }
    }

    @Test
    fun saveCompletionFromReplacedDocumentCannotCleanCurrentDocument() {
        val documentA = sampleDocument()
        val editedA = reduceDocumentEditor(
            startDocumentEditor(documentA),
            EditorAction.ChangeTitle("A edited"),
        )
        val saveA = saveCandidate(editedA)

        val documentB = ScriptDocument(
            id = DocumentId("doc-b"),
            title = "Document B",
            blocks = listOf(ScriptBlock(BlockId("b-block"), ScriptBlockKind.Paragraph, "B text")),
        )
        val replaced = reduceDocumentEditor(
            editedA,
            EditorAction.ReplaceFromDocument(documentB, EditorSessionId("session-b")),
        )
        val editedB = reduceDocumentEditor(replaced, EditorAction.ChangeTitle("B edited"))

        assertFailsWith<IllegalArgumentException> {
            reduceDocumentEditor(editedB, EditorAction.SaveAcknowledged(saveA.token))
        }
        assertTrue(editedB.isDirty)
        assertEquals(0L, editedB.savedGeneration)
    }

    // --- Cycle 9: defensive aliasing, overflow guard, reload ---

    @Test
    fun sourceAndExposedListsCannotMutateEditorState() {
        val source = mutableListOf(
            BlockDraft(BlockId("b0"), ScriptBlockKind.Paragraph, "one"),
            BlockDraft(BlockId("b1"), ScriptBlockKind.Paragraph, "two"),
        )
        val state = EditorState(
            sessionId = EditorSessionId("alias-test"),
            schemaVersion = CURRENT_SCHEMA_VERSION,
            documentId = DocumentId("doc-alias"),
            title = "Alias",
            blocks = source,
            editGeneration = 0L,
            savedGeneration = 0L,
        )

        source.clear()
        runCatching { (state.blocks as MutableList<BlockDraft>).clear() }

        assertEquals(listOf(BlockId("b0"), BlockId("b1")), state.blocks.map { it.id })
    }

    @Test
    fun effectiveEditAtGenerationCeilingFailsExplicitly() {
        val ceiling = EditorState(
            sessionId = EditorSessionId("ceiling-test"),
            schemaVersion = CURRENT_SCHEMA_VERSION,
            documentId = DocumentId("doc-max"),
            title = "Max",
            blocks = listOf(BlockDraft(BlockId("b0"), ScriptBlockKind.Paragraph, "one")),
            editGeneration = Long.MAX_VALUE,
            savedGeneration = Long.MAX_VALUE,
        )

        assertFailsWith<IllegalStateException> {
            reduceDocumentEditor(ceiling, EditorAction.ChangeTitle("changed"))
        }
    }

    @Test
    fun replaceFromDocumentReloadsFreshCleanEditor() {
        val edited = reduceDocumentEditor(
            startDocumentEditor(sampleDocument()),
            EditorAction.ChangeTitle("Dirty"),
        )
        assertTrue(edited.isDirty)

        val other = ScriptDocument(
            id = DocumentId("doc-2"),
            title = "Second document",
            blocks = listOf(ScriptBlock(BlockId("z0"), ScriptBlockKind.Paragraph, "fresh")),
        )
        val reloaded = reduceDocumentEditor(
            edited,
            EditorAction.ReplaceFromDocument(other, EditorSessionId("replacement-session")),
        )

        assertEquals(DocumentId("doc-2"), reloaded.documentId)
        assertEquals("Second document", reloaded.title)
        assertEquals(listOf(BlockId("z0")), reloaded.blocks.map { it.id })
        assertEquals(0L, reloaded.editGeneration)
        assertEquals(0L, reloaded.savedGeneration)
        assertFalse(reloaded.isDirty)
    }
}
