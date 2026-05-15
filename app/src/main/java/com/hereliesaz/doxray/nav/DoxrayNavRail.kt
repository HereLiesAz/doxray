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

@Composable
fun DoxrayNavRail() {
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

        onscreen(alignment = Alignment.Center) {
            AzNavHost(navController = navController, startDestination = Destinations.LIVE) {
                composable(Destinations.LIVE) {
                    val vm: LiveViewModel = viewModel()
                    LiveScreen(viewModel = vm)
                }
                composable(Destinations.DOSSIERS) {
                    val app = LocalContext.current.applicationContext as android.app.Application
                    val dao = AppDatabase.getDatabase(app).identityDao()
                    val vm = remember { DossierListViewModel(dao) }
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
                    val db = AppDatabase.getDatabase(app)
                    val vm = remember(faceId) {
                        DossierDetailViewModel(
                            faceId = faceId,
                            identityDao = db.identityDao(),
                            encounterDao = db.encounterDao(),
                        )
                    }
                    DossierDetailScreen(viewModel = vm, onDeleted = { navController.popBackStack() })
                }
                composable(Destinations.AUDIT) {
                    val app = LocalContext.current.applicationContext as android.app.Application
                    val dao = AppDatabase.getDatabase(app).auditDao()
                    val vm = remember { AuditLogViewModel(dao) }
                    AuditLogScreen(viewModel = vm)
                }
            }
        }
    }
}
