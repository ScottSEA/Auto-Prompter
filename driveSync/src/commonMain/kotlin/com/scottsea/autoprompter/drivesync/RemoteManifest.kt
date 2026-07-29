package com.scottsea.autoprompter.drivesync

import com.scottsea.autoprompter.core.document.DocumentId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.jvm.JvmInline

@JvmInline
value class RemoteManifestId(val value: String) {
    init {
        require(value.matches(Regex("[0-9a-f]{64}"))) {
            "Remote manifest id must be a lowercase SHA-256 value."
        }
    }
}

class RemoteManifest internal constructor(
    val id: RemoteManifestId,
    heads: Map<DocumentId, Set<RemoteRevisionId>>,
) {
    private val headSnapshot: Map<DocumentId, List<RemoteRevisionId>> =
        heads.entries
            .sortedBy { it.key.value }
            .associate { (documentId, revisions) ->
                documentId to revisions.distinct().sorted()
            }

    val heads: Map<DocumentId, Set<RemoteRevisionId>>
        get() = headSnapshot.mapValues { (_, revisions) -> revisions.toSet() }
}

fun createRemoteManifest(
    heads: RemoteHeads,
    digester: ContentDigester,
): RemoteManifest {
    val canonical = canonicalManifestContent(heads.heads)
    return RemoteManifest(
        id = RemoteManifestId(digester.sha256(canonical)),
        heads = heads.heads,
    )
}

private fun canonicalManifestContent(
    heads: Map<DocumentId, Set<RemoteRevisionId>>,
): String =
    buildString {
        append("autoprompter.drive.manifest.v1|")
        heads.entries.sortedBy { it.key.value }.forEach { (documentId, revisions) ->
            append(documentId.value.length)
            append(':')
            append(documentId.value)
            append('|')
            revisions.sorted().forEach { revision ->
                append(revision.value)
                append('|')
            }
        }
    }

internal class RemoteManifestCodec(
    private val digester: ContentDigester,
    private val json: Json =
        Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
        },
) {
    fun encode(manifest: RemoteManifest): String =
        json.encodeToString(
            RemoteManifestWireV1.serializer(),
            RemoteManifestWireV1(
                schemaVersion = 1,
                kind = MANIFEST_KIND,
                manifestId = manifest.id.value,
                documents =
                    manifest.heads.map { (documentId, heads) ->
                        RemoteDocumentHeadsWireV1(
                            documentId = documentId.value,
                            heads = heads.sorted().map(RemoteRevisionId::value),
                        )
                    },
            ),
        )

    fun decode(encoded: String): RemoteManifest {
        val wire = json.decodeFromString(RemoteManifestWireV1.serializer(), encoded)
        require(wire.schemaVersion == 1) {
            "Unsupported Drive manifest schema ${wire.schemaVersion}."
        }
        require(wire.kind == MANIFEST_KIND) {
            "Unexpected Drive manifest kind ${wire.kind}."
        }
        val documentIds = wire.documents.map(RemoteDocumentHeadsWireV1::documentId)
        require(documentIds == documentIds.distinct().sorted()) {
            "Drive manifest documents must be sorted and unique."
        }
        wire.documents.forEach { document ->
            require(document.heads.isNotEmpty()) {
                "Drive manifest document ${document.documentId} has no heads."
            }
            require(document.heads == document.heads.distinct().sorted()) {
                "Drive manifest heads must be sorted and unique."
            }
        }
        val heads =
            wire.documents.associate { document ->
                DocumentId(document.documentId) to document.heads.map(::RemoteRevisionId).toSet()
            }
        val reconstructed =
            createRemoteManifest(
                RemoteHeads(heads),
                digester,
            )
        require(reconstructed.id == RemoteManifestId(wire.manifestId)) {
            "Drive manifest failed content-address verification."
        }
        return reconstructed
    }
}

@Serializable
private data class RemoteManifestWireV1(
    @SerialName("schemaVersion")
    val schemaVersion: Int,
    @SerialName("kind")
    val kind: String,
    @SerialName("manifestId")
    val manifestId: String,
    @SerialName("documents")
    val documents: List<RemoteDocumentHeadsWireV1>,
)

@Serializable
private data class RemoteDocumentHeadsWireV1(
    @SerialName("documentId")
    val documentId: String,
    @SerialName("heads")
    val heads: List<String>,
)

private const val MANIFEST_KIND = "autoprompter.drive.manifest.v1"
