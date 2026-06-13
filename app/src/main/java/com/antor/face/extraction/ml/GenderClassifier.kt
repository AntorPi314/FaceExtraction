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
import kotlin.math.abs

/**
 * TFLite Gender Classifier — multi-model support
 *
 * ┌─────────────────────────┬───────────────────────────────────────────────┐
 * │ Model                   │ Architecture                                  │
 * ├─────────────────────────┼───────────────────────────────────────────────┤
 * │ gender_model.tflite     │ Custom CNN (shubham0204)                       │
 * │ (DEFAULT)               │ Input:  [1, 128, 128, 3]  float32  0.0–1.0    │
 * │                         │ Output: [1, 2]  raw LeakyReLU → argmax        │
 * ├─────────────────────────┼───────────────────────────────────────────────┤
 * │ gender_smuct.tflite     │ MobileNetV2 Transfer Learning                 │
 * │ gender_utkface.tflite   │ Input:  [1, 128, 128, 3]  float32  -1.0–1.0  │
 * │ (SMUCT / UTKFACE)       │ Output: [1, 1]  sigmoid → threshold 0.5      │
 * │                         │ Labels: 0 = Male, 1 = Female                  │
 * └─────────────────────────┴───────────────────────────────────────────────┘
 */
class GenderClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var inputSize = 128
    private var isLoaded = false

    // কোন ধরনের model লোড হয়েছে সেটা track করা
    private var modelType: ModelType = ModelType.DEFAULT

    private enum class ModelType {
        DEFAULT,    // Custom CNN — normalize [0,1], output [1,2] argmax
        MOBILENET   // MobileNetV2 — preprocess_input [-1,1], output [1,1] sigmoid
    }

    companion object {
        private const val TAG = "GenderClassifier"

        // Model file names (assets folder এ রাখতে হবে)
        const val MODEL_DEFAULT = "gender_model.tflite"
        const val MODEL_SMUCT   = "gender_smuct.tflite"
        const val MODEL_UTKFACE = "gender_utkface.tflite"

        // Default model এর output indices (UTKFace: 0=male, 1=female)
        private const val IDX_MALE   = 0
        private const val IDX_FEMALE = 1

        // Default model এর minimum score gap (argmax tie-break avoid করতে)
        private const val MIN_SCORE_GAP = 0.05f

        // MobileNetV2 model এর sigmoid threshold
        private const val SIGMOID_THRESHOLD = 0.5f
    }

    fun load(modelFile: String = MODEL_DEFAULT): Boolean {
        return try {
            val afd     = context.assets.openFd(modelFile)
            val channel = FileInputStream(afd.fileDescriptor).channel
            val buffer  = channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
            interpreter = Interpreter(buffer)

            // Auto-detect input size: shape = [1, H, W, 3]
            val shape = interpreter!!.getInputTensor(0).shape()
            if (shape.size >= 3) inputSize = shape[1]

            // Model type detect করি output shape দেখে:
            //   [1, 2] → DEFAULT (argmax)
            //   [1, 1] → MOBILENET (sigmoid)
            val outputShape = interpreter!!.getOutputTensor(0).shape()
            modelType = if (outputShape.last() == 1) ModelType.MOBILENET else ModelType.DEFAULT

            Log.d(TAG, "Model loaded: $modelFile | Type: $modelType | Input: ${inputSize}×${inputSize} | OutputShape: ${outputShape.toList()}")
            isLoaded = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Model load failed ($modelFile): ${e.message}")
            isLoaded = false
            false
        }
    }

    fun classify(bitmap: Bitmap): Gender {
        if (!isLoaded || interpreter == null) return Gender.UNKNOWN

        return try {
            // 1. Resize to model's expected input size
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

            // 2. Build input buffer based on model type
            val inputBuffer = when (modelType) {
                ModelType.DEFAULT  -> buildInputDefault(resized)
                ModelType.MOBILENET -> buildInputMobileNet(resized)
            }

            // 3. Run inference based on model type
            when (modelType) {
                ModelType.DEFAULT   -> classifyDefault(inputBuffer)
                ModelType.MOBILENET -> classifyMobileNet(inputBuffer)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Classify error: ${e.message}")
            Gender.UNKNOWN
        }
    }

    // ─── DEFAULT model preprocessing ─────────────────────────────────────────
    // Normalize pixel values to [0.0, 1.0] — simple /255 normalization
    private fun buildInputDefault(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        buffer.rewind()

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R → [0,1]
            buffer.putFloat(((pixel shr 8)  and 0xFF) / 255.0f) // G → [0,1]
            buffer.putFloat(( pixel          and 0xFF) / 255.0f) // B → [0,1]
        }
        return buffer
    }

    // ─── MobileNetV2 model preprocessing ─────────────────────────────────────
    // TensorFlow preprocess_input: pixel / 127.5 - 1.0  → range [-1.0, 1.0]
    // এটা Keras এর MobileNetV2 এর জন্য required normalization
    private fun buildInputMobileNet(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        buffer.rewind()

        val pixels = IntArray(inputSize * inputSize)
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 127.5f - 1.0f) // R → [-1,1]
            buffer.putFloat(((pixel shr 8)  and 0xFF) / 127.5f - 1.0f) // G → [-1,1]
            buffer.putFloat(( pixel          and 0xFF) / 127.5f - 1.0f) // B → [-1,1]
        }
        return buffer
    }

    // ─── DEFAULT model inference ──────────────────────────────────────────────
    // Output: [1, 2] raw LeakyReLU scores → argmax decide করে
    private fun classifyDefault(inputBuffer: ByteBuffer): Gender {
        val output = Array(1) { FloatArray(2) }
        interpreter?.run(inputBuffer, output)

        val maleScore   = output[0][IDX_MALE]
        val femaleScore = output[0][IDX_FEMALE]
        val gap         = abs(maleScore - femaleScore)

        Log.d(TAG, "[DEFAULT] male=%.4f  female=%.4f  gap=%.4f".format(maleScore, femaleScore, gap))

        return when {
            gap < MIN_SCORE_GAP     -> Gender.UNKNOWN
            maleScore > femaleScore -> Gender.MALE
            else                    -> Gender.FEMALE
        }
    }

    // ─── MobileNetV2 model inference ─────────────────────────────────────────
    // Output: [1, 1] sigmoid probability → 0=Male, 1=Female, threshold=0.5
    private fun classifyMobileNet(inputBuffer: ByteBuffer): Gender {
        val output = Array(1) { FloatArray(1) }
        interpreter?.run(inputBuffer, output)

        val prob = output[0][0]  // sigmoid output: 0.0=Male, 1.0=Female

        Log.d(TAG, "[MOBILENET] sigmoid_prob=%.4f  →  %s".format(prob, if (prob >= SIGMOID_THRESHOLD) "FEMALE" else "MALE"))

        return if (prob >= SIGMOID_THRESHOLD) Gender.FEMALE else Gender.MALE
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isLoaded    = false
    }
}
