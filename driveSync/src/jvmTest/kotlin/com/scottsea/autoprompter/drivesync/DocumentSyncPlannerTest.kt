package com.scottsea.autoprompter.drivesync

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.store.DocumentState
import com.scottsea.autoprompter.core.document.store.SavePrecondition
import com.scottsea.autoprompter.core.document.store.StoreGeneration
import com.scottsea.autoprompter.core.document.store.StoredDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DocumentSyncPlannerTest {
    private val documentId = DocumentId("document")
    private val rootRevision = RemoteRevisionId("a".repeat(64))
    private val remoteRevision = RemoteRevisionId("b".repeat(64))

    @Test
    fun firstLocalDocumentUploadsAsRootRevision() {
        val local = live("Local", generation = 1)

        val plan = planDocumentSync(local, remote = null, anchor = null)

        val upload = assertIs<DocumentSyncPlan.UploadLive>(plan)
        assertEquals(emptySet(), upload.parents)
        assertEquals(local.snapshot, upload.snapshot)
    }

    @Test
    fun firstRemoteDocumentAppliesLocally() {
        val remoteDocument = document("Remote")

        val plan =
            planDocumentSync(
                local = DocumentState.Missing(documentId, lastGeneration = null),
                remote = RemoteDocumentState.Live(remoteRevision, remoteDocument),
                anchor = null,
            )

        assertEquals(
            DocumentSyncPlan.ApplyRemoteLive(
                remoteRevision,
                remoteDocument,
                SavePrecondition.MatchesMissing(lastGeneration = null),
            ),
            plan,
        )
    }

    @Test
    fun localEditAfterConvergenceUploadsOnRemoteHead() {
        val local = live("Local edit", generation = 2)
        val anchor = DocumentSyncAnchor(StoreGeneration(1), setOf(rootRevision))

        val plan =
            planDocumentSync(
                local = local,
                remote = RemoteDocumentState.Live(rootRevision, document("Original")),
                anchor = anchor,
            )

        val upload = assertIs<DocumentSyncPlan.UploadLive>(plan)
        assertEquals(setOf(rootRevision), upload.parents)
    }

    @Test
    fun concurrentLocalAndRemoteEditsRemainExplicitConflict() {
        val local = live("Local edit", generation = 2)
        val anchor = DocumentSyncAnchor(StoreGeneration(1), setOf(rootRevision))

        val plan =
            planDocumentSync(
                local = local,
                remote = RemoteDocumentState.Live(remoteRevision, document("Remote edit")),
                anchor = anchor,
            )

        assertEquals(
            DocumentSyncPlan.Conflict(
                documentId = documentId,
                remoteHeads = setOf(remoteRevision),
                reason = SyncConflictReason.ConcurrentChanges,
            ),
            plan,
        )
    }

    @Test
    fun remoteDeleteAfterConvergenceAppliesLocally() {
        val local = live("Original", generation = 1)
        val anchor = DocumentSyncAnchor(StoreGeneration(1), setOf(rootRevision))

        val plan =
            planDocumentSync(
                local = local,
                remote = RemoteDocumentState.Deleted(remoteRevision),
                anchor = anchor,
            )

        assertEquals(
            DocumentSyncPlan.ApplyRemoteDelete(
                remoteRevision,
                expectedLiveGeneration = StoreGeneration(1),
            ),
            plan,
        )
    }

    @Test
    fun localDeleteAfterConvergenceUploadsTombstone() {
        val local = DocumentState.Missing(documentId, StoreGeneration(2))
        val anchor = DocumentSyncAnchor(StoreGeneration(1), setOf(rootRevision))

        val plan =
            planDocumentSync(
                local = local,
                remote = RemoteDocumentState.Live(rootRevision, document("Original")),
                anchor = anchor,
            )

        assertEquals(
            DocumentSyncPlan.UploadTombstone(
                documentId = documentId,
                localGeneration = StoreGeneration(2),
                parents = setOf(rootRevision),
            ),
            plan,
        )
    }

    @Test
    fun unchangedConvergedStateIsNoOp() {
        val local = live("Original", generation = 1)
        val anchor = DocumentSyncAnchor(StoreGeneration(1), setOf(rootRevision))

        val plan =
            planDocumentSync(
                local = local,
                remote = RemoteDocumentState.Live(rootRevision, document("Original")),
                anchor = anchor,
            )

        assertEquals(DocumentSyncPlan.NoChange(anchor), plan)
    }

    @Test
    fun missingRemoteHistoryAfterConvergenceIsConflict() {
        val local = live("Original", generation = 1)
        val anchor = DocumentSyncAnchor(StoreGeneration(1), setOf(rootRevision))

        val plan = planDocumentSync(local, remote = null, anchor = anchor)

        assertEquals(
            DocumentSyncPlan.Conflict(
                documentId,
                emptySet(),
                SyncConflictReason.RemoteHistoryMissing,
            ),
            plan,
        )
    }

    @Test
    fun existingRemoteConflictIsNeverAutoResolved() {
        val local = live("Original", generation = 1)

        val plan =
            planDocumentSync(
                local,
                RemoteDocumentState.Conflict(setOf(rootRevision, remoteRevision)),
                anchor = DocumentSyncAnchor(StoreGeneration(1), setOf(rootRevision)),
            )

        assertEquals(
            DocumentSyncPlan.Conflict(
                documentId,
                setOf(rootRevision, remoteRevision),
                SyncConflictReason.RemoteConflict,
            ),
            plan,
        )
    }

    @Test
    fun remoteHeadOnlyChangeWithSameContentConvergesWithoutLocalWrite() {
        val local = live("Original", generation = 1)
        val anchor = DocumentSyncAnchor(StoreGeneration(1), setOf(rootRevision))

        val plan =
            planDocumentSync(
                local,
                RemoteDocumentState.Live(remoteRevision, document("Original")),
                anchor,
            )

        assertEquals(
            DocumentSyncPlan.Converged(
                DocumentSyncAnchor(StoreGeneration(1), setOf(remoteRevision)),
            ),
            plan,
        )
    }

    @Test
    fun remoteHeadOnlyChangeWithBothSidesDeletedConvergesWithoutLocalWrite() {
        val local = DocumentState.Missing(documentId, StoreGeneration(2))
        val anchor = DocumentSyncAnchor(StoreGeneration(2), setOf(rootRevision))

        val plan =
            planDocumentSync(
                local,
                RemoteDocumentState.Deleted(remoteRevision),
                anchor,
            )

        assertEquals(
            DocumentSyncPlan.Converged(
                DocumentSyncAnchor(StoreGeneration(2), setOf(remoteRevision)),
            ),
            plan,
        )
    }

    @Test
    fun remoteLiveOverwriteCarriesObservedLocalGeneration() {
        val local = live("Original", generation = 3)
        val anchor = DocumentSyncAnchor(StoreGeneration(3), setOf(rootRevision))
        val remoteDocument = document("Remote edit")

        val plan =
            planDocumentSync(
                local,
                RemoteDocumentState.Live(remoteRevision, remoteDocument),
                anchor,
            )

        assertEquals(
            DocumentSyncPlan.ApplyRemoteLive(
                remoteRevision,
                remoteDocument,
                SavePrecondition.Matches(StoreGeneration(3)),
            ),
            plan,
        )
    }

    @Test
    fun remoteLiveRecreationCarriesObservedTombstoneGeneration() {
        val local = DocumentState.Missing(documentId, StoreGeneration(4))
        val anchor = DocumentSyncAnchor(StoreGeneration(4), setOf(rootRevision))
        val remoteDocument = document("Remote recreation")

        val plan =
            planDocumentSync(
                local,
                RemoteDocumentState.Live(remoteRevision, remoteDocument),
                anchor,
            )

        assertEquals(
            DocumentSyncPlan.ApplyRemoteLive(
                remoteRevision,
                remoteDocument,
                SavePrecondition.MatchesMissing(StoreGeneration(4)),
            ),
            plan,
        )
    }

    private fun live(title: String, generation: Long): DocumentState.Live =
        DocumentState.Live(
            StoredDocument(document(title), StoreGeneration(generation)),
        )

    private fun document(title: String): ScriptDocument =
        ScriptDocument(
            id = documentId,
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
