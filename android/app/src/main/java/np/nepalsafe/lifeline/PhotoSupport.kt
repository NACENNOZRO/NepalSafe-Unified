package np.nepalsafe.lifeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

object PhotoSupport {
    fun copyToCache(context: Context, uri: Uri, prefix: String): File {
        val target = File.createTempFile(prefix, ".jpg", context.cacheDir)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to read selected photo" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    fun compressedDataUri(file: File, maxWidth: Int = 640, quality: Int = 35): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > maxWidth * 2) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: error("The selected file is not a readable image")
        val resized = if (bitmap.width > maxWidth) {
            val height = (bitmap.height * (maxWidth.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
        } else {
            bitmap
        }
        val bytes = ByteArrayOutputStream().use { output ->
            resized.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
        if (resized !== bitmap) resized.recycle()
        bitmap.recycle()
        return "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }
}
