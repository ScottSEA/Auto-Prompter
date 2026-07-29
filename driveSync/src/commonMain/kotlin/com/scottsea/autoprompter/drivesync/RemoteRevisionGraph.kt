package com.scottsea.autoprompter.drivesync

import com.scottsea.autoprompter.core.document.DocumentId
import com.scottsea.autoprompter.core.document.ScriptDocument
import com.scottsea.autoprompter.core.document.encodeScriptDocument
import kotlin.jvm.JvmInline

private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

fun interface ContentDigester {
    fun sha256(text: String): String
}

@JvmInline
value class RemoteRevisionId(val value: String) : Comparable<RemoteRevisionId> {
    init {
        require(SHA256_PATTERN.matches(value)) {
            "Remote revision id must be a lowercase SHA-256 value."
        }
    }

    override fun compareTo(other: RemoteRevisionId): Int = value.compareTo(other.value)
}

enum class RemoteRevisionKind { Live, Tombstone }

/** One immutable, content-addressed document revision. */
class RemoteRevision internal constructor(
    val id: RemoteRevisionId,
    val documentId: DocumentId,
    parents: Collection<RemoteRevisionId>,
    val kind: RemoteRevisionKind,
    val documentJson: String?,
) {
    private val parentSnapshot: List<RemoteRevisionId> = parents.distinct().sorted()
    val parents: List<RemoteRevisionId> get() = parentSnapshot.toList()

    init {
        require(id !in parentSnapshot) { "Remote revision cannot name itself as a parent." }
        when (kind) {
            RemoteRevisionKind.Live ->
                require(!documentJson.isNullOrBlank()) { "Live revision requires document JSON." }
            RemoteRevisionKind.Tombstone ->
                require(documentJson == null) { "Tombstone revision cannot carry document JSON." }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RemoteRevision &&
            id == other.id &&
            documentId == other.documentId &&
            parentSnapshot == other.parentSnapshot &&
            kind == other.kind &&
            documentJson == other.documentJson

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + documentId.hashCode()
        result = 31 * result + parentSnapshot.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + (documentJson?.hashCode() ?: 0)
        return result
    }
}

fun createLiveRevision(
    document: ScriptDocument,
    parents: Set<RemoteRevisionId>,
    digester: ContentDigester,
): RemoteRevision =
    createRevision(
        documentId = document.id,
        parents = parents,
        kind = RemoteRevisionKind.Live,
        documentJson = encodeScriptDocument(document),
        digester = digester,
    )

fun createTombstoneRevision(
    documentId: DocumentId,
    parents: Set<RemoteRevisionId>,
    digester: ContentDigester,
): RemoteRevision =
    createRevision(
        documentId = documentId,
        parents = parents,
        kind = RemoteRevisionKind.Tombstone,
        documentJson = null,
        digester = digester,
    )

private fun createRevision(
    documentId: DocumentId,
    parents: Set<RemoteRevisionId>,
    kind: RemoteRevisionKind,
    documentJson: String?,
    digester: ContentDigester,
): RemoteRevision {
    val canonical =
        canonicalRevisionContent(
            documentId = documentId,
            parents = parents,
            kind = kind,
            documentJson = documentJson,
        )
    val id = RemoteRevisionId(digester.sha256(canonical))
    return RemoteRevision(id, documentId, parents, kind, documentJson)
}

internal fun canonicalRevisionContent(
    documentId: DocumentId,
    parents: Collection<RemoteRevisionId>,
    kind: RemoteRevisionKind,
    documentJson: String?,
): String =
    buildString {
        appendLengthPrefixed("autoprompter.drive.revision.v1")
        appendLengthPrefixed(documentId.value)
        appendLengthPrefixed(kind.name)
        parents.distinct().sorted().forEach { parent ->
            appendLengthPrefixed(parent.value)
        }
        appendLengthPrefixed(documentJson ?: "")
    }

private fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.length)
    append(':')
    append(value)
    append('|')
}

class RemoteHeads internal constructor(heads: Map<DocumentId, Set<RemoteRevisionId>>) {
    private val headSnapshot: Map<DocumentId, List<RemoteRevisionId>> =
        heads.entries
            .sortedBy { it.key.value }
            .associate { (id, values) -> id to values.distinct().sorted() }

    val heads: Map<DocumentId, Set<RemoteRevisionId>>
        get() =
            headSnapshot.mapValues { (_, values) ->
                values.toSet()
            }
}

/**
 * Rebuilds authoritative remote heads from immutable revisions only.
 *
 * Unknown or cross-document parents are corruption/incomplete-upload signals and fail explicitly;
 * callers may retry after the eventually-consistent remote listing settles.
 */
fun reconstructRemoteHeads(revisions: Collection<RemoteRevision>): RemoteHeads {
    val byId = LinkedHashMap<RemoteRevisionId, RemoteRevision>()
    revisions.forEach { revision ->
        val prior = byId[revision.id]
        if (prior == null) {
            byId[revision.id] = revision
        } else {
            require(prior == revision) {
                "Remote revision id ${revision.id.value} has conflicting content."
            }
        }
    }

    val referencedParents = mutableSetOf<RemoteRevisionId>()
    byId.values.forEach { revision ->
        revision.parents.forEach { parentId ->
            val parent =
                requireNotNull(byId[parentId]) {
                    "Remote revision ${revision.id.value} references missing parent ${parentId.value}."
                }
            require(parent.documentId == revision.documentId) {
                "Remote revision parent belongs to a different document."
            }
            referencedParents += parentId
        }
    }
    requireAcyclic(byId)

    val heads =
        byId.values
            .filter { it.id !in referencedParents }
            .groupBy(RemoteRevision::documentId)
            .mapValues { (_, values) -> values.map(RemoteRevision::id).toSet() }
    return RemoteHeads(heads)
}

private fun requireAcyclic(byId: Map<RemoteRevisionId, RemoteRevision>) {
    val visiting = mutableSetOf<RemoteRevisionId>()
    val visited = mutableSetOf<RemoteRevisionId>()

    fun visit(id: RemoteRevisionId) {
        if (id in visited) return
        require(visiting.add(id)) {
            "Remote revision graph contains a cycle at ${id.value}."
        }
        byId.getValue(id).parents.forEach(::visit)
        visiting.remove(id)
        visited.add(id)
    }

    byId.keys.forEach(::visit)
}
