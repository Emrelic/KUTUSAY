package com.emrelic.kutusay.ui.navigation

import android.net.Uri
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Camera : Screen("camera/{type}") {
        fun createRoute(type: CameraType) = "camera/${type.name}"
    }
    object InvoiceResult : Screen("invoice_result/{invoiceId}?imageUri={imageUri}") {
        fun createRoute(invoiceId: Long, imageUri: String? = null): String {
            val encodedUri = imageUri?.let {
                URLEncoder.encode(it, StandardCharsets.UTF_8.toString())
            }
            return if (encodedUri != null) {
                "invoice_result/$invoiceId?imageUri=$encodedUri"
            } else {
                "invoice_result/$invoiceId"
            }
        }
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
                            // InvoiceResult ekranina git ve imageUri'yi aktar
                            navController.navigate(Screen.InvoiceResult.createRoute(0, uri.toString())) {
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
                navArgument("invoiceId") { type = NavType.LongType },
                navArgument("imageUri") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val invoiceId = backStackEntry.arguments?.getLong("invoiceId") ?: 0L
            val encodedImageUri = backStackEntry.arguments?.getString("imageUri")
            val imageUri = encodedImageUri?.let {
                Uri.parse(URLDecoder.decode(it, StandardCharsets.UTF_8.toString()))
            }

            InvoiceResultScreen(
                invoiceId = invoiceId,
                initialImageUri = imageUri,
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
