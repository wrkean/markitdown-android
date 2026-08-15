package com.markitdown.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.chaquo.python.Python
import com.markitdown.android.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks a document (Storage Access Framework) and converts it to Markdown
 * entirely on-device via the bundled MarkItDown + Chaquopy.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var lastMarkdown: String? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) handleFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pickButton.setOnClickListener {
            pickFile.launch(SUPPORTED_MIME_TYPES)
        }
        binding.shareButton.setOnClickListener { shareOutput() }
        updateShareButton()
    }

    private fun handleFile(uri: Uri) {
        val name = queryDisplayName(uri) ?: "document"
        val ext = name.substringAfterLast('.', "").lowercase()

        // Belt and braces: some file providers ignore the MIME-type filter.
        if (ext !in SUPPORTED_EXTENSIONS) {
            binding.status.text =
                getString(R.string.status_unsupported, ext.ifEmpty { "<none>" })
            return
        }

        binding.progress.visibility = View.VISIBLE
        binding.status.text = getString(R.string.status_converting)
        binding.pickButton.isEnabled = false
        binding.shareButton.isEnabled = false
        binding.output.text = ""

        // Python runs fine off the main thread; do the conversion on IO.
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                // Reject oversized files before streaming them into memory; the
                // provider-reported size is authoritative where it is present.
                val size = queryFileSize(uri)
                if (size != null && size > MAX_FILE_SIZE_BYTES) {
                    tooLargeError()
                }

                val bytes = try {
                    readFileWithProgress(uri)
                } catch (e: Exception) {
                    val fromRecents = isRecentsOpenFailure(e)
                    val hint = if (fromRecents) getString(R.string.error_open_hint) else ""
                    throw FileOpenException(
                        "Failed to open $uri: ${e.message ?: e}. $hint", e, fromRecents
                    )
                }

                // Belt and braces: the reported size can be missing or wrong,
                // so re-check what was actually read into memory.
                if (bytes.size > MAX_FILE_SIZE_BYTES) {
                    tooLargeError()
                }

                // If the activity was destroyed mid-read, bail before running
                // the expensive conversion.
                ensureActive()

                Python.getInstance()
                    .getModule("markitdown_android")
                    .callAttr("convert_bytes", bytes, name)
                    .toString()
            }.onFailure { if (it is CancellationException) throw it }

            withContext(Dispatchers.Main) {
                binding.progress.visibility = View.GONE
                binding.pickButton.isEnabled = true
                result
                    .onSuccess { markdown ->
                        lastMarkdown = markdown
                        binding.status.text = getString(R.string.status_converted, name)
                        binding.output.text = markdown
                        updateShareButton()
                    }
                    .onFailure { e ->
                        lastMarkdown = null
                        if (e is FileOpenException && e.fromRecents) {
                            binding.status.text = getString(R.string.recents_status)
                            binding.output.text = ""
                            showRecentsDialog()
                        } else {
                            // Our own IllegalStateException wrappers carry short,
                            // user-friendly messages (open failures, size limits).
                            // PyException carries the full traceback; its last
                            // non-blank line is the actual error summary, so use
                            // that for the status bar. The full traceback is
                            // shown below in the output pane.
                            val shortMessage = if (e is IllegalStateException) {
                                e.message ?: e.toString()
                            } else {
                                val rootCause = generateSequence(e) { it.cause }.last()
                                val rootMessage = rootCause.message ?: rootCause.toString()
                                rootMessage.lineSequence().lastOrNull { it.isNotBlank() }
                                    ?: rootMessage
                            }
                            binding.status.text = getString(R.string.status_error, shortMessage)
                            val detail = buildString {
                                appendLine(e.toString())
                                var cause = e.cause
                                while (cause != null) {
                                    appendLine("Caused by: $cause")
                                    cause = cause.cause
                                }
                            }
                            binding.output.text = detail
                        }
                        updateShareButton()
                    }
            }
        }
    }

    /** Throws the user-facing "file too large" error. */
    private fun tooLargeError(): Nothing =
        error(getString(R.string.status_too_large, MAX_FILE_SIZE_BYTES / 1024 / 1024))

    private fun openSelectedFile(uri: Uri): InputStream {
        return try {
            contentResolver.openInputStream(uri)
                ?: throw FileNotFoundException("Could not open file (null stream) – $uri")
        } catch (e: FileNotFoundException) {
            openMediaDocumentFallback(uri) ?: throw e
        }
    }

    /**
     * Reads the whole file into memory, driving the determinate progress bar
     * while it streams in. Falls back to an indeterminate bar when the file
     * size can't be queried (e.g. a broken Recents URI).
     */
    private fun readFileWithProgress(uri: Uri): ByteArray {
        val size = queryFileSize(uri)?.takeIf { it > 0 }
        if (size != null) {
            runOnUiThread {
                binding.progress.isIndeterminate = false
                binding.progress.progress = 0
            }
        }
        val buffer = ByteArray(READ_CHUNK_SIZE)
        val output = ByteArrayOutputStream()
        var readTotal = 0L
        var lastPosted = -1
        openSelectedFile(uri).use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                if (size != null) {
                    readTotal += n
                    val percent = ((readTotal * 100) / size).toInt()
                    if (percent != lastPosted) {
                        lastPosted = percent
                        val p = percent
                        runOnUiThread { binding.progress.progress = p }
                    }
                }
            }
        }
        if (size != null) {
            runOnUiThread { binding.progress.isIndeterminate = true }
        }
        return output.toByteArray()
    }

    private fun queryFileSize(uri: Uri): Long? {
        return runCatching {
            contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            }
        }.getOrNull()
    }

    /**
     * Some Android versions return broken media DocumentsProvider URIs from the
     * system picker's Recents tab, for example:
     *   content://com.android.providers.media.documents/document/document:1001468564
     * Those can fail with ENOENT even when the file exists. The numeric suffix
     * is the real MediaStore row ID, so retry via MediaStore.
     */
    private fun openMediaDocumentFallback(uri: Uri): InputStream? {
        if (uri.authority != "com.android.providers.media.documents") return null

        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: uri.lastPathSegment
            ?: return null
        val mediaId = docId.substringAfterLast(':').toLongOrNull() ?: return null

        val candidates = buildList {
            val lowerName = (queryDisplayName(uri) ?: "").lowercase(Locale.US)
            when {
                lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
                    || lowerName.endsWith(".png") || lowerName.endsWith(".webp") -> {
                    add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                }
                lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v") -> {
                    add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                }
                lowerName.endsWith(".mp3") || lowerName.endsWith(".wav")
                    || lowerName.endsWith(".m4a") -> {
                    add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
                }
            }
            add(MediaStore.Files.getContentUri("external"))
            add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        }

        for (baseUri in candidates.distinct()) {
            val mediaUri = Uri.withAppendedPath(baseUri, mediaId.toString())
            try {
                return contentResolver.openInputStream(mediaUri)
            } catch (_: FileNotFoundException) {
                // Try the next MediaStore collection.
            } catch (_: SecurityException) {
                // The picked URI may not grant access to this fallback collection.
            }
        }
        return null
    }

    private fun shareOutput() {
        val markdown = lastMarkdown ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, markdown)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }

    private fun updateShareButton() {
        binding.shareButton.isEnabled = !lastMarkdown.isNullOrBlank()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    /**
     * True when a file-open failure looks like the classic "picked from
     * Recents" symptom: the URI is stale/broken and the underlying provider
     * reports ENOENT even though the file may still exist elsewhere.
     */
    private fun isRecentsOpenFailure(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            val message = cause.message ?: ""
            if (message.contains("ENOENT") || message.contains("No such file")) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun showRecentsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.recents_dialog_title)
            .setMessage(R.string.recents_dialog_message)
            .setPositiveButton(R.string.recents_dialog_retry) { _, _ ->
                pickFile.launch(SUPPORTED_MIME_TYPES)
            }
            .setNegativeButton(R.string.recents_dialog_cancel, null)
            .show()
    }

    /** A file could not be opened; [fromRecents] marks the stale-Recents case. */
    private class FileOpenException(
        message: String,
        cause: Throwable,
        val fromRecents: Boolean,
    ) : IllegalStateException(message, cause)

    companion object {
        private const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024  // 50 MB
        private const val READ_CHUNK_SIZE = 64 * 1024              // 64 KB

        /**
         * Formats MarkItDown can convert fully offline. This list drives both
         * the file-picker filter and the post-pick validation.
         */
        val SUPPORTED_EXTENSIONS = setOf(
            "pdf",
            "docx", "xlsx", "pptx",
            "epub", "zip",
            "html", "htm",
            "txt", "text", "md", "markdown",
            "csv",
            "json", "jsonl",
            "xml", "rss", "atom",
            "msg", "ipynb",
        )

        val SUPPORTED_MIME_TYPES = arrayOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/epub+zip",
            "application/zip",
            "text/html", "application/xhtml+xml",
            "text/plain",
            "text/csv", "application/csv",
            "application/json",
            "text/xml", "application/xml",
            "application/vnd.ms-outlook",
            "application/x-ipynb+json",
        )
    }
}
