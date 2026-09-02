package dev.local.physicalmemory.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.local.physicalmemory.ui.inventory.ItemListUiState

@Composable
fun ItemsScreen(state: ItemListUiState,onItem: (Long)->Unit,onRetry: ()->Unit,enabled: Boolean) {
    LazyColumn(Modifier.fillMaxSize().testTag("items-screen"),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("全部物品",style=MaterialTheme.typography.headlineLarge)
            Text("共 ${state.rows.size} 件",Modifier.testTag("items-count"))
            Text("点击物品查看详情、调整信息或管理库存。")
        }
        if(state.loading) item {CircularProgressIndicator(Modifier.testTag("items-loading"))}
        state.error?.let {error->item {Text(error,color=MaterialTheme.colorScheme.error);TextButton(onRetry) {Text("重试")}}}
        if(!state.loading && state.error==null && state.rows.isEmpty()) item {
            Column(Modifier.padding(vertical=32.dp).testTag("items-empty"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("还没有物品",style=MaterialTheme.typography.titleLarge)
                Text("在首页记录物品，确认保存后会显示在这里。")
            }
        }
        items(state.rows,key={it.id}) {item->
            OutlinedCard(onClick={onItem(item.id)},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("stored-item-${item.id}")) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(item.name,style=MaterialTheme.typography.titleLarge)
                    Text("位置：${item.location.ifBlank {"未记录"}}")
                    Text("库存：${item.quantity} 份")
                }
            }
        }
    }
}
