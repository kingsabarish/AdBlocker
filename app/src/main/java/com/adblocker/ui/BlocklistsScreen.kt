package com.adblocker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BlocklistsScreen(vm: SettingsViewModel) {
    val allLists by vm.allLists.collectAsState(initial = emptyList())
    val customLists by vm.customLists.collectAsState(initial = emptyList())
    val rules by vm.customRules.collectAsState(initial = "")
    var newUrl by remember { mutableStateOf("") }
    var rulesText by remember { mutableStateOf(rules) }

    LaunchedEffect(rules) { rulesText = rules }

    val defaultLists = allLists.filter { it.isDefault }
    val userCustomLists = allLists.filter { !it.isDefault }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Section: Built-in Blocklist Sources ──
        item { Text("Blocklist Sources", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "Built-in ad/tracker/malware blocking lists. Toggle on/off; only enabled lists are fetched when the VPN starts.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(defaultLists, key = { it.url }) { entry ->
            BlocklistRow(
                label = entry.label ?: entry.url,
                description = entry.description,
                url = entry.url,
                enabled = entry.enabled,
                isDefault = true,
                onToggle = { vm.setDefaultListEnabled(entry.url, it) },
                onDelete = null,
            )
        }

        // ── Section: Custom Blocklist URLs ──
        item { Spacer(Modifier.height(12.dp)) }
        item { Text("Custom Blocklist URLs", style = MaterialTheme.typography.titleMedium) }
        item {
            Text(
                "Add your own hosts/AdAway formatted blocklist URLs. Toggle on/off; only enabled lists are fetched.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("https://example.com/blocklist.txt") },
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.addUrl(newUrl); newUrl = "" }) { Text("Add") }
            }
        }
        items(userCustomLists, key = { it.url }) { entry ->
            BlocklistRow(
                label = entry.label ?: entry.url,
                description = entry.description,
                url = entry.url,
                enabled = entry.enabled,
                isDefault = false,
                onToggle = { vm.setBlocklistEnabled(entry.url, it) },
                onDelete = { vm.removeUrl(entry.url) },
            )
        }

        // ── Section: Custom Rules ──
        item { Spacer(Modifier.height(12.dp)) }
        item { Text("Custom Rules", style = MaterialTheme.typography.titleMedium) }
        item {
            Text("One domain per line, e.g. ads.example.com", style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(
                value = rulesText,
                onValueChange = { rulesText = it },
                modifier = Modifier.fillMaxWidth().height(160.dp),
                placeholder = { Text("ads.example.com\n*.tracker.net") },
            )
        }
        item {
            Button(onClick = { vm.saveRules(rulesText) }) { Text("Save rules") }
        }
    }
}

@Composable
private fun BlocklistRow(
    label: String,
    description: String?,
    url: String,
    enabled: Boolean,
    isDefault: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (description != null) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove")
                }
            }
        }
    }
}
