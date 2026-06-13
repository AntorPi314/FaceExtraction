package com.antor.face.extraction.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.view.OrientationEventListener
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

    // ✅ REMOVED: getCorrectRotation() — nowhere called, CameraX handles rotation via
    //             imageInfo.rotationDegrees + targetRotation (set in FaceCaptureService)
    // ✅ REMOVED: physicalOrientationToRotationDegrees() — nowhere called,
    //             physicalOrientationToSurfaceRotation() covers the only actual use case

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

    /**
     * Camera sensor orientation + physical device orientation মিলিয়ে
     * bitmap কে কতটুকু rotate করতে হবে সেই degree বের করে।
     *
     * EXIF বা CameraX এর imageInfo.rotationDegrees ব্যবহার করা হয় না —
     * সেগুলো phone বাঁকা থাকলে (flat/tilted) ভুল দেয়।
     * OrientationEventListener সরাসরি physical sensor পড়ে, তাই সবসময় accurate।
     *
     * @param context          camera sensor orientation পড়ার জন্য
     * @param physicalOrientation OrientationEventListener.onOrientationChanged() এর value
     * @param useFrontCamera   front camera হলে mirror compensation লাগে
     */
    fun calculateRotationDegrees(
        context: Context,
        physicalOrientation: Int,
        useFrontCamera: Boolean
    ): Int {
        // Physical orientation থেকে device এর actual rotation degree বের করা
        val deviceDegrees = when {
            physicalOrientation == OrientationEventListener.ORIENTATION_UNKNOWN -> 0
            physicalOrientation <= 45 || physicalOrientation > 315  -> 0   // Portrait
            physicalOrientation in 46..135                          -> 90  // Landscape (right side down)
            physicalOrientation in 136..225                         -> 180 // Upside-down portrait
            physicalOrientation in 226..315                         -> 270 // Landscape (left side down)
            else                                                    -> 0
        }

        // Camera sensor orientation পড়া (hardware এ fixed, সাধারণত 90 বা 270)
        val sensorDegrees = try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val facing = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING)
                if (useFrontCamera)
                    facing == CameraCharacteristics.LENS_FACING_FRONT
                else
                    facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
            cameraId?.let {
                cameraManager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION)
            } ?: 90
        } catch (e: Exception) {
            90 // most Android phones এ back camera sensor 90° rotated
        }

        // Final rotation calculation:
        // Front camera তে sensor mirror হয়, তাই আলাদা formula
        return if (useFrontCamera) {
            (sensorDegrees + deviceDegrees + 360) % 360
        } else {
            (sensorDegrees - deviceDegrees + 360) % 360
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
