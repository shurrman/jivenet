package net.jivenet.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import net.jivenet.client.config.TunnelConfig
import net.jivenet.client.config.TunnelMode
import net.jivenet.client.ui.StatsCard

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        vm = vm,
                        onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, onSettings: () -> Unit) {
    val cfg by vm.config.collectAsState()
    val stats by vm.stats.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // VpnService.prepare() возвращает Intent если нужно user consent.
    // RESULT_OK → запускаем TunnelService с ACTION_START.
    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            ctx.startForegroundService(
                Intent(ctx, TunnelService::class.java).setAction(TunnelService.ACTION_START)
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).padding(24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            StatusIndicator(stats.connected)

            Text(
                text = statusText(stats),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium
            )

            Text(
                modeLabel(cfg.mode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!cfg.isComplete()) {
                AssistChip(
                    onClick = onSettings,
                    label = { Text("Заполните настройки") }
                )
            }

            ToggleButton(
                connected = stats.connected,
                enabled = cfg.isComplete(),
            ) {
                if (stats.connected) stopActive(ctx, cfg)
                else startActive(ctx, cfg, vpnPermission::launch)
            }

            if (cfg.mode == TunnelMode.PROXY && stats.connected) {
                Text(stringResource(R.string.proxy_hint, "127.0.0.1", cfg.localPort))
            }

            if (stats.lastError.isNotEmpty()) {
                Text(
                    "⚠ ${stats.lastError}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(8.dp))
            StatsCard(stats)
        }
    }
}

@Composable
private fun StatusIndicator(connected: Boolean) {
    val color = if (connected) Color(0xFF2E7D32) else Color(0xFF9E9E9E)
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun ToggleButton(connected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        Text(stringResource(if (connected) R.string.action_disconnect else R.string.action_connect))
    }
}

private fun statusText(s: TunnelStats): String = when {
    s.connected -> "Подключено"
    s.uptimeSec > 0 -> "Подключение…"
    else -> "Отключено"
}

private fun modeLabel(m: TunnelMode): String =
    if (m == TunnelMode.VPN) "Режим: VPN (весь трафик)" else "Режим: SOCKS5-прокси"

private fun startActive(
    ctx: android.content.Context,
    cfg: TunnelConfig,
    requestVpnPermission: (Intent) -> Unit,
) {
    when (cfg.mode) {
        TunnelMode.VPN -> {
            // Если пользователь ранее не давал consent на VPN — Android вернёт
            // Intent для системного диалога. Иначе prepare() == null и можно
            // стартовать сразу.
            val prep = VpnService.prepare(ctx)
            if (prep != null) {
                requestVpnPermission(prep)
            } else {
                ctx.startForegroundService(
                    Intent(ctx, TunnelService::class.java).setAction(TunnelService.ACTION_START)
                )
            }
        }
        TunnelMode.PROXY -> {
            ctx.startForegroundService(
                Intent(ctx, ProxyService::class.java).setAction(ProxyService.ACTION_START)
            )
        }
    }
}

private fun stopActive(ctx: android.content.Context, cfg: TunnelConfig) {
    val (cls, action) = when (cfg.mode) {
        TunnelMode.VPN -> TunnelService::class.java to TunnelService.ACTION_STOP
        TunnelMode.PROXY -> ProxyService::class.java to ProxyService.ACTION_STOP
    }
    ctx.startService(Intent(ctx, cls).setAction(action))
}

