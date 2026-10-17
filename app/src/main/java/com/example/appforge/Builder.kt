package com.example.appforge

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

private const val WORKFLOW_PATH = ".github/workflows/build.yml"

/** Kirim proyek ke repo sementara, jalankan GitHub Actions, unduh APK. */
suspend fun buildApk(ctx: Context, store: Store, project: String, log: (String) -> Unit): File =
    withContext(Dispatchers.IO) {
        if (store.token.isBlank()) error("Isi GitHub token dulu di Pengaturan")
        val gh = GitHub(store.token)
        val owner = gh.user()
        log("Login sebagai $owner")

        val repo = "appforge-" + project.lowercase().replace(Regex("[^a-z0-9-]"), "-") +
            "-" + System.currentTimeMillis() / 1000
        val branch = gh.createRepo(repo)
        log("Repo sementara dibuat: $repo")

        try {
            val dir = File(store.root, project)
            val files = store.files(project).filter { File(dir, it).length() > 0 }
            files.forEachIndexed { i, p ->
                gh.putFile(owner, repo, p, File(dir, p).readBytes())
                log("Unggah ${i + 1}/${files.size}: $p")
            }
            if (WORKFLOW_PATH !in files) {
                gh.putFile(owner, repo, WORKFLOW_PATH, Templates.workflow.toByteArray())
                log("Workflow bawaan dipasang")
            }

            gh.dispatch(owner, repo, branch)
            log("Workflow dijalankan, menunggu runner...")

            var runId = 0L
            repeat(20) {
                if (runId == 0L) {
                    runId = gh.latestRun(owner, repo)
                    if (runId == 0L) delay(3000)
                }
            }
            if (runId == 0L) error("Run tidak ditemukan")

            val seen = mutableSetOf<String>()
            var jobId = 0L
            var conclusion: String
            while (true) {
                val (status, concl) = gh.runState(owner, repo, runId)
                val (jid, steps) = gh.jobs(owner, repo, runId)
                if (jid != 0L) jobId = jid
                steps.forEach { s ->
                    val line = "• ${s.name}: ${s.state}"
                    if (seen.add(line)) log(line)
                }
                if (status == "completed") { conclusion = concl; break }
                delay(5000)
            }

            if (conclusion != "success") {
                if (jobId != 0L) log(gh.jobLog(owner, repo, jobId))
                error("Build gagal ($conclusion)")
            }

            var url: String? = null
            repeat(10) {
                if (url == null) {
                    url = gh.artifactUrl(owner, repo, runId)
                    if (url == null) delay(3000)
                }
            }
            val artifact = url ?: error("Artifact APK tidak ditemukan (cek path upload-artifact)")
            log("Mengunduh APK...")
            val zip = gh.download(artifact)

            val outDir = File(ctx.getExternalFilesDir(null), "apk").also { it.mkdirs() }
            val out = File(outDir, "$project.apk")
            var found = false
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name.endsWith(".apk")) {
                        out.outputStream().use { z.copyTo(it) }
                        found = true
                        break
                    }
                    e = z.nextEntry
                }
            }
            if (!found) error("Tidak ada file .apk di dalam artifact")
            log("✓ Selesai: ${out.name}")
            out
        } finally {
            runCatching { gh.deleteRepo(owner, repo) }
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
