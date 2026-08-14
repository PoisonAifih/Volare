package dev.aifih.onthefly.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.aifih.onthefly.ServiceLocator
import dev.aifih.onthefly.ui.theme.OnTheFlyTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OnTheFlyTheme {
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                OnTheFlyNavHost()
            }
        }
    }
}

object Routes {
    const val API_KEY = "apiKey"
    const val AGENTS = "agents"
    const val NEW_AGENT = "newAgent"
    const val AGENT_DETAIL = "agent/{agentId}"

    fun agentDetail(agentId: String): String = "agent/$agentId"
}

@Composable
private fun OnTheFlyNavHost() {
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
                onSignOut = {
                    ServiceLocator.apiKeyStore.clear()
                    navController.navigate(Routes.API_KEY) {
                        popUpTo(Routes.AGENTS) { inclusive = true }
                    }
                },
            )
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
