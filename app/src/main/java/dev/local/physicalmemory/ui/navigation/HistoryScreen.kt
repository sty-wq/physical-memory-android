package dev.local.physicalmemory.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.local.physicalmemory.history.HistoryRecord
import dev.local.physicalmemory.ui.inventory.HistoryUiState
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(state: HistoryUiState, onItem: (Long)->Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val today=LocalDate.now();val zone=ZoneId.systemDefault()
    val groups=state.rows.groupBy { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
    LazyColumn(modifier.fillMaxSize().testTag("history-screen"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("历史",style=MaterialTheme.typography.headlineLarge);Text("已完成的操作 · 点击查看物品当前状态") }
        state.error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
        if(state.rows.isEmpty()) item {
            Column(Modifier.padding(vertical=48.dp).testTag("history-empty"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("暂无历史记录",style=MaterialTheme.typography.titleLarge)
                Text("完成一次物品记录或查询后，相关操作会显示在这里。")
            }
        }
        groups.forEach { (day,rows) ->
            item(key="day-$day") { Text(when(day) { today -> "今天"; today.minusDays(1) -> "昨天";else -> day.toString() },style=MaterialTheme.typography.titleMedium) }
            items(rows,key={it.key}) { row -> HistoryRow(row,enabled) { onItem(row.itemId) } }
        }
    }
}

@Composable private fun HistoryRow(row: HistoryRecord,enabled: Boolean,onClick: ()->Unit) {
    OutlinedCard(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("history-${row.key}")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(Instant.ofEpochMilli(row.completedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),style=MaterialTheme.typography.labelMedium)
            Text(row.summary,style=MaterialTheme.typography.bodyLarge)
        }
    }
}
