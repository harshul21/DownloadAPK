import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import android.os.Build

@Composable
fun UpdateScreen() {
    val context = LocalContext.current
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0) }
    var statusMessage by remember { mutableStateOf("") }
    
    // Create the ApkUpdaterManager instance
    val updaterManager = remember { ApkUpdaterManager(context) }
    
    // Clean up when the composable is disposed
    DisposableEffect(Unit) {
        onDispose {
            updaterManager.cleanup()
        }
    }
    
    // Create the update listener
    val updateListener = remember {
        object : ApkUpdaterManager.ApkUpdateListener {
            override fun onDownloadStarted() {
                isDownloading = true
                statusMessage = "Downloading update..."
            }
            
            override fun onDownloadProgress(progress: Int) {
                downloadProgress = progress
            }
            
            override fun onDownloadCompleted() {
                statusMessage = "Download completed. Preparing installation..."
            }
            
            override fun onDownloadFailed(reason: String) {
                isDownloading = false
                statusMessage = "Download failed: $reason"
            }
            
            override fun onInstallationInitiated() {
                isDownloading = false
                statusMessage = "Installation started"
            }
            
            override fun onError(error: String) {
                isDownloading = false
                statusMessage = "Error: $error"
            }
        }
    }
    
    // Permission request launcher
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Replace with your actual APK URL
            val apkUrl = "https://your-server.com/your-app-update.apk"
            updaterManager.downloadAndInstallApk(apkUrl, listener = updateListener)
        } else {
            Toast.makeText(
                context,
                "Storage permission is required to download the update",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (statusMessage.isNotEmpty()) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
        
        if (isDownloading) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LinearProgressIndicator(
                    progress = downloadProgress / 100f,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "$downloadProgress%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            Button(
                onClick = {
                    if (updaterManager.hasRequiredPermissions()) {
                        // Replace with your actual APK URL
                        val apkUrl = "https://your-server.com/your-app-update.apk"
                        updaterManager.downloadAndInstallApk(apkUrl, listener = updateListener)
                    } else {
                        // Only Android 9 and below need this permission
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                }
            ) {
                Text("Update App")
            }
        }
    }
}
