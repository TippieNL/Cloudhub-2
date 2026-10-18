package nl.tippie.cloudhub.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * What a photo says about itself: its size in pixels, and what the camera
 * wrote into it.
 *
 * Read from the bytes the viewer has already downloaded to draw the photo --
 * the image cache keeps them -- so asking for the details costs no request.
 * Every field is optional: a screenshot has no camera, a PNG no EXIF at all,
 * and a photo whose bytes have not arrived yet has nothing to read.
 */
data class PhotoFacts(
    /** Pixels, the right way up: a portrait photo stored sideways reads as portrait. */
    val width: Int? = null,
    val height: Int? = null,
    /** When it was taken, by the camera's own clock: "2024-05-17 14:03". */
    val taken: String? = null,
    /** "Google Pixel 8". */
    val camera: String? = null,
    /** "f/1.7 · 1/120 s · ISO 50 · 6.9 mm". */
    val exposure: String? = null,
) {
    val dimensions: String? get() = dimensions(width, height)

    companion object {

        /** "4032 × 3024 · 12.2 MP". */
        fun dimensions(width: Int?, height: Int?): String? {
            if (width == null || height == null || width <= 0 || height <= 0) return null
            val megapixels = width.toLong() * height / 1_000_000.0
            return String.format(Locale.US, "%d × %d · %.1f MP", width, height, megapixels)
        }

        /**
         * EXIF's "2024:05:17 14:03:22" as "2024-05-17 14:03".
         *
         * Cameras with no clock set write zeros, and some write blanks; both
         * mean "unknown", and showing "0000-00-00" would be stating nonsense.
         */
        fun taken(exif: String?): String? {
            val match = EXIF_DATE.matchEntire(exif?.trim().orEmpty()) ?: return null
            val (year, month, day, hour, minute) = match.destructured
            if (year == "0000" || month == "00" || day == "00") return null
            return "$year-$month-$day $hour:$minute"
        }

        private val EXIF_DATE = Regex("""(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2})(?::\d{2}.*)?""")

        /**
         * Make and model as one name, without the make twice.
         *
         * Many cameras repeat it in the model -- make "Canon", model "Canon
         * EOS R6" -- and some put it in capitals in one and not the other.
         */
        fun camera(make: String?, model: String?): String? {
            val m = make?.trim().orEmpty()
            val n = model?.trim().orEmpty()
            return when {
                n.isEmpty() -> m.ifEmpty { null }
                m.isEmpty() || n.startsWith(m, ignoreCase = true) -> n
                else -> "$m $n"
            }
        }

        /** Aperture, shutter, ISO and focal length -- whichever of them the camera wrote. */
        fun exposure(fNumber: Double?, seconds: Double?, iso: Int?, focalMm: Double?): String? {
            val parts = listOfNotNull(
                fNumber?.takeIf { it > 0 }?.let { "f/" + trimmed(it) },
                seconds?.takeIf { it > 0 }?.let(::shutter),
                iso?.takeIf { it > 0 }?.let { "ISO $it" },
                focalMm?.takeIf { it > 0 }?.let { trimmed(it) + " mm" },
            )
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

        /**
         * A shutter speed the way a camera shows it: "1/120 s" below a second,
         * "2 s" or "1.5 s" above.
         */
        fun shutter(seconds: Double): String =
            if (seconds >= 1.0) trimmed(seconds) + " s"
            else "1/" + Math.round(1.0 / seconds) + " s"

        /** One decimal at most, and none when it would be ".0": f/2, f/1.8, 50 mm. */
        private fun trimmed(value: Double): String {
            val text = String.format(Locale.US, "%.1f", value)
            return if (text.endsWith(".0")) text.dropLast(2) else text
        }

        /**
         * The facts of a photo the image cache holds, filed under the URL the
         * viewer loaded it from -- or nothing, if its bytes have not arrived.
         */
        // Experimental in Coil 2, deliberately used: see LocalCopies.copyOf.
        @OptIn(coil.annotation.ExperimentalCoilApi::class)
        suspend fun cached(context: Context, url: String): PhotoFacts? = withContext(Dispatchers.IO) {
            runCatching {
                context.imageLoader.diskCache?.openSnapshot(url)?.use { read(it.data.toFile()) }
            }.getOrNull()
        }

        /**
         * Read a photo's facts from a file of its bytes, or nothing if they
         * cannot be read -- a format the platform does not parse is not an error
         * worth telling anyone about, it just has no details to show.
         */
        fun read(file: File): PhotoFacts? = runCatching {
            val path = file.absolutePath
            val exif = runCatching { ExifInterface(path) }.getOrNull()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)

            var width = bounds.outWidth.takeIf { it > 0 }
                ?: exif?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)?.takeIf { it > 0 }
            var height = bounds.outHeight.takeIf { it > 0 }
                ?: exif?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)?.takeIf { it > 0 }
            // The pixels are stored as the sensor saw them; a phone held
            // upright writes a landscape image and a note saying to turn it.
            val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            if (orientation in SIDEWAYS) width = height.also { height = width }

            fun number(tag: String): Double? =
                exif?.getAttributeDouble(tag, -1.0)?.takeIf { it > 0 }

            PhotoFacts(
                width = width,
                height = height,
                taken = taken(
                    exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
                ),
                camera = camera(exif?.getAttribute(ExifInterface.TAG_MAKE), exif?.getAttribute(ExifInterface.TAG_MODEL)),
                exposure = exposure(
                    fNumber = number(ExifInterface.TAG_F_NUMBER),
                    seconds = number(ExifInterface.TAG_EXPOSURE_TIME),
                    iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)?.takeIf { it > 0 },
                    focalMm = number(ExifInterface.TAG_FOCAL_LENGTH),
                ),
            )
        }.getOrNull()

        private val SIDEWAYS = setOf(
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
        )
    }
}
