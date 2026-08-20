package dev.aifih.volare.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.aifih.volare.ServiceLocator
import dev.aifih.volare.ui.theme.VolareTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Without this the window stays decor fitted, `WindowInsets.ime` is never dispatched,
        // and every `imePadding()` in the app silently measures zero. Android 15 also stopped
        // resizing the window for `adjustResize`, so nothing else moves content off the
        // keyboard either.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            VolareTheme {
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                VolareNavHost()
            }
        }
    }
}

object Routes {
    const val API_KEY = "apiKey"
    const val AGENTS = "agents"
    const val NEW_AGENT = "newAgent"
    const val UPDATE = "update"
    const val AGENT_DETAIL = "agent/{agentId}"

    fun agentDetail(agentId: String): String = "agent/$agentId"
}

@Composable
private fun VolareNavHost() {
    val navController = rememberNavController()
    val start = if (ServiceLocator.apiKeyStore.isConfigured()) Routes.AGENTS else Routes.API_KEY

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.API_KEY) {
            ApiKeyScreen(
                onConnected = {
                    navController.navigate(Routes.AGENTS) {
                        popUpTo(Routes.API_KEY) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.AGENTS) {
            AgentListScreen(
                onNewAgent = { navController.navigate(Routes.NEW_AGENT) },
                onOpenAgent = { agentId -> navController.navigate(Routes.agentDetail(agentId)) },
                onCheckUpdates = { navController.navigate(Routes.UPDATE) },
                onSignOut = {
                    ServiceLocator.apiKeyStore.clear()
                    ServiceLocator.repository.clearCache()
                    navController.navigate(Routes.API_KEY) {
                        popUpTo(Routes.AGENTS) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.UPDATE) {
            UpdateScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.NEW_AGENT) {
            NewAgentScreen(
                onBack = { navController.popBackStack() },
                onCreated = { agentId ->
                    navController.navigate(Routes.agentDetail(agentId)) {
                        popUpTo(Routes.NEW_AGENT) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.AGENT_DETAIL,
            arguments = listOf(navArgument("agentId") { type = NavType.StringType }),
        ) { entry ->
            AgentDetailScreen(
                agentId = entry.arguments?.getString("agentId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
