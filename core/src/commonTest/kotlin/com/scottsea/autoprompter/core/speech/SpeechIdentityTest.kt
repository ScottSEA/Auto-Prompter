package com.scottsea.autoprompter.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechIdentityTest {

    @Test
    fun languageTagRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { LanguageTag("") }
        assertFailsWith<IllegalArgumentException> { LanguageTag("   ") }
        assertEquals("en-US", LanguageTag("en-US").value)
    }

    @Test
    fun sessionIdRejectsBlank() {
        assertFailsWith<IllegalArgumentException> { SpeechSessionId("") }
        assertEquals("s-1", SpeechSessionId("s-1").value)
    }

    @Test
    fun utteranceIdRejectsNegativeAndAdvancesMonotonically() {
        assertFailsWith<IllegalArgumentException> { UtteranceId(-1L) }
        assertEquals(0L, UtteranceId.FIRST.value)
        assertEquals(1L, UtteranceId.FIRST.next().value)
        assertFailsWith<IllegalStateException> { UtteranceId(Long.MAX_VALUE).next() }
    }

    @Test
    fun revisionRejectsNegativeAndAdvancesMonotonically() {
        assertFailsWith<IllegalArgumentException> { Revision(-1L) }
        assertEquals(0L, Revision.FIRST.value)
        assertEquals(1L, Revision.FIRST.next().value)
        assertFailsWith<IllegalStateException> { Revision(Long.MAX_VALUE).next() }
    }

    @Test
    fun revisionPrecedesOrdersStrictly() {
        assertTrue(Revision(1L).precedes(Revision(2L)))
        assertFalse(Revision(2L).precedes(Revision(2L)))
        assertFalse(Revision(3L).precedes(Revision(2L)))
    }

    @Test
    fun confidenceMustBeWithinUnitRange() {
        assertFailsWith<IllegalArgumentException> { SpeechConfidence(-0.01) }
        assertFailsWith<IllegalArgumentException> { SpeechConfidence(1.01) }
        assertEquals(0.5, SpeechConfidence(0.5).value)
    }
}
