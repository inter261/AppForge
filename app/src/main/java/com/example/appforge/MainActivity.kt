package com.example.appforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File

sealed class Screen {
    object Projects : Screen()
    object Settings : Screen()
    data class Files(val project: String) : Screen()
    data class Edit(val project: String, val path: String) : Screen()
    data class Build(val project: String) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        setContent { MaterialTheme { App(store) } }
    }
}

@Composable
fun App(store: Store) {
    var screen by remember { mutableStateOf<Screen>(Screen.Projects) }

    BackHandler(screen != Screen.Projects) {
        screen = when (val s = screen) {
            is Screen.Edit -> Screen.Files(s.project)
            is Screen.Build -> Screen.Files(s.project)
            else -> Screen.Projects
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            when (val s = screen) {
                is Screen.Projects -> ProjectsScreen(store) { screen = it }
                is Screen.Settings -> SettingsScreen(store) { screen = Screen.Projects }
                is Screen.Files -> FilesScreen(store, s.project) { screen = it }
                is Screen.Edit -> EditScreen(store, s.project, s.path) { screen = Screen.Files(s.project) }
                is Screen.Build -> BuildScreen(store, s.project)
            }
        }
    }
}

@Composable
fun NameDialog(title: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(t, { t = it }, singleLine = true) },
        confirmButton = { TextButton({ if (t.isNotBlank()) onOk(t.trim()) }) { Text("OK") } },
        dismissButton = { TextButton(onDismiss) { Text("Batal") } }
    )
}

@Composable
fun ProjectsScreen(store: Store, go: (Screen) -> Unit) {
    var list by remember { mutableStateOf(store.projects()) }
    var dialog by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("AppForge", style = MaterialTheme.typography.headlineSmall)
        TextButton({ go(Screen.Settings) }) { Text("Pengaturan") }
    }
    Button({ dialog = true }) { Text("Proyek baru (template Hello)") }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        list.forEach { name ->
            Text(name, Modifier.fillMaxWidth().clickable { go(Screen.Files(name)) }.padding(vertical = 14.dp))
        }
    }
    if (dialog) NameDialog("Nama proyek", { dialog = false }) { n ->
        store.create(n)
        list = store.projects()
        dialog = false
    }
}

@Composable
fun FilesScreen(store: Store, p: String, go: (Screen) -> Unit) {
    val files = remember { mutableStateOf(store.files(p)) }
    var dialog by remember { mutableStateOf(false) }

    Text(p, style = MaterialTheme.typography.headlineSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ go(Screen.Build(p)) }) { Text("Build") }
        OutlinedButton({ dialog = true }) { Text("File baru") }
        TextButton({ store.delete(p); go(Screen.Projects) }) { Text("Hapus") }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        files.value.forEach { f ->
            Text(f, Modifier.fillMaxWidth().clickable { go(Screen.Edit(p, f)) }.padding(vertical = 12.dp))
        }
    }
    if (dialog) NameDialog("Path file (mis. app/src/main/Foo.kt)", { dialog = false }) { n ->
        runCatching { store.write(p, n, "") }
        files.value = store.files(p)
        dialog = false
    }
}

@Composable
fun ColumnScope.EditScreen(store: Store, p: String, path: String, back: () -> Unit) {
    var text by remember { mutableStateOf(store.read(p, path)) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(path, Modifier.weight(1f))
        Button({ store.write(p, path, text); back() }) { Text("Simpan") }
    }
    OutlinedTextField(
        text, { text = it },
        Modifier.fillMaxWidth().weight(1f),
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    )
}

@Composable
fun BuildScreen(store: Store, p: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var logs by remember { mutableStateOf(listOf<String>()) }
    var running by remember { mutableStateOf(false) }
    var apk by remember { mutableStateOf<File?>(null) }

    Text("Build: $p", style = MaterialTheme.typography.headlineSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                scope.launch {
                    running = true; apk = null; logs = emptyList()
                    try {
                        apk = buildApk(ctx, store, p) { line -> logs = logs + line }
                    } catch (e: Exception) {
                        logs = logs + "✗ ${e.message}"
                    }
                    running = false
                }
            },
            enabled = !running
        ) { Text(if (running) "Sedang build..." else "Mulai build") }
        apk?.let { f -> OutlinedButton({ installApk(ctx, f) }) { Text("Install APK") } }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        logs.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

@Composable
fun SettingsScreen(store: Store, back: () -> Unit) {
    var t by remember { mutableStateOf(store.token) }
    Text("Pengaturan", style = MaterialTheme.typography.headlineSmall)
    OutlinedTextField(
        t, { t = it }, Modifier.fillMaxWidth(),
        label = { Text("GitHub token") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true
    )
    Text("Buat token (classic) di GitHub: Settings > Developer settings > Personal access tokens. Centang scope: repo, workflow, delete_repo.")
    Button({ store.token = t.trim(); back() }) { Text("Simpan") }
}
