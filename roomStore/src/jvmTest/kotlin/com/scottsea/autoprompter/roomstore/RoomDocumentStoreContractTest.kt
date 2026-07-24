package com.scottsea.autoprompter.roomstore

import com.scottsea.autoprompter.store.contract.contractConcurrentSamePreconditionSaves
import com.scottsea.autoprompter.store.contract.contractDefensiveAliasing
import com.scottsea.autoprompter.store.contract.contractDeleteTombstonesAndStaleConflicts
import com.scottsea.autoprompter.store.contract.contractIndependentIdsAndDeterministicOrder
import com.scottsea.autoprompter.store.contract.contractMatchesUpdatesAndStaleConflicts
import com.scottsea.autoprompter.store.contract.contractMustBeMissingAgainstLiveConflicts
import com.scottsea.autoprompter.store.contract.contractMustBeMissingCreatesGenerationOne
import com.scottsea.autoprompter.store.contract.contractNeverCreatedIsMissing
import com.scottsea.autoprompter.store.contract.contractRecreateAfterDeleteIsAbaSafe
import kotlin.test.Test

/**
 * Runs the exact reusable nine-behavior [DocumentStore] contract against a real, durable Room/SQLite
 * database -- a fresh temp database per behavior, closed and deleted afterward. The Room adapter must
 * satisfy the contract identically to the InMemory reference; no behavior is duplicated here.
 */
class RoomDocumentStoreContractTest {

    @Test
    fun neverCreatedIsMissing() = roomStoreTest(::contractNeverCreatedIsMissing)

    @Test
    fun mustBeMissingCreatesGenerationOne() =
        roomStoreTest(::contractMustBeMissingCreatesGenerationOne)

    @Test
    fun mustBeMissingAgainstLiveConflicts() =
        roomStoreTest(::contractMustBeMissingAgainstLiveConflicts)

    @Test
    fun matchesUpdatesAndStaleConflicts() =
        roomStoreTest(::contractMatchesUpdatesAndStaleConflicts)

    @Test
    fun deleteTombstonesAndStaleConflicts() =
        roomStoreTest(::contractDeleteTombstonesAndStaleConflicts)

    @Test
    fun recreateAfterDeleteIsAbaSafe() =
        roomStoreTest(::contractRecreateAfterDeleteIsAbaSafe)

    @Test
    fun independentIdsAndDeterministicOrder() =
        roomStoreTest(::contractIndependentIdsAndDeterministicOrder)

    @Test
    fun defensiveAliasing() = roomStoreTest(::contractDefensiveAliasing)

    @Test
    fun concurrentSamePreconditionSaves() =
        roomStoreTest(::contractConcurrentSamePreconditionSaves)
}
