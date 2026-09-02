package dev.local.physicalmemory.ui.inventory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import androidx.navigation.NavGraph.Companion.findStartDestination
import dev.local.physicalmemory.ui.navigation.*
import dev.local.physicalmemory.ui.voice.HoldPhase
import dev.local.physicalmemory.voice.SpeechRecognitionState

/** Home/Items/History tabs, explicit edit routes and one shared detail sheet. */
@Composable
fun InventoryScreen(s: InventoryUiState,model: InventoryViewModel,
    onHoldStart: ()->Boolean = { model.hold.down() },onPermissions: ()->Unit = {}) {
    val nav=rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route=entry?.destination?.route
    val hold by model.hold.state.collectAsStateWithLifecycle()
    val history by model.history.collectAsStateWithLifecycle()
    val items by model.items.collectAsStateWithLifecycle()
    LaunchedEffect(s.editorOpen,s.itemEdit!=null,route) {
        val target=when {s.itemEdit!=null->"item-edit";s.editorOpen->"draft";else->null}
        if(target!=null && route!=target) nav.navigate(target) {launchSingleTop=true}
        else if(target==null && route in setOf("draft","item-edit")) nav.popBackStack()
    }
    BackHandler(hold.phase in setOf(HoldPhase.Starting,HoldPhase.Recording,HoldPhase.CancelArmed)) { model.cancelSpeech() }
    Scaffold(bottomBar={
        if(route !in setOf("draft","item-edit")) NavigationBar {
            listOf("home" to "首页","items" to "物品","history" to "历史").forEach { (target,label) ->
                NavigationBarItem(selected=route==target,onClick={
                    model.cancelSpeech()
                    nav.navigate(target) { popUpTo(nav.graph.findStartDestination().id) { saveState=true };launchSingleTop=true;restoreState=true }
                },enabled=!s.busy && hold.phase !in setOf(HoldPhase.Starting,HoldPhase.Recording,HoldPhase.CancelArmed,HoldPhase.Processing),
                    icon={Text(when(target) {"home"->"⌂";"items"->"▤";else->"◷"})},label={Text(label)},modifier=Modifier.testTag("tab-$target"))
            }
        }
    }) { padding ->
        NavHost(navController=nav,startDestination="home",modifier=Modifier.padding(padding).consumeWindowInsets(padding)) {
            composable("home") {
                HomeScreen(HomeScreenState(s.input,s.busy,s.parsing,s.message,
                    s.speech.speechState is SpeechRecognitionState.Recognizing || s.speech.speechState is SpeechRecognitionState.Finalizing,s.speech.permissionMessage!=null),
                    hold,model.hold,model::inputChanged,{model.parse()},model::cancelParsing,onHoldStart,model::prepareSpeech,onPermissions=onPermissions)
            }
            composable("history") { HistoryScreen(history,model::openItem,!s.busy) }
            composable("items") {ItemsScreen(items,model::openItem,model::loadItems,!s.busy)}
            composable("draft") { DraftEditorScreen(DraftEditorUiState(s.input,s.draft,s.busy,s.resolvingName,s.parsing,s.message),model) }
            composable("item-edit") {ItemEditScreen(s.itemEdit,s.editExpiryId,s.busy,s.message,model)}
        }
    }
    s.detail?.let { item ->
        ItemDetailSheet(ItemDetailUiState(item,s.busy,s.pendingDeletion,s.message),model::dismissDetail,
            model::requestDelete,model::cancelDelete,model::confirmDelete,
            onEdit={model.beginItemEdit()},onEditExpiry={model.beginItemEdit(it)},onAddInventory=model::addInventoryFromDetail)
    }
}
