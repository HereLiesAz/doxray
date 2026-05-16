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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.db.DatabaseExporter
import com.hereliesaz.doxray.db.DatabaseImporter
import com.hereliesaz.doxray.nav.DoxrayNavRail
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
                    DoxrayNavRail(
                        onExportClicked = { exportLauncher.launch("doxxr-export-${System.currentTimeMillis()}.zip") },
                        onImportClicked = { importLauncher.launch(arrayOf("application/zip")) },
                    )
                }
            }
        }
    }
}
