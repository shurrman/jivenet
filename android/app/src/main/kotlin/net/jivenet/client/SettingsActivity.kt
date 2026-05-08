package net.jivenet.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.jivenet.client.config.ConfigRepository
import net.jivenet.client.config.TunnelConfig
import net.jivenet.client.config.TunnelMode
import net.jivenet.client.ui.QrScanner

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = ConfigRepository(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        repo = repo,
                        onScanRequested = { needCameraAndOpen ->
                            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED) {
                                needCameraAndOpen(true)
                            } else {
                                requestCameraLauncher.launch(Manifest.permission.CAMERA)
                                pendingOpen = needCameraAndOpen
                            }
                        },
                        onSave = { cfg ->
                            lifecycleScope.launch { repo.save(cfg) }
                        },
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    private var pendingOpen: ((Boolean) -> Unit)? = null
    private val requestCameraLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingOpen?.invoke(granted)
        pendingOpen = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    repo: ConfigRepository,
    onScanRequested: (callback: (Boolean) -> Unit) -> Unit,
    onSave: (TunnelConfig) -> Unit,
    onBack: () -> Unit,
) {
    val current by repo.flow.collectAsStateWithLifecycle(initialValue = TunnelConfig())
    var form by remember(current) { mutableStateOf(current) }
    var scanner by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val msgApplied = stringResource(R.string.config_applied)
    val msgCameraNeeded = "Нужно разрешение на камеру"

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("←") }
                }
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = form.domain,
                onValueChange = { form = form.copy(domain = it) },
                label = { Text(stringResource(R.string.label_domain)) },
                placeholder = { Text(stringResource(R.string.hint_domain)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                ),
            )
            // Publickey — 64 hex-символа, в одну строку не влезают на экране
            // узкого телефона. singleLine=false + minLines=2 даёт wrap в 2 строки,
            // моноширинный шрифт для удобства сверки.
            OutlinedTextField(
                value = form.pubkey,
                onValueChange = { form = form.copy(pubkey = it.trim().lowercase()) },
                label = { Text(stringResource(R.string.label_pubkey)) },
                singleLine = false,
                minLines = 2,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                // bodySmall + monospace: 64 hex-символа точно влезают в 2 строки
                // на узком экране (360dp) с запасом.
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
            )
            DohField(form.doh) { form = form.copy(doh = it) }
            ModeSelector(form.mode) { form = form.copy(mode = it) }
            OutlinedTextField(
                value = form.localPort.toString(),
                onValueChange = {
                    form = form.copy(localPort = it.toIntOrNull()?.coerceIn(1024, 65535) ?: form.localPort)
                },
                label = { Text(stringResource(R.string.label_port)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        onScanRequested { granted ->
                            if (granted) scanner = true
                            else scope.launch { snack.showSnackbar(msgCameraNeeded) }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr))
                    Spacer(Modifier.width(6.dp))
                    Text("QR", maxLines = 1)
                }

                Button(
                    onClick = {
                        if (form.isComplete()) {
                            onSave(form)
                            scope.launch { snack.showSnackbar(msgApplied) }
                        } else {
                            error = "заполните все поля"
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.save)) }
            }

            error?.let {
                Text("⚠ $it", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (scanner) {
        QrScanner(
            onScanned = { text ->
                scanner = false
                runCatching { TunnelConfig.fromJson(text, form) }
                    .onSuccess { form = it; error = null }
                    .onFailure { error = it.message ?: "QR не распарсен" }
            },
            onClose = { scanner = false }
        )
    }
}

@Composable
private fun DohField(current: String, onChange: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Пресеты: два транспорта.
    //   DoH (https://IP/...) — основной путь. Host обязан быть IP, т.к.
    //     Go runtime на Android без cgo не резолвит имена.
    //   UDP (udp://host:53) — запасной, через резолвер оператора. Работает
    //     на мобильных сетях РФ, где весь трафик на известные DoH IP заблокирован.
    val presets = listOf(
        "Авто UDP"    to "udp://auto:53",  // подменяется на адрес ОС в момент клика
        "Cloudflare"  to "https://1.1.1.1/dns-query",
        "Google"      to "https://8.8.8.8/dns-query",
        "Quad9"       to "https://9.9.9.9/dns-query",
        "OpenDNS"     to "https://208.67.222.222/dns-query",
        "CleanBrowsing" to "https://185.228.168.9/dns-query",
        "AliDNS"      to "https://223.5.5.5/dns-query",
    )
    // Валидация: допустим https:// с IP, udp://host:port, dot://host:port.
    val validation = remember(current) { validateResolver(current) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = current,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.label_doh)) },
            singleLine = true,
            isError = validation != null && current.isNotEmpty(),
            supportingText = {
                if (current.isNotEmpty() && validation != null) {
                    Text(validation, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        "https://<IP>/... для DoH или udp://<IP>:53 " +
                            "(если мобильный оператор режет DoH)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
            ),
        )
        FlowChips(
            labels = presets.map { it.first },
            current = presets.firstOrNull { matchesPreset(current, it.second) }?.first,
        ) { label ->
            val raw = presets.firstOrNull { it.first == label }?.second ?: return@FlowChips
            val resolved = if (raw == "udp://auto:53") {
                SystemDns.udpResolverUrl(ctx) ?: "udp://8.8.8.8:53"
            } else raw
            onChange(resolved)
        }
    }
}

