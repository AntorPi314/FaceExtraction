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
    const val ALL_DIR = "all"
    const val ALL_MALE_DIR = "all/male"
    const val ALL_FEMALE_DIR = "all/female"
    const val CAPTURED_FILENAME = "captured.jpg"

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

    fun getAllDir(context: Context): File {
        val dir = File(getRootDir(context), ALL_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getAllMaleDir(context: Context): File {
        val dir = File(getRootDir(context), ALL_MALE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getAllFemaleDir(context: Context): File {
        val dir = File(getRootDir(context), ALL_FEMALE_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Last captured full-frame image (always overwritten) */
    fun getCapturedFile(context: Context): File =
        File(getRootDir(context), CAPTURED_FILENAME)

    /** Save the last captured frame — overwrites previous */
    fun saveLastCaptured(context: Context, bitmap: Bitmap) {
        try {
            val file = getCapturedFile(context)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveFace(context: Context, bitmap: Bitmap, gender: Gender): File? {
        return try {
            val dir = if (gender == Gender.MALE) getMaleDir(context) else getFemaleDir(context)
            val allGenderDir = if (gender == Gender.MALE) getAllMaleDir(context) else getAllFemaleDir(context)

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date())
            val filename = "face_${timestamp}.jpg"

            // Save to current session gender folder (male/ or female/)
            val genderFile = File(dir, filename)
            FileOutputStream(genderFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            // Save a copy to all/male/ or all/female/ (cumulative history)
            val allGenderFile = File(allGenderDir, filename)
            FileOutputStream(allGenderFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            genderFile
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

    fun getAllMaleFiles(context: Context): List<File> =
        getAllMaleDir(context).listFiles()
            ?.filter { it.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun getAllFemaleFiles(context: Context): List<File> =
        getAllFemaleDir(context).listFiles()
            ?.filter { it.extension == "jpg" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun getAllFiles(context: Context): List<File> =
        (getAllMaleFiles(context) + getAllFemaleFiles(context))
            .sortedByDescending { it.lastModified() }

    fun getMaleCount(context: Context): Int = getMaleFiles(context).size
    fun getFemaleCount(context: Context): Int = getFemaleFiles(context).size
    fun getAllMaleCount(context: Context): Int = getAllMaleFiles(context).size
    fun getAllFemaleCount(context: Context): Int = getAllFemaleFiles(context).size
    fun getAllCount(context: Context): Int = getAllFiles(context).size
    fun getTotalCount(context: Context): Int = getMaleCount(context) + getFemaleCount(context)

    /**
     * Called on user "Clear All" action — deletes everything including cumulative all/ history.
     */
    fun clearAll(context: Context) {
        getMaleDir(context).listFiles()?.forEach { it.delete() }
        getFemaleDir(context).listFiles()?.forEach { it.delete() }
        getAllMaleDir(context).listFiles()?.forEach { it.delete() }
        getAllFemaleDir(context).listFiles()?.forEach { it.delete() }
        getCapturedFile(context).takeIf { it.exists() }?.delete()
    }

    /**
     * Called before saving new faces in a capture session.
     * Clears only male/female (current session) — all/male/ and all/female/ are NEVER
     * cleared here, so getAllCount() grows cumulatively across all captures.
     */
    fun clearCurrentSession(context: Context) {
        getMaleDir(context).listFiles()?.forEach { it.delete() }
        getFemaleDir(context).listFiles()?.forEach { it.delete() }
    }
}

enum class Gender { MALE, FEMALE, UNKNOWN }
