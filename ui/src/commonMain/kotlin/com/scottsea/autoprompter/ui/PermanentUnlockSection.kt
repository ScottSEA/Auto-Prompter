package com.scottsea.autoprompter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.scottsea.autoprompter.core.entitlement.PermanentEntitlementState
import com.scottsea.autoprompter.core.entitlement.PermanentOwnership
import com.scottsea.autoprompter.core.entitlement.PermanentUnlockBillingGateway
import com.scottsea.autoprompter.core.entitlement.PermanentUnlockCatalogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PermanentUnlockSection(
    state: PermanentEntitlementState?,
    gateway: PermanentUnlockBillingGateway?,
    commerceUnavailableMessage: String?,
) {
    if (state == null && commerceUnavailableMessage == null) return

    val catalog =
        gateway?.catalog?.collectAsState()?.value ?: PermanentUnlockCatalogState.Unknown
    val scope = rememberCoroutineScope()
    var operation by remember(gateway) { mutableStateOf<Job?>(null) }
    var operationFailure by remember(gateway) { mutableStateOf<String?>(null) }

    fun launchOperation(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operationFailure = null
        operation =
            scope.launch {
                try {
                    block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    operationFailure = failure.message ?: "Google Play operation failed."
                } finally {
                    operation = null
                }
            }
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Permanent unlock", style = MaterialTheme.typography.labelLarge)
            if (state != null) {
                Text(permanentUnlockStatus(state), style = MaterialTheme.typography.bodyLarge)
            }
            commerceUnavailableMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall)
            }
            operationFailure?.let { failure ->
                Text(failure, color = MaterialTheme.colorScheme.error)
            }
            if (gateway != null && state != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!state.hasPermanentUnlock && catalog is PermanentUnlockCatalogState.Available) {
                        Button(
                            onClick = { launchOperation(gateway::launchPurchase) },
                            enabled = operation?.isActive != true,
                        ) {
                            Text("Unlock permanently — ${catalog.offer.formattedPrice}")
                        }
                    }
                    OutlinedButton(
                        onClick = { launchOperation(gateway::restoreOwnership) },
                        enabled = operation?.isActive != true,
                    ) {
                        Text("Restore purchase")
                    }
                }
                when (catalog) {
                    is PermanentUnlockCatalogState.Unavailable ->
                        Text(catalog.detail, style = MaterialTheme.typography.bodySmall)
                    PermanentUnlockCatalogState.Loading ->
                        Text("Checking Google Play…", style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
            }
        }
    }
}

internal fun permanentUnlockStatus(state: PermanentEntitlementState): String =
    when (state.ownership) {
        PermanentOwnership.Free -> "Free version"
        is PermanentOwnership.Pending -> "Purchase pending. Premium features unlock after completion."
        is PermanentOwnership.StoreVerified -> "Unlocked permanently."
        is PermanentOwnership.CachedVerified ->
            "Unlocked permanently. Offline access remains available."
        is PermanentOwnership.Revoked ->
            "Permanent unlock is no longer owned by this Google Play account."
    }
