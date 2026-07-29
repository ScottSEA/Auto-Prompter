package com.scottsea.autoprompter.webstore

import com.scottsea.autoprompter.store.contract.contractConcurrentSamePreconditionSaves
import com.scottsea.autoprompter.store.contract.contractDefensiveAliasing
import com.scottsea.autoprompter.store.contract.contractDeleteTombstonesAndStaleConflicts
import com.scottsea.autoprompter.store.contract.contractIndependentIdsAndDeterministicOrder
import com.scottsea.autoprompter.store.contract.contractMatchesUpdatesAndStaleConflicts
import com.scottsea.autoprompter.store.contract.contractMatchesMissingIsAbaSafe
import com.scottsea.autoprompter.store.contract.contractMustBeMissingAgainstLiveConflicts
import com.scottsea.autoprompter.store.contract.contractMustBeMissingCreatesGenerationOne
import com.scottsea.autoprompter.store.contract.contractNeverCreatedIsMissing
import com.scottsea.autoprompter.store.contract.contractRecreateAfterDeleteIsAbaSafe
import kotlin.test.Test

/**
 * Runs the exact reusable nine-behavior [DocumentStore] contract against a real, durable IndexedDB
 * database in the headless browser -- a fresh uniquely-named database per behavior, closed and
 * deleted afterward. The IndexedDB adapter must satisfy the contract identically to the InMemory
 * reference and the Room adapter; no behavior is duplicated here.
 */
class IndexedDbDocumentStoreContractTest {

    @Test
    fun neverCreatedIsMissing() = webStoreTest(::contractNeverCreatedIsMissing)

    @Test
    fun mustBeMissingCreatesGenerationOne() =
        webStoreTest(::contractMustBeMissingCreatesGenerationOne)

    @Test
    fun mustBeMissingAgainstLiveConflicts() =
        webStoreTest(::contractMustBeMissingAgainstLiveConflicts)

    @Test
    fun matchesUpdatesAndStaleConflicts() =
        webStoreTest(::contractMatchesUpdatesAndStaleConflicts)

    @Test
    fun deleteTombstonesAndStaleConflicts() =
        webStoreTest(::contractDeleteTombstonesAndStaleConflicts)

    @Test
    fun recreateAfterDeleteIsAbaSafe() =
        webStoreTest(::contractRecreateAfterDeleteIsAbaSafe)

    @Test
    fun independentIdsAndDeterministicOrder() =
        webStoreTest(::contractIndependentIdsAndDeterministicOrder)

    @Test
    fun defensiveAliasing() = webStoreTest(::contractDefensiveAliasing)

    @Test
    fun concurrentSamePreconditionSaves() =
        webStoreTest(::contractConcurrentSamePreconditionSaves)

    @Test
    fun matchesMissingIsAbaSafe() =
        webStoreTest(::contractMatchesMissingIsAbaSafe)
}
