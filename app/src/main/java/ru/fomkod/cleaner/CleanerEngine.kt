package ru.fomkod.cleaner

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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

data class LargeFile(
    val file: File,
    val size: Long
)

data class AppInfo(
    val packageName: String,
    val appName: String,
    val isSystem: Boolean
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
        removeEmptyFolders(root)

        val mb = freed / (1024 * 1024)
        return CleanResult(freed, "Очищено: $mb МБ! Телефон работает отлично")
    }

    suspend fun fullCleanScan(context: Context): FullCleanResult {
        val simple = simpleClean(context)
        val groups = findDuplicates(context)
        val largeFiles = findLargeFiles(context)
        val unusedApps = findUnusedApps(context)
        return FullCleanResult(simple.freedBytes, groups, largeFiles, unusedApps)
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

    private fun removeEmptyFolders(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                removeEmptyFolders(file)
                if (file.listFiles()?.isEmpty() == true) {
                    file.delete()
                }
            }
        }
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

    private fun findLargeFiles(context: Context): List<LargeFile> {
        val large = mutableListOf<LargeFile>()
        val root = Environment.getExternalStorageDirectory()
        val threshold = 50 * 1024 * 1024L // 50 MB

        if (root.exists()) {
            root.walkTopDown().forEach { file ->
                if (file.isFile && file.length() > threshold && !file.name.startsWith(".")) {
                    large.add(LargeFile(file, file.length()))
                }
            }
        }
        return large.sortedByDescending { it.size }.take(20)
    }

    private fun findUnusedApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return apps.filter { app ->
            (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && app.packageName != context.packageName
        }.map { app ->
            AppInfo(
                packageName = app.packageName,
                appName = pm.getApplicationLabel(app).toString(),
                isSystem = false
            )
        }.sortedBy { it.appName }
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
            file.absolutePath
        }
    }

    fun deleteSelected(groups: List<DuplicateGroup>, selectedIndices: Set<Int>): Long {
        var freed = 0L
        groups.forEachIndexed { index, group ->
            if (selectedIndices.contains(index)) {
                group.files.drop(1).forEach { file ->
                    val size = file.length()
                    if (file.delete()) freed += size
                }
            }
        }
        return freed
    }

    fun deleteLargeFiles(files: List<LargeFile>, selected: Set<Int>): Long {
        var freed = 0L
        files.forEachIndexed { index, large ->
            if (selected.contains(index)) {
                val size = large.size
                if (large.file.delete()) freed += size
            }
        }
        return freed
    }
}

data class FullCleanResult(
    val freedBytes: Long,
    val duplicates: List<DuplicateGroup>,
    val largeFiles: List<LargeFile>,
    val unusedApps: List<AppInfo>
)
