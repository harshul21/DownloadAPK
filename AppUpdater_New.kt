import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class ApkUpdater(private val context: Context) {
    private var downloadId: Long = -1L
    private var trackingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var apkFileName: String

    fun startUpdate(
        apkUrl: String,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!context.hasNetwork()) {
            onError("No internet connection")
            return
        }

        apkFileName = "update_${System.currentTimeMillis()}.apk"
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("App Update")
            setDescription("Downloading new version")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                apkFileName
            )
            allowScanningByMediaScanner()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setRequiresCharging(false)
            }
        }

        downloadId = downloadManager.enqueue(request)
        trackDownloadProgress(downloadManager, onProgress, onSuccess, onError)
    }

    private fun trackDownloadProgress(
        downloadManager: DownloadManager,
        onProgress: (Int) -> Unit,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        trackingJob = scope.launch {
            var downloading = true
            while (downloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                downloadManager.query(query).use { cursor ->
                    if (cursor.moveToFirst()) {
                        when (cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                downloading = false
                                onProgress(100)
                                handleInstallation(onSuccess, onError)
                            }
                            DownloadManager.STATUS_FAILED -> {
                                downloading = false
                                onError("Download failed: ${
                                    cursor.getString(cursor.getColumnIndex(DownloadManager.COLUMN_REASON))
                                }")
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                val total = cursor.getLong(
                                    cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                )
                                val downloaded = cursor.getLong(
                                    cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                )
                                val progress = (downloaded * 100 / total).toInt()
                                onProgress(progress.coerceIn(0, 100))
                            }
                        }
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun handleInstallation(onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            onError("Allow installation from unknown sources")
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            return
        }

        installApk(onError)
        onSuccess()
    }

    private fun installApk(onError: (String) -> Unit) {
        val apkFile = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            apkFileName
        )

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            context.startActivity(installIntent)
        } catch (e: Exception) {
            onError("Installation failed: ${e.localizedMessage}")
        }
    }

    fun cancel() {
        trackingJob?.cancel()
    }
}

fun Context.hasNetwork(): Boolean {
    val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return connectivityManager.activeNetwork?.let { true } ?: false
}
