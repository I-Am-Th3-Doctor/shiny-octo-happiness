package io.github.iamth3doctor.searchlight.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.contacts.ContactEntry
import io.github.iamth3doctor.searchlight.data.WebEngine
import java.io.File

/** Intents for everything that isn't launching an app. */
object Actions {

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun openFile(context: Context, path: String) {
        val file = File(path)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), mimeOf(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(context, intent)) openWith(context, path)
    }

    fun openWith(context: Context, path: String) {
        val file = File(path)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, file), "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun share(context: Context, path: String) {
        val file = File(path)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeOf(file.name))
            .putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Opens a folder (relative to shared storage) in the system Files app. */
    fun openFolder(context: Context, relativeDir: String) {
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relativeDir")
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!start(context, intent, toastOnFail = false)) {
            copy(context, "/$relativeDir")
            Toast.makeText(context, R.string.folder_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Songs, photos and videos from MediaStore: open in whatever app handles the type. */
    fun openMedia(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        start(context, intent)
    }

    fun shareMedia(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openContact(context: Context, c: ContactEntry) =
        start(context, Intent(Intent.ACTION_VIEW, c.lookupUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Opens the dialer with the number filled in; no CALL_PHONE permission needed. */
    fun dial(context: Context, number: String) =
        start(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun message(context: Context, number: String) =
        start(context, Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun webSearch(context: Context, engine: WebEngine, query: String) =
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse(engine.url(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Searchlight", text))
        Toast.makeText(context, context.getString(R.string.copied, text), Toast.LENGTH_SHORT).show()
    }

    private fun start(context: Context, intent: Intent, toastOnFail: Boolean = true): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        if (toastOnFail) Toast.makeText(context, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
        false
    } catch (e: SecurityException) {
        if (toastOnFail) Toast.makeText(context, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
        false
    }
}
