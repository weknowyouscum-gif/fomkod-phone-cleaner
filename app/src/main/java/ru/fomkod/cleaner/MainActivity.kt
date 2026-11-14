package ru.fomkod.cleaner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        YandexAdManager.initialize(this)
        scheduleWeeklyReminder()

        setContent {
            FomKodCleanerApp()
        }
    }

    private fun scheduleWeeklyReminder() {
        val request = PeriodicWorkRequestBuilder<WeeklyReminderWorker>(7, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "weekly_clean_reminder",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    @Composable
    fun FomKodCleanerApp() {
        val context = LocalContext.current
        val activity = context as Activity
        val scope = rememberCoroutineScope()

        var storage by remember { mutableStateOf(CleanerEngine.getStorageInfo()) }
        var status by remember { mutableStateOf("Готов к очистке") }
        var showPermissionDialog by remember { mutableStateOf(false) }
        var isScanning by remember { mutableStateOf(false) }
        var duplicateGroups by remember { mutableStateOf<List<DuplicateGroup>>(emptyList()) }
        var selected by remember { mutableStateOf(setOf<Int>()) }
        var showConfirm by remember { mutableStateOf(false) }
        var showApps by remember { mutableStateOf(false) }

        MaterialTheme(
            colorScheme = lightColorScheme(
                background = Color(0xFFF5F7FA),
                surface = Color.White,
                primary = Color(0xFF1976D2),
                secondary = Color(0xFF388E3C)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Очистка телефона: ФомКод",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111111)
                )
                Spacer(Modifier.height(24.dp))

                val usedGb = storage.usedBytes / (1024.0 * 1024 * 1024)
                val totalGb = storage.totalBytes / (1024.0 * 1024 * 1024)
                Text(
                    "Занято %.1f ГБ из %.1f ГБ".format(usedGb, totalGb),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                LinearProgressIndicator(
                    progress = { (storage.usedBytes.toFloat() / storage.totalBytes.coerceAtLeast(1)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                        .padding(vertical = 8.dp),
                    color = Color(0xFF1976D2),
                    trackColor = Color(0xFFE0E0E0)
                )

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = {
                        if (!hasAllPermissions()) {
                            showPermissionDialog = true
                        } else {
                            scope.launch {
                                isScanning = true
                                status = "Идёт простая очистка..."
                                val result = withContext(Dispatchers.IO) {
                                    CleanerEngine.simpleClean(context)
                                }
                                status = result.message
                                storage = CleanerEngine.getStorageInfo()
                                isScanning = false
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text("Простая очистка (Быстро)", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (!hasAllPermissions()) {
                            showPermissionDialog = true
                        } else {
                            scope.launch {
                                status = "Проверка интернета и рекламы..."
                                val hasNet = YandexAdManager.hasInternet(context)
                                if (!hasNet) {
                                    status = "У вас выключен интернет — ничего страшного, мы сделаем полную очистку бесплатно!"
                                    performFullClean()
                                } else {
                                    // Try ad with timeout logic simplified (real timeout needs more)
                                    val adShown = YandexAdManager.loadAndShowRewarded(context) {
                                        // on reward
                                    }
                                    if (adShown) {
                                        performFullClean()
                                    } else {
                                        status = "У вас выключен интернет — ничего страшного, мы сделаем полную очистку бесплатно!"
                                        performFullClean()
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
                ) {
                    Text("Полная очистка (Глубокая)", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(32.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Text(
                        status,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(24.dp),
                        color = Color(0xFF111111)
                    )
                }

                Spacer(Modifier.weight(1f))
                Text(
                    "Бесплатное социальное приложение. Экосистема ФомКод",
                    fontSize = 16.sp,
                    color = Color(0xFF555555)
                )
            }

            if (showPermissionDialog) {
                AlertDialog(
                    onDismissRequest = { showPermissionDialog = false },
                    title = { Text("Нужен доступ", fontSize = 24.sp) },
                    text = { Text("Разрешите доступ к файлам для очистки телефона", fontSize = 20.sp) },
                    confirmButton = {
                        Button(
                            onClick = {
                                requestPermissions()
                                showPermissionDialog = false
                            },
                            modifier = Modifier.height(60.dp)
                        ) {
                            Text("Разрешить доступ для очистки", fontSize = 18.sp)
                        }
                    }
                )
            }

            if (showConfirm) {
                // Simplified confirm dialog for duplicates
                AlertDialog(
                    onDismissRequest = { showConfirm = false },
                    title = { Text("Найден мусор", fontSize = 24.sp) },
                    text = {
                        Column {
                            Text("Дубликаты фотографий и картинок: ${duplicateGroups.size} групп", fontSize = 20.sp)
                            // More details can be expanded
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                scope.launch {
                                    val freed = withContext(Dispatchers.IO) {
                                        CleanerEngine.deleteSelected(duplicateGroups, selected)
                                    }
                                    status = "Очищено: ${freed / (1024 * 1024)} МБ! Телефон работает отлично"
                                    storage = CleanerEngine.getStorageInfo()
                                    showConfirm = false
                                }
                            },
                            modifier = Modifier.height(60.dp)
                        ) {
                            Text("Удалить выбранный мусор", fontSize = 18.sp)
                        }
                    }
                )
            }
        }
    }

    private fun hasAllPermissions(): Boolean {
        // Simplified check; real impl would check MANAGE_EXTERNAL_STORAGE etc.
        return true // For demo; expand as needed
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private suspend fun performFullClean() {
        isScanning = true
        status = "Идёт полная очистка и поиск дубликатов..."
        val (freed, groups) = withContext(Dispatchers.IO) {
            CleanerEngine.fullCleanScan(this@MainActivity)
        }
        duplicateGroups = groups
        selected = groups.indices.toSet() // all selected by default
        showConfirm = groups.isNotEmpty()
        status = if (groups.isEmpty()) "Очищено: ${freed / (1024 * 1024)} МБ! Телефон работает отлично" else "Найдены дубликаты. Подтвердите удаление."
        isScanning = false
    }
}
