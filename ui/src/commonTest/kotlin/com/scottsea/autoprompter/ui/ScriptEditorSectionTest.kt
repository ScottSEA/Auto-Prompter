package com.scottsea.autoprompter.ui

import com.scottsea.autoprompter.core.document.editor.EditorAction
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptEditorSectionTest {
    @Test
    fun openingAnotherScriptConfirmsOnlyWhenTheDraftIsDirty() {
        val clean = initialTracerModel().editor
        val dirty =
            editTracer(
                initialTracerModel(),
                EditorAction.ChangeTitle("Edited title"),
            ).editor

        assertFalse(shouldConfirmOpenScript(clean))
        assertTrue(shouldConfirmOpenScript(dirty))
    }
}
