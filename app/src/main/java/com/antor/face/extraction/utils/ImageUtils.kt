package com.antor.face.extraction.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.WindowManager
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

    /**
     * Camera sensor orientation + display rotation মিলিয়ে
     * সঠিক rotation degree বের করে।
     *
     * ফোন যেভাবেই ধরুক — portrait, landscape, উল্টো —
     * সবসময় upright bitmap পাওয়া যাবে।
     */
    fun getCorrectRotation(context: Context, useFrontCamera: Boolean): Int {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val facing = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                if (useFrontCamera)
                    facing == CameraCharacteristics.LENS_FACING_FRONT
                else
                    facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull() ?: return 0

            val sensorOrientation = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            val displayDegrees = when (getDisplayRotation(context)) {
                Surface.ROTATION_0   -> 0
                Surface.ROTATION_90  -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else                 -> 0
            }

            if (useFrontCamera) {
                (sensorOrientation + displayDegrees + 360) % 360
            } else {
                (sensorOrientation - displayDegrees + 360) % 360
            }
        } catch (e: Exception) {
            e.printStackTrace()
            0
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

    @Suppress("DEPRECATION")
    private fun getDisplayRotation(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.rotation
        }
    }
}
