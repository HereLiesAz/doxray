package com.hereliesaz.doxray

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.db.DatabaseExporter
import com.hereliesaz.doxray.db.DatabaseImporter
import com.hereliesaz.doxray.nav.DoxrayNavRail
import com.hereliesaz.doxray.ui.live.InputMode
import com.hereliesaz.doxray.ui.live.LiveViewModel
import com.hereliesaz.doxray.ui.live.LocalLiveViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(requiredPermissions)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val ctx = LocalContext.current
                    val scope = rememberCoroutineScope()
                    val activity = this@MainActivity
                    val liveVm: LiveViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer {
                                LiveViewModel(application, activity)
                            }
                        },
                    )
                    val cameraPermission = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        if (granted) liveVm.onCameraPermissionGranted()
                        else liveVm.appendLog("Camera permission denied; phone mode unavailable.")
                    }
                    LaunchedEffect(Unit) {
                        liveVm.permissionRequest.collect {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    }
                    val inputMode by liveVm.inputMode.collectAsState()
                    val exportLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.CreateDocument("application/zip"),
                    ) { uri ->
                        if (uri != null) {
                            scope.launch(Dispatchers.IO) {
                                val db = AppDatabase.getDatabase(ctx)
                                val exporter = DatabaseExporter(db.identityDao(), db.encounterDao(), db.auditDao())
                                ctx.contentResolver.openOutputStream(uri)?.use { exporter.export(it) }
                            }
                        }
                    }
                    val importLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        if (uri != null) {
                            scope.launch(Dispatchers.IO) {
                                val db = AppDatabase.getDatabase(ctx)
                                val importer = DatabaseImporter(db.identityDao(), db.encounterDao(), db.auditDao())
                                ctx.contentResolver.openInputStream(uri)?.use { importer.import(it) }
                            }
                        }
                    }
                    CompositionLocalProvider(LocalLiveViewModel provides liveVm) {
                        DoxrayNavRail(
                            inputMode = inputMode,
                            onSwapInputClicked = {
                                if (inputMode == InputMode.META) {
                                    liveVm.requestPhoneCamera()
                                } else {
                                    liveVm.switchToMeta()
                                }
                            },
                            onExportClicked = { exportLauncher.launch("doxxr-export-${System.currentTimeMillis()}.zip") },
                            onImportClicked = { importLauncher.launch(arrayOf("application/zip")) },
                        )
                    }
                }
            }
        }
    }
}
