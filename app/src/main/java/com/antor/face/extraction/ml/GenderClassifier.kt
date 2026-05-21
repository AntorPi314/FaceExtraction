package com.antor.face.extraction.ml

import android.content.Context
import android.graphics.Bitmap
import com.antor.face.extraction.utils.Gender
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * TFLite Gender Classifier
 *
 * দুটো model support করে:
 *
 * Option A (Recommended - shubham0204):
 *   File name: gender_model.tflite
 *   Download: https://github.com/shubham0204/Age-Gender_Estimation_TF-Android
 *   Google Drive: https://drive.google.com/drive/folders/13478oTfOHD9Fkf53FtLXQEXO_IlgIPP5
 *   File: model_gender_q.tflite → rename to gender_model.tflite
 *   Input: 128x128 RGB | Output: [female_prob, male_prob]
 *
 * Option B (farmaker47):
 *   File name: gender_model.tflite
 *   Download: https://drive.google.com/open?id=1IkfW_TKiXZqr2jNhFXt6ohgzQkd8-Dts
 *   Input size auto-detected from model
 *
 * Place the file at: app/src/main/assets/gender_model.tflite
 */
class GenderClassifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var inputSize = 128 // default, auto-detected from model
    private var isLoaded = false

    companion object {
        private const val MODEL_FILE = "gender_model.tflite"
    }

    fun load(): Boolean {
        return try {
            val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val mappedByteBuffer = fileChannel.map(
                FileChannel.MapMode.READ_ONLY, startOffset, declaredLength
            )
            interpreter = Interpreter(mappedByteBuffer)

            // Auto-detect input size from model
            val inputShape = interpreter!!.getInputTensor(0).shape()
            if (inputShape.size >= 3) {
                inputSize = inputShape[1] // [1, H, W, 3] → H
            }

            isLoaded = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            isLoaded = false
            false
        }
    }

    fun classify(bitmap: Bitmap): Gender {
        if (!isLoaded || interpreter == null) return Gender.UNKNOWN

        return try {
            // Resize to model input size
            val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

            // Convert to ByteBuffer (normalized 0-1)
            val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            inputBuffer.rewind()

            val pixels = IntArray(inputSize * inputSize)
            resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

            for (pixel in pixels) {
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }

            // Output: [female_prob, male_prob]
            val output = Array(1) { FloatArray(2) }
            interpreter?.run(inputBuffer, output)

            val femaleProb = output[0][0]
            val maleProb = output[0][1]

            when {
                maleProb > femaleProb -> Gender.MALE
                else -> Gender.FEMALE
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Gender.UNKNOWN
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
    }
}
