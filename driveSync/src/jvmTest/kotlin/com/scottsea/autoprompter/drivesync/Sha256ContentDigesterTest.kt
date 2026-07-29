package com.scottsea.autoprompter.drivesync

import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256ContentDigesterTest {
    @Test
    fun matchesPublishedSha256Vectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256ContentDigester.sha256(""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256ContentDigester.sha256("abc"),
        )
    }
}
