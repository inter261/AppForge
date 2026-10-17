package com.example.appforge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class Screen {
    object Projects : Screen()
    object Settings : Screen()
    data class Files(val project: String) : Screen()
    data class Edit(
        val project: String,
        val path: String,
        val line: Int = 0,
        val note: String = "",
        val backTo: Screen? = null
    ) : Screen()
    data class Build(val project: String) : Screen()
    data class Hist(val project: String) : Screen()
    data class LogView(val project: String, val id: Long) : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        val hist = HistoryStore(applicationContext)
        setContent { MaterialTheme { App(store, hist) } }
    }
}

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("AppForge", text))
}

fun fmt(ms: Long): String = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
fun App(store: Store, hist: HistoryStore) {
    var screen by remember { mutableStateOf<Screen>(Screen.Projects) }

    BackHandler(screen != Screen.Projects) {
        screen = when (val s = screen) {
            is Screen.Edit -> s.backTo ?: Screen.Files(s.project)
            is Screen.Build -> Screen.Files(s.project)
            is Screen.Hist -> Screen.Files(s.project)
            is Screen.LogView -> Screen.Hist(s.project)
            else -> Screen.Projects
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            val go: (Screen) -> Unit = { screen = it }
            when (val s = screen) {
                is Screen.Projects -> ProjectsScreen(store, go)
                is Screen.Settings -> SettingsScreen(store) { screen = Screen.Projects }
                is Screen.Files -> FilesScreen(store, s.project, go)
                is Screen.Edit -> EditScreen(store, s) { screen = s.backTo ?: Screen.Files(s.project) }
                is Screen.Build -> BuildScreen(store, hist, s.project, go)
                is Screen.Hist -> HistScreen(hist, s.project, go)
                is Screen.LogView -> LogScreen(store, hist, s, go)
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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(store.projects()) }
    var dialog by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            msg = "Mengimpor..."
            msg = try {
                val r = withContext(Dispatchers.IO) { importZip(ctx, store, uri) }
                list = store.projects()
                "Diimpor: $r"
            } catch (e: Exception) {
                "Gagal impor: ${e.message}"
            }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("AppForge", style = MaterialTheme.typography.headlineSmall)
        TextButton({ go(Screen.Settings) }) { Text("Pengaturan") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ dialog = true }) { Text("Proyek baru") }
        OutlinedButton({ picker.launch(arrayOf("*/*")) }) { Text("Import ZIP") }
    }
    if (msg.isNotEmpty()) Text(msg, Modifier.padding(top = 8.dp))
    Column(Modifier.verticalScroll(rememberScrollState())) {
        list.forEach { name ->
            Text(name, Modifier.fillMaxWidth().clickable { go(Screen.Files(name)) }.padding(vertical = 14.dp))
        }
    }
    if (dialog) NameDialog("Nama proyek (template Hello)", { dialog = false }) { n ->
        store.create(n)
        list = store.projects()
        dialog = false
    }
}

@Composable
fun FilesScreen(store: Store, p: String, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateOf(store.files(p)) }
    var dialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var persistent by remember { mutableStateOf(store.persistent(p)) }
    var msg by remember { mutableStateOf("") }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            msg = try {
                withContext(Dispatchers.IO) { exportZip(ctx, store, p, uri) }
                "ZIP berhasil diekspor"
            } catch (e: Exception) {
                "Gagal ekspor: ${e.message}"
            }
        }
    }

    Text(p, style = MaterialTheme.typography.headlineSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ go(Screen.Build(p)) }) { Text("Build") }
        OutlinedButton({ go(Screen.Hist(p)) }) { Text("Riwayat") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ dialog = true }) { Text("File baru") }
        OutlinedButton({ export.launch("$p.zip") }) { Text("Ekspor") }
        TextButton({ confirmDelete = true }) { Text("Hapus") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(persistent, { persistent = it; store.setPersistent(p, it) })
        Spacer(Modifier.width(8.dp))
        Text("Repo tetap (build berikutnya lebih cepat)")
    }
    if (msg.isNotEmpty()) Text(msg)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        files.value.forEach { f ->
            Text(f, Modifier.fillMaxWidth().clickable { go(Screen.Edit(p, f, backTo = Screen.Files(p))) }.padding(vertical = 12.dp))
        }
    }
    if (dialog) NameDialog("Path file (mis. app/src/main/Foo.kt)", { dialog = false }) { n ->
        runCatching { store.write(p, n, "") }
        files.value = store.files(p)
        dialog = false
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Hapus proyek $p?") },
        text = { Text("Semua file proyek di HP akan dihapus. Ekspor ZIP dulu kalau perlu.") },
        confirmButton = { TextButton({ store.delete(p); go(Screen.Projects) }) { Text("Hapus") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Batal") } }
    )
}

private fun offsetOfLine(text: String, line: Int): Int {
    if (line <= 1) return 0
    var off = 0
    var l = 1
    while (l < line) {
        val i = text.indexOf('\n', off)
        if (i < 0) return text.length
        off = i + 1
        l++
    }
    return off
}

@Composable
fun ColumnScope.EditScreen(store: Store, s: Screen.Edit, back: () -> Unit) {
    val initial = remember(s) { store.read(s.project, s.path) }
    var tf by remember(s) { mutableStateOf(TextFieldValue(initial, TextRange(offsetOfLine(initial, s.line)))) }
    val focus = remember { FocusRequester() }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(s.path, Modifier.weight(1f))
        Button({ store.write(s.project, s.path, tf.text); back() }) { Text("Simpan") }
    }
    if (s.note.isNotEmpty()) Text("Baris ${s.line}: ${s.note}", color = MaterialTheme.colorScheme.error)
    OutlinedTextField(
        tf, { tf = it },
        Modifier.fillMaxWidth().weight(1f).focusRequester(focus),
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    )
    LaunchedEffect(s) { if (s.line > 0) focus.requestFocus() }
}

