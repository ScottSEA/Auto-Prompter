package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.document.editor.EditorAction
import com.scottsea.autoprompter.core.document.editor.InvalidEditorDocumentException
import com.scottsea.autoprompter.core.document.editor.reduceDocumentEditor
import com.scottsea.autoprompter.core.document.editor.saveCandidate
import com.scottsea.autoprompter.core.document.toScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TracerEditorTest {

    @Test
    fun scenarioSelectionStartsEditorFromTheSameCanonicalDocument() {
        val model = selectScenario(initialTracerModel(), 1)

        assertEquals(model.document.id, model.editor.documentId)
        assertEquals(model.document.title, model.editor.title)
        assertEquals(model.document.blocks.map { it.id }, model.editor.blocks.map { it.id })
        assertEquals(0L, model.editor.editGeneration)
        assertFalse(model.editor.isDirty)
    }

    @Test
    fun editingThroughReducerActionsMarksTheEditorDirty() {
        val model = initialTracerModel()
        assertFalse(model.editor.isDirty)

        val edited = editTracer(model, EditorAction.ChangeTitle("Rewritten title"))

        assertTrue(edited.editor.isDirty)
        assertEquals("Rewritten title", edited.editor.title)
    }

    @Test
    fun applyToPromptRestartsPromptSessionFromTheEditedDocumentAndPreservesEditor() {
        val model = initialTracerModel()
        val firstBlock = model.editor.blocks.first().id
        val edited = editTracer(model, EditorAction.ChangeBlockText(firstBlock, "brand new opening line"))

        val applied = applyEditorToPrompt(edited)

        // The document was replaced with the validated draft...
        assertEquals("brand new opening line", applied.document.blocks.first().text)
        // ...and the prompt session was restarted from exactly that document's script.
        assertEquals(applied.document.toScript(), applied.session.script)
        // The editor draft is preserved untouched (apply does not acknowledge a save).
        assertEquals(edited.editor, applied.editor)
    }

    @Test
    fun anInvalidDraftIsNotApplicableAndTheSaveSeamRejectsIt() {
        val model = initialTracerModel()
        val firstBlock = model.editor.blocks.first().id
        val blanked = editTracer(model, EditorAction.ChangeBlockText(firstBlock, "   "))

        assertFalse(canApplyEditor(blanked))
        assertFailsWith<InvalidEditorDocumentException> { applyEditorToPrompt(blanked) }
    }

    @Test
    fun markSavedAcknowledgesTheCurrentGenerationAndReturnsToClean() {
        val edited = editTracer(initialTracerModel(), EditorAction.ChangeTitle("Renamed"))
        assertTrue(edited.editor.isDirty)

        val saved = markEditorSaved(edited)

        assertFalse(saved.editor.isDirty)
    }

    @Test
    fun invalidDraftCannotBeMarkedSaved() {
        val model = initialTracerModel()
        val firstBlock = model.editor.blocks.first().id
        val invalid = editTracer(model, EditorAction.ChangeBlockText(firstBlock, ""))

        assertFailsWith<InvalidEditorDocumentException> { markEditorSaved(invalid) }
        assertTrue(invalid.editor.isDirty)
    }

    @Test
    fun resetPromptingLeavesTheEditorDraftUntouched() {
        val edited = editTracer(initialTracerModel(), EditorAction.ChangeTitle("Draft in progress"))

        val reset = reset(edited)

        assertEquals(edited.editor, reset.editor)
    }

    @Test
    fun reopeningSameScenarioInvalidatesPreviousSaveTokens() {
        val firstOpen = initialTracerModel()
        val firstEdit = editTracer(firstOpen, EditorAction.ChangeTitle("First edit"))
        val oldToken = saveCandidate(firstEdit.editor).token

        val reopened = selectScenario(firstOpen, 0)
        val secondEdit = editTracer(reopened, EditorAction.ChangeTitle("Second edit"))

        assertFailsWith<IllegalArgumentException> {
            reduceDocumentEditor(secondEdit.editor, EditorAction.SaveAcknowledged(oldToken))
        }
    }
}
