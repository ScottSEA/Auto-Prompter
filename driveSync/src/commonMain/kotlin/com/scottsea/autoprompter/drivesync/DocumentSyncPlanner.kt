package com.scottsea.autoprompter.drivesync

import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.store.DocumentState
import com.scottsea.autoprompter.core.document.store.SavePrecondition
import com.scottsea.autoprompter.core.document.store.StoreGeneration
import com.scottsea.autoprompter.core.document.store.StoredDocument

/** The exact local/remote pair that was known converged after the last successful sync. */
class DocumentSyncAnchor(
    val localGeneration: StoreGeneration?,
    remoteHeads: Collection<RemoteRevisionId>,
) {
    private val remoteHeadSnapshot = remoteHeads.distinct().sorted()
    val remoteHeads: Set<RemoteRevisionId> get() = remoteHeadSnapshot.toSet()

    init {
        require(remoteHeadSnapshot.isNotEmpty()) { "A sync anchor requires at least one remote head." }
    }

    override fun equals(other: Any?): Boolean =
        other is DocumentSyncAnchor &&
            localGeneration == other.localGeneration &&
            remoteHeadSnapshot == other.remoteHeadSnapshot

    override fun hashCode(): Int = 31 * (localGeneration?.hashCode() ?: 0) + remoteHeadSnapshot.hashCode()
}

enum class SyncConflictReason {
    InitialDivergence,
    ConcurrentChanges,
    RemoteConflict,
    RemoteHistoryMissing,
}

sealed interface DocumentSyncPlan {
    data class NoChange(val anchor: DocumentSyncAnchor?) : DocumentSyncPlan
    data class Converged(val anchor: DocumentSyncAnchor) : DocumentSyncPlan

    class UploadLive(
        val snapshot: StoredDocument,
        parents: Collection<RemoteRevisionId>,
    ) : DocumentSyncPlan {
        private val parentSnapshot = parents.distinct().sorted()
        val parents: Set<RemoteRevisionId> get() = parentSnapshot.toSet()

        override fun equals(other: Any?): Boolean =
            other is UploadLive && snapshot == other.snapshot && parentSnapshot == other.parentSnapshot

        override fun hashCode(): Int = 31 * snapshot.hashCode() + parentSnapshot.hashCode()
    }

    class UploadTombstone(
        val documentId: DocumentId,
        val localGeneration: StoreGeneration,
        parents: Collection<RemoteRevisionId>,
    ) : DocumentSyncPlan {
        private val parentSnapshot = parents.distinct().sorted()
        val parents: Set<RemoteRevisionId> get() = parentSnapshot.toSet()

        override fun equals(other: Any?): Boolean =
            other is UploadTombstone &&
                documentId == other.documentId &&
                localGeneration == other.localGeneration &&
                parentSnapshot == other.parentSnapshot

        override fun hashCode(): Int {
            var result = documentId.hashCode()
            result = 31 * result + localGeneration.hashCode()
            result = 31 * result + parentSnapshot.hashCode()
            return result
        }
    }

    data class ApplyRemoteLive(
        val remoteRevisionId: RemoteRevisionId,
        val document: ScriptDocument,
        val localPrecondition: SavePrecondition,
    ) : DocumentSyncPlan

    data class ApplyRemoteDelete(
        val remoteRevisionId: RemoteRevisionId,
        val expectedLiveGeneration: StoreGeneration,
    ) : DocumentSyncPlan

    class Conflict(
        val documentId: DocumentId,
        remoteHeads: Collection<RemoteRevisionId>,
        val reason: SyncConflictReason,
    ) : DocumentSyncPlan {
        private val remoteHeadSnapshot = remoteHeads.distinct().sorted()
        val remoteHeads: Set<RemoteRevisionId> get() = remoteHeadSnapshot.toSet()

        override fun equals(other: Any?): Boolean =
            other is Conflict &&
                documentId == other.documentId &&
                remoteHeadSnapshot == other.remoteHeadSnapshot &&
                reason == other.reason

        override fun hashCode(): Int {
            var result = documentId.hashCode()
            result = 31 * result + remoteHeadSnapshot.hashCode()
            result = 31 * result + reason.hashCode()
            return result
        }
    }
}

/**
 * Plans one document sync without clocks, remote CAS, or hidden conflict resolution.
 *
 * The caller applies the returned operation with the local DocumentStore's optimistic preconditions,
 * publishes immutable revisions remotely, and records a new [DocumentSyncAnchor] only after success.
 */
