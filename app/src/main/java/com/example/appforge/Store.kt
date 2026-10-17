package com.example.appforge

import android.content.Context
import java.io.File

class Store(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("appforge", Context.MODE_PRIVATE)

    // TODO: pindahkan ke EncryptedSharedPreferences / Android Keystore
    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    fun persistent(project: String): Boolean = prefs.getBoolean("persist:$project", true)
    fun setPersistent(project: String, v: Boolean) = prefs.edit().putBoolean("persist:$project", v).apply()

    val root: File = File(ctx.filesDir, "projects").also { it.mkdirs() }

    private fun safe(name: String, path: String): File {
        require(!path.contains("..") && !path.startsWith("/")) { "Path tidak valid" }
        return File(File(root, name), path)
    }

    fun projects(): List<String> =
        root.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun create(rawName: String) {
        val name = rawName.replace(Regex("[^A-Za-z0-9_-]"), "-")
        Templates.hello.forEach { (p, c) -> write(name, p, c) }
    }

    fun files(name: String): List<String> {
        val d = File(root, name)
        return d.walkTopDown().filter { it.isFile }.map { it.relativeTo(d).path }.sorted().toList()
    }

    fun read(name: String, path: String): String = safe(name, path).readText()

    fun write(name: String, path: String, text: String) {
        val f = safe(name, path)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    fun delete(name: String) {
        File(root, name).deleteRecursively()
    }
}
