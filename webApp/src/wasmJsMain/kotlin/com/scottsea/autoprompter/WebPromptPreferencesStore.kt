@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.scottsea.autoprompter

import com.scottsea.autoprompter.core.settings.PromptPreferences
import com.scottsea.autoprompter.core.settings.PromptPreferencesStore
import com.scottsea.autoprompter.core.settings.decodePromptPreferences
import com.scottsea.autoprompter.core.settings.encodePromptPreferences

class WebPromptPreferencesStore : PromptPreferencesStore {
    override suspend fun load(): PromptPreferences {
        val encoded = readLocalStorage(PROMPT_KEY) ?: return PromptPreferences.DEFAULT
        return decodePromptPreferences(encoded)
    }

    override suspend fun save(preferences: PromptPreferences) {
        writeLocalStorage(PROMPT_KEY, encodePromptPreferences(preferences))
    }

    private companion object {
        const val PROMPT_KEY = "auto-prompter.prompt-preferences.v1"
    }
}

@JsFun("(key) => window.localStorage.getItem(key)")
private external fun readLocalStorage(key: String): String?

@JsFun("(key, value) => window.localStorage.setItem(key, value)")
private external fun writeLocalStorage(key: String, value: String)