/** null если ОК, иначе — текст ошибки. */
private fun validateResolver(raw: String): String? {
    if (raw.isBlank()) return null
    val ipv4 = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
    return when {
        raw.startsWith("udp://") || raw.startsWith("dot://") -> {
            val hp = raw.substringAfter("://")
            val host = hp.substringBeforeLast(":")
            val port = hp.substringAfterLast(":", "").toIntOrNull()
            when {
                host.isEmpty() || port == null -> "Ожидается host:port после udp:// / dot://"
                !ipv4.matches(host) -> "Хост должен быть IP-адресом"
                else -> null
            }
        }
        raw.startsWith("https://") -> {
            val host = runCatching { java.net.URI(raw).host }.getOrNull()
            when {
                host.isNullOrEmpty() -> "Не разобрал URL"
                ipv4.matches(host) -> null
                host.startsWith("[") && host.endsWith("]") -> null
                else -> "Хост в URL должен быть IP-адресом"
            }
        }
        else -> "Префикс должен быть https:// / udp:// / dot://"
    }
}

/** Совпадает ли текущее значение поля с пресетом (учёт авто-UDP). */
private fun matchesPreset(current: String, preset: String): Boolean {
    if (preset == "udp://auto:53") return current.startsWith("udp://")
    return current == preset
}

/** FlowRow с FilterChip — автоматически переносит чипы на новую строку при нехватке ширины. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(
    labels: List<String>,
    current: String?,
    onPick: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEach { label ->
            FilterChip(
                selected = label == current,
                onClick = { onPick(label) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun ModeSelector(current: TunnelMode, onChange: (TunnelMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.label_mode), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = current == TunnelMode.VPN,
                onClick = { onChange(TunnelMode.VPN) },
                label = { Text("VPN") },
            )
            FilterChip(
                selected = current == TunnelMode.PROXY,
                onClick = { onChange(TunnelMode.PROXY) },
                label = { Text("SOCKS/HTTP") },
            )
        }
        Text(
            text = when (current) {
                TunnelMode.VPN ->
                    "VPN-режим: весь трафик устройства захватывается через TUN. " +
                    "Android спросит разрешение при первом подключении. Работает на " +
                    "Wi-Fi и мобильной сети без adb и SocksDroid."
                TunnelMode.PROXY ->
                    "Прокси-режим: локальный HTTP/SOCKS5 на 127.0.0.1:<порт>. " +
                    "Настройте прокси в Wi-Fi или используйте SocksDroid. На мобильной " +
                    "сети потребуется adb или SocksDroid."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

