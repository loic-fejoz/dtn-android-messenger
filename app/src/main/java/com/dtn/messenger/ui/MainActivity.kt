package com.dtn.messenger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dtn.messenger.data.dao.*
import com.dtn.messenger.data.model.ViewerType
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val localServiceDao: LocalServiceDao by inject()
    private val bundleRecordDao: BundleRecordDao by inject()
    private val routingRuleDao: RoutingRuleDao by inject()
    private val convergenceProfileDao: ConvergenceProfileDao by inject()
    private val bpsecKeyDao: BpsecKeyDao by inject()
    private val systemLogDao: SystemLogDao by inject()

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            if (permissions.values.all { it }) {
                startEngineService()
            }
        }

    private val currentIntentState = mutableStateOf<android.content.Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentIntentState.value = intent
        checkAndRequestPermissions()

        if (hasRequiredPermissions()) {
            startEngineService()
        }

        setContent {
            DtnTheme {
                val navController = rememberNavController()
                val activeIntent by currentIntentState

                LaunchedEffect(activeIntent) {
                    val intent = activeIntent
                    if (intent?.action == android.content.Intent.ACTION_SEND ||
                        intent?.action == android.content.Intent.ACTION_SEND_MULTIPLE
                    ) {
                        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: ""
                        val dest = intent.getStringExtra("dest") ?: ""
                        var streamUri = ""

                        if (intent.action == android.content.Intent.ACTION_SEND) {
                            val uri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM) ?: intent.data
                            streamUri = uri?.toString() ?: ""
                        } else {
                            val uris = intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                            streamUri = uris?.firstOrNull()?.toString() ?: ""
                        }

                        navController.navigate(
                            "send_bundle?dest=${android.net.Uri.encode(
                                dest,
                            )}&sharedText=${android.net.Uri.encode(text)}&sharedFileUri=${android.net.Uri.encode(streamUri)}",
                        )
                        // Clear intent so it doesn't trigger again on configuration changes
                        currentIntentState.value = null
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = "registry",
                ) {
                    composable("registry") {
                        RegistryScreen(
                            navController = navController,
                            localServiceDao = localServiceDao,
                            bundleRecordDao = bundleRecordDao,
                        )
                    }

                    composable(
                        route = "service_view/{serviceEid}",
                        arguments = listOf(navArgument("serviceEid") { type = NavType.StringType }),
                    ) { backStackEntry ->
                        val serviceEid = backStackEntry.arguments?.getString("serviceEid") ?: ""
                        var viewerType by remember { mutableStateOf<ViewerType?>(null) }

                        LaunchedEffect(serviceEid) {
                            viewerType = localServiceDao.getById(serviceEid)?.viewerType
                        }

                        when (viewerType) {
                            ViewerType.CHAT -> {
                                ChatScreen(
                                    navController = navController,
                                    serviceEid = serviceEid,
                                    localServiceDao = localServiceDao,
                                    bundleRecordDao = bundleRecordDao,
                                )
                            }
                            ViewerType.BUNDLE_LIST -> {
                                BundleListScreen(
                                    navController = navController,
                                    serviceEid = serviceEid,
                                    localServiceDao = localServiceDao,
                                    bundleRecordDao = bundleRecordDao,
                                )
                            }
                            else -> {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(color = NeonCyan)
                                }
                            }
                        }
                    }

                    composable(
                        route = "send_bundle?dest={dest}&sourceService={sourceService}&sharedText={sharedText}&sharedFileUri={sharedFileUri}",
                        arguments =
                            listOf(
                                navArgument("dest") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                                navArgument("sourceService") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                                navArgument("sharedText") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                                navArgument("sharedFileUri") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                            ),
                    ) { backStackEntry ->
                        val dest = backStackEntry.arguments?.getString("dest") ?: ""
                        val sourceService = backStackEntry.arguments?.getString("sourceService") ?: ""
                        val sharedText = backStackEntry.arguments?.getString("sharedText") ?: ""
                        val sharedFileUri = backStackEntry.arguments?.getString("sharedFileUri") ?: ""
                        SendBundleScreen(
                            navController = navController,
                            bundleRecordDao = bundleRecordDao,
                            localServiceDao = localServiceDao,
                            preFilledDest = dest,
                            preFilledSourceService = sourceService,
                            preFilledPayloadText = sharedText,
                            preFilledFileUri = sharedFileUri,
                        )
                    }

                    composable("config") {
                        ConfigurationScreen(
                            navController = navController,
                            convergenceProfileDao = convergenceProfileDao,
                            routingRuleDao = routingRuleDao,
                            bpsecKeyDao = bpsecKeyDao,
                            localServiceDao = localServiceDao,
                        )
                    }

                    composable("logs") {
                        SystemLogScreen(
                            navController = navController,
                            logDao = systemLogDao,
                        )
                    }
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val neededPermissions =
            permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }

        if (neededPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(neededPermissions.toTypedArray())
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntentState.value = intent
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startEngineService() {
        val serviceIntent = android.content.Intent(this, com.dtn.messenger.service.DtnEngineService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent)
    }
}
