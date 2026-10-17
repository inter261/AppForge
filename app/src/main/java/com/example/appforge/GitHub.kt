package com.example.appforge

import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Step(val name: String, val state: String)

private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k)

class GitHub(private val token: String) {
    private val http = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()
    private val api = "https://api.github.com"

    private fun builder(url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")

    private fun send(r: Request): Pair<Int, String> =
        http.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }

    private fun get(path: String) = send(builder(api + path).build())
    private fun post(path: String, body: String) =
        send(builder(api + path).post(body.toRequestBody(json)).build())

    fun user(): String {
        val (c, b) = get("/user")
        if (c != 200) error("Token ditolak (HTTP $c)")
        return JSONObject(b).getString("login")
    }

    /** Mengembalikan nama default branch. */
    fun createRepo(name: String): String {
        val body = JSONObject().put("name", name).put("private", true).put("auto_init", true).toString()
        val (c, b) = post("/user/repos", body)
        if (c != 201) error("Gagal membuat repo (HTTP $c): ${b.take(200)}")
        return JSONObject(b).str("default_branch").ifEmpty { "main" }
    }

    fun putFile(owner: String, repo: String, path: String, data: ByteArray) {
        val body = JSONObject()
            .put("message", "add $path")
            .put("content", Base64.encodeToString(data, Base64.NO_WRAP))
            .toString()
        val url = "$api/repos/$owner/$repo/contents/" + path.split("/").joinToString("/") { Uri.encode(it) }
        repeat(3) { attempt ->
            val (c, b) = send(builder(url).put(body.toRequestBody(json)).build())
            if (c == 200 || c == 201) return
            if (attempt == 2) error("Gagal unggah $path (HTTP $c): ${b.take(200)}")
            Thread.sleep(1500)
        }
    }

    fun dispatch(owner: String, repo: String, ref: String) {
        repeat(10) {
            val (c, _) = post(
                "/repos/$owner/$repo/actions/workflows/build.yml/dispatches",
                JSONObject().put("ref", ref).toString()
            )
            if (c == 204) return
            Thread.sleep(3000)
        }
        error("Gagal menjalankan workflow. Cek scope token ('workflow') dan pastikan build.yml punya trigger workflow_dispatch.")
    }

    fun latestRun(owner: String, repo: String): Long {
        val (c, b) = get("/repos/$owner/$repo/actions/runs?event=workflow_dispatch&per_page=1")
        if (c != 200) return 0
        val a = JSONObject(b).getJSONArray("workflow_runs")
        return if (a.length() > 0) a.getJSONObject(0).getLong("id") else 0
    }

    /** status to conclusion */
    fun runState(owner: String, repo: String, id: Long): Pair<String, String> {
        val j = JSONObject(get("/repos/$owner/$repo/actions/runs/$id").second)
        return j.str("status") to j.str("conclusion")
    }

    fun jobs(owner: String, repo: String, id: Long): Pair<Long, List<Step>> {
        val arr = JSONObject(get("/repos/$owner/$repo/actions/runs/$id/jobs").second).getJSONArray("jobs")
        if (arr.length() == 0) return 0L to emptyList()
        val job = arr.getJSONObject(0)
        val steps = job.optJSONArray("steps")
        val list = (0 until (steps?.length() ?: 0)).map {
            val s = steps!!.getJSONObject(it)
            Step(s.str("name"), s.str("conclusion").ifEmpty { s.str("status") })
        }
        return job.getLong("id") to list
    }

    fun jobLog(owner: String, repo: String, jobId: Long): String =
        get("/repos/$owner/$repo/actions/jobs/$jobId/logs").second.lines().takeLast(40).joinToString("\n")

    fun artifactUrl(owner: String, repo: String, id: Long): String? {
        val arr = JSONObject(get("/repos/$owner/$repo/actions/runs/$id/artifacts").second).getJSONArray("artifacts")
        return if (arr.length() > 0) arr.getJSONObject(0).getString("archive_download_url") else null
    }

    fun download(url: String): ByteArray =
        http.newCall(builder(url).build()).execute().use {
            if (!it.isSuccessful) error("Unduh gagal (HTTP ${it.code})")
            it.body!!.bytes()
        }

    fun deleteRepo(owner: String, repo: String) {
        send(builder("$api/repos/$owner/$repo").delete().build())
    }
}
