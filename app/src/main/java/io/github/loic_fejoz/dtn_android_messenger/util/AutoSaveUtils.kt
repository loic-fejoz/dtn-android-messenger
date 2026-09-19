package io.github.loic_fejoz.dtn_android_messenger.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.github.loic_fejoz.dtn_android_messenger.data.model.BundleRecord
import io.github.loic_fejoz.dtn_android_messenger.data.model.LocalService
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AutoSaveUtils {

    fun generateAutoSaveFileName(record: BundleRecord, extension: String): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestampStr = dateFormat.format(Date(record.creationTimestamp))
        val cleanBundleId = record.bundleId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "${timestampStr}_${cleanBundleId}.$extension"
    }

    fun getMimeTypeFromExtension(ext: String): String {
        return PayloadUtils.getMimeTypeFromExtension(ext)
    }

    /**
     * Pure file save helper used for testing or fallback directory writes.
     */
    fun savePayloadToFolder(
        service: LocalService,
        record: BundleRecord,
        payloadFile: File,
        targetDirectory: File
    ): File? {
        if (!service.autoSaveEnabled) return null
        if (!payloadFile.exists() || payloadFile.length() == 0L) return null

        val ext = PayloadUtils.getPayloadFileExtension(payloadFile.absolutePath)
        val fileName = generateAutoSaveFileName(record, ext)

        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
        }

        val outFile = File(targetDirectory, fileName)
        payloadFile.inputStream().use { input ->
            FileOutputStream(outFile).use { out ->
                input.copyTo(out)
            }
        }
        return outFile
    }

    /**
     * Android MediaStore / Public directory auto-save implementation.
     */
    fun autoSavePayload(
        context: Context,
        service: LocalService,
        record: BundleRecord,
        payloadFile: File
    ): String? {
        if (!service.autoSaveEnabled) return null
        if (!payloadFile.exists() || payloadFile.length() == 0L) return null

        val targetDirName = service.autoSaveTargetDirectory.ifBlank { "Podcasts" }
        val ext = PayloadUtils.getPayloadFileExtension(payloadFile.absolutePath)
        val fileName = generateAutoSaveFileName(record, ext)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relativePath = when (targetDirName.lowercase(Locale.US)) {
                    "podcasts" -> Environment.DIRECTORY_PODCASTS
                    "music" -> Environment.DIRECTORY_MUSIC
                    "download", "downloads" -> Environment.DIRECTORY_DOWNLOADS
                    "documents" -> Environment.DIRECTORY_DOCUMENTS
                    "pictures" -> Environment.DIRECTORY_PICTURES
                    "movies" -> Environment.DIRECTORY_MOVIES
                    else -> Environment.DIRECTORY_PODCASTS
                }

                val contentUri = when (relativePath) {
                    Environment.DIRECTORY_PODCASTS, Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    Environment.DIRECTORY_MOVIES -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.MIME_TYPE, getMimeTypeFromExtension(ext))
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(contentUri, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        payloadFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    uri.toString()
                } else null
            } else {
                @Suppress("DEPRECATION")
                val targetDir = Environment.getExternalStoragePublicDirectory(targetDirName)
                val savedFile = savePayloadToFolder(service, record, payloadFile, targetDir)
                savedFile?.absolutePath
            }
        } catch (e: Exception) {
            android.util.Log.e("AutoSaveUtils", "Failed to auto-save payload to $targetDirName: ${e.message}", e)
            null
        }
    }
}
