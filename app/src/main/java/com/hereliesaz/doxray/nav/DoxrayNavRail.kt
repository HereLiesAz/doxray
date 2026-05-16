package com.hereliesaz.doxray.nav

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHost
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.doxray.db.AppDatabase
import com.hereliesaz.doxray.ui.audit.AuditLogScreen
import com.hereliesaz.doxray.ui.audit.AuditLogViewModel
import com.hereliesaz.doxray.ui.dossier.DossierDetailScreen
import com.hereliesaz.doxray.ui.dossier.DossierDetailViewModel
import com.hereliesaz.doxray.ui.dossier.DossierListScreen
import com.hereliesaz.doxray.ui.dossier.DossierListViewModel
import com.hereliesaz.doxray.ui.live.LiveScreen
import com.hereliesaz.doxray.ui.live.LiveViewModel
import com.hereliesaz.doxray.ui.live.LocalLiveViewModel

@Composable
fun DoxrayNavRail(
    inputMode: com.hereliesaz.doxray.ui.live.InputMode = com.hereliesaz.doxray.ui.live.InputMode.META,
    onSwapInputClicked: () -> Unit = {},
    onExportClicked: () -> Unit = {},
    onImportClicked: () -> Unit = {},
) {
    val navController = rememberNavController()
    val currentBackStack by navController.currentBackStackEntryAsState()
    // Capture composable-scoped color before entering the non-composable DSL lambda
    val primaryColor = MaterialTheme.colorScheme.primary
    AzHostActivityLayout(
        navController = navController,
        modifier = Modifier,
        currentDestination = currentBackStack?.destination?.route,
        initiallyExpanded = false,
    ) {
        azConfig(dockingSide = AzDockingSide.LEFT, packButtons = true)
        azTheme(activeColor = primaryColor, translucentBackground = Color.Black.copy(alpha = 0.5f))
        azRailItem(id = "live", text = "Live", route = Destinations.LIVE)
        azRailItem(id = "dossiers", text = "Dossiers", route = Destinations.DOSSIERS)
        azRailItem(id = "audit", text = "Audit", route = Destinations.AUDIT)
        azRailItem(
            id = "input-mode",
            text = if (inputMode == com.hereliesaz.doxray.ui.live.InputMode.META) "Camera" else "Glasses",
            route = "swap-input",
        )
        azMenuItem(id = "export-db", text = "Export DB", route = "export-db", onClick = onExportClicked)
        azMenuItem(id = "import-db", text = "Import DB", route = "import-db", onClick = onImportClicked)

        onscreen(alignment = Alignment.Center) {
            AzNavHost(navController = navController, startDestination = Destinations.LIVE) {
                composable(Destinations.LIVE) {
                    val vm = LocalLiveViewModel.current
                    LiveScreen(viewModel = vm)
                }
                composable(Destinations.DOSSIERS) {
                    val app = LocalContext.current.applicationContext as android.app.Application
                    val vm: DossierListViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { DossierListViewModel(AppDatabase.getDatabase(app).identityDao()) }
                        },
                    )
                    DossierListScreen(viewModel = vm, onOpen = { faceId ->
                        navController.navigate(Destinations.dossierDetail(faceId))
                    })
                }
                composable(
                    route = Destinations.DOSSIER_DETAIL,
                    arguments = listOf(navArgument("faceId") { type = NavType.StringType }),
                ) { entry ->
                    val faceId = entry.arguments?.getString("faceId").orEmpty()
                    val app = LocalContext.current.applicationContext as android.app.Application
                    val vm: DossierDetailViewModel = viewModel(
                        key = faceId,
                        factory = viewModelFactory {
                            initializer {
                                val db = AppDatabase.getDatabase(app)
                                DossierDetailViewModel(
                                    faceId = faceId,
                                    identityDao = db.identityDao(),
                                    encounterDao = db.encounterDao(),
                                )
                            }
                        },
                    )
                    DossierDetailScreen(viewModel = vm, onDeleted = { navController.popBackStack() })
                }
                composable(Destinations.AUDIT) {
                    val app = LocalContext.current.applicationContext as android.app.Application
                    val vm: AuditLogViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { AuditLogViewModel(AppDatabase.getDatabase(app).auditDao()) }
                        },
                    )
                    AuditLogScreen(viewModel = vm)
                }
                composable("swap-input") {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        onSwapInputClicked()
                        navController.popBackStack()
                    }
                }
            }
        }
    }
}
