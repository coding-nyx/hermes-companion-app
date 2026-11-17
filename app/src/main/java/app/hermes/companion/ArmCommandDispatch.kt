package app.hermes.companion

import app.hermes.companion.model.DeviceCommand
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** ARM and file work may wait independently so DISARM can always interrupt them. */
internal suspend fun dispatchArmCommands(
    commands: Flow<DeviceCommand>,
    armBusy: suspend (DeviceCommand) -> Unit,
    filesBusy: suspend (DeviceCommand) -> Unit = armBusy,
    handle: suspend (DeviceCommand) -> Unit,
) = coroutineScope {
    var armReply: Job? = null
    var fileReply: Job? = null
    commands.collect { command ->
        if (command.action == "device.arm") {
            if (armReply?.isActive == true) armBusy(command)
            else armReply = launch(start = CoroutineStart.UNDISPATCHED) { handle(command) }
        } else if (command.action == "device.files") {
            if (fileReply?.isActive == true) filesBusy(command)
            else fileReply = launch(start = CoroutineStart.UNDISPATCHED) { handle(command) }
        } else {
            handle(command)
        }
    }
}
