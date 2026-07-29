package com.scottsea.autoprompter.drivesync

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteRevisionGraphTest {
    private val digest =
        ContentDigester { text ->
            MessageDigest.getInstance("SHA-256")
                .digest(text.encodeToByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }

    @Test
    fun concurrentBranchesSurviveUntilExplicitMergeRevision() {
        val root = createLiveRevision(document("Root"), emptySet(), digest)
        val left = createLiveRevision(document("Left edit"), setOf(root.id), digest)
        val right = createLiveRevision(document("Right edit"), setOf(root.id), digest)

        val conflicted = reconstructRemoteHeads(listOf(root, left, right))

        assertEquals(
            setOf(left.id, right.id),
            conflicted.heads.getValue(DocumentId("document")),
        )

        val merge =
            createLiveRevision(
                document("Resolved edit"),
                setOf(left.id, right.id),
                digest,
            )
        val resolved = reconstructRemoteHeads(listOf(root, left, right, merge))

        assertEquals(setOf(merge.id), resolved.heads.getValue(DocumentId("document")))
    }

    @Test
    fun corruptCycleIsRejectedInsteadOfAppearingAsAnEmptyRemote() {
        val firstId = RemoteRevisionId("a".repeat(64))
        val secondId = RemoteRevisionId("b".repeat(64))
        val first =
            RemoteRevision(
                id = firstId,
                documentId = DocumentId("document"),
                parents = listOf(secondId),
                kind = RemoteRevisionKind.Tombstone,
                documentJson = null,
            )
        val second =
            RemoteRevision(
                id = secondId,
                documentId = DocumentId("document"),
                parents = listOf(firstId),
                kind = RemoteRevisionKind.Tombstone,
                documentJson = null,
            )

        assertFailsWith<IllegalArgumentException> {
            reconstructRemoteHeads(listOf(first, second))
        }
    }

    @Test
    fun exposedCollectionsCannotMutateContentAddressedSnapshots() {
        val parent = RemoteRevisionId("a".repeat(64))
        val revision = createLiveRevision(document("Snapshot"), setOf(parent), digest)
        @Suppress("UNCHECKED_CAST")
        val exposedParents = revision.parents as MutableList<RemoteRevisionId>
        runCatching { exposedParents.clear() }

        val heads = RemoteHeads(mapOf(DocumentId("document") to setOf(revision.id)))
        @Suppress("UNCHECKED_CAST")
        val exposedHeads = heads.heads as MutableMap<DocumentId, Set<RemoteRevisionId>>
        runCatching { exposedHeads.clear() }

        assertEquals(listOf(parent), revision.parents)
        assertEquals(setOf(revision.id), heads.heads.getValue(DocumentId("document")))
    }

    private fun document(title: String): ScriptDocument =
        ScriptDocument(
            id = DocumentId("document"),
            title = title,
            blocks =
                listOf(
                    ScriptBlock(
                        id = BlockId("paragraph"),
                        kind = ScriptBlockKind.Paragraph,
                        text = "A stable script body.",
                    ),
                ),
        )
}
