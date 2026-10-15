package com.votera.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Candidate photos are fetched from the server (with the login token) and kept in memory.
 *  The cache key includes photoVersion, so a replaced photo shows up on every phone right away. */
object PhotoCache {
    private val cache = LruCache<String, ImageBitmap>(60)
    private fun key(id: Int, version: Int) = "$id:$version"
    fun get(id: Int, version: Int): ImageBitmap? = cache.get(key(id, version))

    suspend fun load(id: Int, version: Int): ImageBitmap? {
        get(id, version)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bytes = Net.api.photo(id).bytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                if (bmp != null) cache.put(key(id, version), bmp)
                bmp
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** The candidate's photo in a circle. Falls back to colored initials when there is no photo (or while it loads). */
@Composable
fun CandidateAvatar(c: CandidateDto, size: Dp = 44.dp) {
    var bmp by remember(c.id, c.photoVersion) { mutableStateOf(PhotoCache.get(c.id, c.photoVersion)) }
    LaunchedEffect(c.id, c.photoVersion) {
        if (c.photoVersion > 0 && bmp == null) bmp = PhotoCache.load(c.id, c.photoVersion)
    }
    val b = bmp
    if (b != null) Image(bitmap = b, contentDescription = c.fullName, contentScale = ContentScale.Crop,
        modifier = Modifier.size(size).clip(CircleShape))
    else Avatar(c.fullName, c.id, size)
}

/** Reads the chosen picture, crops it to a centered square, shrinks it to 480 px and returns JPEG bytes (about 40 KB). */
fun compressPhoto(ctx: Context, uri: Uri): ByteArray? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1200 || bounds.outHeight / sample > 1200) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        // Phones often store photos sideways and add a rotation tag. Read it so the photo is upright.
        val orientation = try {
            ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (e: Exception) { null } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side, Matrix().apply { postRotate(degrees) }, true)
        val scaled = Bitmap.createScaledBitmap(square, 480, 480, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    } catch (e: Exception) {
        null
    }
}

/** Picture for an announcement post: keeps its shape (no square crop), upright, longest side 1280 px, JPEG (about 150 KB). */
fun compressPostImage(ctx: Context, uri: Uri): ByteArray? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val orientation = try {
            ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        } catch (e: Exception) { null } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val upright = if (degrees != 0f) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true) else bmp
        val longest = maxOf(upright.width, upright.height)
        val scale = if (longest > 1280) 1280f / longest else 1f
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(upright, (upright.width * scale).toInt(), (upright.height * scale).toInt(), true) else upright
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
        out.toByteArray()
    } catch (e: Exception) {
        null
    }
}

/** Announcement pictures, fetched with the login token and kept in memory. The key includes the version, so a replaced picture refreshes. */
object PostImageCache {
    private val cache = LruCache<String, ImageBitmap>(20)
    private fun key(id: Int, version: Int) = "$id:$version"
    fun get(id: Int, version: Int): ImageBitmap? = cache.get(key(id, version))

    suspend fun load(id: Int, version: Int): ImageBitmap? {
        get(id, version)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val bytes = Net.api.announcementImage(id).bytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                if (bmp != null) cache.put(key(id, version), bmp)
                bmp
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
}

fun bytesToImage(bytes: ByteArray): ImageBitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
