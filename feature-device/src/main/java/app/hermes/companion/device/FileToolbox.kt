package app.hermes.companion.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.Base64
import app.hermes.companion.domain.DeviceLanePolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Direct shared-storage access. Every operation rechecks consent and the OS grant. */
class FileToolbox(
    private val context: Context,
    private val armed: () -> Boolean,
    private val protected: () -> Set<String>,
    private val roots: () -> List<File> = {
        (listOf(Environment.getExternalStorageDirectory()) +
            context.getSystemService(StorageManager::class.java).storageVolumes.mapNotNull { it.directory })
            .map { it.canonicalFile }.distinct()
    },
    private val granted: () -> Boolean = { Environment.isExternalStorageManager() },
) {
    class Failure(val code: String, message: String) : Exception(message)
    private val deadline = android.os.SystemClock.elapsedRealtime() + 110_000
    private val storageRoots by lazy { roots().map { it.canonicalFile } }

    fun access(): JSONObject = JSONObject().put("granted", granted())
        .put("roots", JSONArray(storageRoots.map { it.path }))
        .put("scope", "shared_storage; other apps' private data remains Android-protected")
        .put("max_chunk_bytes", MAX_CHUNK)

    fun enable(): JSONObject {
        if (!armed()) throw Failure("disarmed", "call mobile_arm first")
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return access().put("settings_opened", true)
    }

    private fun check() {
        DevicePrivacy.initialize(context)
        if (DevicePrivacy.active) throw Failure("privacy_active", "private entry pauses all remote file access")
        if (!armed()) throw Failure("disarmed", "Hands was disarmed; file operation stopped")
        if (!granted()) throw Failure("files_access_denied", "Allow Companion in Android All files access")
        if (android.os.SystemClock.elapsedRealtime() > deadline) throw Failure("file_timeout", "operation exceeded 110 seconds; use smaller subdirectories")
    }

    private fun allowed(file: File): File {
        val canonical = file.canonicalFile
        if (storageRoots.none { canonical.toPath().startsWith(it.toPath()) }) {
            throw Failure("path_denied", "path is outside mounted shared storage")
        }
        val parts = canonical.toPath().map { it.toString() }
        for (i in 0 until (parts.size - 2).coerceAtLeast(0)) {
            if (parts[i].equals("Android", true) && parts[i + 1] in setOf("data", "obb", "media") &&
                DeviceLanePolicy.isProtected(parts[i + 2], protected())) {
                throw Failure("protected_package", "file belongs to a protected app")
            }
        }
        return canonical
    }

    private fun path(raw: String): File {
        if (raw.indexOf('\u0000') >= 0) throw Failure("invalid_args", "NUL in path")
        val file = File(raw)
        return allowed(if (file.isAbsolute) file else File(storageRoots.firstOrNull()
            ?: throw Failure("storage_unavailable", "no mounted shared storage"), raw))
    }

    private fun mutable(file: File) {
        if (storageRoots.any { it == file }) throw Failure("path_denied", "cannot replace a storage root")
    }

    private fun version(file: File) = "${file.length()}:${file.lastModified()}"

    private fun info(file: File): JSONObject {
        if (!file.exists()) throw Failure("not_found", "file not found")
        return JSONObject().put("path", file.path).put("name", file.name)
            .put("directory", file.isDirectory).put("size", file.length())
            .put("modified_ms", file.lastModified()).put("version", version(file))
            .put("readable", file.canRead()).put("writable", file.canWrite())
    }

    private fun bytes(args: JSONObject): ByteArray {
        if (args.has("content") == args.has("data_base64")) {
            throw Failure("invalid_args", "supply exactly one of content or data_base64")
        }
        if (args.has("content") && args.getString("content").length > MAX_CHUNK) throw Failure("chunk_too_large", "use mobile_file_push")
        val data = if (args.has("content")) args.getString("content").toByteArray(Charsets.UTF_8) else {
            val encoded = args.getString("data_base64")
            if (encoded.length > (MAX_CHUNK + 2) / 3 * 4) throw Failure("chunk_too_large", "use chunked transfer")
            try { java.util.Base64.getDecoder().decode(encoded) }
            catch (_: IllegalArgumentException) { throw Failure("invalid_args", "invalid base64") }
        }
        if (data.size > MAX_CHUNK) throw Failure("chunk_too_large", "use mobile_file_push for larger files")
        return data
    }

    private fun fileOnly(file: File) {
        if (!file.isFile) throw Failure(if (file.exists()) "not_a_file" else "not_found", "expected a regular file")
    }

    private fun commit(stage: File, target: File, overwrite: Boolean) {
        check()
        allowed(target)
        if (overwrite) Files.move(stage.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        else Files.move(stage.toPath(), target.toPath())
    }

    private fun walk(file: File, recursive: Boolean): List<File> {
        if (!file.exists()) throw Failure("not_found", "file not found")
        if (!file.isDirectory) return listOf(file)
        if (!recursive) throw Failure("recursive_required", "directory operation requires recursive=true")
        val found = ArrayList<File>()
        Files.walk(file.toPath()).use { paths ->
            val iterator = paths.iterator()
            while (iterator.hasNext()) {
                check()
                val entry = iterator.next()
                if (Files.isSymbolicLink(entry)) throw Failure("path_denied", "recursive operations do not follow symlinks")
                found += allowed(entry.toFile())
                if (found.size > 10_000) throw Failure("operation_too_large", "operate on smaller subdirectories")
            }
        }
        return found
    }

    private fun copyBytes(source: File, target: File) {
        source.inputStream().use { input -> target.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                allowed(source)
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
            }
        } }
    }

    fun execute(raw: String): JSONObject {
        check()
        val args = JSONObject(raw)
        val action = args.getString("action")
        val file = path(args.optString("path", ""))
        val overwrite = args.optBoolean("overwrite", false)
        return when (action) {
            "stat" -> info(file)
            "list" -> {
                if (!file.isDirectory) throw Failure("not_a_directory", "expected a directory")
                val offset = args.optInt("offset", 0)
                val limit = args.optInt("limit", 100).coerceIn(1, 500)
                if (offset < 0) throw Failure("invalid_args", "negative offset")
                val entries = (file.listFiles() ?: throw Failure("files_access_denied", "cannot list this directory"))
                    .filter { check(); runCatching { allowed(it) }.isSuccess }
                    .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                val page = entries.drop(offset).take(limit)
                check()
                JSONObject().put("path", file.path).put("items", JSONArray(page.map { info(it) }))
                    .put("total", entries.size).put("next_offset", if (offset.toLong() + page.size < entries.size) offset + page.size else JSONObject.NULL)
            }
            "read" -> {
                fileOnly(file)
                val before = version(file)
                if (args.has("version") && args.getString("version") != before) throw Failure("file_changed", "file changed during transfer")
                val offset = args.optLong("offset", 0)
                if (offset < 0 || offset > file.length()) throw Failure("invalid_args", "offset outside file")
                val buffer = ByteArray(args.optInt("max_bytes", MAX_CHUNK).coerceIn(1, MAX_CHUNK))
                val data = RandomAccessFile(file, "r").use { input ->
                    input.seek(offset)
                    buffer.copyOf(input.read(buffer).coerceAtLeast(0))
                }
                check()
                if (version(file) != before) throw Failure("file_changed", "file changed during read")
                val result = JSONObject().put("path", file.path).put("offset", offset).put("bytes", data.size)
                    .put("size", file.length()).put("version", before).put("eof", offset + data.size >= file.length())
                when (args.optString("encoding", "utf8")) {
                    "base64" -> result.put("data_base64", Base64.encodeToString(data, Base64.NO_WRAP))
                    "utf8" -> {
                        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(data)).toString() }
                        catch (_: java.nio.charset.CharacterCodingException) {
                            throw Failure("invalid_utf8", "use encoding=base64 for binary or split UTF-8 data")
                        }
                        result.put("content", text)
                    }
                    else -> throw Failure("invalid_args", "encoding must be utf8 or base64")
                }
            }
            "hash" -> {
                fileOnly(file)
                val before = version(file)
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check()
                        allowed(file)
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                if (version(file) != before) throw Failure("file_changed", "file changed during hashing")
                info(file).put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
            }
            "write" -> {
                mutable(file)
                val data = bytes(args)
                val stage = File.createTempFile(".hermes-write-", ".part", file.parentFile)
                try {
                    stage.outputStream().use { it.write(data) }
                    commit(stage, file, overwrite)
                } finally { stage.delete() }
                info(file)
            }
            "append" -> {
                mutable(file)
                fileOnly(file)
                val data = bytes(args)
                val offset = args.getLong("offset")
                RandomAccessFile(file, "rw").use { output ->
                    if (offset < 0 || output.length() != offset) throw Failure("file_changed", "append offset must equal file size")
                    check()
                    output.seek(offset)
                    try { output.write(data) } catch (t: IOException) { output.setLength(offset); throw t }
                }
                info(file)
            }
            "mkdir" -> {
                mutable(file)
                if (file.exists()) throw Failure("already_exists", "path exists")
                if (args.optBoolean("parents", false)) Files.createDirectories(file.toPath()) else Files.createDirectory(file.toPath())
                info(file)
            }
            "move", "copy" -> {
                mutable(file)
                val target = path(args.getString("destination"))
                mutable(target)
                if (target.toPath().startsWith(file.toPath()) || file.toPath().startsWith(target.toPath())) {
                    throw Failure("invalid_args", "source and destination must not contain each other")
                }
                val entries = walk(file, args.optBoolean("recursive", false))
                // Validate all destinations before changing anything (including protected app paths).
                entries.forEach { allowed(File(target, file.toPath().relativize(it.toPath()).toString())) }
                if (action == "move") {
                    commit(file, target, overwrite)
                } else {
                    val before = version(file)
                    val stage = if (file.isDirectory) Files.createTempDirectory(target.parentFile.toPath(), ".hermes-copy-").toFile()
                        else File.createTempFile(".hermes-copy-", ".part", target.parentFile)
                    try {
                        for (entry in entries) {
                            check()
                            allowed(entry)
                            val to = if (file.isDirectory) File(stage, file.toPath().relativize(entry.toPath()).toString()) else stage
                            if (entry.isDirectory) Files.createDirectories(to.toPath()) else copyBytes(entry, to)
                        }
                        if (version(file) != before) throw Failure("file_changed", "source changed during copy")
                        commit(stage, target, overwrite)
                    } finally { stage.deleteRecursively() }
                }
                info(target)
            }
            "delete" -> {
                mutable(file)
                if (file.isDirectory && !args.optBoolean("recursive", false)) {
                    Files.delete(file.toPath())
                } else {
                    val entries = walk(file, args.optBoolean("recursive", false))
                    entries.asReversed().forEach { check(); allowed(it); Files.delete(it.toPath()) }
                }
                JSONObject().put("deleted", true).put("path", file.path)
            }
            else -> throw Failure("invalid_args", "unknown file action")
        }
    }

    companion object {
        const val MAX_CHUNK = 192 * 1024
        fun errorCode(t: Throwable): String = when (t) {
            is Failure -> t.code
            is SecurityException, is java.nio.file.AccessDeniedException -> "files_access_denied"
            is java.nio.file.FileAlreadyExistsException -> "already_exists"
            is java.nio.file.NoSuchFileException -> "not_found"
            is java.nio.file.DirectoryNotEmptyException -> "directory_not_empty"
            is org.json.JSONException, is IllegalArgumentException -> "invalid_args"
            is java.io.FileNotFoundException -> if (t.message.orEmpty().contains("Permission denied")) "files_access_denied" else "not_found"
            else -> "file_io_error"
        }
    }
}
