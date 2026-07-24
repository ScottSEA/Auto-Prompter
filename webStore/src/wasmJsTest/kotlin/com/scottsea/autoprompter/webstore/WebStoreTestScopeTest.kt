package com.scottsea.autoprompter.webstore

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFails

class WebStoreTestScopeTest {

    @Test
    fun cleanupReportsDatabaseDeletionFailure() = runTest {
        val scope = WebStoreTestScope {
            throw IllegalStateException("simulated delete failure")
        }
        scope.newDatabaseName()

        assertFails { scope.cleanup() }
    }
}
