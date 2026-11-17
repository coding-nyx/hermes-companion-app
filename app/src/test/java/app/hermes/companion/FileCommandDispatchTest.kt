package app.hermes.companion

import app.hermes.companion.model.DeviceCommand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileCommandDispatchTest {
    @Test fun disarmBypassesLongFileWorkAndOtherFileCommandsGetBusy() = runTest {
        val commands = Channel<DeviceCommand>(Channel.UNLIMITED)
        val finish = Channel<Unit>()
        val events = mutableListOf<String>()
        backgroundScope.launch {
            dispatchArmCommands(commands.receiveAsFlow(), armBusy = { error("unexpected arm") },
                filesBusy = { events += "busy" }) { command ->
                if (command.action == "device.files") {
                    events += "file-start"
                    finish.receive()
                    events += "file-stop"
                } else events += command.action
            }
        }
        commands.send(DeviceCommand("file-1", "device.files"))
        runCurrent()
        commands.send(DeviceCommand("file-2", "device.files"))
        commands.send(DeviceCommand("stop", "device.disarm"))
        commands.send(DeviceCommand("off", "device.accessibility.disable"))
        runCurrent()
        assertEquals(listOf("file-start", "busy", "device.disarm", "device.accessibility.disable"), events)
        finish.send(Unit)
        runCurrent()
        assertEquals("file-stop", events.last())
    }
}
