package org.ekatra.alfred.lessons

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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

/** Longest decoded shorter side: 640 px of 1024 is crisp on a phone and keeps one image near 0.8 MB. */
internal const val MAX_DECODE_SHORT_SIDE = 640

/**
 * Size the decode to the view's WIDTH, aiming at the image's SHORTER side, and never above
 * [MAX_DECODE_SHORT_SIDE]. A wide, short cover banner and a square module image both end up
 * about 640 px across, not a full 1024 x 1024 (about 4 MB as ARGB). Never upscales.
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
 * A slot shows the image once decoded, a placeholder only while a download for it is pending, and
 * nothing at all otherwise (no URL, a failed download, a review lesson), so no empty box is left behind.
 */
internal fun imageSlot(decoded: Boolean, downloadPending: Boolean): ImageSlot = when {
    decoded -> ImageSlot.SHOW_IMAGE
    downloadPending -> ImageSlot.PLACEHOLDER
    else -> ImageSlot.NOTHING
}

/**
 * A lesson illustration read from the local file, so it works offline.
 *
 * @param pending a download for this image is queued or running.
 * @param stamp changes each time THIS file is written; only then is it decoded again.
 * @param shape sizing and clipping (height or aspect ratio, rounded corners), applied only when the
 *   slot is drawn, so a slot with nothing to show takes no space.
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
        val bitmap by produceState<ImageBitmap?>(null, file.path, stamp, widthPx) {
            value = withContext(Dispatchers.IO) { decodeSampled(file, widthPx) }
        }
        val image = bitmap
        when (imageSlot(decoded = image != null, downloadPending = pending)) {
            ImageSlot.SHOW_IMAGE -> Image(
                bitmap = image!!, contentDescription = contentDescription,
                contentScale = contentScale, modifier = shape.fillMaxWidth()
            )
            ImageSlot.PLACEHOLDER -> Box(shape.fillMaxWidth().background(Color(0xFFE2E8F0)))
            ImageSlot.NOTHING -> Unit
        }
    }
}
