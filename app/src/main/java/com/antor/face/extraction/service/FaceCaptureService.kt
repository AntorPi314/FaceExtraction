package com.antor.face.extraction.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.antor.face.extraction.MainActivity
import com.antor.face.extraction.ml.FaceProcessor
import com.antor.face.extraction.ml.GenderClassifier
import com.antor.face.extraction.server.FaceWebServer
import com.antor.face.extraction.utils.AppSettings
import com.antor.face.extraction.utils.FileManager
import com.antor.face.extraction.utils.Gender
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import android.content.pm.ServiceInfo

class FaceCaptureService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "face_extraction_channel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "FaceCaptureService"

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_START_SERVER_ONLY = "ACTION_START_SERVER_ONLY"
        // ✅ FIX ⑬: ACTION_PROCESS_GALLERY সরানো হয়েছে।
        // Gallery image এখন pendingBitmapToProcess AtomicReference দিয়ে pass হয়,
        // তারপর ACTION_PROCESS_BITMAP দিয়ে trigger করা হয়।
        const val ACTION_MANUAL_CAPTURE = "ACTION_MANUAL_CAPTURE"
        const val ACTION_PROCESS_BITMAP = "ACTION_PROCESS_BITMAP"

        const val BROADCAST_STATUS = "com.antor.face.extraction.STATUS"
        const val EXTRA_LOG = "extra_log"
        const val EXTRA_MALE_COUNT = "extra_male_count"
        const val EXTRA_FEMALE_COUNT = "extra_female_count"
        const val EXTRA_ALL_COUNT = "extra_all_count"

        const val BROADCAST_FRAME = "com.antor.face.extraction.FRAME"
        const val EXTRA_LIVE_FRAME = "extra_live_frame"
        const val EXTRA_CAPTURED_FACE = "extra_captured_face"

        const val BROADCAST_COUNTDOWN = "com.antor.face.extraction.COUNTDOWN"
        const val EXTRA_COUNTDOWN_SECONDS = "extra_countdown_seconds"

        const val BROADCAST_REQUEST_CAPTURE = "com.antor.face.extraction.REQUEST_CAPTURE"

        // ✅ EXTRA_GALLERY_JPEG সরানো হয়েছে — আর Intent extra দিয়ে JPEG pass হয় না
        // const val EXTRA_GALLERY_JPEG = "extra_gallery_jpeg"  // REMOVED

        var isRunning = false
        var isServerOnly = false

        val latestLiveFrame = AtomicReference<Bitmap?>(null)
        val latestCapturedFace = AtomicReference<Bitmap?>(null)
        val pendingBitmapToProcess = AtomicReference<Bitmap?>(null)
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var captureJob: Job? = null
    private var countdownJob: Job? = null

    private lateinit var faceProcessor: FaceProcessor
    private lateinit var genderClassifier: GenderClassifier
    private var webServer: FaceWebServer? = null

    private var lastLog = ""
    private var isStopped = false

    override fun onCreate() {
        super.onCreate()
        faceProcessor = FaceProcessor()
        genderClassifier = GenderClassifier(this)
        val selectedModel = AppSettings.getSelectedModel(this)
        val modelFile = when (selectedModel) {
            "smuct"   -> GenderClassifier.MODEL_SMUCT
            "utkface" -> GenderClassifier.MODEL_UTKFACE
            else      -> GenderClassifier.MODEL_DEFAULT
        }
        genderClassifier.load(modelFile)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START_SERVER_ONLY -> startServerOnly()
            // ✅ FIX ⑬: ACTION_PROCESS_GALLERY case সরানো হয়েছে।
            // Gallery image এখন ACTION_PROCESS_BITMAP দিয়ে আসে (pendingBitmapToProcess থেকে)।
            ACTION_MANUAL_CAPTURE -> {
                if (isRunning && !isServerOnly) {
                    sendLocalBroadcast(Intent(BROADCAST_REQUEST_CAPTURE))
                }
            }
            ACTION_PROCESS_BITMAP -> {
                // ✅ "Processing..." log — bitmap receive করার সাথে সাথে দেখাবে
                log("Processing...")
                val bitmap = pendingBitmapToProcess.getAndSet(null)
                if (bitmap != null) {
                    serviceScope.launch(Dispatchers.IO) { processImage(bitmap) }
                }
            }
            else -> startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        isStopped = false
        isRunning = true
        isServerOnly = false

        startForeground(
            NOTIFICATION_ID,
            buildNotification("Running..."),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
        startWebServer()
        startCaptureLoop()
    }

    private fun startServerOnly() {
        isRunning = true
        isServerOnly = true
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Server only — pick an image"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
        startWebServer()
        log("Server started (gallery mode)")
    }

    private fun startWebServer() {
        val port = AppSettings.getServerPort(this)
        webServer = FaceWebServer(this, port)
        try {
            webServer?.start()
            log("Web server started on port $port")
        } catch (e: Exception) {
            log("Web server failed: ${e.message}")
        }
    }

    private fun startCaptureLoop(captureImmediately: Boolean = false) {
        val intervalSec = AppSettings.getCaptureInterval(this)
        val intervalMs  = intervalSec * 1000L

        if (!captureImmediately) log("Capture interval: ${intervalSec}s")

        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            var remaining = intervalSec
            while (isActive) {
                broadcastCountdown(remaining)
                delay(1000L)
                remaining = (remaining - 1).coerceAtLeast(0)
                if (remaining == 0) remaining = intervalSec
            }
        }

        captureJob?.cancel()
        captureJob = serviceScope.launch {
            if (captureImmediately) {
                sendLocalBroadcast(Intent(BROADCAST_REQUEST_CAPTURE))
                resetCountdown(intervalSec)
            }
            while (isActive) {
                delay(intervalMs)
                sendLocalBroadcast(Intent(BROADCAST_REQUEST_CAPTURE))
                resetCountdown(intervalSec)
            }
        }
    }

    private fun resetCountdown(intervalSec: Int) {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            var remaining = intervalSec
            while (isActive) {
                broadcastCountdown(remaining)
                delay(1000L)
                remaining = (remaining - 1).coerceAtLeast(0)
                if (remaining == 0) remaining = intervalSec
            }
        }
    }

    suspend fun processImage(bitmap: Bitmap) {
        FileManager.saveLastCaptured(this, bitmap)

        latestCapturedFace.set(bitmap)
        sendLocalBroadcast(Intent(BROADCAST_FRAME).putExtra(EXTRA_CAPTURED_FACE, true))

        val faces = faceProcessor.detectAndCrop(bitmap)

        // ✅ FIX ⑥: original bitmap recycle করা হচ্ছে।
        // face crop হয়ে গেলে original full-frame bitmap আর দরকার নেই।
        // latestCapturedFace-এ এটা set আছে — তাই recycle করলে UI crash হবে।
        // তাই recycle করছি না এখানে; বরং পরের processImage() call এ
        // latestCapturedFace.set() করার আগে পুরনোটা recycle করা হচ্ছে।
        val previousCapture = latestCapturedFace.getAndSet(bitmap)
        // পুরনো bitmap যদি বর্তমানের থেকে আলাদা হয় তাহলে recycle করো
        if (previousCapture !== bitmap && previousCapture != null && !previousCapture.isRecycled) {
            previousCapture.recycle()
        }

        if (faces.isEmpty()) {
            log("No faces detected")
            val totalMale   = FileManager.getMaleCount(this)
            val totalFemale = FileManager.getFemaleCount(this)
            val totalAll    = FileManager.getAllCount(this)
            broadcastStatus(totalMale, totalFemale, totalAll)
            updateNotification("No faces — Male: $totalMale  Female: $totalFemale")
            return
        }

        log("Found ${faces.size} face(s), classifying...")

        FileManager.clearCurrentSession(this)

        var maleCount    = 0
        var femaleCount  = 0
        var unknownCount = 0

        for (face in faces) {
            when (genderClassifier.classify(face.bitmap)) {
                Gender.MALE   -> { FileManager.saveFace(this, face.bitmap, Gender.MALE); maleCount++ }
                Gender.FEMALE -> { FileManager.saveFace(this, face.bitmap, Gender.FEMALE); femaleCount++ }
                Gender.UNKNOWN -> { unknownCount++; Log.d(TAG, "Face skipped — low confidence") }
            }
            // ✅ crop করা face bitmap recycle করো — saveFace() এর পরে আর দরকার নেই
            if (!face.bitmap.isRecycled) face.bitmap.recycle()
        }

        val totalMale   = FileManager.getMaleCount(this)
        val totalFemale = FileManager.getFemaleCount(this)
        val totalAll    = FileManager.getAllCount(this)

        val unknownInfo = if (unknownCount > 0) " | Skipped=$unknownCount" else ""
        log("Saved: Male=$maleCount Female=$femaleCount$unknownInfo | Total M=$totalMale F=$totalFemale All=$totalAll")
        broadcastStatus(totalMale, totalFemale, totalAll)
        updateNotification("Male: $totalMale  Female: $totalFemale  All: $totalAll")
    }

    private fun broadcastCountdown(seconds: Int) {
        sendLocalBroadcast(Intent(BROADCAST_COUNTDOWN).putExtra(EXTRA_COUNTDOWN_SECONDS, seconds))
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        lastLog = message
        broadcastLog(message)
    }

    private fun broadcastLog(message: String) {
        sendLocalBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_LOG, message)
            putExtra(EXTRA_MALE_COUNT, FileManager.getMaleCount(this@FaceCaptureService))
            putExtra(EXTRA_FEMALE_COUNT, FileManager.getFemaleCount(this@FaceCaptureService))
            putExtra(EXTRA_ALL_COUNT, FileManager.getAllCount(this@FaceCaptureService))
        })
    }

    private fun broadcastStatus(male: Int, female: Int, all: Int) {
        sendLocalBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_LOG, lastLog)
            putExtra(EXTRA_MALE_COUNT, male)
            putExtra(EXTRA_FEMALE_COUNT, female)
            putExtra(EXTRA_ALL_COUNT, all)
        })
    }

    private fun sendLocalBroadcast(intent: Intent) {
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun stopCapture() {
        if (isStopped) return
        isStopped = true
        isRunning    = false
        isServerOnly = false
        captureJob?.cancel()
        countdownJob?.cancel()
        webServer?.stop()
        latestLiveFrame.set(null)
        latestCapturedFace.set(null)
        if (::genderClassifier.isInitialized) genderClassifier.close()
        if (::faceProcessor.isInitialized) faceProcessor.close()
        serviceScope.cancel()
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Face Extraction Service", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Running face detection in background" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, FaceCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Face Extraction")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
