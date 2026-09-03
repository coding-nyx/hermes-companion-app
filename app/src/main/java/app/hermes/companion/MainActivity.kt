package app.hermes.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hermes.companion.design.CompanionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val originExtra = intent.getStringExtra(EXTRA_ORIGIN)
        val autoConnect = intent.getBooleanExtra(EXTRA_AUTOCONNECT, originExtra != null)
        setContent {
            CompanionTheme {
                val app = application as CompanionApp
                val vm: CompanionViewModel = viewModel(factory = CompanionViewModel.factory(app))
                val state by vm.state.collectAsStateWithLifecycle()
                LaunchedEffect(originExtra, autoConnect) {
                    if (originExtra != null) {
                        vm.onOriginChange(originExtra)
                        if (autoConnect) vm.connect(originExtra)
                    }
                }
                CompanionShell(
                    state = state,
                    onOriginChange = vm::onOriginChange,
                    onConnect = { vm.connect() },
                    onSelectProfile = vm::selectProfile,
                    onTab = vm::selectTab,
                    onOpenSession = vm::openSession,
                    onNewThread = vm::newThread,
                    onCloseChat = vm::closeChat,
                    onDraftChange = vm::onDraftChange,
                    onSend = vm::send,
                    onInterrupt = vm::interrupt,
                    onApproval = vm::respondApproval,
                )
            }
        }
    }

    companion object {
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_AUTOCONNECT = "autoconnect"
    }
}
