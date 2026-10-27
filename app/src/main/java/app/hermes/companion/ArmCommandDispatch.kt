package app.hermes.companion

import app.hermes.companion.model.DeviceCommand
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Only the ARM acknowledgement may wait independently; ordinary commands stay serial. */
internal suspend fun dispatchArmCommands(
    commands: Flow<DeviceCommand>,
    armBusy: suspend (DeviceCommand) -> Unit,
    handle: suspend (DeviceCommand) -> Unit,
) = coroutineScope {
    var armReply: Job? = null
    commands.collect { command ->
        if (command.action == "device.arm") {
            if (armReply?.isActive == true) armBusy(command)
            else armReply = launch(start = CoroutineStart.UNDISPATCHED) { handle(command) }
        } else {
            handle(command)
        }
    }
}
