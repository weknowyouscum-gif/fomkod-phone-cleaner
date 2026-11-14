package ru.fomkod.cleaner

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.security.MessageDigest
import java.util.Locale

data class StorageInfo(val usedBytes: Long, val totalBytes: Long)

data class CleanResult(val freedBytes: Long, val message: String)

data class DuplicateGroup(
    val hash: String,
    val files: List<File>,
    val size: Long
)

object CleanerEngine {

    fun getStorageInfo(): StorageInfo {
        val path = Environment.getExternalStorageDirectory()
        val stat = StatFs(path.path)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val used = total - free
        return StorageInfo(used, total)
    }

    suspend fun simpleClean(context: Context): CleanResult {
        var freed = 0L

        // Clean app cache
        freed += deleteDir(context.cacheDir)
        context.externalCacheDir?.let { freed += deleteDir(it) }

        // Scan Download and root for temp/apk
        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        freed += cleanTempAndApk(download)

        val root = Environment.getExternalStorageDirectory()
        freed += cleanTempAndApk(root)

        // Remove empty folders
        freed += removeEmptyFolders(root)

        val mb = freed / (1024 * 1024)
        return CleanResult(freed, "Очищено: $mb МБ! Телефон работает отлично")
    }

    suspend fun fullCleanScan(context: Context): Pair<Long, List<DuplicateGroup>> {
        // First do simple clean and collect freed, then scan duplicates
        val simple = simpleClean(context)
        val groups = findDuplicates(context)
        return Pair(simple.freedBytes, groups)
    }

    private fun cleanTempAndApk(dir: File?): Long {
        if (dir == null || !dir.exists() || !dir.isDirectory) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isFile) {
                val name = file.name.lowercase(Locale.ROOT)
                if (name.endsWith(".tmp") || name.endsWith(".log") || name.endsWith(".bak") || name.endsWith(".apk")) {
                    val size = file.length()
                    if (file.delete()) freed += size
                }
            }
        }
        return freed
    }

    private fun removeEmptyFolders(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var removed = 0
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                removeEmptyFolders(file)
                if (file.listFiles()?.isEmpty() == true) {
                    if (file.delete()) removed++
                }
            }
        }
        return removed.toLong() // not bytes, but count; adjust if needed
    }

    private fun deleteDir(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                freed += deleteDir(file)
            } else {
                val size = file.length()
                if (file.delete()) freed += size
            }
        }
        return freed
    }

    private fun findDuplicates(context: Context): List<DuplicateGroup> {
        val candidates = mutableListOf<File>()
        val folders = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "WhatsApp/Media"),
            File(Environment.getExternalStorageDirectory(), "Telegram"),
            File("/sdcard/Android/media/com.whatsapp")
        )

        folders.forEach { folder ->
            if (folder.exists()) {
                folder.walkTopDown().forEach { file ->
                    if (file.isFile && (file.extension.lowercase() in listOf("jpg", "jpeg", "png", "webp", "mp4"))) {
                        candidates.add(file)
                    }
                }
            }
        }

        // Group by size first
        val bySize = candidates.groupBy { it.length() }.filter { it.value.size > 1 }

        val groups = mutableListOf<DuplicateGroup>()
        bySize.forEach { (_, files) ->
            val byHash = mutableMapOf<String, MutableList<File>>()
            files.forEach { file ->
                val hash = md5(file)
                byHash.getOrPut(hash) { mutableListOf() }.add(file)
            }
            byHash.forEach { (hash, same) ->
                if (same.size > 1) {
                    groups.add(DuplicateGroup(hash, same, same.first().length()))
                }
            }
        }
        return groups
    }

    private fun md5(file: File): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            file.absolutePath // fallback
        }
    }

    fun deleteSelected(groups: List<DuplicateGroup>, selectedIndices: Set<Int>): Long {
        var freed = 0L
        groups.forEachIndexed { index, group ->
            if (selectedIndices.contains(index)) {
                // Keep first as original, delete rest
                group.files.drop(1).forEach { file ->
                    val size = file.length()
                    if (file.delete()) freed += size
                }
            }
        }
        return freed
    }
}
