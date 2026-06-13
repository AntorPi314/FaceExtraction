package com.antor.face.extraction.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.view.OrientationEventListener
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

object ImageUtils {

    /**
     * Gallery থেকে আসা Bitmap এর EXIF orientation পড়ে সঠিকভাবে rotate করে।
     */
    fun fixExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            inputStream.close()

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90    -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180   -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270   -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL   -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(-90f)
                    matrix.preScale(-1f, 1f)
                }
                else -> return bitmap
            }

            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }

    // ✅ REMOVED getCorrectRotation() — nowhere called
    // ✅ REMOVED physicalOrientationToRotationDegrees() — nowhere called
    // ✅ FIX ⑫: calculateRotationDegrees() সরানো হয়েছে।
    //    CameraManager query + complex sensor orientation formula ছিল কিন্তু
    //    পুরো codebase-এ কোথাও call হতো না। Dead code।

    /**
     * Sensor-based rotation দিয়ে ImageCapture এর targetRotation বের করে।
     * CameraX এর setTargetRotation() এ pass করতে হয়।
     */
    fun physicalOrientationToSurfaceRotation(physicalOrientation: Int): Int {
        return when {
            physicalOrientation == OrientationEventListener.ORIENTATION_UNKNOWN -> Surface.ROTATION_0
            physicalOrientation <= 45 || physicalOrientation > 315  -> Surface.ROTATION_0
            physicalOrientation in 46..135                          -> Surface.ROTATION_90
            physicalOrientation in 136..225                         -> Surface.ROTATION_180
            physicalOrientation in 226..315                         -> Surface.ROTATION_270
            else                                                    -> Surface.ROTATION_0
        }
    }

    fun rotateBitmap(bitmap: Bitmap, degrees: Int, flipHorizontal: Boolean = false): Bitmap {
        if (degrees == 0 && !flipHorizontal) return bitmap
        return try {
            val matrix = Matrix()
            if (degrees != 0) matrix.postRotate(degrees.toFloat())
            if (flipHorizontal) matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap
        }
    }
}
