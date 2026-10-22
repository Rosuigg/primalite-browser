package app.primalite.primalite

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Host side of the `app.primalite.browser/native` channel.
 *
 * * `pickTextFile` opens the Storage Access Framework picker for bookmark
 *   HTML/JSON exports.
 * * `saveImage` / `saveTextFile` write into the public gallery and Downloads
 *   folder through MediaStore (API 29+) or an app-scoped folder plus a media
 *   scan (API 24–28). No storage permission is required on either path.
 * * `openFile` and `shareText` back the Downloads page and bookmark sharing.
 */
class MainActivity : FlutterActivity() {
    companion object {
        private const val CHANNEL = "app.primalite.browser/native"
        private const val REQUEST_PICK_DOCUMENT = 2001
        private const val MAX_PICK_BYTES = 32L * 1024 * 1024
        private const val IMAGE_FOLDER = "Primalite"
        private const val DOWNLOAD_FOLDER = "Primalite"
    }

    private var pendingPick: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler { call, result -> handleCall(call, result) }
    }

    private fun handleCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "pickTextFile" -> pickTextFile(result)
            "saveTextFile" -> {
                val name = call.argument<String>("fileName") ?: "primalite.txt"
                val mime = call.argument<String>("mimeType") ?: "text/plain"
                val content = call.argument<String>("content") ?: ""
                runCatching { saveTextFile(name, mime, content) }
                    .onSuccess { result.success(it) }
                    .onFailure { result.error("save_failed", it.message, null) }
            }
            "saveImage" -> {
                val bytes = call.argument<ByteArray>("bytes")
                if (bytes == null) {
                    result.error("bad_args", "Missing image bytes", null)
                    return
                }
                val name = call.argument<String>("fileName") ?: "image.jpg"
                val mime = call.argument<String>("mimeType") ?: "image/jpeg"
                runCatching { saveImage(name, mime, bytes) }
                    .onSuccess { result.success(it) }
                    .onFailure { result.error("save_failed", it.message, null) }
            }
            "openFile" -> result.success(openFile(call.argument<String>("uri") ?: ""))
            "shareText" -> result.success(
                shareText(
                    call.argument<String>("text") ?: "",
                    call.argument<String>("subject") ?: ""
                )
            )
            else -> result.notImplemented()
        }
    }

    // ---------------------------------------------------------------------
    // File picker
    // ---------------------------------------------------------------------

    private fun pickTextFile(result: MethodChannel.Result) {
        if (pendingPick != null) {
            result.error("busy", "A file picker is already open", null)
            return
        }
        pendingPick = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "text/html",
                    "application/json",
                    "text/plain",
                    "application/octet-stream"
                )
            )
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_DOCUMENT)
        } catch (error: Exception) {
            pendingPick = null
            result.error("picker_failed", error.message, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_PICK_DOCUMENT) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val result = pendingPick
        pendingPick = null
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            result.success(null)
            return
        }
        try {
            val name = queryDisplayName(uri)
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0L
                while (true) {
                    val read = input.read(chunk)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_PICK_BYTES) {
                        throw IllegalStateException("The bookmarks file is too large")
                    }
                    output.write(chunk, 0, read)
                }
                output.toByteArray()
            } ?: throw IllegalStateException("Could not open that file")
            result.success(mapOf("name" to name, "content" to String(bytes, Charsets.UTF_8)))
        } catch (error: Exception) {
            result.error("read_failed", error.message, null)
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        var name = "bookmarks.html"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            name = cursor.getString(index) ?: name
                        }
                    }
                }
        } catch (_: Exception) {
            // Fall back to the default name.
        }
        return name
    }

    // ---------------------------------------------------------------------
    // Saving
    // ---------------------------------------------------------------------

    private fun saveImage(fileName: String, mimeType: String, bytes: ByteArray): Map<String, Any?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + IMAGE_FOLDER
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the image in the gallery")
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: throw IllegalStateException("Could not write the image")
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return mapOf(
                "name" to queryDisplayName(uri),
                "uri" to uri.toString(),
                "path" to "Pictures/$IMAGE_FOLDER/${queryDisplayName(uri)}",
                "localPath" to ""
            )
        }

        val directory = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir,
            IMAGE_FOLDER
        )
        if (!directory.exists()) directory.mkdirs()
        val file = uniqueFile(directory, fileName)
        file.writeBytes(bytes)
        MediaScannerConnection.scanFile(
            this,
            arrayOf(file.absolutePath),
            arrayOf(mimeType),
            null
        )
        return mapOf(
            "name" to file.name,
            "uri" to Uri.fromFile(file).toString(),
            "path" to file.absolutePath,
            "localPath" to file.absolutePath
        )
    }

    private fun saveTextFile(fileName: String, mimeType: String, content: String): Map<String, Any?> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_FOLDER
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the file in Downloads")
            try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(content.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Could not write the file")
            } catch (error: Exception) {
                contentResolver.delete(uri, null, null)
                throw error
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return mapOf(
                "name" to queryDisplayName(uri),
                "uri" to uri.toString(),
                "path" to "Download/$DOWNLOAD_FOLDER/${queryDisplayName(uri)}",
                "localPath" to ""
            )
        }

        val directory = File(
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir,
            DOWNLOAD_FOLDER
        )
        if (!directory.exists()) directory.mkdirs()
        val file = uniqueFile(directory, fileName)
        file.writeText(content, Charsets.UTF_8)
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mimeType), null)
        return mapOf(
            "name" to file.name,
            "uri" to Uri.fromFile(file).toString(),
            "path" to file.absolutePath,
            "localPath" to file.absolutePath
        )
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        val initial = File(directory, fileName)
        if (!initial.exists()) return initial
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        for (index in 1 until 1000) {
            val candidate = File(directory, "$base ($index)$extension")
            if (!candidate.exists()) return candidate
        }
        return File(directory, "$base-${System.currentTimeMillis()}$extension")
    }

    // ---------------------------------------------------------------------
    // Opening / sharing
    // ---------------------------------------------------------------------

    private fun openFile(uriString: String): Boolean {
        if (uriString.isEmpty()) return false
        return try {
            val uri = Uri.parse(uriString)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun shareText(text: String, subject: String): Boolean {
        if (text.isEmpty()) return false
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_TEXT, text)
                if (subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            startActivity(
                Intent.createChooser(intent, if (subject.isEmpty()) "Share" else subject)
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}
