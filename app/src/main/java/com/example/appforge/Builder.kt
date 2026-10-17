package com.example.appforge

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

private const val WORKFLOW_PATH = ".github/workflows/build.yml"
private const val MARK = "AppForge" // penanda repo buatan AppForge (di deskripsi repo)

class BuildResult(val success: Boolean, val message: String, val log: String, val hasApk: Boolean)

private fun cleanLog(raw: String): String =
    raw.replace("\uFEFF", "").replace(Regex("(?m)^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z "), "")

/** Blocking: panggil dari thread IO. */
fun runBuild(store: Store, project: String, apkOut: File, log: (String) -> Unit): BuildResult {
    if (store.token.isBlank()) error("Isi GitHub token dulu di Pengaturan")
    val gh = GitHub(store.token)
    val owner = gh.user()
    log("Login sebagai $owner")

    val persistent = store.persistent(project)
    val slug = project.lowercase().replace(Regex("[^a-z0-9-]"), "-")
    val repo: String
    val branch: String
    if (persistent) {
        repo = "appforge-$slug"
        val info = gh.repoInfo(owner, repo)
        if (info == null) {
            branch = gh.createRepo(repo, MARK)
            log("Repo tetap dibuat: $repo")
        } else {
            val desc = if (info.isNull("description")) "" else info.getString("description")
            if (desc != MARK) error("Repo $repo sudah ada dan bukan buatan AppForge. Ganti nama proyek atau matikan 'Repo tetap'.")
            branch = info.optString("default_branch", "main")
            log("Memakai repo tetap: $repo")
        }
    } else {
        repo = "appforge-$slug-${System.currentTimeMillis() / 1000}"
        branch = gh.createRepo(repo, MARK)
        log("Repo sementara dibuat: $repo")
    }

    try {
        val dir = File(store.root, project)
        val files = LinkedHashMap<String, ByteArray>()
        store.files(project).forEach { p ->
            val f = File(dir, p)
            if (f.length() > 0) files[p] = f.readBytes()
        }
        if (WORKFLOW_PATH !in files) files[WORKFLOW_PATH] = Templates.workflow.toByteArray()

        log("Mengirim ${files.size} file...")
        val up = gh.commitAll(owner, repo, branch, files, "AppForge build")
        log(if (up == 0) "Tidak ada perubahan file" else "$up file baru/berubah terkirim")

        val before = gh.latestRun(owner, repo)
        gh.dispatch(owner, repo, branch)
        log("Workflow dijalankan, menunggu runner...")

        var runId = 0L
        repeat(20) {
            if (runId == 0L) {
                val r = gh.latestRun(owner, repo)
                if (r > before) runId = r else Thread.sleep(3000)
            }
        }
        if (runId == 0L) error("Run tidak ditemukan")

        val seen = mutableSetOf<String>()
        var jobId = 0L
        var conclusion = ""
        while (true) {
            val (status, concl) = gh.runState(owner, repo, runId)
            val (jid, steps) = gh.jobs(owner, repo, runId)
            if (jid != 0L) jobId = jid
            steps.forEach { s ->
                val line = "• ${s.name}: ${s.state}"
                if (seen.add(line)) log(line)
            }
            if (status == "completed") { conclusion = concl; break }
            Thread.sleep(5000)
        }

        val full = if (jobId != 0L) cleanLog(gh.jobLog(owner, repo, jobId)) else "(log tidak tersedia)"
        if (conclusion != "success") return BuildResult(false, "Build gagal ($conclusion)", full, false)

        var url: String? = null
        repeat(10) {
            if (url == null) {
                url = gh.artifactUrl(owner, repo, runId)
                if (url == null) Thread.sleep(3000)
            }
        }
        val artifact = url
            ?: return BuildResult(false, "Artifact APK tidak ditemukan (cek path upload-artifact)", full, false)
        log("Mengunduh APK...")
        val zip = gh.download(artifact)

        var found = false
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.endsWith(".apk")) {
                    apkOut.parentFile?.mkdirs()
                    apkOut.outputStream().use { z.copyTo(it) }
                    found = true
                    break
                }
                e = z.nextEntry
            }
        }
        if (!found) return BuildResult(false, "Tidak ada file .apk di dalam artifact", full, false)
        return BuildResult(true, "Berhasil", full, true)
    } finally {
        if (!persistent) runCatching { gh.deleteRepo(owner, repo) }
    }
}

/** Build berjalan di scope sendiri, jadi tidak terhenti saat pindah layar. */
object BuildManager {
    var running by mutableStateOf<String?>(null)
    var lastProject by mutableStateOf<String?>(null)
    var lastId by mutableStateOf<Long?>(null)
    var logs by mutableStateOf(listOf<String>())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(app: Context, store: Store, hist: HistoryStore, project: String) {
        if (running != null) return
        running = project
        lastProject = project
        lastId = null
        logs = emptyList()
        val id = System.currentTimeMillis()
        scope.launch {
            val tmp = File(app.cacheDir, "build-$id.apk")
            val res = try {
                runBuild(store, project, tmp) { line -> logs = logs + line }
            } catch (e: Exception) {
                BuildResult(false, e.message ?: e.toString(), logs.joinToString("\n"), false)
            }
            val secs = ((System.currentTimeMillis() - id) / 1000).toInt()
            val entry = Entry(id, project, res.success, secs, res.message, if (res.hasApk) tmp.length() else 0)
            hist.save(entry, res.log)
            if (res.hasApk) {
                tmp.copyTo(hist.apk(entry), overwrite = true)
                tmp.delete()
            }
            lastId = id
            running = null
        }
    }
}

fun installApk(ctx: Context, f: File) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    ctx.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
