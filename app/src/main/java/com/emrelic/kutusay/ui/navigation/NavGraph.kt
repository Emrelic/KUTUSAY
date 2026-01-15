package com.emrelic.kutusay.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.emrelic.kutusay.ui.screens.home.HomeScreen
import com.emrelic.kutusay.ui.screens.camera.CameraScreen
import com.emrelic.kutusay.ui.screens.invoice.InvoiceResultScreen
import com.emrelic.kutusay.ui.screens.boxcount.BoxCountScreen
import com.emrelic.kutusay.ui.screens.comparison.ComparisonScreen

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Camera : Screen("camera/{type}") {
        fun createRoute(type: CameraType) = "camera/${type.name}"
    }
    object InvoiceResult : Screen("invoice_result/{invoiceId}") {
        fun createRoute(invoiceId: Long) = "invoice_result/$invoiceId"
    }
    object BoxCount : Screen("box_count/{invoiceId}") {
        fun createRoute(invoiceId: Long) = "box_count/$invoiceId"
    }
    object Comparison : Screen("comparison/{invoiceId}") {
        fun createRoute(invoiceId: Long) = "comparison/$invoiceId"
    }
}

enum class CameraType {
    INVOICE,
    BOXES
}

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String = Screen.Home.route
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onStartInvoiceCheck = {
                    navController.navigate(Screen.Camera.createRoute(CameraType.INVOICE))
                }
            )
        }

        composable(
            route = Screen.Camera.route,
            arguments = listOf(
                navArgument("type") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val typeString = backStackEntry.arguments?.getString("type") ?: CameraType.INVOICE.name
            val cameraType = CameraType.valueOf(typeString)

            CameraScreen(
                cameraType = cameraType,
                onPhotoTaken = { uri ->
                    when (cameraType) {
                        CameraType.INVOICE -> {
                            // InvoiceResult ekranina git (invoiceId gecici olarak 0)
                            navController.navigate(Screen.InvoiceResult.createRoute(0)) {
                                // Camera ekranini stack'ten kaldir
                                popUpTo(Screen.Camera.route) { inclusive = true }
                            }
                        }
                        CameraType.BOXES -> {
                            navController.popBackStack()
                        }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.InvoiceResult.route,
            arguments = listOf(
                navArgument("invoiceId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val invoiceId = backStackEntry.arguments?.getLong("invoiceId") ?: 0L

            InvoiceResultScreen(
                invoiceId = invoiceId,
                onContinueToBoxCount = { id ->
                    navController.navigate(Screen.BoxCount.createRoute(id))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.BoxCount.route,
            arguments = listOf(
                navArgument("invoiceId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val invoiceId = backStackEntry.arguments?.getLong("invoiceId") ?: 0L

            BoxCountScreen(
                invoiceId = invoiceId,
                onTakePhoto = {
                    navController.navigate(Screen.Camera.createRoute(CameraType.BOXES))
                },
                onCompare = { id ->
                    navController.navigate(Screen.Comparison.createRoute(id))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Comparison.route,
            arguments = listOf(
                navArgument("invoiceId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val invoiceId = backStackEntry.arguments?.getLong("invoiceId") ?: 0L

            ComparisonScreen(
                invoiceId = invoiceId,
                onNewCheck = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }
    }
}
