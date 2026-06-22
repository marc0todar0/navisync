package eu.todaro.navisync

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import eu.todaro.navisync.ui.MainScreen
import eu.todaro.navisync.ui.MainViewModel
import eu.todaro.navisync.ui.theme.NaviSyncTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NaviSyncTheme {
                val vm: MainViewModel = viewModel()
                MainScreen(
                    vm = vm,
                    hasStorageAccess = { hasAllFilesAccess() },
                    requestStorageAccess = { requestAllFilesAccess() },
                    requestNotifications = { requestNotifications() },
                )
            }
        }
        requestNotifications()
    }

    private fun hasAllFilesAccess(): Boolean =
        Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
