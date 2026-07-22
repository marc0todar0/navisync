package eu.todaro.navisync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.rememberCoroutineScope
import eu.todaro.navisync.domain.PushProgress
import eu.todaro.navisync.domain.SyncProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    hasStorageAccess: () -> Boolean,
    requestStorageAccess: () -> Unit,
    requestNotifications: () -> Unit,
) {
    var hasAccess by remember { mutableStateOf(hasStorageAccess()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("NaviSync") }) },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasAccess) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Accesso allo storage", style = MaterialTheme.typography.titleMedium)
                        Text("NaviSync deve poter scrivere la tua musica nella cartella scelta. Concedi l'accesso a tutti i file.")
                        Button(onClick = requestStorageAccess) { Text("Concedi accesso") }
                        OutlinedButton(onClick = { hasAccess = hasStorageAccess() }) { Text("Ho concesso, ricontrolla") }
                    }
                }
            }

            SetupSection(vm)
            HorizontalDivider()

            var tab by remember { mutableIntStateOf(0) }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Scarica") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Carica playlist") })
            }
            when (tab) {
                0 -> SyncSection(vm, enabled = hasAccess)
                else -> PushSection(vm)
            }
        }
    }
}

@Composable
private fun SetupSection(vm: MainViewModel) {
    Text("Server Navidrome", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        value = vm.baseUrl,
        onValueChange = { vm.baseUrl = it },
        label = { Text("URL server (es. https://music.miosito.it)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = vm.username,
        onValueChange = { vm.username = it },
        label = { Text("Username") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = vm.password,
        onValueChange = { vm.password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = vm.rootFolder,
        onValueChange = { vm.rootFolder = it },
        label = { Text("Cartella di destinazione") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = vm.downloadCovers, onCheckedChange = { vm.downloadCovers = it })
        Spacer(Modifier.height(0.dp)); Text("  Scarica copertine (cover.jpg)")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = vm.syncPlaylists, onCheckedChange = { vm.syncPlaylists = it })
        Text("  Sincronizza playlist (.m3u8)")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = vm.syncFavorites, onCheckedChange = { vm.syncFavorites = it })
        Text("  Sincronizza preferiti (★) come playlist")
    }
    if (vm.syncFavorites) {
        val favName = vm.favoritesName.trim().ifBlank { "Liked Songs" }
        OutlinedTextField(
            value = vm.favoritesName,
            onValueChange = { vm.favoritesName = it },
            label = { Text("Nome playlist preferiti") },
            supportingText = {
                Text("In download genera «$favName.m3u8» dai preferiti; in upload una playlist con questo nome aggiorna le stelle sul server.")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = vm.mirrorMode, onCheckedChange = { vm.mirrorMode = it })
        Text("  Mirror (elimina i file non più sul server)")
    }
    Text("Download paralleli: ${vm.parallelism}")
    Slider(
        value = vm.parallelism.toFloat(),
        onValueChange = { vm.parallelism = it.toInt().coerceIn(1, 8) },
        valueRange = 1f..8f,
        steps = 6,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { vm.testConnection() }) { Text("Verifica") }
        Button(onClick = { vm.save() }) { Text("Salva") }
    }
    when (val s = vm.testState) {
        is TestState.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.height(18.dp)); Text("  Verifica in corso…")
        }
        is TestState.Ok -> Text("✓ Connessione riuscita", color = MaterialTheme.colorScheme.primary)
        is TestState.Error -> Text("✗ ${s.message}", color = MaterialTheme.colorScheme.error)
        TestState.Idle -> {}
    }
}

