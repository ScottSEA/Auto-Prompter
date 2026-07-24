package com.scottsea.autoprompter

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import com.scottsea.autoprompter.ui.TracerApp
import com.scottsea.autoprompter.webspeech.browserLiveSpeechRuntime
import com.scottsea.autoprompter.webstore.IndexedDbDocumentStore
import com.scottsea.autoprompter.webstore.openIndexedDbDocumentStore
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private sealed interface BootstrapState {
    data object Opening : BootstrapState
    data class Ready(val store: IndexedDbDocumentStore) : BootstrapState
    data class Failed(val message: String) : BootstrapState
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Durable web storage: the app is backed by an IndexedDB DocumentStore that survives reloads
    // and browser restarts (the web analogue of Android's Room database). Opening/migrating
    // IndexedDB is asynchronous, so we drive it from a retained page-lifetime coroutine scope --
    // NOT GlobalScope -- and keep the connection open for the lifetime of the page.
    //
    // If opening fails we surface a fatal bootstrap error instead of silently substituting an
    // in-memory store: a memory fallback would falsely advertise durability and quietly discard
    // the user's work on the next reload.
    val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = mutableStateOf<BootstrapState>(BootstrapState.Opening)

    // The browser live speech runtime is feature-detected once here (unprefixed SpeechRecognition,
    // then webkitSpeechRecognition). On a browser without it, this returns an explicit unsupported
    // runtime; either way the tracer's live speech card degrades honestly. It owns its own mic and
    // is online / vendor-dependent -- see the README live speech section.
    val speech = browserLiveSpeechRuntime()

    bootstrapScope.launch {
        try {
            val store = openIndexedDbDocumentStore()
            state.value = BootstrapState.Ready(store)
            store.isOpenFlow.first { open -> !open }
            if ((state.value as? BootstrapState.Ready)?.store === store) {
                state.value = BootstrapState.Failed(
                    "Local database connection closed after a browser storage change. Reload this page.",
                )
            }
        } catch (throwable: Throwable) {
            state.value = BootstrapState.Failed(throwable.message ?: throwable.toString())
        }
    }

    ComposeViewport(document.body!!) {
        when (val current = state.value) {
            is BootstrapState.Ready -> TracerApp(
                store = current.store,
                speech = speech,
                onStoreFailure = { failure ->
                    state.value = BootstrapState.Failed(
                        "Local database operation failed: " +
                            "${failure.message ?: failure}. Reload this page.",
                    )
                },
            )
            BootstrapState.Opening -> BootstrapMessage("Opening local database…")
            is BootstrapState.Failed -> BootstrapMessage(
                message = "Failed to open local storage: ${current.message}",
                isError = true,
            )
        }
    }
}

@Composable
private fun BootstrapMessage(message: String, isError: Boolean = false) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = message,
                    color = if (isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
