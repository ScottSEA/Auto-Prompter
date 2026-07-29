package com.scottsea.autoprompter.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class PromptPreferences(
    val fontScale: Float,
    val readingHorizonFraction: Float,
    val mirrorHorizontally: Boolean,
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

fun encodePromptPreferences(preferences: PromptPreferences): String =
    preferencesJson.encodeToString(
        PromptPreferencesWireV1.serializer(),
        PromptPreferencesWireV1(
            schemaVersion = 1,
            fontScale = preferences.fontScale,
            readingHorizonFraction = preferences.readingHorizonFraction,
            mirrorHorizontally = preferences.mirrorHorizontally,
        ),
    )

fun decodePromptPreferences(encoded: String): PromptPreferences {
    val wire =
        preferencesJson.decodeFromString(
            PromptPreferencesWireV1.serializer(),
            encoded,
        )
    require(wire.schemaVersion == 1) {
        "Unsupported prompt preferences schema ${wire.schemaVersion}."
    }
    return PromptPreferences(
        fontScale = wire.fontScale,
        readingHorizonFraction = wire.readingHorizonFraction,
        mirrorHorizontally = wire.mirrorHorizontally,
    )
}