@Composable
fun BuildScreen(store: Store, hist: HistoryStore, p: String, go: (Screen) -> Unit) {
    val app = LocalContext.current.applicationContext
    val running = BuildManager.running
    val busyHere = running == p
    val showHere = BuildManager.lastProject == p
    val lastId = BuildManager.lastId

    Text("Build: $p", style = MaterialTheme.typography.headlineSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ BuildManager.start(app, store, hist, p) }, enabled = running == null) {
            Text(if (busyHere) "Sedang build..." else "Mulai build")
        }
        if (!busyHere && showHere && lastId != null) {
            OutlinedButton({ go(Screen.LogView(p, lastId)) }) { Text("Lihat hasil") }
        }
    }
    if (running != null && !busyHere) Text("Build proyek lain sedang berjalan: $running")
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (showHere) BuildManager.logs.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

@Composable
fun HistScreen(hist: HistoryStore, p: String, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(hist.list(p)) }

    Text("Riwayat: $p", style = MaterialTheme.typography.headlineSmall)
    if (list.isEmpty()) Text("Belum ada build.")
    Column(Modifier.verticalScroll(rememberScrollState())) {
        list.forEach { e ->
            Column(Modifier.fillMaxWidth().clickable { go(Screen.LogView(p, e.id)) }.padding(vertical = 10.dp)) {
                val size = if (e.apkSize > 0) "  •  %.1f MB".format(e.apkSize / 1048576.0) else ""
                Text("${if (e.success) "✓" else "✗"} ${fmt(e.id)}  •  ${e.seconds} dtk$size")
                Row {
                    if (hist.apk(e).exists()) TextButton({ installApk(ctx, hist.apk(e)) }) { Text("Install") }
                    TextButton({ hist.delete(e); list = hist.list(p) }) { Text("Hapus") }
                }
            }
        }
    }
}

@Composable
fun ColumnScope.LogScreen(store: Store, hist: HistoryStore, s: Screen.LogView, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val e = remember(s) { hist.get(s.project, s.id) }
    if (e == null) {
        Text("Entri tidak ditemukan")
        return
    }
    val log = remember(s) { hist.log(e) }
    val errors = remember(s) { parseErrors(log, store.files(s.project).toSet()) }

    Text(if (e.success) "✓ Berhasil • ${e.seconds} dtk" else "✗ ${e.message}", style = MaterialTheme.typography.titleMedium)
    Text(fmt(e.id))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (hist.apk(e).exists()) Button({ installApk(ctx, hist.apk(e)) }) { Text("Install APK") }
        OutlinedButton({ copyText(ctx, log.takeLast(100_000)) }) { Text("Salin log") }
        if (!e.success) OutlinedButton({ copyText(ctx, errorSummary(errors, log)) }) { Text("Salin error") }
    }
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
        if (errors.isNotEmpty()) {
            Text("Error (${errors.size}), ketuk untuk buka file:", style = MaterialTheme.typography.titleSmall)
            errors.forEach { er ->
                Text(
                    "${er.path}:${er.line}\n${er.msg}",
                    Modifier.fillMaxWidth()
                        .clickable { go(Screen.Edit(s.project, er.path, er.line, er.msg, s)) }
                        .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Text("Log (400 baris terakhir; 'Salin log' menyalin bagian akhirnya):", style = MaterialTheme.typography.titleSmall)
        Text(log.lines().takeLast(400).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
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
