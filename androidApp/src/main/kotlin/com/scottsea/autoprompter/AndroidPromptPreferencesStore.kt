package com.scottsea.autoprompter

import android.content.Context
import com.scottsea.autoprompter.core.settings.PromptPreferences
import com.scottsea.autoprompter.core.settings.PromptPreferencesStore
import com.scottsea.autoprompter.core.settings.decodePromptPreferences
import com.scottsea.autoprompter.core.settings.encodePromptPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class AndroidPromptPreferencesStore(context: Context) : PromptPreferencesStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): PromptPreferences = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(PROMPT_KEY, null)
            ?: return@withContext PromptPreferences.DEFAULT
        decodePromptPreferences(encoded)
    }

    override suspend fun save(preferences: PromptPreferences) = withContext(Dispatchers.IO) {
        val encoded = encodePromptPreferences(preferences)
        if (!this@AndroidPromptPreferencesStore.preferences.edit().putString(PROMPT_KEY, encoded).commit()) {
            throw IOException("Could not save prompt preferences.")
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "prompt-preferences"
        const val PROMPT_KEY = "schema-v1"
    }
}
