package com.scottsea.autoprompter

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.scottsea.autoprompter.core.document.store.InMemoryDocumentStore
import com.scottsea.autoprompter.ui.TracerApp
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Web keeps the in-memory reference store: one process-only instance for the page's lifetime.
    // This is NOT persistence -- everything is lost on reload. A durable web adapter (Room's
    // WebWorker/OPFS driver) is deferred to a later slice behind this same DocumentStore seam.
    val store = InMemoryDocumentStore()
    ComposeViewport(document.body!!) {
        TracerApp(store)
    }
}
