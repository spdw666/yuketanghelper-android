package cn.yukehelper.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap

/** Local QR decoding only: no image ever leaves the device. */
object QrImages {
    private val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
        put(DecodeHintType.TRY_HARDER, true)
        put(DecodeHintType.CHARACTER_SET, "UTF-8")
    }

    fun decode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 8 || height < 8) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return decode(RGBLuminanceSource(width, height, pixels))
    }

    /** Camera frames arrive as a Y plane whose row stride can exceed the visible width. */
    fun decodeYuv(data: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        if (width < 8 || height < 8 || rowStride < width) return null
        val needed = rowStride * height
        val buffer = if (data.size == needed) data else data.copyOf(needed)
        return decode(PlanarYUVLuminanceSource(buffer, rowStride, height, 0, 0, width, height, false))
    }

    private fun decode(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply { setHints(hints) }
        for (candidate in listOf(source, source.invert())) {
            try {
                val text = reader.decodeWithState(BinaryBitmap(HybridBinarizer(candidate))).text
                if (!text.isNullOrBlank()) return text.trim()
            } catch (_: Exception) {
                // Not in this frame (or not inverted); try the next representation.
            } finally { reader.reset() }
        }
        return null
    }

    /** Gallery imports are sampled down first so a 12MP screenshot does not exhaust the heap. */
    fun readBitmap(context: Context, uri: Uri, maxSide: Int = 1_600): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }
}