@Composable
private fun SyncSection(vm: MainViewModel, enabled: Boolean) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()

    Text("Sincronizzazione", style = MaterialTheme.typography.titleMedium)
    Button(
        onClick = { vm.startSync() },
        enabled = enabled && !running && vm.baseUrl.isNotBlank() && vm.rootFolder.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (running) "Sync in corso…" else "Sync ora") }

    if (progress.phase != SyncProgress.Phase.IDLE) {
        val label = when (progress.phase) {
            SyncProgress.Phase.INDEXING -> "Indicizzazione…"
            SyncProgress.Phase.DOWNLOADING -> "Download ${progress.doneFiles}/${progress.totalFiles}"
            SyncProgress.Phase.PLAYLISTS -> "Esporto playlist…"
            SyncProgress.Phase.DONE -> "Completato"
            SyncProgress.Phase.FAILED -> "Errore"
            SyncProgress.Phase.IDLE -> ""
        }
        Text(label)
        if (progress.phase == SyncProgress.Phase.DOWNLOADING && progress.totalFiles > 0) {
            LinearProgressIndicator(
                progress = { progress.doneFiles.toFloat() / progress.totalFiles },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (progress.phase == SyncProgress.Phase.INDEXING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (progress.currentFile.isNotBlank()) {
            Text(progress.currentFile, style = MaterialTheme.typography.bodySmall)
        }
        Text("Scaricati: ${progress.bytesDownloaded / (1024 * 1024)} MB", style = MaterialTheme.typography.bodySmall)
    }

    if (progress.log.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                progress.log.takeLast(12).forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PushSection(vm: MainViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by vm.pushProgress.collectAsStateWithLifecycle()
    val running by vm.pushRunning.collectAsStateWithLifecycle()
    val plans by vm.pushPlans.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    val name = displayName(context, uri) ?: return@mapNotNull null
                    if (!name.endsWith(".m3u", true) && !name.endsWith(".m3u8", true)) return@mapNotNull null
                    val content = runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    }.getOrNull() ?: return@mapNotNull null
                    name to content
                }
            }
            if (files.isNotEmpty()) vm.analyzeM3u(files)
        }
    }

    Text("Carica playlist su Navidrome", style = MaterialTheme.typography.titleMedium)
    Text(
        "Seleziona file .m3u/.m3u8 (es. esportati da Auxio). Le tracce vengono abbinate alla libreria: " +
            "playlist con nome già esistente vengono sostituite, le altre create.",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        onClick = { picker.launch(arrayOf("*/*")) },
        enabled = !running && vm.baseUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Seleziona file .m3u/.m3u8") }

    if (plans.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                plans.forEach { plan ->
                    val action = when {
                        plan.skipped -> "saltata"
                        plan.isFavorites -> "preferiti ★"
                        plan.existingId != null -> "sostituisci"
                        else -> "crea"
                    }
                    val dup = if (plan.duplicateNames) " ⚠ nome duplicato" else ""
                    Text(
                        "${plan.name} — ${plan.match.matched}/${plan.match.total} abbinate · $action$dup",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (plan.skipped) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    if (plan.skipped && plan.match.unmatched.isNotEmpty()) {
                        plan.match.unmatched.take(10).forEach { u ->
                            Text("   ✗ $u", style = MaterialTheme.typography.bodySmall)
                        }
                        if (plan.match.unmatched.size > 10) {
                            Text("   … e altre ${plan.match.unmatched.size - 10}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Button(
            onClick = { vm.pushPlaylists() },
            enabled = !running && plans.any { !it.skipped },
            modifier = Modifier.fillMaxWidth(),
        ) {
            val n = plans.count { !it.skipped }
            Text(if (running) "Caricamento…" else "Carica su Navidrome ($n)")
        }
    }

    if (progress.phase != PushProgress.Phase.IDLE) {
        val label = when (progress.phase) {
            PushProgress.Phase.ANALYZING -> "Analisi… ${progress.done}/${progress.total}"
            PushProgress.Phase.PUSHING -> "Caricamento ${progress.done}/${progress.total}"
            PushProgress.Phase.DONE -> "Completato"
            PushProgress.Phase.FAILED -> "Errore: ${progress.error ?: ""}"
            PushProgress.Phase.IDLE -> ""
        }
        Text(label)
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (progress.log.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    progress.log.takeLast(12).forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return c.getString(idx)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}
