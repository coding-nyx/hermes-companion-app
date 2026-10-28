package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.companion.backend.*
import app.hermes.companion.backend.*
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.ChatMessage
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.*
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[31],application=android.app.Application::class,qualifiers="w360dp-h720dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionPrototypeRenderTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val c=BackendCoordinator(scope,PrototypeTestStore(),BackendFactory {_,_,_->error("Render test must not dial a host")})
    @After fun close() {c.close();scope.cancel()}
    private fun mount() {
        c.show()
        compose.setContent { val state by c.state.collectAsState();CompanionTheme {BackendWorkspaceScreen(state,c,{}, {})} }
    }
    private fun capture(name:String) {
        compose.waitForIdle();compose.runOnIdle {
            val global=Class.forName("android.view.WindowManagerGlobal").getDeclaredMethod("getInstance").invoke(null)
            val field=global.javaClass.getDeclaredField("mViews").apply {isAccessible=true}
            @Suppress("UNCHECKED_CAST") val roots=field.get(global) as List<android.view.View>
            val view=compose.activity.window.decorView;val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
            roots.forEach {root->val pos=IntArray(2);root.getLocationOnScreen(pos);canvas.save();canvas.translate(pos[0].toFloat(),pos[1].toFloat());root.draw(canvas);canvas.restore()}
            File("/tmp/companion-prototype-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    private fun exercise(name:String,large:Boolean=false) {
        if(large) org.robolectric.RuntimeEnvironment.setFontScale(2f)
        mount();capture("$name-setup-hermes")
        compose.onNodeWithText("Sign in with a password").performScrollTo().performClick()
        compose.onNodeWithTag("backend.username").performScrollTo().assertHeightIsAtLeast(48.dp);capture("$name-hermes-sign-in")
        compose.onNodeWithText("Hide sign-in fields").performScrollTo().performClick()
        compose.onNodeWithTag("backend.kind.OPENCLAW").performScrollTo().performClick();capture("$name-setup-openclaw")
        compose.onNodeWithTag("backend.token").performScrollTo().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Use a gateway password").performScrollTo().performClick()
        compose.onNodeWithTag("backend.password").performScrollTo().assertHeightIsAtLeast(48.dp);capture("$name-openclaw-password")
        compose.onNodeWithText("Use a gateway token").performScrollTo().performClick()
        compose.onNodeWithTag("backend.kind.CODYNC").performScrollTo().performClick();capture("$name-setup-codync")
        compose.onNodeWithTag("backend.sample").performScrollTo().performClick()
        compose.waitUntil(5000) {c.state.value.bots.size==6}
        compose.onNodeWithTag("navigation.bar").assertIsDisplayed();capture("$name-bots")
        compose.onNodeWithTag("backend.new").performClick();compose.onNodeWithTag("backend.new.group").performClick()
        compose.onNodeWithTag("backend.editor.create").assertIsNotEnabled()
        compose.onNodeWithTag("backend.pick.hermes:coder").performScrollTo().performClick()
        compose.onNodeWithTag("backend.pick.openclaw:research").performScrollTo().performClick()
        compose.onNodeWithTag("backend.editor.create").assertIsEnabled();capture("$name-group-form")
        compose.onNodeWithTag("backend.editor.create").performClick()
        compose.waitUntil(5000) {c.state.value.thread!=null}
        compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
        assertEquals("Coder, Research",c.state.value.thread!!.title)
        compose.onNodeWithTag("chat.draft").assertHeightIsAtLeast(48.dp);capture("$name-group-chat")
        compose.onNodeWithTag("backend.chat.back").performClick()
        compose.onNodeWithTag("backend.bot.hermes:coder").performScrollTo().performClick()
        compose.waitUntil(5000) {c.state.value.thread?.botId=="hermes:coder"}
        compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
        compose.onNodeWithTag("chat.draft").performClick().performTextInput("A complete\nmultiline draft")
        listOf("chat.attach","chat.slash","chat.voice","chat.models").forEach {compose.onNodeWithTag(it).assertDoesNotExist()}
        capture("$name-chat-editing")
        compose.onNodeWithTag("backend.chat.back").performClick()
        compose.onNodeWithTag("backend.bot.hermes:coder").performScrollTo().performClick()
        compose.waitUntil(5000) {c.state.value.thread?.botId=="hermes:coder"}
        assertEquals("A complete\nmultiline draft",c.state.value.draft)
    }
    private fun botForm(name:String) {
        mount();compose.onNodeWithTag("backend.sample").performScrollTo().performClick()
        compose.waitUntil(5000) {c.state.value.bots.size==6}
        compose.onNodeWithTag("backend.new").performClick();compose.onNodeWithTag("backend.new.bot").performClick()
        compose.onNodeWithTag("backend.editor.create").assertIsNotEnabled()
        compose.onNodeWithTag("backend.editor.name").performScrollTo().performTextInput("Helper")
        compose.onNodeWithTag("backend.editor.create").assertIsEnabled();capture("$name-bot-form")
        compose.onNodeWithTag("backend.editor.create").performClick()
        compose.waitUntil(5000) {c.state.value.thread?.title=="Helper"}
        compose.onNodeWithTag("backend.connections").performClick()
        compose.onNodeWithText("Connected to Sample workspace").assertIsDisplayed();capture("$name-connections")
    }
    @Test fun existingHermesBotsMenuOpensTheSharedConnections() {
        var opened=false
        compose.setContent { CompanionTheme {
            CompanionShell(CompanionState(origin="https://fixture.invalid",hostName="Lab",tab=MainTab.BOTS),
                onOriginChange={},onConnect={},onSelectProfile={},onTab={},onOpenSession={},onNewThread={},onCloseChat={},
                onDraftChange={},onSend={},onInterrupt={},onApproval={},onBackendConnections={opened=true})
        } }
        compose.onNodeWithTag("bots.options").performClick();compose.onNodeWithTag("bots.connections").performClick();assertTrue(opened)
    }
    @Test fun darkBotFormAndConnections()=botForm("dark")
    @Test @Config(qualifiers="w360dp-h720dp-notnight-xhdpi") fun lightBotFormAndConnections()=botForm("light")
    @Test fun largeBotFormAndConnections() {org.robolectric.RuntimeEnvironment.setFontScale(2f);botForm("large")}
    @Test fun darkScreensAndOpenForms()=exercise("dark")
    @Test @Config(qualifiers="w360dp-h720dp-notnight-xhdpi") fun lightScreensAndOpenForms()=exercise("light")
    @Test fun largeTextReflowsThroughSetupRosterAndForms()=exercise("large",true)
}

class PrototypeTestStore:BackendStore {
    private val text=mutableMapOf<String,String>();private val chats=mutableMapOf<String,List<ChatMessage>>()
    override fun connections()=emptyList<AgentConnection>()
    override fun save(connection:AgentConnection) {}
    override fun forget(connection:AgentConnection) {}
    override fun credentials(connection:AgentConnection)=AgentCredentials(ByteArray(32))
    override fun credentials(connection:AgentConnection,credentials:AgentCredentials) {}
    override fun draft(owner:String,thread:String)=text["$owner/$thread/draft"].orEmpty()
    override fun draft(owner:String,thread:String,value:String) {text["$owner/$thread/draft"]=value}
    override fun pending(owner:String,thread:String)=text["$owner/$thread/pending"]
    override fun pending(owner:String,thread:String,operation:String?) {if(operation==null) text.remove("$owner/$thread/pending") else text["$owner/$thread/pending"]=operation}
    override fun history(owner:String,thread:String)=chats["$owner/$thread"].orEmpty()
    override fun history(owner:String,thread:String,messages:List<ChatMessage>) {chats["$owner/$thread"]=messages}
}
