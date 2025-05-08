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

/**
 * A utility class to handle downloading and installing APK files
 */
class ApkUpdaterManager(private val context: Context) {
    
    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    
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
        Thread {
            var isDownloading = true
            while (isDownloading) {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = downloadManager.query(query)
                
                if (cursor.moveToFirst()) {
                    val statusColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusColumnIndex != -1) {
                        val status = cursor.getInt(statusColumnIndex)
                        
                        when (status) {
                            DownloadManager.STATUS_FAILED -> {
                                isDownloading = false
                                listener.onDownloadFailed("Download failed")
                            }
                            DownloadManager.STATUS_PAUSED -> {
                                // Do nothing
                            }
                            DownloadManager.STATUS_PENDING -> {
                                // Do nothing
                            }
                            DownloadManager.STATUS_RUNNING -> {
                                val totalBytes = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                val downloadedBytes = cursor.getLong(cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                
                                if (totalBytes > 0) {
                                    val progress = (downloadedBytes * 100 / totalBytes).toInt()
                                    listener.onDownloadProgress(progress)
                                }
                            }
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                isDownloading = false
                            }
                        }
                    }
                }
                cursor.close()
                
                try {
                    Thread.sleep(500)
                } catch (e: InterruptedException) {
                    isDownloading = false
                }
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
                    val downloadManager = context?.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    
                    if (cursor.moveToFirst()) {
                        val statusColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusColumnIndex != -1 && cursor.getInt(statusColumnIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                            listener.onDownloadCompleted()
                            installApk(apkName, listener)
                        } else {
                            listener.onDownloadFailed("Download completed with errors")
                        }
                    }
                    cursor.close()
                    
                    // Unregister receiver after handling the download completion
                    unregisterDownloadReceiver()
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        context.registerReceiver(downloadReceiver, filter)
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
        unregisterDownloadReceiver()
    }
}
