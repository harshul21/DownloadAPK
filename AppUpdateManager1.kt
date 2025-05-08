package your.package.name

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A utility class to handle downloading and installing APK files
 */
class ApkUpdaterManager(private val context: Context) {
    
    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    private val isDownloadTracking = AtomicBoolean(false)
    
    /**
     * Interface to receive callbacks about download and installation process
     */
    interface ApkUpdateListener {
        fun onDownloadStarted()
        fun onDownloadProgress(progress: Int)
        fun onDownloadCompleted()
        fun onDownloadFailed(reason: String)
        fun onInstallationInitiated()
        fun onError(error: String)
    }
    
    /**
     * Check if storage permission is needed and granted
     * @return true if permission is granted or not needed
     */
    fun hasRequiredPermissions(): Boolean {
        // For Android 10+ (API 29+), we don't need WRITE_EXTERNAL_STORAGE for app-specific storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true
        }
        
        // For Android 9 and below, check for storage permission
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Download the APK from the given URL
     * @param apkUrl URL of the APK file
     * @param apkName Name to save the APK file as
     * @param listener Callbacks for download and installation events
     */
    fun downloadAndInstallApk(apkUrl: String, apkName: String = "app_update.apk", listener: ApkUpdateListener) {
        if (!hasRequiredPermissions()) {
            listener.onError("Storage permission not granted")
            return
        }
        
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            
            // Create the download destination
            val destinationFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                apkName
            )
            
