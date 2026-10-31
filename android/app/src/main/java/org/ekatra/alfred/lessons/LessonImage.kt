package org.ekatra.alfred.lessons

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How to decode a lesson image: a power-of-two [sampleSize] for BitmapFactory, and the
 * [outWidth] x [outHeight] it should end up at after the final scale.
 */
internal data class DecodePlan(val sampleSize: Int, val outWidth: Int, val outHeight: Int) {
    /** RGB_565 is 2 bytes per pixel; lesson images are opaque JPEGs, so nothing is lost. */
    val bytes: Long get() = outWidth.toLong() * outHeight * 2
}

/**
 * Longest decoded shorter side. The images are 1024 px, and on a phone whose view is about 1000 px wide
 * anything lower is upscaled and soft. At RGB_565 a full 1024 x 1024 image is 2 MiB.
 */
internal const val MAX_DECODE_SHORT_SIDE = 1024

/**
 * Size the decode to the view's WIDTH, aiming at the image's SHORTER side, and never above
 * [MAX_DECODE_SHORT_SIDE]. A wide, short cover banner and a square module image are sized the same
 * way, so neither decodes more than it needs. Never upscales.
 */
internal fun computeDecodePlan(srcWidth: Int, srcHeight: Int, viewWidthPx: Int): DecodePlan {
    if (srcWidth <= 0 || srcHeight <= 0) return DecodePlan(1, maxOf(srcWidth, 0), maxOf(srcHeight, 0))
    val shortSide = minOf(srcWidth, srcHeight)
    // A view that has not been measured yet (width 0) gets the standard size rather than a 1 px decode.
    val target = if (viewWidthPx <= 0) MAX_DECODE_SHORT_SIDE else minOf(viewWidthPx, MAX_DECODE_SHORT_SIDE)
    if (shortSide <= target) return DecodePlan(1, srcWidth, srcHeight)

    var sample = 1
    while (shortSide / (sample * 2) >= target) sample *= 2
    val scale = target.toDouble() / shortSide
    return DecodePlan(sample, maxOf(1, Math.round(srcWidth * scale).toInt()), maxOf(1, Math.round(srcHeight * scale).toInt()))
}

/** Decode [file] for a view [viewWidthPx] wide. Null if it is missing or not a readable image. */
internal fun decodeSampled(file: File, viewWidthPx: Int): ImageBitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val plan = computeDecodePlan(bounds.outWidth, bounds.outHeight, viewWidthPx)
    val options = BitmapFactory.Options().apply {
        inSampleSize = plan.sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
        // After sampling, scale the remainder to the exact target using the density trick.
        val afterSample = bounds.outWidth / plan.sampleSize
        if (afterSample != plan.outWidth && afterSample > 0) {
            inScaled = true
            inDensity = afterSample
            inTargetDensity = plan.outWidth
        }
    }
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}

/** What a lesson image slot draws. */
internal enum class ImageSlot { SHOW_IMAGE, PLACEHOLDER, NOTHING }

/**
 * @param hasImage a decoded bitmap is in hand (it may be the previous one for this file while a newer
 *   file is still decoding, which avoids a flash).
 * @param decodeSettled the decode for the current file has finished, successfully or not.
 * @param fileExists the image file is on disk.
 * @param downloadPending a download for this image is queued or running.
 *
 * The placeholder holds the space through the whole gap from "download queued" to "bitmap ready", including
 * decoding. It used to end when the download did, so the slot collapsed and the text jumped back down
 * when the bitmap arrived a moment later.
 */
internal fun imageSlot(hasImage: Boolean, decodeSettled: Boolean, fileExists: Boolean, downloadPending: Boolean): ImageSlot = when {
    hasImage && fileExists -> ImageSlot.SHOW_IMAGE
    downloadPending -> ImageSlot.PLACEHOLDER
    fileExists && !decodeSettled -> ImageSlot.PLACEHOLDER // on disk, being decoded
    else -> ImageSlot.NOTHING
}

/**
 * Whether the screen should emit this slot at all. An empty slot must not be emitted: even a zero-height
 * child adds a `spacedBy` gap in the parent column.
 */
internal fun slotVisible(fileExists: Boolean, knownBroken: Boolean, downloadPending: Boolean): Boolean =
    downloadPending || (fileExists && !knownBroken)

/**
 * Decoded bitmaps by file path, so an image that is replaced keeps showing its previous bitmap until the
 * new one is ready, and so scrolling or rotating does not decode again. Also remembers files that could
 * not be decoded, so a corrupt file collapses its slot instead of holding a placeholder forever.
 */
internal object LessonBitmapCache {
    class Entry(val image: ImageBitmap, val stamp: Long, val widthPx: Int)

    private val lru = SizedLru<Entry>(maxBytes = 12L * 1024 * 1024)
    // Snapshot state, so a screen that read it recomposes when a decode fails.
    private val broken = mutableStateMapOf<String, Long>()

    fun last(path: String): Entry? = lru[path]

    fun put(path: String, stamp: Long, widthPx: Int, image: ImageBitmap) {
        lru.put(path, Entry(image, stamp, widthPx), image.width.toLong() * image.height * 2)
        broken.remove(path)
    }

    fun markBroken(path: String, stamp: Long) { broken[path] = stamp }

    fun isBroken(path: String, stamp: Long) = broken[path] == stamp
}

/**
 * Whether to emit a [LessonImage] for this file at all. Call it unconditionally and wrap the image in `if`.
 */
@Composable
internal fun rememberImageSlotVisible(file: File, pending: Boolean, stamp: Long): Boolean {
    val exists = remember(file.path, stamp) { file.exists() }
    return slotVisible(exists, LessonBitmapCache.isBroken(file.path, stamp), pending)
}

/**
 * A lesson illustration read from the local file, so it works offline.
 *
 * @param pending a download for this image is queued or running.
 * @param stamp changes each time THIS file is written; only then is it decoded again.
 * @param shape sizing and clipping (height or aspect ratio, rounded corners).
 */
@Composable
internal fun LessonImage(
    file: File,
    pending: Boolean,
    stamp: Long,
    contentDescription: String,
    shape: Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val exists = remember(file.path, stamp) { file.exists() }
        // Starts from the previous bitmap for this file, if any; produceState keeps the old value while it re-runs.
        val decoded by produceState(
            initialValue = Pair(LessonBitmapCache.last(file.path)?.image, false),
            file.path, stamp, widthPx
        ) {
            val cached = LessonBitmapCache.last(file.path)
            if (cached != null && cached.stamp == stamp && cached.widthPx == widthPx) {
                value = cached.image to true
            } else {
                val image = withContext(Dispatchers.IO) { decodeSampled(file, widthPx) }
                if (image != null) LessonBitmapCache.put(file.path, stamp, widthPx, image)
                else if (file.exists()) LessonBitmapCache.markBroken(file.path, stamp)
                value = (image ?: value.first.takeIf { file.exists() }) to true
            }
        }
        val (image, settled) = decoded
        when (imageSlot(hasImage = image != null, decodeSettled = settled, fileExists = exists, downloadPending = pending)) {
            ImageSlot.SHOW_IMAGE -> Image(
                bitmap = image!!, contentDescription = contentDescription,
                contentScale = contentScale, modifier = shape.fillMaxWidth()
            )
            ImageSlot.PLACEHOLDER -> Box(shape.fillMaxWidth().background(Color(0xFFE2E8F0)))
            ImageSlot.NOTHING -> Unit
        }
    }
}
