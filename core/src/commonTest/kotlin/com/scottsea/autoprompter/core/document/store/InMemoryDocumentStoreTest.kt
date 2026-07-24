package com.scottsea.autoprompter.core.document.store

import com.scottsea.autoprompter.store.contract.DocumentStoreFactory
import com.scottsea.autoprompter.store.contract.contractConcurrentSamePreconditionSaves
import com.scottsea.autoprompter.store.contract.contractDefensiveAliasing
import com.scottsea.autoprompter.store.contract.contractDeleteTombstonesAndStaleConflicts
import com.scottsea.autoprompter.store.contract.contractIndependentIdsAndDeterministicOrder
import com.scottsea.autoprompter.store.contract.contractMatchesUpdatesAndStaleConflicts
import com.scottsea.autoprompter.store.contract.contractMustBeMissingAgainstLiveConflicts
import com.scottsea.autoprompter.store.contract.contractMustBeMissingCreatesGenerationOne
import com.scottsea.autoprompter.store.contract.contractNeverCreatedIsMissing
import com.scottsea.autoprompter.store.contract.contractRecreateAfterDeleteIsAbaSafe
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
