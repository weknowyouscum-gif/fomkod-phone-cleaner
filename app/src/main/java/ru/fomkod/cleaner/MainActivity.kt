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
import androidx.compose.ui.text.style.TextAlign
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
        val scope = rememberCoroutineScope()

        var storage by remember { mutableStateOf(CleanerEngine.getStorageInfo()) }
        var status by remember { mutableStateOf("Готов к очистке") }
        var showPermissionDialog by remember { mutableStateOf(false) }
        var fullResult by remember { mutableStateOf<FullCleanResult?>(null) }
        var showFullDialog by remember { mutableStateOf(false) }

        fun requestPermissionsLocal() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                } catch (e: Exception) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
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

        MaterialTheme(
            colorScheme = lightColorScheme(
                background = Color(0xFFF8F9FA),
                surface = Color.White,
                primary = Color(0xFF1976D2),
                secondary = Color(0xFF2E7D32)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Очистка телефона",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0D47A1)
                )
                Text(
                    "ФомКод",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF1565C0)
                )
                Spacer(Modifier.height(28.dp))

                val usedGb = storage.usedBytes / (1024.0 * 1024 * 1024)
                val totalGb = storage.totalBytes / (1024.0 * 1024 * 1024)
                Text(
                    "Занято %.1f ГБ из %.1f ГБ".format(usedGb, totalGb),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF212121)
                )
                LinearProgressIndicator(
                    progress = { (storage.usedBytes.toFloat() / storage.totalBytes.coerceAtLeast(1)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .padding(vertical = 10.dp),
                    color = Color(0xFF1976D2),
                    trackColor = Color(0xFFE3F2FD)
                )

                Spacer(Modifier.height(36.dp))

                Button(
                    onClick = {
                        scope.launch {
                            status = "Идёт простая очистка..."
                            val result = withContext(Dispatchers.IO) {
                                CleanerEngine.simpleClean(context)
                            }
                            status = result.message
                            storage = CleanerEngine.getStorageInfo()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                ) {
                    Text("Простая очистка (Быстро)", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = {
                        scope.launch {
                            status = "Проверка интернета..."
                            val hasNet = YandexAdManager.hasInternet(context)
                            if (!hasNet) {
                                status = "Интернет выключен — делаем полную очистку бесплатно"
                            } else {
                                YandexAdManager.loadAndShowRewarded(context) {}
                            }
                            status = "Сканирование дубликатов, больших файлов и приложений..."
                            val result = withContext(Dispatchers.IO) {
                                CleanerEngine.fullCleanScan(context)
                            }
                            fullResult = result
                            showFullDialog = true
                            status = "Найдено для очистки. Выберите, что удалить."
                            storage = CleanerEngine.getStorageInfo()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
                ) {
                    Text("Полная очистка (Глубокая)", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(36.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Text(
                        status,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(28.dp),
                        color = Color(0xFF212121),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(24.dp))
                Text(
                    "Бесплатное социальное приложение · Экосистема ФомКод",
                    fontSize = 14.sp,
                    color = Color(0xFF757575),
                    textAlign = TextAlign.Center
                )
            }

            if (showPermissionDialog) {
                AlertDialog(
                    onDismissRequest = { showPermissionDialog = false },
                    title = { Text("Нужен доступ к файлам", fontSize = 22.sp) },
                    text = { Text("Разрешите доступ ко всем файлам, чтобы приложение могло очистить телефон", fontSize = 18.sp) },
                    confirmButton = {
                        Button(onClick = {
                            requestPermissionsLocal()
                            showPermissionDialog = false
                        }) {
                            Text("Разрешить", fontSize = 18.sp)
                        }
                    }
                )
            }

            if (showFullDialog && fullResult != null) {
                val result = fullResult!!
                AlertDialog(
                    onDismissRequest = { showFullDialog = false },
                    title = { Text("Найдено для очистки", fontSize = 22.sp) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            if (result.duplicates.isNotEmpty()) {
                                Text("Дубликаты фото: ${result.duplicates.size} групп", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                            }
                            if (result.largeFiles.isNotEmpty()) {
                                Text("Большие файлы (>50 МБ): ${result.largeFiles.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                            }
                            if (result.unusedApps.isNotEmpty()) {
                                Text("Сторонние приложения: ${result.unusedApps.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            if (result.duplicates.isEmpty() && result.largeFiles.isEmpty() && result.unusedApps.isEmpty()) {
                                Text("Мусора не найдено. Телефон уже чистый!", fontSize = 18.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                scope.launch {
                                    var totalFreed = result.freedBytes
                                    totalFreed += withContext(Dispatchers.IO) {
                                        CleanerEngine.deleteSelected(result.duplicates, result.duplicates.indices.toSet())
                                    }
                                    totalFreed += withContext(Dispatchers.IO) {
                                        CleanerEngine.deleteLargeFiles(result.largeFiles, result.largeFiles.indices.toSet())
                                    }
                                    status = "Очищено: ${totalFreed / (1024 * 1024)} МБ! Телефон работает отлично"
                                    storage = CleanerEngine.getStorageInfo()
                                    showFullDialog = false
                                }
                            }
                        ) {
                            Text("Удалить всё найденное", fontSize = 18.sp)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showFullDialog = false }) {
                            Text("Позже")
                        }
                    }
                )
            }
        }
    }
}
