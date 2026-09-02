package dev.local.physicalmemory.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.local.physicalmemory.domain.ItemState
import dev.local.physicalmemory.ui.inventory.PendingDeletion

data class ItemDetailUiState(val item: ItemState, val busy: Boolean, val pendingDeletion: PendingDeletion?, val message: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailSheet(state: ItemDetailUiState,onDismiss: ()->Unit,onDelete: (Long)->Unit,
    onCancelDelete: ()->Unit,onConfirmDelete: ()->Unit,onEdit: ()->Unit,onEditExpiry: (Long)->Unit,onAddInventory: ()->Unit) {
    val sheet=rememberModalBottomSheetState(skipPartiallyExpanded=true,
        confirmValueChange={ it!=SheetValue.Hidden || (!state.busy && state.pendingDeletion==null) })
    ModalBottomSheet(onDismissRequest=onDismiss,sheetState=sheet,modifier=Modifier.testTag("item-detail-sheet")) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=700.dp).testTag("detail-list"),
            contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                TextButton(onDismiss,enabled=!state.busy && state.pendingDeletion==null,modifier=Modifier.testTag("close-detail")) { Text("关闭") }
                Text(state.item.name,style=MaterialTheme.typography.headlineMedium,modifier=Modifier.testTag("detail-name"))
                Text("位置：${state.item.location.ifBlank { "未记录" }}",Modifier.testTag("detail-location"))
                Text("数量：${state.item.quantity} 份",Modifier.testTag("detail-quantity"))
                state.message?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
                OutlinedButton(onEdit,enabled=!state.busy && state.pendingDeletion==null,modifier=Modifier.fillMaxWidth().testTag("edit-item-info")) {Text("调整信息")}
                OutlinedButton(onAddInventory,enabled=!state.busy && state.pendingDeletion==null,modifier=Modifier.fillMaxWidth().testTag("add-item-inventory")) {Text("增加库存")}
            }
            if(state.item.units.isEmpty()) item { Text("暂无库存实例",Modifier.testTag("units-empty"));Text("物品及位置记录仍然保留。") }
            itemsIndexed(state.item.units,key={_,unit->unit.id}) { index,unit ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("unit-${unit.id}")) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${state.item.name} #${index+1}",style=MaterialTheme.typography.titleMedium)
                        Text("过期日期：${unit.expiryDate ?: "未记录"}",Modifier.testTag("unit-expiry-${unit.id}"))
                        TextButton({onEditExpiry(unit.id)},enabled=!state.busy && state.pendingDeletion==null,modifier=Modifier.testTag("adjust-unit-${unit.id}")) {Text("调整日期")}
                        TextButton({onDelete(unit.id)},enabled=!state.busy,modifier=Modifier.testTag("delete-unit-${unit.id}")) { Text("删除") }
                    }
                }
            }
        }
    }
    state.pendingDeletion?.let { DeleteConfirmationDialog(it,state.busy,onCancelDelete,onConfirmDelete) }
}

@Composable
fun DeleteConfirmationDialog(pending: PendingDeletion,busy: Boolean,onCancel: ()->Unit,onConfirm: ()->Unit) {
    AlertDialog(onDismissRequest=onCancel,modifier=Modifier.testTag("delete-confirmation"),title={Text("确认删除？")},
        text={Text("${pending.itemName}\n过期日期：${pending.unit.expiryDate ?: "未记录"}\n\n删除后该库存实例将从记录中移除，仅删除选中的这一份。")},
        dismissButton={TextButton(onCancel,enabled=!busy,modifier=Modifier.testTag("cancel-delete")) { Text("取消") }},
        confirmButton={TextButton(onConfirm,enabled=!busy,modifier=Modifier.testTag("confirm-delete")) { Text("确认删除") }})
}
