package org.ekatra.alfred.lessons

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
 * Largest power-of-two `inSampleSize` that still leaves the decoded image at least as big as the
 * view needs, so a 1024x1024 JPEG is not decoded at full size into a 120 dp thumbnail.
 */
internal fun computeSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
    if (srcWidth <= 0 || srcHeight <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
    var sample = 1
    while (srcWidth / (sample * 2) >= reqWidth && srcHeight / (sample * 2) >= reqHeight) sample *= 2
    return sample
}

/** Decode [file] for a [reqWidth] x [reqHeight] view. Null if it is missing or not a readable image. */
internal fun decodeSampled(file: File, reqWidth: Int, reqHeight: Int): ImageBitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
    }
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}

/**
 * A lesson illustration read from the local file, so it works offline.
 *
 * @param expected whether the bundle names an image for this slot. Older bundles and review lessons
 *   do not; those draw nothing at all. If one is expected but the file is not there yet (still
 *   downloading), a plain placeholder holds the space.
 * @param revision changes whenever an image file is written, so a freshly downloaded image appears.
 */
@Composable
internal fun LessonImage(
    file: File,
    expected: Boolean,
    contentDescription: String,
    revision: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (!expected && !file.exists()) return
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val bitmap by produceState<ImageBitmap?>(null, file.path, revision, widthPx, heightPx) {
            value = withContext(Dispatchers.IO) { decodeSampled(file, widthPx, heightPx) }
        }
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image, contentDescription = contentDescription,
                contentScale = contentScale, modifier = Modifier.fillMaxSize()
            )
        } else {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFFE2E8F0)))
        }
    }
}
