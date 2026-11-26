package io.github.loic_fejoz.dtn_android_messenger.cla

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.github.loic_fejoz.dtn_android_messenger.data.dao.SystemLogDao
import io.github.loic_fejoz.dtn_android_messenger.data.model.SystemLog
import io.github.loic_fejoz.dtn_android_messenger.util.PreferencesHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface ClaFile {
    val name: String
    val isDirectory: Boolean
    val length: Long
    val lastModified: Long

    fun readBytes(maxBytes: Long): ByteArray?

    fun writeBytes(bytes: ByteArray): Boolean

    fun renameTo(displayName: String): Boolean

    fun delete(): Boolean
}

interface ClaDirectory {
    fun listFiles(): List<ClaFile>

    fun findFile(name: String): ClaFile?

    fun findOrCreateDirectory(name: String): ClaDirectory?

    fun createFile(
        mimeType: String,
        displayName: String,
    ): ClaFile?
}

fun interface ClaDirectoryFactory {
    fun fromLocation(location: String): ClaDirectory?
}

private fun InputStream.readBoundedBytes(maxBytes: Long): ByteArray? {
    val buffer = ByteArray(8192)
    val out = ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

class SafClaDirectoryFactory(private val context: Context) : ClaDirectoryFactory {
    override fun fromLocation(location: String): ClaDirectory? {
        if (location.isBlank()) return null
        return try {
            val uri = location.toUri()
            val doc = DocumentFile.fromTreeUri(context, uri)
            if (doc != null && doc.exists() && doc.isDirectory) {
                SafClaDirectory(context, context.contentResolver, uri, doc)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private class SafClaDirectory(
        private val context: Context,
        private val contentResolver: ContentResolver,
        private val treeUri: Uri,
        private val doc: DocumentFile,
    ) : ClaDirectory {
        override fun listFiles(): List<ClaFile> {
            val fastList = queryChildrenViaDocumentsContract()
            if (fastList != null) return fastList
            return doc.listFiles().map { SafClaFile(contentResolver, it) }
        }

        private fun queryChildrenViaDocumentsContract(): List<ClaFile>? {
            return try {
                val parentDocId = DocumentsContract.getDocumentId(doc.uri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
                val projection =
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    )
                val result = mutableListOf<ClaFile>()
                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val mtimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(idCol) ?: continue
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                        val childDoc = DocumentFile.fromSingleUri(context, childUri) ?: continue
                        val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else childDoc.name.orEmpty()
                        val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                        val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                        val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                        val mtime = if (mtimeCol >= 0 && !cursor.isNull(mtimeCol)) cursor.getLong(mtimeCol) else 0L
                        result.add(
                            SafClaFile(
                                contentResolver = contentResolver,
                                doc = childDoc,
                                cachedName = name,
                                cachedIsDirectory = isDir,
                                cachedLength = size,
                                cachedLastModified = mtime,
                            ),
                        )
                    }
                } ?: return null
                result
            } catch (_: Exception) {
                null
            }
        }

        override fun findFile(name: String): ClaFile? =
            doc.findFile(name)?.let { SafClaFile(contentResolver, it) }

        override fun findOrCreateDirectory(name: String): ClaDirectory? {
            val existing = doc.findFile(name)
            if (existing != null) {
                return if (existing.isDirectory) SafClaDirectory(context, contentResolver, treeUri, existing) else null
            }
            return doc.createDirectory(name)?.let { SafClaDirectory(context, contentResolver, treeUri, it) }
        }

        override fun createFile(
            mimeType: String,
            displayName: String,
        ): ClaFile? = doc.createFile(mimeType, displayName)?.let { SafClaFile(contentResolver, it) }
    }

    private class SafClaFile(
        private val contentResolver: ContentResolver,
        private val doc: DocumentFile,
        private val cachedName: String? = null,
        private val cachedIsDirectory: Boolean? = null,
        private val cachedLength: Long? = null,
        private val cachedLastModified: Long? = null,
    ) : ClaFile {
        override val name: String
            get() = cachedName ?: doc.name.orEmpty()

        override val isDirectory: Boolean
            get() = cachedIsDirectory ?: doc.isDirectory

        override val length: Long
            get() = cachedLength ?: doc.length()

        override val lastModified: Long
            get() = cachedLastModified ?: doc.lastModified()

        override fun readBytes(maxBytes: Long): ByteArray? {
            if (length > maxBytes) return null
            return try {
                contentResolver.openInputStream(doc.uri)?.use { it.readBoundedBytes(maxBytes) }
            } catch (_: Exception) {
                null
            }
        }

        override fun writeBytes(bytes: ByteArray): Boolean {
            return try {
                val stream =
                    try {
                        contentResolver.openOutputStream(doc.uri, "wt")
                    } catch (_: IllegalArgumentException) {
                        contentResolver.openOutputStream(doc.uri)
                    } catch (_: UnsupportedOperationException) {
                        contentResolver.openOutputStream(doc.uri)
                    }
                stream?.use { out ->
                    out.write(bytes)
                    out.flush()
                    true
                } ?: false
            } catch (_: Exception) {
                false
            }
        }

        override fun renameTo(displayName: String): Boolean =
            try {
                doc.renameTo(displayName)
            } catch (_: Exception) {
                false
            }

        override fun delete(): Boolean =
            try {
                doc.delete()
            } catch (_: Exception) {
                false
            }
    }
}

class LocalFileClaDirectoryFactory : ClaDirectoryFactory {
    override fun fromLocation(location: String): ClaDirectory? {
        if (location.isBlank()) return null
        val dir = File(location)
        return if (dir.exists() && dir.isDirectory) LocalClaDirectory(dir) else null
    }

    private class LocalClaDirectory(private val dir: File) : ClaDirectory {
        private fun resolveChild(name: String): File? {
            if (name.isBlank()) return null
            return try {
                val baseCanonical = dir.canonicalFile
                val target = File(baseCanonical, name).canonicalFile
                if (target.parentFile == baseCanonical) target else null
            } catch (_: Exception) {
                null
            }
        }

        override fun listFiles(): List<ClaFile> =
            dir.listFiles()?.map { LocalClaFile(it) } ?: emptyList()

        override fun findFile(name: String): ClaFile? {
            val target = resolveChild(name) ?: return null
            return if (target.exists()) LocalClaFile(target) else null
        }

        override fun findOrCreateDirectory(name: String): ClaDirectory? {
            val target = resolveChild(name) ?: return null
            if (target.exists()) {
                return if (target.isDirectory) LocalClaDirectory(target) else null
            }
            return if (target.mkdirs()) LocalClaDirectory(target) else null
        }

        override fun createFile(
            mimeType: String,
            displayName: String,
        ): ClaFile? {
            val target = resolveChild(displayName) ?: return null
            return try {
                if (target.exists() || target.createNewFile()) LocalClaFile(target) else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private class LocalClaFile(private var file: File) : ClaFile {
        override val name: String
            get() = file.name

        override val isDirectory: Boolean
            get() = file.isDirectory

        override val length: Long
            get() = file.length()

        override val lastModified: Long
            get() = file.lastModified()

        override fun readBytes(maxBytes: Long): ByteArray? {
            if (length > maxBytes) return null
            return try {
                file.inputStream().use { it.readBoundedBytes(maxBytes) }
            } catch (_: Exception) {
                null
            }
        }

        override fun writeBytes(bytes: ByteArray): Boolean =
            try {
                file.outputStream().use { out ->
                    out.write(bytes)
                    out.flush()
                }
                true
            } catch (_: Exception) {
                false
            }

        override fun renameTo(displayName: String): Boolean {
            val parent = file.parentFile ?: return false
            val target = File(parent, displayName)
            val renamed = file.renameTo(target)
            if (renamed) {
                file = target
            }
            return renamed
        }

        override fun delete(): Boolean = file.delete()
    }
}

class FileClaAdapter(
    private val context: Context? = null,
    private val logDao: SystemLogDao? = null,
    private val directoryFactory: ClaDirectoryFactory =
        SafClaDirectoryFactory(requireNotNull(context) { "Context required for default SafClaDirectoryFactory" }),
    private val maxBundleSizeBytesProvider: () -> Long = {
        context?.let { PreferencesHelper.getMaxBundleSizeBytes(it) }
            ?: (PreferencesHelper.DEFAULT_MAX_BUNDLE_SIZE_MB * 1024L * 1024L)
    },
    private val logger: ((level: String, message: String) -> Unit)? = null,
) : ConvergenceLayerAdapter {
    data class NamedBundle(
        val bundleId: String,
        val bundleBytes: ByteArray,
        val onAcknowledged: (suspend () -> Unit)? = null,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NamedBundle) return false
            return bundleId == other.bundleId && bundleBytes.contentEquals(other.bundleBytes)
        }

        override fun hashCode(): Int {
            var result = bundleId.hashCode()
            result = 31 * result + bundleBytes.contentHashCode()
            return result
        }
    }

    private data class FileSnapshot(
        val file: ClaFile,
        val name: String,
        val isDirectory: Boolean,
        val length: Long,
        val lastModified: Long,
    )

    private data class ResolvedDirectories(
        val folderName: String,
        val rxDir: ClaDirectory,
        val txDir: ClaDirectory,
    )

    override val name: String = "FileCLA"

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val failedFileSignatures = ConcurrentHashMap<String, String>()

    private fun log(
        level: String,
        message: String,
    ) {
        val customLogger = logger
        if (customLogger != null) {
            customLogger(level, message)
            return
        }
        when (level) {
            "ERROR" -> android.util.Log.e("FileClaAdapter", message)
            "WARN" -> android.util.Log.w("FileClaAdapter", message)
            else -> android.util.Log.i("FileClaAdapter", message)
        }
        if (logDao != null) {
            adapterScope.launch {
                try {
                    logDao.insert(SystemLog(timestamp = System.currentTimeMillis(), level = level, message = message))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun resolveClaDirectories(
        baseTreeUri: String,
        claName: String,
        failureLogLevel: String?,
    ): ResolvedDirectories? {
        val folderName = sanitizeFolderName(claName)
        if (folderName.isBlank()) {
            if (failureLogLevel != null) {
                log(failureLogLevel, "File-CLA operation skipped: invalid CLA folder name '$claName'")
            }
            return null
        }
        val baseDir = directoryFactory.fromLocation(baseTreeUri)
        if (baseDir == null) {
            if (failureLogLevel != null) {
                log(failureLogLevel, "File-CLA '$folderName' base folder is unavailable ($baseTreeUri)")
            }
            return null
        }
        val claDir = baseDir.findOrCreateDirectory(folderName)
        val rxDir = claDir?.findOrCreateDirectory(RX_DIR_NAME)
        val txDir = claDir?.findOrCreateDirectory(TX_DIR_NAME)
        if (rxDir == null || txDir == null) {
            if (failureLogLevel != null) {
                log(failureLogLevel, "File-CLA '$folderName' RX/TX directories could not be opened or created")
            }
            return null
        }
        return ResolvedDirectories(folderName, rxDir, txDir)
    }

    fun ensureClaFolders(
        baseTreeUri: String,
        claName: String,
    ): Boolean = resolveClaDirectories(baseTreeUri, claName, failureLogLevel = null) != null

    suspend fun scanIncoming(
        baseTreeUri: String,
        claName: String,
        listener: suspend (ByteArray) -> Boolean,
    ): Int =
        scanMutex.withLock {
            withContext(Dispatchers.IO) {
                val dirs = resolveClaDirectories(baseTreeUri, claName, failureLogLevel = "WARN") ?: return@withContext 0
                val folderName = dirs.folderName
                val prefix = "$baseTreeUri|$folderName|"
                val maxBytes = maxBundleSizeBytesProvider()
                val snapshots =
                    dirs.rxDir.listFiles()
                        .map { FileSnapshot(it, it.name, it.isDirectory, it.length, it.lastModified) }
                        .sortedWith(compareBy<FileSnapshot> { it.lastModified }.thenBy { it.name })

                val seenFailureKeys = HashSet<String>()
                var ingestedCount = 0
                for (snap in snapshots) {
                    if (processIncomingFile(snap, folderName, prefix, maxBytes, listener, seenFailureKeys)) {
                        ingestedCount++
                    }
                }

                // Prune cache entries for files that were removed externally from RX (API 23 compatible)
                for (key in failedFileSignatures.keys.toList()) {
                    if (key.startsWith(prefix) && key !in seenFailureKeys) {
                        failedFileSignatures.remove(key)
                    }
                }
                ingestedCount
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun processIncomingFile(
        snap: FileSnapshot,
        folderName: String,
        prefix: String,
        maxBytes: Long,
        listener: suspend (ByteArray) -> Boolean,
        seenFailureKeys: MutableSet<String>,
    ): Boolean {
        val fileName = snap.name
        if (snap.isDirectory || fileName.isBlank()) return false
        if (fileName.startsWith(".") || fileName.endsWith(".tmp", ignoreCase = true)) return false

        val signature = "${snap.lastModified}:${snap.length}"
        val failureKey = "$prefix$fileName"
        if (failedFileSignatures[failureKey] == signature) {
            seenFailureKeys.add(failureKey)
            return false
        }
        if (snap.length > maxBytes) {
            log(
                "WARN",
                "File-CLA '$folderName' skipping '$fileName': size (${snap.length} bytes) exceeds limit ($maxBytes bytes)",
            )
            failedFileSignatures[failureKey] = signature
            seenFailureKeys.add(failureKey)
            return false
        }

        val bytes = snap.file.readBytes(maxBytes)
        if (bytes == null || bytes.isEmpty()) {
            log("WARN", "File-CLA '$folderName' skipping '$fileName': empty, oversized, or unreadable")
            failedFileSignatures[failureKey] = signature
            seenFailureKeys.add(failureKey)
            return false
        }

        val accepted =
            try {
                listener(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("WARN", "File-CLA '$folderName' failed to process '$fileName': ${e.message}")
                false
            }

        if (accepted) {
            if (snap.file.delete()) {
                failedFileSignatures.remove(failureKey)
            } else {
                log("WARN", "File-CLA '$folderName' ingested '$fileName' but could not delete it from RX")
                failedFileSignatures[failureKey] = signature
                seenFailureKeys.add(failureKey)
            }
            return true
        }
        log("WARN", "File-CLA '$folderName' rejected '$fileName' (invalid BPv7 bundle); leaving in RX")
        failedFileSignatures[failureKey] = signature
        seenFailureKeys.add(failureKey)
        return false
    }

    suspend fun sendNamedBundles(
        baseTreeUri: String,
        claName: String,
        bundles: List<NamedBundle>,
    ): Boolean =
        withContext(Dispatchers.IO) {
            if (bundles.isEmpty()) return@withContext false

            val dirs = resolveClaDirectories(baseTreeUri, claName, failureLogLevel = "ERROR") ?: return@withContext false
            val folderName = dirs.folderName
            val txDir = dirs.txDir

            val existingTxFiles = txDir.listFiles().associateBy { it.name }.toMutableMap()
            var anySuccess = false
            for (bundle in bundles) {
                val safeId = sanitizeFolderName(bundle.bundleId).ifBlank { UUID.randomUUID().toString() }
                val finalName = "$safeId.bundle"
                val tmpName = "$safeId.bundle.tmp"

                existingTxFiles.remove(tmpName)?.delete()

                val tmpFile = txDir.createFile(BUNDLE_MIME_TYPE, tmpName)
                if (tmpFile == null) {
                    log("ERROR", "File-CLA '$folderName' failed to create '$tmpName' in TX")
                    continue
                }

                if (!tmpFile.writeBytes(bundle.bundleBytes)) {
                    tmpFile.delete()
                    log("ERROR", "File-CLA '$folderName' failed to write bytes to '$tmpName'")
                    continue
                }

                // Delete pre-existing finalName only after .tmp has been completely written
                existingTxFiles.remove(finalName)?.delete()

                val finalized =
                    if (tmpFile.renameTo(finalName)) {
                        existingTxFiles[finalName] = tmpFile
                        true
                    } else {
                        // Some SAF providers do not support renameTo; fall back to direct creation.
                        val directFile = txDir.createFile(BUNDLE_MIME_TYPE, finalName)
                        val wroteDirect = directFile != null && directFile.writeBytes(bundle.bundleBytes)
                        tmpFile.delete()
                        if (!wroteDirect) {
                            directFile?.delete()
                        } else if (directFile != null) {
                            existingTxFiles[finalName] = directFile
                        }
                        wroteDirect
                    }

                if (!finalized) {
                    log("ERROR", "File-CLA '$folderName' failed to finalize '$finalName' in TX")
                    continue
                }

                try {
                    bundle.onAcknowledged?.invoke()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("ERROR", "File-CLA '$folderName' onAcknowledged failed for '${bundle.bundleId}': ${e.message}")
                }
                anySuccess = true
            }
            anySuccess
        }

    override fun start(
        scope: CoroutineScope,
        listener: suspend (ByteArray) -> Boolean,
    ) {
        // Polling is driven reactively by DtnEngineService based on configured intervals.
    }

    override fun stop() {
        adapterScope.cancel()
    }

    override suspend fun sendBundle(
        bundleBytes: ByteArray,
        targetAddress: String,
        onAcknowledged: (suspend () -> Unit)?,
    ): Boolean = sendBundles(listOf(bundleBytes to onAcknowledged), targetAddress)

    override suspend fun sendBundles(
        bundles: List<Pair<ByteArray, (suspend () -> Unit)?>>,
        targetAddress: String,
    ): Boolean {
        if (bundles.isEmpty()) return false
        val named =
            bundles.map { (bytes, onAck) ->
                NamedBundle(
                    bundleId = UUID.randomUUID().toString(),
                    bundleBytes = bytes,
                    onAcknowledged = onAck,
                )
            }
        return sendNamedBundles(targetAddress, name, named)
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_SECONDS = 300L
        const val MIN_POLL_INTERVAL_SECONDS = 5L
        const val BUNDLE_MIME_TYPE = "application/x-dtn-bundle"
        const val RX_DIR_NAME = "RX"
        const val TX_DIR_NAME = "TX"

        fun sanitizeFolderName(rawName: String): String {
            var sanitized = rawName.trim()
            if (sanitized.isEmpty()) return ""
            sanitized = sanitized.replace(Regex("[\\u0000-\\u001F]"), "")
            sanitized = sanitized.replace(Regex("\\.{2,}"), "_")
            sanitized = sanitized.replace(Regex("[/\\\\:*?\"<>|]"), "_")
            while (sanitized.startsWith(".")) {
                sanitized = "_" + sanitized.substring(1)
            }
            sanitized = sanitized.trim { it <= ' ' || it == '.' }
            if (sanitized.none { it.isLetterOrDigit() }) return ""
            return sanitized
        }

        fun ensureClaFolders(
            context: Context,
            baseTreeUri: String,
            claName: String,
        ): Boolean {
            val adapter = FileClaAdapter(context = context)
            return try {
                adapter.ensureClaFolders(baseTreeUri, claName)
            } finally {
                adapter.stop()
            }
        }
    }
}