            // If file exists, delete it
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            
            // Set up the download request
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("App Update")
                .setDescription("Downloading app update...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destinationFile))
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            
            // Register broadcast receiver for download completion
            registerDownloadReceiver(apkName, listener)
            
            // Start the download and get the download ID
            downloadId = downloadManager.enqueue(request)
            listener.onDownloadStarted()
            
            // Start a thread to track progress
            trackDownloadProgress(downloadManager, listener)
            
        } catch (e: Exception) {
            listener.onError("Error starting download: ${e.message}")
        }
    }
    
    /**
     * Track download progress and report through listener
     */
    private fun trackDownloadProgress(downloadManager: DownloadManager, listener: ApkUpdateListener) {
        if (isDownloadTracking.getAndSet(true)) {
            return  // Already tracking, don't start another thread
        }
        
        Thread {
            try {
                var lastProgress = 0
                var consecutiveErrors = 0
                
                while (isDownloadTracking.get()) {
                    try {
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        
                        if (cursor.moveToFirst()) {
                            val statusColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            
                            if (statusColumnIndex != -1) {
                                val status = cursor.getInt(statusColumnIndex)
                                
                                when (status) {
                                    DownloadManager.STATUS_FAILED -> {
                                        listener.onDownloadFailed("Download failed")
                                        isDownloadTracking.set(false)
                                    }
                                    DownloadManager.STATUS_SUCCESSFUL -> {
                                        // Ensure we always show 100% before completing
                                        if (lastProgress < 100) {
                                            listener.onDownloadProgress(100)
                                        }
                                        // We'll let the BroadcastReceiver handle the completed state
                                        isDownloadTracking.set(false)
                                    }
                                    DownloadManager.STATUS_RUNNING -> {
                                        val totalSizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                        val downloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                        
                                        if (totalSizeIndex != -1 && downloadedIndex != -1) {
                                            val totalBytes = cursor.getLong(totalSizeIndex)
                                            val downloadedBytes = cursor.getLong(downloadedIndex)
                                            
                                            if (totalBytes > 0) {
                                                val progress = (downloadedBytes * 100 / totalBytes).toInt()
                                                if (progress != lastProgress) {
                                                    lastProgress = progress
                                                    listener.onDownloadProgress(progress)
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                // Reset error counter on successful query
                                consecutiveErrors = 0
                            }
                        } else {
                            // No results found, increment error counter
                            consecutiveErrors++
                            
                            // If we've had too many errors in a row, the download might be gone
                            if (consecutiveErrors > 5) {
                                // Check if the file exists and is complete (download might have completed between checks)
                                val file = File(
                                    context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                                    "app_update.apk"
                                )
                                
                                if (file.exists() && file.length() > 0) {
                                    // File exists, assume download complete
                                    if (lastProgress < 100) {
                                        listener.onDownloadProgress(100)
                                    }
                                    listener.onDownloadCompleted()
                                    isDownloadTracking.set(false)
                                } else {
                                    // No file, something went wrong
                                    listener.onDownloadFailed("Download query failed repeatedly")
                                    isDownloadTracking.set(false)
                                }
                            }
                        }
                        
                        cursor.close()
                    } catch (e: Exception) {
                        consecutiveErrors++
                        if (consecutiveErrors > 5) {
                            listener.onError("Error tracking download: ${e.message}")
                            isDownloadTracking.set(false)
                        }
                    }
                    
                    // Sleep for a bit before checking again
                    Thread.sleep(500)
                }
            } catch (e: Exception) {
                listener.onError("Progress tracking error: ${e.message}")
            } finally {
                isDownloadTracking.set(false)
            }
        }.start()
    }
    
    /**
     * Register a BroadcastReceiver to be notified when download completes
     */
    private fun registerDownloadReceiver(apkName: String, listener: ApkUpdateListener) {
        // Unregister any existing receiver
        unregisterDownloadReceiver()
        
        // Create and register a new receiver
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
                
                if (id == downloadId && downloadId != -1L) {
                    try {
                        val downloadManager = context?.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                        
                        if (downloadManager != null) {
                            val query = DownloadManager.Query().setFilterById(downloadId)
                            val cursor = downloadManager.query(query)
                            
                            if (cursor.moveToFirst()) {
                                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                
                                if (statusIndex != -1) {
                                    val status = cursor.getInt(statusIndex)
                                    
                                    when (status) {
                                        DownloadManager.STATUS_SUCCESSFUL -> {
                                            // Ensure we stop the progress tracking
                                            isDownloadTracking.set(false)
                                            
                                            // Make sure we report progress as 100%
                                            listener.onDownloadProgress(100)
                                            
                                            // Report completion and start installation
                                            listener.onDownloadCompleted()
                                            installApk(apkName, listener)
                                        }
                                        DownloadManager.STATUS_FAILED -> {
                                            isDownloadTracking.set(false)
                                            
                                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                            val errorMessage = if (reasonIndex != -1) {
                                                "Download failed: ${getErrorReason(cursor.getInt(reasonIndex))}"
                                            } else {
                                                "Download failed with unknown error"
                                            }
                                            
                                            listener.onDownloadFailed(errorMessage)
                                        }
                                        else -> {
                                            // This shouldn't happen, but handle it anyway
                                            isDownloadTracking.set(false)
                                            listener.onDownloadFailed("Download completed with unexpected status: $status")
                                        }
                                    }
                                }
                            }
                            cursor.close()
                        }
                    } catch (e: Exception) {
                        listener.onError("Error processing download completion: ${e.message}")
                    } finally {
                        // Unregister receiver after handling the download completion
                        unregisterDownloadReceiver()
                    }
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, filter)
        }
    }
    
    /**
     * Convert DownloadManager error codes to human-readable messages
     */
    private fun getErrorReason(reason: Int): String {
        return when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume download"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage device not found"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists"
            DownloadManager.ERROR_FILE_ERROR -> "File error"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient storage space"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled HTTP code"
            DownloadManager.ERROR_UNKNOWN -> "Unknown error"
            else -> "Error code: $reason"
        }
    }
    
    /**
     * Unregister the download completion receiver
     */
    fun unregisterDownloadReceiver() {
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Receiver not registered, ignore
            }
            downloadReceiver = null
        }
    }
    
    /**
     * Install the downloaded APK
     */
    private fun installApk(apkName: String, listener: ApkUpdateListener) {
        try {
            val file = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                apkName
            )
            
            if (!file.exists()) {
                listener.onError("Update file not found")
                return
            }
            
            if (file.length() == 0L) {
                listener.onError("Update file is empty")
                return
            }
            
            val intent = Intent(Intent.ACTION_VIEW)
            val apkUri: Uri
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // For Android 7+, we need to use a FileProvider
                apkUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                // For older versions
                apkUri = Uri.fromFile(file)
            }
            
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            
            listener.onInstallationInitiated()
            
        } catch (e: Exception) {
            listener.onError("Installation error: ${e.message}")
        }
    }
    
    /**
     * Clean up resources when no longer needed
     */
    fun cleanup() {
        isDownloadTracking.set(false)
        unregisterDownloadReceiver()
    }
}
