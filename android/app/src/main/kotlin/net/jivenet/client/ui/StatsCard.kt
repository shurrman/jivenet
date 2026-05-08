package net.jivenet.client.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.jivenet.client.R
import net.jivenet.client.TunnelStats

@Composable
fun StatsCard(s: TunnelStats) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.stats_uptime, formatUptime(s.uptimeSec)))
            Text(stringResource(R.string.stats_traffic, humanBytes(s.bytesSent), humanBytes(s.bytesRecv)))
            Text(stringResource(R.string.stats_streams, s.activeConns, s.totalConns))
        }
    }
}

private fun formatUptime(secs: Long): String {
    if (secs <= 0) return "—"
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun humanBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val kb = n / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
