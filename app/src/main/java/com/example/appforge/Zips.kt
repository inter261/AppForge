package com.example.appforge

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private val SKIP = setOf(".git", ".gradle", ".idea", "__MACOSX", ".DS_Store")

private fun skipped(rel: String): Boolean {
    if (rel.startsWith("/") || rel.contains("..") || rel == "local.properties") return true
    val seg = rel.split("/")
    return seg.indices.any { i ->
        seg[i] in SKIP || (seg[i] == "build" && "src" !in seg.take(i)) // folder hasil build, bukan paket "build" di dalam src
    }
}

/** Mengembalikan ringkasan, mis. "MyApp (42 file)". */
fun importZip(ctx: Context, store: Store, uri: Uri): String {
    val cr = ctx.contentResolver
    var base = "proyek"
    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) base = c.getString(0).removeSuffix(".zip")
    }
    val orig = base.replace(Regex("[^A-Za-z0-9_-]"), "-").ifEmpty { "proyek" }
    var name = orig
    var n = 2
    while (File(store.root, name).exists()) name = "$orig-${n++}"

    // Lewat pertama: cari folder induk tunggal (mis. "MyApp/") supaya bisa dibuang.
    val names = mutableListOf<String>()
    ZipInputStream((cr.openInputStream(uri) ?: error("Tidak bisa membuka file")).buffered()).use { z ->
        var e = z.nextEntry
        while (e != null) { if (!e.isDirectory) names += e.name; e = z.nextEntry }
    }
    if (names.isEmpty()) error("ZIP kosong")
    val root = names.map { it.substringBefore('/', "") }.distinct().singleOrNull()
        ?.takeIf { r -> r.isNotEmpty() && names.all { it.startsWith("$r/") } } ?: ""
    val prefix = if (root.isEmpty()) "" else "$root/"

    val dest = File(store.root, name)
    var count = 0
    ZipInputStream((cr.openInputStream(uri) ?: error("Tidak bisa membuka file")).buffered()).use { z ->
        var e = z.nextEntry
        while (e != null) {
            if (!e.isDirectory) {
                val rel = e.name.removePrefix(prefix)
                if (!skipped(rel)) {
                    val f = File(dest, rel)
                    f.parentFile?.mkdirs()
                    f.outputStream().use { z.copyTo(it) }
                    count++
                }
            }
            e = z.nextEntry
        }
    }
    if (count == 0) { dest.deleteRecursively(); error("Tidak ada file yang bisa diimpor") }
    return "$name ($count file)"
}

fun exportZip(ctx: Context, store: Store, project: String, uri: Uri) {
    val dir = File(store.root, project)
    ZipOutputStream((ctx.contentResolver.openOutputStream(uri) ?: error("Tidak bisa menulis file")).buffered()).use { z ->
        store.files(project).forEach { p ->
            z.putNextEntry(ZipEntry("$project/$p"))
            File(dir, p).inputStream().use { it.copyTo(z) }
            z.closeEntry()
        }
    }
}
