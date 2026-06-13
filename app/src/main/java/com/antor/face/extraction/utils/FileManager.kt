package com.antor.face.extraction.utils

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object FileManager {

    const val MALE_DIR = "male"
    const val FEMALE_DIR = "female"
    const val ALL_DIR = "all"
    const val ALL_MALE_DIR = "all/male"
    const val ALL_FEMALE_DIR = "all/female"
    const val CAPTURED_FILENAME = "captured.jpg"

    // ✅ FIX ⑩: count cache — প্রতি call এ listFiles() করা বন্ধ।
    // save/clear এ increment/decrement করা হয়, -1 মানে "dirty" (re-read দরকার)।
    private val cachedMaleCount    = AtomicInteger(-1)
    private val cachedFemaleCount  = AtomicInteger(-1)
    private val cachedAllMaleCount = AtomicInteger(-1)
    private val cachedAllFemaleCount = AtomicInteger(-1)

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

            // ✅ FIX ⑨: double compress সরানো হয়েছে।
            // আগে: gender/ এবং all/gender/ দুইটাতে আলাদা compress + write।
            // এখন: gender/ তে একবার compress করে write, তারপর all/gender/ তে file copy।
            val genderFile = File(dir, filename)
            FileOutputStream(genderFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            // File copy — bitmap আর compress করা হচ্ছে না
            val allGenderFile = File(allGenderDir, filename)
            FileInputStream(genderFile).use { src ->
                FileOutputStream(allGenderFile).use { dst ->
                    src.copyTo(dst)
                }
            }

            // ✅ FIX ⑩: cache increment
            if (gender == Gender.MALE) {
                cachedMaleCount.updateAndGet { if (it < 0) -1 else it + 1 }
                cachedAllMaleCount.updateAndGet { if (it < 0) -1 else it + 1 }
            } else {
                cachedFemaleCount.updateAndGet { if (it < 0) -1 else it + 1 }
                cachedAllFemaleCount.updateAndGet { if (it < 0) -1 else it + 1 }
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

    // ✅ FIX ⑩: count গুলো cache থেকে পড়ছে, dirty হলে disk থেকে re-read
    fun getMaleCount(context: Context): Int {
        val cached = cachedMaleCount.get()
        if (cached >= 0) return cached
        val count = getMaleFiles(context).size
        cachedMaleCount.set(count)
        return count
    }

    fun getFemaleCount(context: Context): Int {
        val cached = cachedFemaleCount.get()
        if (cached >= 0) return cached
        val count = getFemaleFiles(context).size
        cachedFemaleCount.set(count)
        return count
    }

    fun getAllMaleCount(context: Context): Int {
        val cached = cachedAllMaleCount.get()
        if (cached >= 0) return cached
        val count = getAllMaleFiles(context).size
        cachedAllMaleCount.set(count)
        return count
    }

    fun getAllFemaleCount(context: Context): Int {
        val cached = cachedAllFemaleCount.get()
        if (cached >= 0) return cached
        val count = getAllFemaleFiles(context).size
        cachedAllFemaleCount.set(count)
        return count
    }

    fun getAllCount(context: Context): Int = getAllMaleCount(context) + getAllFemaleCount(context)

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
        // ✅ FIX ⑩: cache reset
        cachedMaleCount.set(0)
        cachedFemaleCount.set(0)
        cachedAllMaleCount.set(0)
        cachedAllFemaleCount.set(0)
    }

    /**
     * Called before saving new faces in a capture session.
     * Clears only male/female (current session) — all/male/ and all/female/ are NEVER
     * cleared here, so getAllCount() grows cumulatively across all captures.
     */
    fun clearCurrentSession(context: Context) {
        getMaleDir(context).listFiles()?.forEach { it.delete() }
        getFemaleDir(context).listFiles()?.forEach { it.delete() }
        // ✅ FIX ⑩: session cache reset (all/ counts অপরিবর্তিত)
        cachedMaleCount.set(0)
        cachedFemaleCount.set(0)
    }
}

enum class Gender { MALE, FEMALE, UNKNOWN }
