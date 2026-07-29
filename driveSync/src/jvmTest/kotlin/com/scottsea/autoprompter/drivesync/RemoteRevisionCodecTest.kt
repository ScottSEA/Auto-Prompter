package com.scottsea.autoprompter.drivesync

import com.scottsea.autoprompter.core.document.BlockId
import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptBlock
import com.scottsea.autoprompter.core.document.ScriptBlockKind
import com.scottsea.autoprompter.core.document.ScriptDocument
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteRevisionCodecTest {
    private val digest = JvmSha256Digester
    private val codec = RemoteRevisionCodec(digest)

    @Test
    fun encodedRevisionRoundTripsAndVerifiesItsContentAddress() {
        val revision = createLiveRevision(document("Original"), emptySet(), digest)

        val decoded = codec.decode(codec.encode(revision))

        assertEquals(revision, decoded)
    }

    @Test
    fun modifiedPayloadWithOldRevisionIdIsRejected() {
        val revision = createLiveRevision(document("Original"), emptySet(), digest)
        val encoded = codec.encode(revision)
        val modified = encoded.replace("Original", "Modified")

        assertFailsWith<IllegalArgumentException> {
            codec.decode(modified)
        }
    }

    @Test
    fun schemaVersionIsRequiredAndParentsMustAlreadyBeCanonical() {
        val firstParent = RemoteRevisionId("a".repeat(64))
        val secondParent = RemoteRevisionId("b".repeat(64))
        val revision =
            createLiveRevision(
                document("Original"),
                setOf(firstParent, secondParent),
                digest,
            )
        val encoded = codec.encode(revision)

        assertFailsWith<Exception> {
            codec.decode(encoded.replace("\"schemaVersion\":1,", ""))
        }
        val nonCanonicalParents =
            encoded.replace(
                "\"parents\":[\"${firstParent.value}\",\"${secondParent.value}\"]",
                "\"parents\":[\"${secondParent.value}\",\"${firstParent.value}\",\"${firstParent.value}\"]",
            )
        assertFailsWith<IllegalArgumentException> {
            codec.decode(nonCanonicalParents)
        }
    }

    @Test
    fun livePayloadDocumentIdMustMatchRevisionEnvelope() {
        val revision = createLiveRevision(document("Original"), emptySet(), digest)
        val otherDocumentId = DocumentId("other-document")
        val poisonedId =
            digest.sha256(
                canonicalRevisionContent(
                    documentId = otherDocumentId,
                    parents = emptyList(),
                    kind = RemoteRevisionKind.Live,
                    documentJson = revision.documentJson,
                ),
            )
        val poisoned =
            codec.encode(revision)
                .replace(revision.id.value, poisonedId)
                .replace("\"documentId\":\"document\"", "\"documentId\":\"other-document\"")

        assertFailsWith<IllegalArgumentException> {
            codec.decode(poisoned)
        }
    }

    private fun document(title: String): ScriptDocument =
        ScriptDocument(
            id = DocumentId("document"),
            title = title,
            blocks =
                listOf(
                    ScriptBlock(
                        id = BlockId("paragraph"),
                        kind = ScriptBlockKind.Paragraph,
                        text = "Body",
                    ),
                ),
        )
}

internal object JvmSha256Digester : ContentDigester {
    override fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}
