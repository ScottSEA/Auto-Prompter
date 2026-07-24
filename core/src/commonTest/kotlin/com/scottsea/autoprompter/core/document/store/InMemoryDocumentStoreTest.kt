package com.scottsea.autoprompter.core.document.store

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Runs the shared [DocumentStore] contract against [InMemoryDocumentStore]. Each behavior is its own
 * platform test (so counts and failures are per-behavior) but all assertions live in the reusable
 * contract functions, ready to run against future Room/IndexedDB adapters unchanged.
 */
class InMemoryDocumentStoreTest {

    private val newStore: DocumentStoreFactory = { InMemoryDocumentStore() }

    @Test
    fun neverCreatedIsMissing() = runTest { contractNeverCreatedIsMissing(newStore) }

    @Test
    fun mustBeMissingCreatesGenerationOne() = runTest { contractMustBeMissingCreatesGenerationOne(newStore) }

    @Test
    fun mustBeMissingAgainstLiveConflicts() = runTest { contractMustBeMissingAgainstLiveConflicts(newStore) }

    @Test
    fun matchesUpdatesAndStaleConflicts() = runTest { contractMatchesUpdatesAndStaleConflicts(newStore) }

    @Test
    fun deleteTombstonesAndStaleConflicts() = runTest { contractDeleteTombstonesAndStaleConflicts(newStore) }

    @Test
    fun recreateAfterDeleteIsAbaSafe() = runTest { contractRecreateAfterDeleteIsAbaSafe(newStore) }

    @Test
    fun independentIdsAndDeterministicOrder() = runTest { contractIndependentIdsAndDeterministicOrder(newStore) }

    @Test
    fun defensiveAliasing() = runTest { contractDefensiveAliasing(newStore) }

    @Test
    fun concurrentSamePreconditionSaves() = runTest { contractConcurrentSamePreconditionSaves(newStore) }
}
