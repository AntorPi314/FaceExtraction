package com.antor.face.extraction.utils

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileManager {

    const val MALE_DIR = "male"
    const val FEMALE_DIR = "female"

    fun getRootDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "FaceExtraction")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getMaleDir(context: Context): File {
        val dir = File(getRootDir(context), MALE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getFemaleDir(context: Context): File {
        val dir = File(getRootDir(context), FEMALE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveFace(context: Context, bitmap: Bitmap, gender: Gender): File? {
        return try {
            val dir = if (gender == Gender.MALE) getMaleDir(context) else getFemaleDir(context)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val file = File(dir, "face_${timestamp}.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getMaleFiles(context: Context): List<File> =
        getMaleDir(context).listFiles()
            ?.filter { it.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun getFemaleFiles(context: Context): List<File> =
        getFemaleDir(context).listFiles()
            ?.filter { it.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun getMaleCount(context: Context): Int = getMaleFiles(context).size
    fun getFemaleCount(context: Context): Int = getFemaleFiles(context).size
    fun getTotalCount(context: Context): Int = getMaleCount(context) + getFemaleCount(context)

    fun clearAll(context: Context) {
        getMaleDir(context).listFiles()?.forEach { it.delete() }
        getFemaleDir(context).listFiles()?.forEach { it.delete() }
    }
}

enum class Gender { MALE, FEMALE, UNKNOWN }
