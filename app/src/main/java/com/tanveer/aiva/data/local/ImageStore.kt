package com.tanveer.aiva.data.local

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.tanveer.aiva.data.remote.EncodedImage
import com.tanveer.aiva.data.remote.extensionForMime
import com.tanveer.aiva.data.remote.sniffImageMime
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Copies compressed images into app-private storage.
 * Chat rows store relative paths (`attachments/…`, `generated/…`), never content URIs.
 * Files are removed with the message, the thread, or clear-history. Nothing here is backed up.
 */
class ImageStore(filesDir: File) {
    private val root = File(filesDir, "images").apply { mkdirs() }
    private val attachments = File(root, "attachments").apply { mkdirs() }
    private val generated = File(root, "generated").apply { mkdirs() }

    fun import(resolver: ContentResolver, uri: Uri): String {
        val declared = resolver.getType(uri)
        if (declared != null && !declared.startsWith("image/")) {
            throw IllegalArgumentException("Only image files can be attached.")
        }
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            if (descriptor.statSize > ImageLimits.MAX_SOURCE_BYTES) {
                throw IllegalArgumentException("That image is over 20 MB. Choose a smaller one.")
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalArgumentException("Couldn’t read that image.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalArgumentException("That file isn’t a supported image.")
        }
        val sample = subsample(bounds.outWidth, bounds.outHeight)
        val decoded = resolver.openInputStream(uri)?.use { input ->
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeStream(input, null, opts)
        } ?: throw IllegalArgumentException("Couldn’t decode that image.")
        return try {
            val fitted = fit(decoded)
            val bytes = ByteArrayOutputStream()
            val wrote = fitted.compress(Bitmap.CompressFormat.JPEG, ImageLimits.JPEG_QUALITY, bytes)
            if (fitted !== decoded) fitted.recycle()
            if (!wrote || bytes.size() == 0) throw IllegalArgumentException("Couldn’t compress that image.")
            if (bytes.size() > ImageLimits.MAX_STORED_BYTES) {
                throw IllegalArgumentException("That image is still too large after compressing.")
            }
            val jpeg = bytes.toByteArray()
            if (sniffImageMime(jpeg) != "image/jpeg") throw IllegalArgumentException("That file isn’t a supported image.")
            val name = "${UUID.randomUUID()}.jpg"
            File(attachments, name).writeBytes(jpeg)
            "attachments/$name"
        } finally {
            decoded.recycle()
        }
    }

    fun encode(relativePath: String): EncodedImage {
        val file = file(relativePath) ?: throw IllegalArgumentException("An attached image is no longer on this phone.")
        if (file.length() > ImageLimits.MAX_STORED_BYTES) {
            throw IllegalArgumentException("An attached image is too large to send.")
        }
        val bytes = file.readBytes()
        val mime = sniffImageMime(bytes) ?: throw IllegalArgumentException("An attached image could not be read.")
        return EncodedImage(mime, Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    fun saveGenerated(bytes: ByteArray, mimeType: String): String {
        val sniffed = sniffImageMime(bytes) ?: throw IllegalArgumentException("The provider did not return an image file.")
        if (bytes.size > 12_000_000) throw IllegalArgumentException("The generated image is too large to store.")
        val name = "${UUID.randomUUID()}.${extensionForMime(sniffed)}"
        File(generated, name).writeBytes(bytes)
        return "generated/$name"
    }

    fun thumbnail(relativePath: String, edgePx: Int): Bitmap? {
        val file = file(relativePath) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = subsample(bounds.outWidth, bounds.outHeight, edgePx.coerceAtLeast(32))
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    fun file(relativePath: String): File? {
        if (relativePath.isBlank() || relativePath.contains("..") || relativePath.startsWith("/")) return null
        if (relativePath.startsWith("content:") || relativePath.startsWith("file:")) return null
        val candidate = File(root, relativePath)
        val rootPath = root.canonicalPath
        val path = runCatching { candidate.canonicalPath }.getOrNull() ?: return null
        if (!path.startsWith(rootPath + File.separator)) return null
        return candidate.takeIf { it.isFile }
    }

    fun delete(relativePaths: Collection<String>) {
        relativePaths.forEach { path -> file(path)?.delete() }
    }

    fun deleteUnreferenced(keep: Set<String>) {
        listOf(attachments, generated).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                val relative = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                if (relative !in keep) file.delete()
            }
        }
    }

    private fun fit(source: Bitmap): Bitmap {
        val max = ImageLimits.MAX_EDGE_PX
        val w = source.width
        val h = source.height
        if (w <= max && h <= max) return source
        val scale = max.toFloat() / maxOf(w, h).toFloat()
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, nw, nh, true)
    }
}
