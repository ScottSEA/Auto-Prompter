package com.scottsea.autoprompter.drivesync

import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.decodeScriptDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Strict schema-v1 codec that re-verifies the content address on every decode. */
class RemoteRevisionCodec(
    private val digester: ContentDigester,
    private val json: Json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            explicitNulls = true
        },
) {
    fun encode(revision: RemoteRevision): String =
        json.encodeToString(
            RemoteRevisionWireV1.serializer(),
            revision.toWire(),
        )

    fun decode(encoded: String): RemoteRevision {
        val wire = json.decodeFromString(RemoteRevisionWireV1.serializer(), encoded)
        require(wire.schemaVersion == 1) {
            "Unsupported Drive revision schema ${wire.schemaVersion}."
        }
        val id = RemoteRevisionId(wire.revisionId)
        val documentId = DocumentId(wire.documentId)
        val parents = wire.parents.map(::RemoteRevisionId)
        require(wire.parents == wire.parents.distinct().sorted()) {
            "Drive revision parents must be sorted and unique."
        }
        val kind = RemoteRevisionKind.valueOf(wire.kind)
        if (kind == RemoteRevisionKind.Live) {
            val document = decodeScriptDocument(requireNotNull(wire.documentJson))
            require(document.id == documentId) {
                "Drive revision payload document id does not match its envelope."
            }
        }
        val expectedId =
            RemoteRevisionId(
                digester.sha256(
                    canonicalRevisionContent(documentId, parents, kind, wire.documentJson),
                ),
            )
        require(expectedId == id) {
            "Drive revision ${id.value} failed content-address verification."
        }
        return RemoteRevision(
            id = id,
            documentId = documentId,
            parents = parents,
            kind = kind,
            documentJson = wire.documentJson,
        )
    }
}

@Serializable
private data class RemoteRevisionWireV1(
    @SerialName("schemaVersion")
    val schemaVersion: Int,
    @SerialName("kind")
    val kind: String,
    @SerialName("revisionId")
    val revisionId: String,
    @SerialName("documentId")
    val documentId: String,
    @SerialName("parents")
    val parents: List<String>,
    @SerialName("documentJson")
    val documentJson: String?,
)

private fun RemoteRevision.toWire(): RemoteRevisionWireV1 =
    RemoteRevisionWireV1(
        schemaVersion = 1,
        kind = kind.name,
        revisionId = id.value,
        documentId = documentId.value,
        parents = parents.map(RemoteRevisionId::value),
        documentJson = documentJson,
    )
