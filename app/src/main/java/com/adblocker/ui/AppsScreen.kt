package com.adblocker.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap

@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrNull()
            ?.toBitmap()
            ?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
    }
}

@Composable
fun AppsScreen(vm: SettingsViewModel) {
    val apps by vm.installedApps.collectAsState()
    val bypass by vm.bypassApps.collectAsState(initial = emptySet())
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(true) }
    val filtered = remember(apps, query, showSystem) {
        apps
            .filter { showSystem || !it.isSystem }
            .let { if (query.isBlank()) it else it.filter { it.label.contains(query, ignoreCase = true) } }
    }
    val notFiltered = apps.count { it.packageName in bypass }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "${apps.count { !it.isSystem }} user + ${apps.count { it.isSystem }} system apps · $notFiltered not filtered",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {}),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().clickable { showSystem = !showSystem },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = showSystem, onCheckedChange = { showSystem = it })
            Spacer(Modifier.width(8.dp))
            Text("Show system apps", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.packageName }) { app ->
                val blockingOn = app.packageName !in bypass
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.packageName, Modifier.size(40.dp).padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
                    }
                    Switch(
                        checked = blockingOn,
                        onCheckedChange = { vm.setAppBlocking(app.packageName, it) },
                    )
                }
            }
        }
    }
}
