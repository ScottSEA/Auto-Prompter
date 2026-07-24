package com.scottsea.autoprompter.webstore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Pure, database-free tests for the JS-row <-> Kotlin-snapshot mapping helpers. Keeping the mapping
 * pure makes exactly these edge cases -- canonical generation parsing, tombstone nulls -- unit
 * testable without a browser transaction.
 */
class IndexedDbDocumentRowMappingTest {

    @Test
    fun liveRowRoundTripsThroughSnapshot() {
        val snapshot = documentRowJs(
            id = "doc-1",
            generation = "7",
            deleted = false,
            title = "Title",
            payload = "{\"schema\":1}",
        ).toSnapshot()

        assertEquals("doc-1", snapshot.id)
        assertEquals(7L, snapshot.generation)
        assertEquals(false, snapshot.deleted)
        assertEquals("Title", snapshot.title)
        assertEquals("{\"schema\":1}", snapshot.payload)
    }

    @Test
    fun tombstoneRowMapsNullTitleAndPayload() {
        val snapshot = documentRowJs(
            id = "doc-1",
            generation = "2",
            deleted = true,
            title = null,
            payload = null,
        ).toSnapshot()

        assertEquals(2L, snapshot.generation)
        assertEquals(true, snapshot.deleted)
        assertNull(snapshot.title)
        assertNull(snapshot.payload)
    }

    @Test
    fun nonNumericGenerationIsRejected() {
        assertFailsWith<WebStoreCorruptionException> {
            documentRowJs("doc-1", generation = "ten", deleted = false, title = "t", payload = "p").toSnapshot()
        }
    }

    @Test
    fun nonCanonicalGenerationWithLeadingZerosIsRejected() {
        assertFailsWith<WebStoreCorruptionException> {
            documentRowJs("doc-1", generation = "007", deleted = false, title = "t", payload = "p").toSnapshot()
        }
    }
}
