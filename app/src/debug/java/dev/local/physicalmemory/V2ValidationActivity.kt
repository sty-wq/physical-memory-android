package dev.local.physicalmemory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.physicalmemory.ui.inventory.*
import dev.local.physicalmemory.ui.theme.MemoryTheme

/** Debug-only host. Instrumentation supplies an isolated repository; this host never opens the user's DB. */
class V2ValidationActivity : ComponentActivity() {
    private var model: InventoryViewModel? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model=factory?.invoke()
        this.model=model
        setContent { MemoryTheme {
            if(model==null) Text("V2 隔离验证页：请从开发测试启动")
            else { val state by model.state.collectAsStateWithLifecycle(); InventoryScreen(state,model) }
        } }
    }
    override fun onStop() { model?.onPageStopped(); super.onStop() }
    override fun onPause() { model?.onPagePaused(); super.onPause() }
    companion object { var factory: (() -> InventoryViewModel)? = null }
}
