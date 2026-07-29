package com.scottsea.autoprompter.drivesync

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SyncTransportTest {
    @Test
    fun publishingSameImmutableRevisionTwiceIsIdempotent() = runTest {
        val transport = FakeSyncTransport()
        val protocol = DriveSyncProtocol(JvmSha256Digester)
        val revision =
            createLiveRevision(
                ScriptDocument(
                    id = DocumentId("document"),
                    title = "Title",
                    blocks =
                        listOf(
                            ScriptBlock(
                                BlockId("paragraph"),
                                ScriptBlockKind.Paragraph,
                                "Body",
                            ),
                        ),
                ),
                emptySet(),
                JvmSha256Digester,
            )

        val first = protocol.publishRevision(transport, revision)
        val second = protocol.publishRevision(transport, revision)
        val scanned = protocol.scanRevisionHeads(transport)

        assertIs<CreateImmutableResult.Created>(first)
        assertIs<CreateImmutableResult.AlreadyExists>(second)
        assertEquals(1, transport.objects.size)
        assertEquals(setOf(revision.id), scanned.heads.getValue(DocumentId("document")))
    }

    @Test
    fun manifestIsImmutableCacheAndRevisionScanRemainsAuthoritative() = runTest {
        val transport = FakeSyncTransport()
        val protocol = DriveSyncProtocol(Sha256ContentDigester)
        val root =
            createLiveRevision(
                ScriptDocument(
                    id = DocumentId("document"),
                    title = "Root",
                    blocks =
                        listOf(
                            ScriptBlock(
                                BlockId("paragraph"),
                                ScriptBlockKind.Paragraph,
                                "Body",
                            ),
                        ),
                ),
                emptySet(),
                Sha256ContentDigester,
            )
        protocol.publishRevision(transport, root)
        val heads = protocol.scanRevisionHeads(transport)

        val first = protocol.publishManifest(transport, heads)
        val second = protocol.publishManifest(transport, heads)

        assertIs<CreateImmutableResult.Created>(first)
        assertIs<CreateImmutableResult.AlreadyExists>(second)
        assertEquals(2, transport.objects.size)
        assertEquals(heads.heads, protocol.scanRevisionHeads(transport).heads)
    }

    @Test
    fun duplicateDriveFilesWithSameContentAddressRemainOneLogicalRevision() = runTest {
        val transport = FakeSyncTransport()
        val protocol = DriveSyncProtocol(Sha256ContentDigester)
        val revision =
            createTombstoneRevision(
                DocumentId("document"),
                emptySet(),
                Sha256ContentDigester,
            )
        protocol.publishRevision(transport, revision)
        transport.duplicateFirstObject()

        val scanned = protocol.scanRevisionHeads(transport)

        assertEquals(2, transport.objects.size)
        assertEquals(setOf(revision.id), scanned.heads.getValue(DocumentId("document")))
    }
}

private class FakeSyncTransport : SyncTransport {
    data class StoredObject(
        val objectId: String,
        val name: String,
        val content: String,
    )

    val objects = linkedMapOf<String, StoredObject>()
    private var serial = 0

    override suspend fun list(prefix: String): List<RemoteObjectMeta> =
        objects.values.filter { it.name.startsWith(prefix) }.sortedBy { it.objectId }.map { stored ->
            RemoteObjectMeta(objectId = stored.objectId, name = stored.name)
        }

    override suspend fun read(meta: RemoteObjectMeta): String? = objects[meta.objectId]?.content

    override suspend fun createImmutable(name: String, content: String): CreateImmutableResult {
        val existing = objects.values.firstOrNull { it.name == name }
        return if (existing == null) {
            val objectId = "object-${++serial}"
            objects[objectId] = StoredObject(objectId, name, content)
            CreateImmutableResult.Created(RemoteObjectMeta(objectId, name))
        } else {
            require(existing.content == content) { "Immutable object name collision." }
            CreateImmutableResult.AlreadyExists(RemoteObjectMeta(existing.objectId, name))
        }
    }

    fun duplicateFirstObject() {
        val existing = objects.values.first()
        val objectId = "object-${++serial}"
        objects[objectId] = existing.copy(objectId = objectId)
    }
}
