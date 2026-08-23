package ch.rhosys.sbb.data.local.photo

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * Copies a place photo picked via the system photo picker into app-private storage.
 *
 * The picker only hands back a transient content:// Uri scoped to this device — it
 * isn't a file under the app's own data directory, so Android Auto Backup can't
 * capture it, and it stops resolving after a restore to a new device anyway. Copying
 * the bytes into filesDir makes the photo an ordinary app file that backs up and
 * restores like the rest of the app's data.
 */
class PlacePhotoStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val photoDir: File
        get() = File(context.filesDir, "place_photos").apply { mkdirs() }

    suspend fun copyToLocalStorage(sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val destination = File(photoDir, "${UUID.randomUUID()}.jpg")
            val copied = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
                true
            } ?: false
            if (copied) destination.toUri().toString() else null
        }.getOrNull()
    }

    /** No-ops for anything that isn't a file we manage (e.g. a legacy content:// value). */
    suspend fun delete(managedUriString: String?) {
        if (managedUriString == null) return
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = managedUriString.toUri()
                if (uri.scheme != "file") return@runCatching
                val path = uri.path ?: return@runCatching
                File(path).takeIf { it.parentFile == photoDir }?.delete()
            }
        }
    }
}
