package dev.local.physicalmemory.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.local.physicalmemory.domain.draft.*
import dev.local.physicalmemory.nlu.Issue
import dev.local.physicalmemory.ui.inventory.InventoryViewModel

data class DraftEditorUiState(val input: String,val draft: OperationDraft?,val busy: Boolean,
    val resolvingName: Boolean,val parsing: Boolean,val message: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DraftEditorScreen(s: DraftEditorUiState,model: InventoryViewModel) {
    val focus=LocalFocusManager.current
    var editingExpiryKey by rememberSaveable(s.draft?.data?.id) { mutableStateOf<String?>(null) }
    val editingUnit=(s.draft as? AddUnitsDraft)?.data?.units?.firstOrNull { it.key==editingExpiryKey }
    BackHandler { if(!s.busy || s.parsing) model.leaveEditor() }
    Scaffold(contentWindowInsets=WindowInsets(0,0,0,0),topBar={
        TopAppBar(title={Text("确认信息")},navigationIcon={
            TextButton(model::leaveEditor,enabled=!s.busy || s.parsing,modifier=Modifier.testTag("draft-back")) { Text("‹ 返回") }
        })
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).imePadding().testTag("draft-screen"),
            contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                OutlinedTextField(s.input,model::inputChanged,Modifier.fillMaxWidth().testTag("command-input"),enabled=!s.busy,
                    label={Text("识别内容（可修改）")},minLines=2,maxLines=4)
                TextButton(onClick={focus.clearFocus();model.parse()},enabled=!s.busy && s.input.isNotBlank(),modifier=Modifier.testTag("parse-button")) { Text("重新解析") }
                if(s.parsing) { Text("正在理解内容…");TextButton(model::cancelParsing) { Text("取消处理") } }
            }
            s.message?.let { item { Text(it,Modifier.testTag("operation-message")) } }
            s.draft?.let { draft ->
                val d=draft.data
                item {
                    Text(when(draft) { is CreateItemDraft -> "新建物品草稿"; is UpdateItemDraft -> "修改物品草稿"; is AddUnitsDraft -> "增加库存草稿" },style=MaterialTheme.typography.titleLarge)
                    Text("确认后才保存；你可以直接修改下面的信息。")
                    if(d.rawText!=d.correctedText) Text("原始识别：${d.rawText}",style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(d.itemName,model::editName,Modifier.fillMaxWidth().testTag("draft-item"),enabled=!s.busy,label={Text("物品名称")},singleLine=true)
                    if(s.resolvingName) Text("正在核对当前物品…")
                    d.current?.let { current ->
                        Text("当前物品位置：${current.location.ifBlank { "未记录" }}",Modifier.testTag("current-location"))
                        Text("当前库存：${current.quantity} 份",Modifier.testTag("current-quantity"))
                    }
                    OutlinedTextField(d.proposedLocation,model::editLocation,Modifier.fillMaxWidth().testTag("draft-location"),enabled=!s.busy,
                        label={Text("本次确认的位置")},supportingText={Text("未记录位置的新物品可以留空")})
                    if(d.current!=null && d.current.location!=d.proposedLocation.trim()) {
                        Text("${d.current.location.ifBlank { "未记录" }} → ${d.proposedLocation.ifBlank { "未填写" }}",style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("location-change"))
                        Text("确认将修改整个物品的位置，已有库存和本次新增库存都共享此位置。",color=MaterialTheme.colorScheme.primary)
                    } else if(d.current!=null && draft !is AddUnitsDraft) Text("位置没有变化，确认不会重复更新位置。")
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("同时添加库存",Modifier.weight(1f))
                        Switch(draft is AddUnitsDraft,{focus.clearFocus();model.setAddInventory(it)},enabled=!s.busy,modifier=Modifier.testTag("draft-add-inventory"))
                    }
                    if(draft is AddUnitsDraft) {
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(d.countText,model::editCount,Modifier.fillMaxWidth().testTag("draft-count"),enabled=!s.busy,label={Text("新增数量")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                            OutlinedTextField(d.unitLabel,model::editUnitLabel,Modifier.fillMaxWidth().testTag("draft-unit-label"),enabled=!s.busy,label={Text("量词（可选）")},singleLine=true)
                        }
                        d.countText.toIntOrNull()?.takeIf {it in 1..DraftValidator.MAX_UNITS}?.let {count ->
                            Text("当前 ${d.current?.quantity ?: 0} 份 + 本次新增 $count 份 → 确认后 ${(d.current?.quantity ?: 0)+count} 份",Modifier.testTag("draft-quantity-summary"))
                        }
                        Text("每份日期可以单独修改或清空；数量以实际新增实例为准。")
                    } else Text("只保存名称和位置。如需填写数量和到期日期，请开启同时添加库存。")
                }
                items(if(draft is AddUnitsDraft) d.units else emptyList(),key={"draft-${it.key}"}) { unit ->
                    val index=d.units.indexOf(unit)+1
                    OutlinedButton(onClick={focus.clearFocus();editingExpiryKey=unit.key},enabled=!s.busy,
                        modifier=Modifier.fillMaxWidth().testTag("draft-expiry-$index"),shape=MaterialTheme.shapes.small,
                        contentPadding=PaddingValues(16.dp)) {
                        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text("${d.itemName.ifBlank { "物品" }} #$index 到期日期",style=MaterialTheme.typography.labelLarge)
                            Text(unit.expiryDate.ifBlank { "未记录" },style=MaterialTheme.typography.bodyLarge)
                            Text("选择日期 ›",Modifier.align(androidx.compose.ui.Alignment.End),style=MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                item {
                    if(d.nluResult.issues.isNotEmpty()) {
                        Text("需要核对："+d.nluResult.issues.joinToString("、") { issueLabel(it) })
                        Row { Checkbox(d.reviewedIssues,model::reviewIssues,enabled=!s.busy,modifier=Modifier.testTag("review-issues"));Text("我已核对并修正上述信息") }
                    }
                    val errors=DraftValidator.errors(draft)
                    errors.forEach { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(model::cancelDraft,enabled=!s.busy,modifier=Modifier.weight(1f).testTag("cancel-draft")) { Text("取消") }
                        Button(onClick={focus.clearFocus();model.confirmDraft()},enabled=!s.busy && !s.resolvingName && errors.isEmpty(),
                            modifier=Modifier.weight(1f).testTag("confirm-draft")) { Text(if(draft is AddUnitsDraft) "确认添加" else "确认保存") }
                    }
                }
            }
        }
    }
    if(editingUnit!=null && !s.busy) {
        val data=s.draft!!.data
        ExpiryDatePickerPage(value=editingUnit.expiryDate,
            itemLabel="${data.itemName.ifBlank { "物品" }} #${data.units.indexOf(editingUnit)+1}",
            onDismiss={editingExpiryKey=null},onConfirm={date ->
                model.editExpiry(editingUnit.key,date)
                editingExpiryKey=null
            })
    }
}

private fun issueLabel(issue: Issue) = when(issue) {
    Issue.MISSING_ITEM -> "缺少物品名称"; Issue.MISSING_COUNT -> "缺少数量"; Issue.INVALID_COUNT -> "数量无效"
    Issue.INVALID_DATE -> "日期无效"; Issue.AMBIGUOUS_ITEM -> "物品不明确"; Issue.AMBIGUOUS_LOCATION -> "位置不明确"
    Issue.AMBIGUOUS_DATE -> "请逐份核对日期"; Issue.UNSUPPORTED_OPERATION -> "包含暂不支持的操作"
}
