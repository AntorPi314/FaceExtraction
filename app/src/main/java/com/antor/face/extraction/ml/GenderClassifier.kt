package com.antor.face.extraction.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.antor.face.extraction.utils.Gender
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * TFLite Gender Classifier — shubham0204/Age-Gender_Estimation_TF-Android
 *
 * Model architecture (confirmed by binary inspection):
 *   Input:  [1, 128, 128, 3]  — float32, normalized 0.0–1.0
 *   Layers: Conv2D × 5 + BatchNorm + LeakyReLU + MaxPool → Flatten → Dense × 4 + LeakyReLU
 *   Output: [1, 2]  — raw LeakyReLU activations (NOT softmax probabilities!)
 *           index 0 = male score   (UTKFace label: 0 = male)
 *           index 1 = female score (UTKFace label: 1 = female)
 *
 * ⚠️ IMPORTANT: Last activation is LeakyReLU, NOT Softmax.
 *    So output values are NOT bounded to [0,1] and do NOT sum to 1.
 *    We must use argmax (which index is larger) — NOT a probability threshold.
 *
 * Place the model at: app/src/main/assets/gender_model.tflite
 */
class GenderClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var inputSize = 128
    private var isLoaded = false

    companion object {
        private const val TAG = "GenderClassifier"
        private const val MODEL_FILE = "gender_model.tflite"

        // UTKFace dataset: 0 = male, 1 = female
        private const val IDX_MALE = 0
        private const val IDX_FEMALE = 1

        // Minimum score gap to avoid near-tie ambiguity (tunable)
        // যদি male_score এবং female_score এর পার্থক্য এর চেয়ে কম হয় → UNKNOWN
        // 0.0f = সবসময় decision নেবে (tie-break ও করবে)
        // 0.05f = কমপক্ষে 0.05 gap না থাকলে UNKNOWN
        private const val MIN_SCORE_GAP = 0.05f
    }

    fun load(): Boolean {
        return try {
            val afd = context.assets.openFd(MODEL_FILE)
            val channel = FileInputStream(afd.fileDescriptor).channel
            val buffer = channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
            interpreter = Interpreter(buffer)

            // Auto-detect input size: shape = [1, H, W, 3]
            val shape = interpreter!!.getInputTensor(0).shape()
            if (shape.size >= 3) inputSize = shape[1]

            Log.d(TAG, "Model loaded. Input: ${inputSize}×${inputSize} | Output: raw LeakyReLU (argmax)")
            isLoaded = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed: ${e.message}")
            isLoaded = false
            false
        }
    }

    fun classify(bitmap: Bitmap): Gender {
        if (!isLoaded || interpreter == null) return Gender.UNKNOWN

        return try {
            // 1. Resize
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

            // 2. Normalize pixels to [0.0, 1.0] — float32
            val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            inputBuffer.rewind()

            val pixels = IntArray(inputSize * inputSize)
            resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

            for (pixel in pixels) {
                inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
                inputBuffer.putFloat(((pixel shr 8)  and 0xFF) / 255.0f) // G
                inputBuffer.putFloat(( pixel          and 0xFF) / 255.0f) // B
            }

            // 3. Run inference
            // Output is raw LeakyReLU activation — NOT softmax probabilities
            val output = Array(1) { FloatArray(2) }
            interpreter?.run(inputBuffer, output)

            val maleScore   = output[0][IDX_MALE]
            val femaleScore = output[0][IDX_FEMALE]
            val gap = Math.abs(maleScore - femaleScore)

            // Debug log — result ঠিক হলে এই line remove করুন
            Log.d(TAG, "male_score=${"%.4f".format(maleScore)}  female_score=${"%.4f".format(femaleScore)}  gap=${"%.4f".format(gap)}")

            // 4. Argmax + gap check
            when {
                gap < MIN_SCORE_GAP          -> Gender.UNKNOWN  // too close to call
                maleScore > femaleScore      -> Gender.MALE
                else                         -> Gender.FEMALE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Classify error: ${e.message}")
            Gender.UNKNOWN
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }
}
