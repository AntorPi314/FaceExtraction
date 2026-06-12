package com.antor.face.extraction.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.antor.face.extraction.MainActivity
import com.antor.face.extraction.ml.FaceProcessor
import com.antor.face.extraction.ml.GenderClassifier
import com.antor.face.extraction.server.FaceWebServer
import com.antor.face.extraction.utils.AppSettings
import com.antor.face.extraction.utils.FileManager
import com.antor.face.extraction.utils.Gender
import com.antor.face.extraction.utils.ImageUtils
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FaceCaptureService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "face_extraction_channel"
        const val NOTIFICATION_ID = 1001
        const val TAG = "FaceCaptureService"

        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_START_SERVER_ONLY = "ACTION_START_SERVER_ONLY"
        const val ACTION_PROCESS_GALLERY = "ACTION_PROCESS_GALLERY"
        const val ACTION_MANUAL_CAPTURE = "ACTION_MANUAL_CAPTURE"

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

        const val EXTRA_GALLERY_JPEG = "extra_gallery_jpeg"

        var isRunning = false
        var isServerOnly = false
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var captureJob: Job? = null
    private var countdownJob: Job? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private lateinit var faceProcessor: FaceProcessor
    private lateinit var genderClassifier: GenderClassifier
    private var webServer: FaceWebServer? = null

    private var lastLog = ""

    private var lastFrameTime = 0L
    private val frameIntervalMs = 66L // ~15fps

    override fun onCreate() {
        super.onCreate()
        faceProcessor = FaceProcessor()
        genderClassifier = GenderClassifier(this)
        genderClassifier.load()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START_SERVER_ONLY -> {
                startServerOnly()
            }
            ACTION_PROCESS_GALLERY -> {
                val jpeg = intent.getByteArrayExtra(EXTRA_GALLERY_JPEG)
                if (jpeg != null) {
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    if (bitmap != null) {
                        serviceScope.launch(Dispatchers.IO) { processImage(bitmap) }
                    }
                }
            }
            ACTION_MANUAL_CAPTURE -> {
                if (isRunning && !isServerOnly) triggerManualCapture()
            }
            else -> startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        isRunning = true
        isServerOnly = false
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        startWebServer()
        setupCamera()
        startPeriodicCapture()
    }

    private fun startServerOnly() {
        isRunning = true
        isServerOnly = true
        startForeground(NOTIFICATION_ID, buildNotification("Server only — pick an image"))
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

    private fun setupCamera() {
        val useFront = AppSettings.getUseFrontCamera(this)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build().also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        val now = System.currentTimeMillis()
                        if (now - lastFrameTime >= frameIntervalMs) {
                            lastFrameTime = now
                            val bmp = yuv420ToBitmap(imageProxy)
                            if (bmp != null) broadcastFrame(EXTRA_LIVE_FRAME, bmp)
                        }
                        imageProxy.close()
                    }
                }

            val cameraSelector = if (useFront)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    this,
                    cameraSelector,
                    imageCapture,
                    imageAnalysis
                )
                log("Camera ready (${if (useFront) "Front" else "Back"})")
            } catch (e: Exception) {
                log("Camera setup failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startPeriodicCapture() {
        val intervalSec = AppSettings.getCaptureInterval(this)
        val intervalMs = intervalSec * 1000L
        log("Capture interval: ${intervalSec}s")

        countdownJob = serviceScope.launch {
            var remaining = intervalSec
            while (isActive) {
                broadcastCountdown(remaining)
                delay(1000L)
                remaining--
                if (remaining < 0) remaining = intervalSec
            }
        }

        captureJob = serviceScope.launch {
            while (isActive) {
                delay(intervalMs)
                captureAndProcess()
                countdownJob?.cancel()
                countdownJob = launch {
                    var remaining = intervalSec
                    while (isActive) {
                        broadcastCountdown(remaining)
                        delay(1000L)
                        remaining--
                        if (remaining < 0) remaining = intervalSec
                    }
                }
            }
        }
    }

    private fun triggerManualCapture() {
        val intervalSec = AppSettings.getCaptureInterval(this)

        captureJob?.cancel()
        countdownJob?.cancel()

        captureJob = serviceScope.launch {
            captureAndProcess()
            countdownJob = launch {
                var remaining = intervalSec
                while (isActive) {
                    broadcastCountdown(remaining)
                    delay(1000L)
                    remaining--
                    if (remaining < 0) remaining = intervalSec
                }
            }
            val intervalMs = intervalSec * 1000L
            while (isActive) {
                delay(intervalMs)
                captureAndProcess()
                countdownJob?.cancel()
                countdownJob = launch {
                    var remaining = intervalSec
                    while (isActive) {
                        broadcastCountdown(remaining)
                        delay(1000L)
                        remaining--
                        if (remaining < 0) remaining = intervalSec
                    }
                }
            }
        }
    }

    private fun broadcastCountdown(seconds: Int) {
        val intent = Intent(BROADCAST_COUNTDOWN).apply {
            putExtra(EXTRA_COUNTDOWN_SECONDS, seconds)
        }
        sendBroadcast(intent)
    }

    private suspend fun captureAndProcess() {
        val capture = imageCapture ?: return
        log("Capturing image...")

        suspendCancellableCoroutine { continuation ->
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(imageProxy: ImageProxy) {
                        serviceScope.launch(Dispatchers.IO) {
                            try {
                                val bitmap = jpegImageProxyToBitmap(imageProxy)
                                imageProxy.close()
                                if (bitmap != null) {
                                    processImage(bitmap)
                                } else {
                                    log("Failed to decode captured image")
                                }
                            } catch (e: Exception) {
                                log("Process error: ${e.message}")
                            } finally {
                                if (continuation.isActive) {
                                    continuation.resumeWith(Result.success(Unit))
                                }
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        log("Capture failed: ${exception.message}")
                        if (continuation.isActive) {
                            continuation.resumeWith(Result.success(Unit))
                        }
                    }
                }
            )
        }
    }

    private suspend fun processImage(bitmap: Bitmap) {
        FileManager.saveLastCaptured(this, bitmap)

        val faces = faceProcessor.detectAndCrop(bitmap)

        if (faces.isEmpty()) {
            log("No faces detected")
            return
        }

        log("Found ${faces.size} face(s), classifying...")

        broadcastFrame(EXTRA_CAPTURED_FACE, bitmap)

        FileManager.clearCurrentSession(this)

        var maleCount = 0
        var femaleCount = 0
        var unknownCount = 0

        for (face in faces) {
            when (genderClassifier.classify(face.bitmap)) {
                Gender.MALE -> {
                    FileManager.saveFace(this, face.bitmap, Gender.MALE)
                    maleCount++
                }
                Gender.FEMALE -> {
                    FileManager.saveFace(this, face.bitmap, Gender.FEMALE)
                    femaleCount++
                }
                Gender.UNKNOWN -> {
                    unknownCount++
                    Log.d(TAG, "Face skipped — confidence too low")
                }
            }
        }

        val totalMale   = FileManager.getMaleCount(this)
        val totalFemale = FileManager.getFemaleCount(this)
        val totalAll    = FileManager.getAllCount(this)

        val unknownInfo = if (unknownCount > 0) " | Skipped(low conf)=$unknownCount" else ""
        log("Saved: Male=$maleCount Female=$femaleCount$unknownInfo | Total M=$totalMale F=$totalFemale All=$totalAll")
        broadcastStatus(totalMale, totalFemale, totalAll)
        updateNotification("Male: $totalMale  Female: $totalFemale  All: $totalAll")
    }

    // ✅ Sensor + display rotation দিয়ে সবসময় upright bitmap
    private fun yuv420ToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yPlane = imageProxy.planes[0]
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(
                nv21, ImageFormat.NV21,
                imageProxy.width, imageProxy.height, null
            )

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height), 70, out
            )
            val bitmap = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size()) ?: return null

            val useFront = AppSettings.getUseFrontCamera(this)
            val rotation = ImageUtils.getCorrectRotation(this, useFront)
            ImageUtils.rotateBitmap(bitmap, rotation, flipHorizontal = useFront)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ✅ Sensor + display rotation দিয়ে সবসময় upright bitmap
    private fun jpegImageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            val useFront = AppSettings.getUseFrontCamera(this)
            val rotation = ImageUtils.getCorrectRotation(this, useFront)
            ImageUtils.rotateBitmap(bitmap, rotation, flipHorizontal = useFront)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun broadcastFrame(extraKey: String, bitmap: Bitmap) {
        try {
            val maxDim = if (extraKey == EXTRA_LIVE_FRAME) 320 else 1080
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else bitmap

            val quality = if (extraKey == EXTRA_LIVE_FRAME) 55 else 95

            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val bytes = baos.toByteArray()

            val intent = Intent(BROADCAST_FRAME).apply {
                putExtra(extraKey, bytes)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        lastLog = message
        broadcastLog(message)
    }

    private fun broadcastLog(message: String) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_LOG, message)
            putExtra(EXTRA_MALE_COUNT, FileManager.getMaleCount(this@FaceCaptureService))
            putExtra(EXTRA_FEMALE_COUNT, FileManager.getFemaleCount(this@FaceCaptureService))
            putExtra(EXTRA_ALL_COUNT, FileManager.getAllCount(this@FaceCaptureService))
        }
        sendBroadcast(intent)
    }

    private fun broadcastStatus(male: Int, female: Int, all: Int) {
        val intent = Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_LOG, lastLog)
            putExtra(EXTRA_MALE_COUNT, male)
            putExtra(EXTRA_FEMALE_COUNT, female)
            putExtra(EXTRA_ALL_COUNT, all)
        }
        sendBroadcast(intent)
    }

    private fun stopCapture() {
        isRunning = false
        isServerOnly = false
        captureJob?.cancel()
        countdownJob?.cancel()
        webServer?.stop()
        cameraProvider?.unbindAll()
        if (::genderClassifier.isInitialized) genderClassifier.close()
        if (::faceProcessor.isInitialized) faceProcessor.close()
        cameraExecutor.shutdown()
        serviceScope.cancel()
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Face Extraction Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Running face detection in background" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, FaceCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Face Extraction")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getSystemService(NotificationManager::class.java)
        } else {
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        }
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
    }
}
