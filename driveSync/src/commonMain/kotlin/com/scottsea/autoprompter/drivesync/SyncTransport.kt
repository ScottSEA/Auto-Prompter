package com.scottsea.autoprompter.drivesync

data class RemoteObjectMeta(
    val objectId: String,
    val name: String,
) {
    init {
        require(objectId.isNotBlank()) { "Remote object id must not be blank." }
        require(name.isNotBlank()) { "Remote object name must not be blank." }
    }
}

sealed interface CreateImmutableResult {
    data class Created(val meta: RemoteObjectMeta) : CreateImmutableResult
    data class AlreadyExists(val meta: RemoteObjectMeta) : CreateImmutableResult
}

/**
 * Minimal remote object seam implementable with Drive App Data or an in-memory contract fake.
 *
 * Immutable creation is the correctness primitive; no compare-and-swap overwrite is assumed.
 */
interface SyncTransport {
    suspend fun list(prefix: String): List<RemoteObjectMeta>
    suspend fun read(meta: RemoteObjectMeta): String?
    suspend fun createImmutable(name: String, content: String): CreateImmutableResult
}

class DriveSyncProtocol(private val digester: ContentDigester) {
    private val codec = RemoteRevisionCodec(digester)
    private val manifestCodec = RemoteManifestCodec(digester)

    suspend fun publishRevision(
        transport: SyncTransport,
        revision: RemoteRevision,
    ): CreateImmutableResult =
        transport.createImmutable(
            name = revisionObjectName(revision.id),
            content = codec.encode(revision),
        )

    suspend fun scanRevisionHeads(transport: SyncTransport): RemoteHeads {
        val revisions =
            transport.list(REVISION_PREFIX)
                .sortedBy(RemoteObjectMeta::name)
                .map { meta ->
                    require(meta.name.matches(REVISION_NAME_PATTERN)) {
                        "Unexpected Drive revision object name: ${meta.name}."
                    }
                    val content =
                        requireNotNull(transport.read(meta)) {
                            "Drive revision ${meta.objectId} (${meta.name}) disappeared while scanning."
                        }
                    codec.decode(content)
                }
        return reconstructRemoteHeads(revisions)
    }

    suspend fun publishManifest(
        transport: SyncTransport,
        heads: RemoteHeads,
    ): CreateImmutableResult {
        val manifest = createRemoteManifest(heads, digester)
        return transport.createImmutable(
            name = "$MANIFEST_PREFIX${manifest.id.value}.json",
            content = manifestCodec.encode(manifest),
        )
    }

    private fun revisionObjectName(id: RemoteRevisionId): String =
        "$REVISION_PREFIX${id.value}.json"

    private companion object {
        const val REVISION_PREFIX = "rev.v1."
        const val MANIFEST_PREFIX = "manifest.v1."
        val REVISION_NAME_PATTERN = Regex("rev\\.v1\\.[0-9a-f]{64}\\.json")
    }
}
