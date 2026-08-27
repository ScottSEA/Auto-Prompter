package com.scottsea.autoprompter.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceLayoutTest {
    @Test
    fun expandedWorkspaceRequiresTabletWidthAndEnoughHeightForThePrompt() {
        assertFalse(isExpandedWorkspace(899.dp, 800.dp))
        assertFalse(isExpandedWorkspace(900.dp, 599.dp))
        assertTrue(isExpandedWorkspace(900.dp, 600.dp))
        assertTrue(isExpandedWorkspace(1_440.dp, 1_000.dp))
    }
}