fun planDocumentSync(
    local: DocumentState,
    remote: RemoteDocumentState?,
    anchor: DocumentSyncAnchor?,
): DocumentSyncPlan {
    val documentId = local.documentId()
    if (remote is RemoteDocumentState.Conflict) {
        return DocumentSyncPlan.Conflict(
            documentId = documentId,
            remoteHeads = remote.headIds,
            reason = SyncConflictReason.RemoteConflict,
        )
    }
    remote?.requireDocumentId(documentId)

    val localGeneration = local.generation()
    val remoteHeads = remote.headIds()

    if (anchor == null) {
        return initialSyncPlan(local, remote, documentId, localGeneration, remoteHeads)
    }

    val localChanged = localGeneration != anchor.localGeneration
    val remoteChanged = remoteHeads != anchor.remoteHeads
    return when {
        !localChanged && !remoteChanged -> DocumentSyncPlan.NoChange(anchor)
        sameState(local, remote) ->
            DocumentSyncPlan.Converged(
                DocumentSyncAnchor(localGeneration, remoteHeads),
            )
        localChanged && !remoteChanged -> localUploadPlan(local, remoteHeads)
        !localChanged && remoteChanged ->
            if (remote == null) {
                DocumentSyncPlan.Conflict(
                    documentId,
                    emptySet(),
                    SyncConflictReason.RemoteHistoryMissing,
                )
            } else {
                applyRemotePlan(local, remote)
            }
        else ->
            DocumentSyncPlan.Conflict(
                documentId,
                remoteHeads,
                SyncConflictReason.ConcurrentChanges,
            )
    }
}

private fun initialSyncPlan(
    local: DocumentState,
    remote: RemoteDocumentState?,
    documentId: DocumentId,
    localGeneration: StoreGeneration?,
    remoteHeads: Set<RemoteRevisionId>,
): DocumentSyncPlan =
    when {
        remote == null ->
            when (local) {
                is DocumentState.Live -> DocumentSyncPlan.UploadLive(local.snapshot, emptySet())
                is DocumentState.Missing ->
                    local.lastGeneration?.let { generation ->
                        DocumentSyncPlan.UploadTombstone(documentId, generation, emptySet())
                    } ?: DocumentSyncPlan.NoChange(anchor = null)
            }
        local is DocumentState.Missing && local.lastGeneration == null ->
            when (remote) {
                is RemoteDocumentState.Live ->
                    DocumentSyncPlan.ApplyRemoteLive(
                        remote.revisionId,
                        remote.document,
                        SavePrecondition.MatchesMissing(lastGeneration = null),
                    )
                is RemoteDocumentState.Deleted ->
                    DocumentSyncPlan.Converged(
                        DocumentSyncAnchor(localGeneration = null, remoteHeads = remoteHeads),
                    )
                is RemoteDocumentState.Conflict -> error("Handled before initial planning.")
            }
        sameState(local, remote) ->
            DocumentSyncPlan.Converged(DocumentSyncAnchor(localGeneration, remoteHeads))
        else ->
            DocumentSyncPlan.Conflict(
                documentId,
                remoteHeads,
                SyncConflictReason.InitialDivergence,
            )
    }

private fun localUploadPlan(
    local: DocumentState,
    remoteHeads: Set<RemoteRevisionId>,
): DocumentSyncPlan =
    when (local) {
        is DocumentState.Live ->
            DocumentSyncPlan.UploadLive(local.snapshot, remoteHeads)
        is DocumentState.Missing ->
            DocumentSyncPlan.UploadTombstone(
                documentId = local.id,
                localGeneration =
                    requireNotNull(local.lastGeneration) {
                        "A changed local deletion must carry a tombstone generation."
                    },
                parents = remoteHeads,
            )
    }

private fun applyRemotePlan(
    local: DocumentState,
    remote: RemoteDocumentState,
): DocumentSyncPlan =
    when (remote) {
        is RemoteDocumentState.Live ->
            DocumentSyncPlan.ApplyRemoteLive(
                remoteRevisionId = remote.revisionId,
                document = remote.document,
                localPrecondition =
                    when (local) {
                        is DocumentState.Live ->
                            SavePrecondition.Matches(local.snapshot.generation)
                        is DocumentState.Missing ->
                            SavePrecondition.MatchesMissing(local.lastGeneration)
                    },
            )
        is RemoteDocumentState.Deleted ->
            DocumentSyncPlan.ApplyRemoteDelete(
                remoteRevisionId = remote.revisionId,
                expectedLiveGeneration =
                    requireNotNull((local as? DocumentState.Live)?.snapshot?.generation) {
                        "Remote deletion can only be applied to a live local document."
                    },
            )
        is RemoteDocumentState.Conflict -> error("Remote conflict cannot be applied automatically.")
    }

private fun sameState(local: DocumentState, remote: RemoteDocumentState?): Boolean =
    when {
        local is DocumentState.Live && remote is RemoteDocumentState.Live ->
            local.snapshot.document == remote.document
        local is DocumentState.Missing && remote is RemoteDocumentState.Deleted -> true
        else -> false
    }

private fun DocumentState.documentId(): DocumentId =
    when (this) {
        is DocumentState.Live -> snapshot.document.id
        is DocumentState.Missing -> id
    }

private fun DocumentState.generation(): StoreGeneration? =
    when (this) {
        is DocumentState.Live -> snapshot.generation
        is DocumentState.Missing -> lastGeneration
    }

private fun RemoteDocumentState?.headIds(): Set<RemoteRevisionId> =
    when (this) {
        null -> emptySet()
        is RemoteDocumentState.Live -> setOf(revisionId)
        is RemoteDocumentState.Deleted -> setOf(revisionId)
        is RemoteDocumentState.Conflict -> headIds
    }

private fun RemoteDocumentState.requireDocumentId(expected: DocumentId) {
    if (this is RemoteDocumentState.Live) {
        require(document.id == expected) {
            "Local and remote document ids differ: ${document.id.value} != ${expected.value}."
        }
    }
}
