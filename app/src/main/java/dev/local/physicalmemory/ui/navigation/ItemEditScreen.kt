package dev.local.physicalmemory.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.local.physicalmemory.domain.draft.ItemEditDraft
import dev.local.physicalmemory.ui.inventory.InventoryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemEditScreen(draft: ItemEditDraft?,initialExpiryId: Long?,busy: Boolean,message: String?,model: InventoryViewModel) {
    if(draft==null) return
    val focus=LocalFocusManager.current
    var expiryId by rememberSaveable(draft.original.id) {mutableStateOf(initialExpiryId)}
    var addedExpiryKey by rememberSaveable(draft.original.id) {mutableStateOf<String?>(null)}
    var pendingRemovalId by rememberSaveable(draft.original.id) {mutableStateOf<Long?>(null)}
    BackHandler(!busy) {model.cancelItemEdit()}
    Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),topBar={
        TopAppBar(title={Text("调整物品")},navigationIcon={TextButton(model::cancelItemEdit,enabled=!busy,modifier=Modifier.testTag("item-edit-back")) {Text("‹ 返回")}})
    }) {padding->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().testTag("item-edit-screen"),
            contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text("保存后生效；返回或取消不会修改记录。")
                OutlinedTextField(draft.name,model::editStoredName,Modifier.fillMaxWidth().testTag("edit-stored-name"),enabled=!busy,label={Text("物品名称")},singleLine=true)
                OutlinedTextField(draft.location,model::editStoredLocation,Modifier.fillMaxWidth().testTag("edit-stored-location"),enabled=!busy,label={Text("位置")})
            }
            item {
                Text("库存与到期日期",style=MaterialTheme.typography.titleLarge)
                Text("当前库存：${draft.original.quantity} 份")
                Text("调整后库存：${draft.quantity} 份",Modifier.testTag("edit-stock-total"))
                OutlinedTextField(draft.addedCountText,model::editStoredAddedCount,
                    Modifier.fillMaxWidth().testTag("edit-added-count"),enabled=!busy,
                    label={Text("新增库存（份）")},singleLine=true,
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                Text("增加库存请填写新增数量；减少库存请在下方点选要删除的那一份。")
                if(draft.quantity==0) Text("暂无库存，可填写新增数量并选择到期日期。物品记录会保留。",Modifier.testTag("edit-stock-empty"))
            }
            // New entries appear first, immediately below the quantity field, including for zero-stock Items.
            itemsIndexed(draft.addedUnits,key={_,unit->unit.key}) {index,unit->
                OutlinedButton({focus.clearFocus();addedExpiryKey=unit.key},enabled=!busy,
                    modifier=Modifier.fillMaxWidth().testTag("edit-added-expiry-${index+1}"),
                    shape=MaterialTheme.shapes.small,contentPadding=PaddingValues(16.dp)) {
                    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text("新增第 ${index+1} 份 · 到期日期")
                        Text(unit.expiryDate.ifBlank {"未记录"})
                        Text("选择日期 ›",Modifier.align(androidx.compose.ui.Alignment.End))
                    }
                }
            }
            itemsIndexed(draft.original.units,key={_,u->u.id}) {index,unit->
                val removed=unit.id in draft.confirmedRemovedUnitIds
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("现有库存 #${index+1}",style=MaterialTheme.typography.titleMedium)
                        if(removed) {
                            Text("待删除 · 保存调整后生效")
                            TextButton({model.undoStoredUnitRemoval(unit.id)},enabled=!busy,
                                modifier=Modifier.testTag("undo-edit-delete-${unit.id}")) {Text("撤销删除")}
                        } else {
                            OutlinedButton({focus.clearFocus();expiryId=unit.id},enabled=!busy,
                                modifier=Modifier.fillMaxWidth().testTag("edit-stored-expiry-${unit.id}"),shape=MaterialTheme.shapes.small) {
                                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                    Text("到期日期：${draft.expiryDates.getValue(unit.id).ifBlank {"未记录"}}")
                                    Text("选择日期 ›",Modifier.align(androidx.compose.ui.Alignment.End))
                                }
                            }
                            TextButton({focus.clearFocus();pendingRemovalId=unit.id},enabled=!busy,
                                modifier=Modifier.testTag("edit-delete-unit-${unit.id}")) {Text("删除这一份")}
                        }
                    }
                }
            }
            item {
                message?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("item-edit-message"))}
                val errors=draft.errors()
                errors.forEach {Text(it,color=MaterialTheme.colorScheme.error)}
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(model::cancelItemEdit,enabled=!busy,modifier=Modifier.weight(1f).testTag("cancel-item-edit")) {Text("取消")}
                    Button({focus.clearFocus();model.saveItemEdit()},enabled=!busy && errors.isEmpty(),modifier=Modifier.weight(1f).testTag("save-item-edit")) {Text("保存调整")}
                }
            }
        }
    }
    expiryId?.takeIf {it in draft.expiryDates && it !in draft.confirmedRemovedUnitIds && !busy}?.let {id->
        ExpiryDatePickerPage(draft.expiryDates.getValue(id),"${draft.name} #${draft.original.units.indexOfFirst {it.id==id}+1}",
            onDismiss={expiryId=null},onConfirm={model.editStoredExpiry(id,it);expiryId=null})
    }
    addedExpiryKey?.let {key->draft.addedUnits.firstOrNull {it.key==key}}?.takeIf {!busy}?.let {unit->
        ExpiryDatePickerPage(unit.expiryDate,"${draft.name} · 新增第 ${draft.addedUnits.indexOf(unit)+1} 份",
            onDismiss={addedExpiryKey=null},onConfirm={model.editStoredAddedExpiry(unit.key,it);addedExpiryKey=null})
    }
    pendingRemovalId?.let {id->draft.original.units.firstOrNull {it.id==id && id !in draft.confirmedRemovedUnitIds}}?.let {unit->
        AlertDialog(onDismissRequest={pendingRemovalId=null},modifier=Modifier.testTag("edit-delete-confirmation"),
            title={Text("删除这一份库存？")},text={Text("${draft.name} #${draft.original.units.indexOf(unit)+1}\n" +
                "到期日期：${draft.expiryDates.getValue(unit.id).ifBlank {"未记录"}}\n\n仅删除选中的这一份，保存调整后生效。物品记录会保留。")},
            dismissButton={TextButton({pendingRemovalId=null},enabled=!busy,modifier=Modifier.testTag("cancel-edit-delete")) {Text("取消")}},
            confirmButton={TextButton({model.confirmStoredUnitRemoval(unit.id);pendingRemovalId=null},enabled=!busy,
                modifier=Modifier.testTag("confirm-edit-delete")) {Text("确认删除")}})
    }
}
