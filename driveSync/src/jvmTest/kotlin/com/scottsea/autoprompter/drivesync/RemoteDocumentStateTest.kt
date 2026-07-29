package com.scottsea.autoprompter.drivesync

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RemoteDocumentStateTest {
    @Test
    fun singleLiveHeadMaterializesDocument() {
        val document = document("Current")
        val revision = createLiveRevision(document, emptySet(), Sha256ContentDigester)

        val state = resolveRemoteDocuments(listOf(revision))

        val live = assertIs<RemoteDocumentState.Live>(state.getValue(document.id))
        assertEquals(revision.id, live.revisionId)
        assertEquals(document, live.document)
    }

    @Test
    fun tombstoneHeadMaterializesDeletedState() {
        val document = document("Current")
        val live = createLiveRevision(document, emptySet(), Sha256ContentDigester)
        val tombstone =
            createTombstoneRevision(document.id, setOf(live.id), Sha256ContentDigester)

        val state = resolveRemoteDocuments(listOf(live, tombstone))

        assertEquals(
            RemoteDocumentState.Deleted(tombstone.id),
            state.getValue(document.id),
        )
    }

    @Test
    fun divergentHeadsMaterializeConflictWithoutGuessingAWinner() {
        val root = createLiveRevision(document("Root"), emptySet(), Sha256ContentDigester)
        val left = createLiveRevision(document("Left"), setOf(root.id), Sha256ContentDigester)
        val right = createLiveRevision(document("Right"), setOf(root.id), Sha256ContentDigester)

        val state = resolveRemoteDocuments(listOf(root, left, right))

        assertEquals(
            RemoteDocumentState.Conflict(sortedSetOf(left.id, right.id)),
            state.getValue(DocumentId("document")),
        )
    }

    private fun document(title: String): ScriptDocument =
        ScriptDocument(
            id = DocumentId("document"),
            title = title,
            blocks =
                listOf(
                    ScriptBlock(
                        BlockId("paragraph"),
                        ScriptBlockKind.Paragraph,
                        "Body",
                    ),
                ),
        )
}
