package app.hermes.companion

import android.app.KeyguardManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.companion.backend.*
import app.hermes.companion.backend.*
import app.hermes.companion.design.CompanionTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

/** Exercises the real common screens and coordinator; the sample test never reaches a host. */
@RunWith(AndroidJUnit4::class)
class CompanionPrototypeDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var coordinator:BackendCoordinator?=null
    @After fun close() {coordinator?.close();scope.cancel()}
    private fun ready() {
        val i=InstrumentationRegistry.getInstrumentation()
        assertFalse("Unlock the S22 before UI validation",i.targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        i.runOnMainSync {compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    }
    private fun screenshot(name:String) {
        compose.waitForIdle();val i=InstrumentationRegistry.getInstrumentation()
        val bitmap=i.uiAutomation.takeScreenshot() ?: error("No device screenshot")
        val file=File(i.targetContext.filesDir,"prototype-device/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun sampleGroupComposerAndNavigationInDarkLightAndLargeText() {
        ready();val i=InstrumentationRegistry.getInstrumentation()
        val c=BackendCoordinator(scope,BackendVault(i.targetContext,"companion.prototype.device-test"),BackendFactory {_,_,_->error("Sample must not dial a host")});coordinator=c
        var variant by mutableStateOf("dark")
        i.runOnMainSync {compose.activity.setContent {
            val current=LocalConfiguration.current
            val config=Configuration(current).apply {uiMode=(uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if(variant=="light") Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES}
            val density=LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config,LocalDensity provides Density(density.density,if(variant=="large") 2f else 1f)) {
                val state by c.state.collectAsState();CompanionTheme {BackendWorkspaceScreen(state,c,{}, {})}
            }
        };c.show()}
        compose.onNodeWithTag("backend.kind.OPENCLAW").performScrollTo().performClick()
        compose.onNodeWithTag("backend.token").performScrollTo().assertHeightIsAtLeast(48.dp);screenshot("setup-openclaw")
        compose.onNodeWithTag("backend.sample").performScrollTo().performClick()
        compose.waitUntil(5000) {c.state.value.bots.size==6}
        for(mode in listOf("dark","light","large")) {
            i.runOnMainSync {variant=mode};compose.waitForIdle()
            compose.onNodeWithTag("navigation.bar").assertIsDisplayed();screenshot("$mode-bots")
            compose.onNodeWithTag("backend.new").performClick();compose.onNodeWithTag("backend.new.group").performClick()
            compose.onNodeWithTag("backend.pick.hermes:coder").performScrollTo().performClick()
            compose.onNodeWithTag("backend.pick.openclaw:research").performScrollTo().performClick()
            compose.onNodeWithTag("backend.editor.create").assertIsEnabled();screenshot("$mode-group-form")
            compose.onNodeWithTag("backend.editor.create").performClick()
            compose.waitUntil(5000) {c.state.value.thread!=null}
            assertEquals("Coder, Research",c.state.value.thread!!.title)
            compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
            compose.onNodeWithTag("chat.draft").performClick().performTextInput("Complete\nmultiline draft")
            compose.onNodeWithTag("chat.voice").assertDoesNotExist();compose.onNodeWithTag("chat.attach").assertDoesNotExist();screenshot("$mode-chat-editing")
            i.runOnMainSync {compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime())}
            compose.onNodeWithTag("backend.chat.back").performClick()
            i.runOnMainSync {c.openThread(c.state.value.bots.last {it.group}.let {AgentThread(it.id,it.id,it.name,true)})}
            compose.waitUntil(5000) {c.state.value.thread!=null}
            assertEquals("Complete\nmultiline draft",c.state.value.draft)
            i.runOnMainSync {c.back()};compose.waitForIdle()
        }
    }
    @Test fun liveOpenClawRosterAndHistoryOverAnExplicitAdbTunnel() {
        ready();val i=InstrumentationRegistry.getInstrumentation()
        val secret=File(i.targetContext.filesDir,"prototype-openclaw.json")
        assumeTrue("Provide the local smoke-test credential privately before running this test",secret.exists())
        try {
            val input=Json.parseToJsonElement(secret.readText()).jsonObject
            fun value(key:String)=input[key]?.jsonPrimitive?.content.orEmpty()
            val connection=AgentConnection.create(AgentRuntime.OPENCLAW,"http://127.0.0.1:18789","OpenClaw · This Mac (ADB)")
            val vault=BackendVault(i.targetContext)
            vault.credentials(connection,AgentCredentials(Base64.getDecoder().decode(value("seed")),value("token"),value("password")))
            val c=BackendCoordinator(scope,vault,BackendFactory {conn,credentials,_->OpenClawBackend(conn,credentials)});coordinator=c
            i.runOnMainSync {compose.activity.setContent {val state by c.state.collectAsState();CompanionTheme {BackendWorkspaceScreen(state,c,{}, {})}};c.openConnection(connection)}
            compose.waitUntil(30_000) {c.state.value.connection!=null || c.state.value.error!=null}
            assertNull(c.state.value.error);assertTrue(c.state.value.bots.isNotEmpty())
            compose.onNodeWithTag("backend.roster").assertIsDisplayed();screenshot("live-openclaw-bots")
            val bot=c.state.value.bots.first()
            i.runOnMainSync {c.openThread(AgentThread("agent:${bot.id}:companion-prototype-validation",bot.id,bot.name))}
            compose.waitUntil(10_000) {c.state.value.thread!=null && !c.state.value.loadingHistory}
            compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
            assertNull(c.state.value.error)
        } finally {secret.delete()}
    }
}
