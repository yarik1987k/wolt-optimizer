package dev.yk.offercollector.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val brandLight = lightColorScheme(primary = Color(0xFF00658B), primaryContainer = Color(0xFFC4E7FF), onPrimaryContainer = Color(0xFF001E2C))
private val brandDark = darkColorScheme(primary = Color(0xFF7CD0FF), primaryContainer = Color(0xFF004C69), onPrimaryContainer = Color(0xFFC4E7FF))

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    // Material You (system colors) on Android 12+, e.g. Samsung One UI; fixed palette otherwise.
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> brandDark
        else -> brandLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Built around the courier's decisions: plan before, review after, what works, the route; nothing during a shift. */
private enum class Tab(val label: String) { PLAN("לפני"), REVIEW("אחרי"), LEARN("מה עובד"), ROUTE("מסלול"), SETTINGS("הגדרות") }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val settings by vm.settings.collectAsStateWithLifecycle()
                    val message by vm.message.collectAsStateWithLifecycle()
                    var tab by rememberSaveable { mutableStateOf(Tab.PLAN) }
                    // Sub-screens on top of a tab: "pick", "consent", "detail".
                    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
                    var detail by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
                    val snackbar = remember { SnackbarHostState() }

                    LaunchedEffect(message) {
                        message?.let { snackbar.showSnackbar(it); vm.message.value = null }
                    }
                    BackHandler(enabled = overlay != null || tab != Tab.PLAN) {
                        if (overlay == "detail") overlay = "diagnostics" else if (overlay != null) overlay = null else tab = Tab.PLAN
                    }

                    if (!settings.generalConsent || overlay == "consent") {
                        Scaffold { pad -> Box(Modifier.padding(pad).fillMaxSize()) { ConsentScreen(vm) { overlay = null } } }
                        return@CompositionLocalProvider
                    }
                    Scaffold(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        snackbarHost = { SnackbarHost(snackbar) },
                        bottomBar = {
                            if (overlay == null || overlay == "diagnostics" || overlay == "offers") NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                Tab.entries.forEach { t ->
                                    NavigationBarItem(
                                        selected = tab == t,
                                        onClick = { tab = t; overlay = null },
                                        icon = {
                                            Icon(
                                                when (t) {
                                                    Tab.PLAN -> Icons.Filled.DateRange
                                                    Tab.REVIEW -> Icons.Filled.CheckCircle
                                                    Tab.LEARN -> Icons.Filled.Star
                                                    Tab.ROUTE -> Icons.Filled.Place
                                                    Tab.SETTINGS -> Icons.Filled.Settings
                                                },
                                                contentDescription = null,
                                            )
                                        },
                                        label = { Text(t.label) },
                                    )
                                }
                            }
                        },
                    ) { pad ->
                        Box(Modifier.padding(pad).fillMaxSize()) {
                            when {
                                overlay == "pick" -> PackagePickerScreen(vm) { overlay = null }
                                overlay == "detail" && detail != null -> EventDetailScreen(vm, detail!!.first, detail!!.second)
                                overlay == "diagnostics" -> DiagnosticsScreen(vm) { k, id -> detail = k to id; overlay = "detail" }
                                overlay == "offers" -> OffersScreen(vm)
                                tab == Tab.PLAN -> HomeScreen(vm, onPick = { overlay = "pick" }) { PlanCards(vm) }
                                tab == Tab.REVIEW -> ReviewScreen(vm)
                                tab == Tab.LEARN -> LearnScreen(vm)
                                tab == Tab.ROUTE -> RouteScreen(vm)
                                else -> SettingsScreen(
                                    vm, settings, onPick = { overlay = "pick" }, onConsent = { overlay = "consent" },
                                    onDiagnostics = { overlay = "diagnostics" }, onOffers = { overlay = "offers" },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshStatus()
    }
}
