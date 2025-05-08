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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

@Composable
fun ApkUpdater(apkUrl: String, apkName: String = "app_update.apk") {
    val context = LocalContext.current
    var isDownloading by remember { mutableStateOf(false) }
    var downloadId by remember { mutableStateOf(-1L) }
    
    // Permission request launcher
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            downloadApk(context, apkUrl, apkName) { id ->
                downloadId = id
                isDownloading = true
            }
        } else {
            Toast.makeText(
                context,
                "Storage permission is required to download the update",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    // Listen for download completion
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
                if (id == downloadId && downloadId != -1L) {
                    isDownloading = false
                    val downloadManager = context?.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    
                    if (cursor.moveToFirst()) {
                        val statusColumnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusColumnIndex != -1 && cursor.getInt(statusColumnIndex) == DownloadManager.STATUS_SUCCESSFUL) {
                            installApk(context, apkName)
                        } else {
                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                    cursor.close()
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        context.registerReceiver(receiver, filter)
        
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isDownloading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Downloading update...")
        } else {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Android 10+ doesn't require WRITE_EXTERNAL_STORAGE for app-specific storage
                        downloadApk(context, apkUrl, apkName) { id ->
                            downloadId = id
                            isDownloading = true
                        }
                    } else {
                        // For Android 9 and below, check for storage permission
                        when {
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) == PackageManager.PERMISSION_GRANTED -> {
                                downloadApk(context, apkUrl, apkName) { id ->
                                    downloadId = id
                                    isDownloading = true
                                }
                            }
                            else -> {
                                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            }
                        }
                    }
                }
            ) {
                Text("Check for Update")
            }
        }
    }
}

private fun downloadApk(context: Context, apkUrl: String, apkName: String, onDownloadStarted: (Long) -> Unit) {
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
        
        // Start the download and get the download ID
        val downloadId = downloadManager.enqueue(request)
        onDownloadStarted(downloadId)
        
    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun installApk(context: Context, apkName: String) {
    try {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            apkName
        )
        
        if (!file.exists()) {
            Toast.makeText(context, "Update file not found", Toast.LENGTH_SHORT).show()
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
        
    } catch (e: Exception) {
        Toast.makeText(context, "Installation error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// Usage example in your app:
@Composable
fun YourMainScreen() {
    // Replace with your actual APK URL
    val apkUrl = "https://your-server.com/your-app-update.apk"
    
    ApkUpdater(apkUrl = apkUrl)
}
