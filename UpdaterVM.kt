import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class UpdateViewModel : ViewModel() {
    var uiState by mutableStateOf(UpdateUIState())
        private set

    private var apkUpdater: ApkUpdater? = null

    fun initializeUpdater(context: Context) {
        apkUpdater = ApkUpdater(context)
    }

    fun startUpdate(apkUrl: String) {
        uiState = uiState.copy(isDownloading = true, error = null)
        apkUpdater?.startUpdate(
            apkUrl = apkUrl,
            onProgress = { progress ->
                uiState = uiState.copy(
                    progress = progress,
                    status = when {
                        progress == 100 -> "Preparing installation..."
                        else -> "Downloading... $progress%"
                    }
                )
            },
            onSuccess = {
                uiState = uiState.copy(
                    isDownloading = false,
                    status = "Installation starting..."
                )
            },
            onError = { error ->
                uiState = uiState.copy(
                    isDownloading = false,
                    error = error,
                    status = "Update failed"
                )
            }
        )
    }

    fun clearError() {
        uiState = uiState.copy(error = null)
    }

    override fun onCleared() {
        apkUpdater?.cancel()
        super.onCleared()
    }
}

data class UpdateUIState(
    val isDownloading: Boolean = false,
    val progress: Int = 0,
    val status: String? = null,
    val error: String? = null
)
