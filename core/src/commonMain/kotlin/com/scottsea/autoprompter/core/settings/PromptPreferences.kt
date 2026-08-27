package com.scottsea.autoprompter.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int

data class PromptPreferences(
    val fontScale: Float,
    val readingHorizonFraction: Float,
    val mirrorHorizontally: Boolean,
    val predictiveCursorEnabled: Boolean = false,
    val focusStripEnabled: Boolean = false,
    val phraseBiasEnabled: Boolean = false,
) {
    init {
        require(fontScale in 0.75f..2.0f) {
            "Prompt font scale must be within 0.75..2.0: $fontScale."
        }
        require(readingHorizonFraction in 0.25f..0.60f) {
            "Reading horizon must be within 0.25..0.60: $readingHorizonFraction."
        }
    }

    companion object {
        val DEFAULT =
            PromptPreferences(
                fontScale = 1f,
                readingHorizonFraction = 0.40f,
                mirrorHorizontally = false,
                predictiveCursorEnabled = false,
                focusStripEnabled = false,
                phraseBiasEnabled = false,
            )
    }
}

interface PromptPreferencesStore {
    suspend fun load(): PromptPreferences
    suspend fun save(preferences: PromptPreferences)
}

private val preferencesJson =
    Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

@Serializable
private data class PromptPreferencesWireV1(
    @SerialName("schemaVersion")
    val schemaVersion: Int,
    @SerialName("fontScale")
    val fontScale: Float,
    @SerialName("readingHorizonFraction")
    val readingHorizonFraction: Float,
    @SerialName("mirrorHorizontally")
    val mirrorHorizontally: Boolean,
)

@Serializable
private data class PromptPreferencesWireV2(
    @SerialName("schemaVersion")
    val schemaVersion: Int,
    @SerialName("fontScale")
    val fontScale: Float,
    @SerialName("readingHorizonFraction")
    val readingHorizonFraction: Float,
    @SerialName("mirrorHorizontally")
    val mirrorHorizontally: Boolean,
    @SerialName("predictiveCursorEnabled")
    val predictiveCursorEnabled: Boolean,
    @SerialName("focusStripEnabled")
    val focusStripEnabled: Boolean,
    @SerialName("phraseBiasEnabled")
    val phraseBiasEnabled: Boolean,
)

fun encodePromptPreferences(preferences: PromptPreferences): String =
    preferencesJson.encodeToString(
        PromptPreferencesWireV2.serializer(),
        PromptPreferencesWireV2(
            schemaVersion = 2,
            fontScale = preferences.fontScale,
            readingHorizonFraction = preferences.readingHorizonFraction,
            mirrorHorizontally = preferences.mirrorHorizontally,
            predictiveCursorEnabled = preferences.predictiveCursorEnabled,
            focusStripEnabled = preferences.focusStripEnabled,
            phraseBiasEnabled = preferences.phraseBiasEnabled,
        ),
    )

fun decodePromptPreferences(encoded: String): PromptPreferences {
    val schemaVersion =
        preferencesJson
            .parseToJsonElement(encoded)
            .jsonObject["schemaVersion"]
            ?.jsonPrimitive
            ?.int
            ?: error("Prompt preferences schemaVersion is required.")
    return when (schemaVersion) {
        1 -> {
            val wire =
                preferencesJson.decodeFromString(
                    PromptPreferencesWireV1.serializer(),
                    encoded,
                )
            PromptPreferences(
                fontScale = wire.fontScale,
                readingHorizonFraction = wire.readingHorizonFraction,
                mirrorHorizontally = wire.mirrorHorizontally,
            )
        }
        2 -> {
            val wire =
                preferencesJson.decodeFromString(
                    PromptPreferencesWireV2.serializer(),
                    encoded,
                )
            PromptPreferences(
                fontScale = wire.fontScale,
                readingHorizonFraction = wire.readingHorizonFraction,
                mirrorHorizontally = wire.mirrorHorizontally,
                predictiveCursorEnabled = wire.predictiveCursorEnabled,
                focusStripEnabled = wire.focusStripEnabled,
                phraseBiasEnabled = wire.phraseBiasEnabled,
            )
        }
        else -> error("Unsupported prompt preferences schema $schemaVersion.")
    }
}
