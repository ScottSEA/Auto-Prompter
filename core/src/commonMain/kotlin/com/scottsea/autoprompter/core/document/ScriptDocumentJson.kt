package com.scottsea.autoprompter.core.document

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The external, durable format seam for [ScriptDocument].
 *
 * JSON is configured intentionally rather than leniently: unknown keys are rejected so that an
 * evolved or corrupt payload surfaces as an error instead of being silently dropped, and defaults
 * are always written so [ScriptDocument.schemaVersion] is present in every encoded document. There
 * is no unknown-schema fallback; [decodeScriptDocument] relies on [ScriptDocument] construction to
 * reject unsupported schema versions with an informative [IllegalArgumentException].
 */
private val scriptDocumentJson: Json = Json {
    ignoreUnknownKeys = false
    encodeDefaults = true
    prettyPrint = false
}

@Serializable
private data class ScriptDocumentWire(
    @SerialName("schemaVersion")
    val schemaVersion: Int,
    @SerialName("id")
    val id: DocumentId,
    @SerialName("title")
    val title: String,
    @SerialName("blocks")
    val blocks: List<ScriptBlock>,
)

/** Encodes [document] to canonical JSON. */
fun encodeScriptDocument(document: ScriptDocument): String =
    scriptDocumentJson.encodeToString(
        ScriptDocumentWire.serializer(),
        ScriptDocumentWire(
            schemaVersion = document.schemaVersion,
            id = document.id,
            title = document.title,
            blocks = document.blocks,
        ),
    )

/**
 * Decodes a [ScriptDocument] from [json].
 *
 * Malformed JSON surfaces as a serialization exception; an otherwise well-formed document whose
 * schema version is unsupported surfaces as an [IllegalArgumentException] from [ScriptDocument]
 * construction. Neither case falls back to a default document.
 */
fun decodeScriptDocument(json: String): ScriptDocument {
    val wire = scriptDocumentJson.decodeFromString(ScriptDocumentWire.serializer(), json)
    return ScriptDocument(
        schemaVersion = wire.schemaVersion,
        id = wire.id,
        title = wire.title,
        blocks = wire.blocks,
    )
}
