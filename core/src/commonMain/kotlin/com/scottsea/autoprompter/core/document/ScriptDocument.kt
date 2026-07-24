package com.scottsea.autoprompter.core.document

import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The canonical, durable script-document format owned by shared core.
 *
 * This is the milestone's first slice of the fuller document model in the architecture proposal:
 * an immutable domain model, a pinned serialized wire format, and stable IDs. It deliberately does
 * NOT yet carry recording references, sync journal data, presentation style overrides, or
 * timestamps; those arrive with their own storage and sync milestones.
 */

/** The schema version this build reads and writes. Bump only with an explicit migration story. */
const val CURRENT_SCHEMA_VERSION: Int = 1

/** A stable, non-blank identifier for a whole [ScriptDocument]. */
@Serializable
@JvmInline
value class DocumentId(val value: String) {
    init {
        require(value.isNotBlank()) { "DocumentId must not be blank." }
    }
}

/** A stable, non-blank identifier for a single [ScriptBlock] within a document. */
@Serializable
@JvmInline
value class BlockId(val value: String) {
    init {
        require(value.isNotBlank()) { "BlockId must not be blank." }
    }
}

/**
 * The kinds of block modelled in this slice. Both are spoken content by default in this
 * milestone. Recording cues, pauses, and other structural kinds come later.
 */
@Serializable
enum class ScriptBlockKind {
    @SerialName("paragraph")
    Paragraph,

    @SerialName("heading")
    Heading,
}

/** One ordered, immutable block of a [ScriptDocument]: a stable [id], a [kind], and its [text]. */
@Serializable
data class ScriptBlock(
    @SerialName("id")
    val id: BlockId,
    @SerialName("kind")
    val kind: ScriptBlockKind,
    @SerialName("text")
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "Script block text must not be blank." }
    }
}

/**
 * An immutable, ordered script document. [blocks] are held in presentation order; derived token
 * indexes for following are regenerated (see [toScript]) rather than stored here.
 *
 * Construction fails fast: only the supported [schemaVersion] is accepted, the [title] must be
 * non-blank, there must be at least one block, and every [ScriptBlock.id] must be unique.
 */
class ScriptDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: DocumentId,
    val title: String,
    blocks: List<ScriptBlock>,
) {
    private val blockSnapshot = blocks.toList()

    /**
     * Blocks in presentation order. A fresh read-only snapshot prevents callers from mutating
     * either the constructor's source list or the document's internal collection through a cast.
     */
    val blocks: List<ScriptBlock>
        get() = blockSnapshot.toList()

    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported script document schema version $schemaVersion; " +
                "this build supports version $CURRENT_SCHEMA_VERSION."
        }
        require(title.isNotBlank()) { "Script document title must not be blank." }
        require(blockSnapshot.isNotEmpty()) { "Script document must contain at least one block." }
        val ids = blockSnapshot.map { it.id }
        require(ids.toSet().size == ids.size) { "Script document block IDs must be unique." }
    }

    override fun equals(other: Any?): Boolean =
        other is ScriptDocument &&
            schemaVersion == other.schemaVersion &&
            id == other.id &&
            title == other.title &&
            blockSnapshot == other.blockSnapshot

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + blockSnapshot.hashCode()
        return result
    }

    override fun toString(): String =
        "ScriptDocument(schemaVersion=$schemaVersion, id=$id, title=$title, blocks=$blockSnapshot)"
}
