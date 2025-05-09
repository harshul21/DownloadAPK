@Composable
fun UpdateScreen(
    apkUrl: String,
    viewModel: UpdateViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState
    
    // Handle Android 13+ notifications permission
    val notificationPermissionState = rememberPermissionState(
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    LaunchedEffect(Unit) {
        viewModel.initializeUpdater(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionState.launchPermissionRequest()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(
            onClick = { viewModel.startUpdate(apkUrl) },
            enabled = !uiState.isDownloading
        ) {
            Text(if (uiState.isDownloading) "Updating..." else "Start Update")
        }

        Spacer(Modifier.height(24.dp))

        when {
            uiState.error != null -> ErrorMessage(
                message = uiState.error!!,
                onDismiss = { viewModel.clearError() }
            )
            
            uiState.isDownloading -> DownloadProgress(
                progress = uiState.progress,
                status = uiState.status
            )
            
            else -> StatusMessage(message = uiState.status)
        }
    }
}

@Composable
private fun DownloadProgress(progress: Int, status: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }
        LinearProgressIndicator(
            progress = progress / 100f,
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun ErrorMessage(message: String, onDismiss: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDismiss) {
            Text("Dismiss")
        }
    }
}

@Composable
private fun StatusMessage(message: String?) {
    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
