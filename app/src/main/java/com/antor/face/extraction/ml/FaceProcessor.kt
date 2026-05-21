package com.antor.face.extraction.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class DetectedFace(
    val bitmap: Bitmap,
    val boundingBox: Rect,
    val confidence: Float
)

class FaceProcessor {

    private val detector: FaceDetector

    init {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.1f)
            .build()
        detector = FaceDetection.getClient(options)
    }

    /**
     * Bitmap থেকে সব face detect করে crop করে return করে
     */
    suspend fun detectAndCrop(bitmap: Bitmap): List<DetectedFace> =
        suspendCancellableCoroutine { continuation ->
            val image = InputImage.fromBitmap(bitmap, 0)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    val detected = faces.mapNotNull { face ->
                        cropFace(bitmap, face)
                    }
                    continuation.resume(detected)
                }
                .addOnFailureListener { e ->
                    e.printStackTrace()
                    continuation.resume(emptyList())
                }
        }

    private fun cropFace(bitmap: Bitmap, face: Face): DetectedFace? {
        return try {
            val box = face.boundingBox

            // Padding add করি যাতে face ভালো দেখায়
            val padding = (box.width() * 0.2f).toInt()
            val left = maxOf(0, box.left - padding)
            val top = maxOf(0, box.top - padding)
            val right = minOf(bitmap.width, box.right + padding)
            val bottom = minOf(bitmap.height, box.bottom + padding)

            val width = right - left
            val height = bottom - top

            if (width <= 0 || height <= 0) return null

            val cropped = Bitmap.createBitmap(bitmap, left, top, width, height)

            DetectedFace(
                bitmap = cropped,
                boundingBox = Rect(left, top, right, bottom),
                confidence = 1.0f
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun close() {
        detector.close()
    }
}
