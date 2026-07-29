package com.scottsea.autoprompter.drivesync

import com.scottsea.autoprompter.core.document.DocumentId
import kotlin.test.Test
import kotlin.test.assertFailsWith

class RemoteManifestCodecTest {
    private val codec = RemoteManifestCodec(Sha256ContentDigester)

    @Test
    fun manifestRequiresSchemaKindAndCanonicalUniqueHeads() {
        val revision = RemoteRevisionId("a".repeat(64))
        val manifest =
            createRemoteManifest(
                RemoteHeads(mapOf(DocumentId("document") to setOf(revision))),
                Sha256ContentDigester,
            )
        val encoded = codec.encode(manifest)

        assertFailsWith<Exception> {
            codec.decode(encoded.replace("\"schemaVersion\":1,", ""))
        }
        assertFailsWith<Exception> {
            codec.decode(encoded.replace("autoprompter.drive.manifest.v1", "wrong.kind"))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.decode(
                encoded.replace(
                    "\"heads\":[\"${revision.value}\"]",
                    "\"heads\":[\"${revision.value}\",\"${revision.value}\"]",
                ),
            )
        }
    }
}
