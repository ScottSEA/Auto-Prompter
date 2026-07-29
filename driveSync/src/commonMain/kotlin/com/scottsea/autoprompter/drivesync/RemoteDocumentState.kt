package com.scottsea.autoprompter.drivesync

import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.decodeScriptDocument

sealed interface RemoteDocumentState {
    data class Live(
        val revisionId: RemoteRevisionId,
        val document: ScriptDocument,
    ) : RemoteDocumentState

    data class Deleted(val revisionId: RemoteRevisionId) : RemoteDocumentState
    class Conflict(headIds: Collection<RemoteRevisionId>) : RemoteDocumentState {
        private val headSnapshot = headIds.distinct().sorted()
        val headIds: Set<RemoteRevisionId> get() = headSnapshot.toSet()

        init {
            require(headSnapshot.size > 1) { "Remote conflict requires at least two heads." }
        }

        override fun equals(other: Any?): Boolean =
            other is Conflict && headSnapshot == other.headSnapshot

        override fun hashCode(): Int = headSnapshot.hashCode()
    }
}

/** Materializes document states without auto-merging or choosing among divergent remote heads. */
fun resolveRemoteDocuments(
    revisions: Collection<RemoteRevision>,
): Map<DocumentId, RemoteDocumentState> {
    val heads = reconstructRemoteHeads(revisions).heads
    val byId = revisions.associateBy(RemoteRevision::id)
    return heads.entries
        .sortedBy { it.key.value }
        .associate { (documentId, headIds) ->
            val state =
                if (headIds.size > 1) {
                    RemoteDocumentState.Conflict(headIds)
                } else {
                    val head = byId.getValue(headIds.single())
                    when (head.kind) {
                        RemoteRevisionKind.Tombstone ->
                            RemoteDocumentState.Deleted(head.id)
                        RemoteRevisionKind.Live -> {
                            val document = decodeScriptDocument(requireNotNull(head.documentJson))
                            require(document.id == documentId) {
                                "Remote revision payload document id does not match its envelope."
                            }
                            RemoteDocumentState.Live(head.id, document)
                        }
                    }
                }
            documentId to state
        }
}
