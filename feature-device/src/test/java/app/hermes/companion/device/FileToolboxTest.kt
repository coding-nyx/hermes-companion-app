package app.hermes.companion.device

import android.app.Application
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FileToolboxTest {
    @get:Rule val temp = TemporaryFolder()
    private var armed = true
    private var granted = true
    private val protected = setOf("com.protected.*")
    private fun toolbox() = FileToolbox(RuntimeEnvironment.getApplication(), { armed }, { protected },
        roots = { listOf(temp.root) }, granted = { granted })
    private fun call(vararg args: Pair<String, Any>) = toolbox().execute(JSONObject(mapOf(*args)).toString())
    private fun fails(code: String, block: () -> Unit) {
        try { block(); fail("expected $code") }
        catch (t: Exception) { assertEquals(t.message, code, FileToolbox.errorCode(t)) }
    }

    @Test fun contentsAlwaysNeedBothConsentAndCurrentGrant() {
        temp.newFile("private.txt").writeText("private")
        armed = false
        assertTrue(toolbox().access().getBoolean("granted"))
        fails("disarmed") { call("action" to "read", "path" to "private.txt") }
        armed = true
        granted = false
        fails("files_access_denied") { call("action" to "list", "path" to "") }
        fails("files_access_denied") { call("action" to "write", "path" to "new", "content" to "x") }
        assertFalse(File(temp.root, "new").exists())
    }

    @Test fun writeIsNoClobberUnlessExplicitAndSupportsUnicodeAndBinary() {
        call("action" to "write", "path" to "note.txt", "content" to "hello नमस्ते\n")
        fails("already_exists") { call("action" to "write", "path" to "note.txt", "content" to "lost") }
        assertEquals("hello नमस्ते\n", File(temp.root, "note.txt").readText())
        call("action" to "write", "path" to "note.txt", "data_base64" to "/wABAg==", "overwrite" to true)
        assertEquals("/wABAg==", call("action" to "read", "path" to "note.txt", "encoding" to "base64").getString("data_base64"))
        fails("invalid_utf8") { call("action" to "read", "path" to "note.txt") }
        assertEquals(listOf("note.txt"), temp.root.listFiles()!!.map { it.name })
    }

    @Test fun appendChecksOffsetAndReadVersionDetectsChanges() {
        call("action" to "write", "path" to "chunk", "content" to "abc")
        val before = call("action" to "stat", "path" to "chunk").getString("version")
        call("action" to "append", "path" to "chunk", "content" to "def", "offset" to 3)
        fails("file_changed") { call("action" to "append", "path" to "chunk", "content" to "repeat", "offset" to 3) }
        fails("file_changed") { call("action" to "read", "path" to "chunk", "version" to before) }
        val read = call("action" to "read", "path" to "chunk", "offset" to 2, "max_bytes" to 2)
        assertEquals("cd", read.getString("content"))
        assertFalse(read.getBoolean("eof"))
        assertEquals("abcdef", File(temp.root, "chunk").readText())
    }

    @Test fun paginatedListsAreStableAndTraversalAndSymlinksCannotEscape() {
        temp.newFolder("folder")
        temp.newFile("b")
        temp.newFile("a")
        val page = call("action" to "list", "path" to "", "limit" to 2)
        assertEquals("folder", page.getJSONArray("items").getJSONObject(0).getString("name"))
        assertEquals(2, page.getInt("next_offset"))
        assertEquals("b", call("action" to "list", "path" to "", "offset" to 2).getJSONArray("items").getJSONObject(0).getString("name"))
        fails("path_denied") { call("action" to "stat", "path" to "../outside") }
        Files.createSymbolicLink(File(temp.root, "escape").toPath(), temp.root.parentFile.toPath())
        fails("path_denied") { call("action" to "write", "path" to "escape/outside", "content" to "x") }
        fails("path_denied") { call("action" to "delete", "path" to "", "recursive" to true) }
    }

    @Test fun protectedDescendantStopsRecursiveChangesBeforeAnyMutation() {
        val tree = temp.newFolder("Android", "media")
        File(tree, "ordinary.txt").writeText("keep")
        val protectedDir = File(tree, "com.protected.secret").apply { mkdir() }
        File(protectedDir, "secret").writeText("keep too")
        fails("protected_package") { call("action" to "read", "path" to "Android/media/com.protected.secret/secret") }
        fails("protected_package") { call("action" to "delete", "path" to "Android/media", "recursive" to true) }
        fails("protected_package") { call("action" to "copy", "path" to "Android/media", "destination" to "copied", "recursive" to true) }
        assertEquals("keep", File(tree, "ordinary.txt").readText())
        assertFalse(File(temp.root, "copied").exists())
    }

    @Test fun recursiveCopyMoveDeleteAndHashWorkForMultichunkBinaryFiles() {
        val source = temp.newFolder("source")
        val bytes = ByteArray(FileToolbox.MAX_CHUNK * 2 + 3) { (it % 251).toByte() }
        File(source, "binary").writeBytes(bytes)
        fails("recursive_required") { call("action" to "copy", "path" to "source", "destination" to "copy") }
        call("action" to "copy", "path" to "source", "destination" to "copy", "recursive" to true)
        assertArrayEquals(bytes, File(temp.root, "copy/binary").readBytes())
        assertEquals(call("action" to "hash", "path" to "source/binary").getString("sha256"),
            call("action" to "hash", "path" to "copy/binary").getString("sha256"))
        call("action" to "move", "path" to "copy", "destination" to "moved", "recursive" to true)
        assertFalse(File(temp.root, "copy").exists())
        fails("directory_not_empty") { call("action" to "delete", "path" to "moved") }
        call("action" to "delete", "path" to "moved", "recursive" to true)
        assertFalse(File(temp.root, "moved").exists())
        assertArrayEquals(bytes, File(source, "binary").readBytes())
    }

    @Test fun oversizedOrMalformedWritesLeaveExistingFilesIntact() {
        temp.newFile("kept").writeText("original")
        fails("chunk_too_large") { call("action" to "write", "path" to "kept", "content" to "x".repeat(FileToolbox.MAX_CHUNK + 1), "overwrite" to true) }
        fails("invalid_args") { call("action" to "write", "path" to "kept", "data_base64" to "!", "overwrite" to true) }
        assertEquals("original", File(temp.root, "kept").readText())
    }

    @Test fun disarmDuringStreamingCopyStopsBeforePublishingAndCleansStage() {
        temp.newFile("large").writeBytes(ByteArray(1024 * 1024))
        var checks = 0
        val files = FileToolbox(RuntimeEnvironment.getApplication(), { ++checks < 6 }, { emptySet() },
            roots = { listOf(temp.root) }, granted = { true })
        fails("disarmed") { files.execute("""{"action":"copy","path":"large","destination":"copy"}""") }
        assertEquals(listOf("large"), temp.root.listFiles()!!.map { it.name })
    }
}
