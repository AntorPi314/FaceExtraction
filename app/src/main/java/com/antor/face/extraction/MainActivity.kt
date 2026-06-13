package com.antor.face.extraction

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.antor.face.extraction.service.FaceCaptureService
import com.antor.face.extraction.ui.MainScreen
import com.antor.face.extraction.ui.theme.FaceExtractionTheme
import com.antor.face.extraction.utils.AppSettings
import com.antor.face.extraction.utils.FileManager
import com.antor.face.extraction.utils.ImageUtils
import com.antor.face.extraction.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// ✅ ARCHITECTURE CHANGE:
// Camera এখন সম্পূর্ণ MainActivity তে — Android background camera restriction নেই
// Service শুধু face processing + web server + countdown করে
// MainActivity → camera চালায়, frame তোলে → Service কে processing এর জন্য পাঠায়
//
// ✅ ROTATION CHANGE:
// Auto rotation (OrientationEventListener) সম্পূর্ণ বাদ দেওয়া হয়েছে।
// User Camera panel এর rotate icon দিয়ে manually 0→90→180→270 rotate করবে।
// সেই manualRotationDegrees ব্যবহার করে bitmap rotate করা হয়।

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // ── UI state ──────────────────────────────────────────────────────────────
    private val _isRunning = mutableStateOf(false)
    private val _isServerOnly = mutableStateOf(false)
    private val _logMessages = mutableStateListOf<String>()
    private val _maleCount = mutableIntStateOf(0)
    private val _femaleCount = mutableIntStateOf(0)
    private val _allCount = mutableIntStateOf(0)
    private val _serverUrl = mutableStateOf("")
    private val _intervalSeconds = mutableIntStateOf(AppSettings.DEFAULT_INTERVAL)
    private val _useFrontCamera = mutableStateOf(AppSettings.DEFAULT_USE_FRONT_CAMERA)
    private val _selectedModel = mutableStateOf(AppSettings.DEFAULT_MODEL)
    private val _countdownSeconds = mutableIntStateOf(0)
    private val _liveBitmap = mutableStateOf<Bitmap?>(null)
    private val _capturedBitmap = mutableStateOf<Bitmap?>(null)

    // ✅ Manual rotation state — 0, 90, 180, 270 মধ্যে cycle করবে
    private val _manualRotationDegrees = mutableIntStateOf(0)

    // ── Camera (Activity এ রাখা হচ্ছে — background restriction নেই) ──────────
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraActive = false

    // ✅ FIX ①: activityScope সরানো হয়েছে — lifecycleScope ব্যবহার করা হচ্ছে।
    // lifecycleScope Activity destroy হলে automatically cancel হয়, leak নেই।

    // Frame rate throttle — live preview ফাস্ট রাখার জন্য কম FPS এ analyze করা হচ্ছে
    private var lastFrameTime = 0L
    private val frameIntervalMs = 200L // ~5fps — preview এর জন্য যথেষ্ট, CPU বাঁচায়

    // ✅ Preview downscale target — full-res frame কনভার্ট করলে স্লো হয়,
    // তাই ছোট সাইজে scale করে দেখানো হয়
    private val previewMaxDimension = 480

    // YUV conversion buffer — reuse করা হচ্ছে
    private val yuvBaos = ByteArrayOutputStream(64 * 1024)

    // ✅ FIX ②: nv21 ByteArray class-level এ pre-allocate করা হয়েছে।
    // 480×640 portrait target এ 460,800 bytes। প্রতি frame এ নতুন allocation বাঁচবে।
    // camera size change হলে lazy re-allocate করা হয়।
    private var nv21Buffer = ByteArray(0)
    private var nv21Width = 0
    private var nv21Height = 0

    // ✅ FIX ③: Matrix class-level এ একটাই রাখা হয়েছে।
    // প্রতি frame এ নতুন Matrix() allocate করা বন্ধ।
    private val reusableMatrix = Matrix()

    // ✅ FIX ④: BitmapFactory.Options একটাই রাখা হয়েছে।
    // inBitmap দিয়ে পুরনো bitmap reuse করা হয় — heap allocation কমে।
    private val bitmapOptions = BitmapFactory.Options()
    private var inBitmapCache: Bitmap? = null

    // ── Broadcast receivers ───────────────────────────────────────────────────

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val log = intent.getStringExtra(FaceCaptureService.EXTRA_LOG) ?: return
            _maleCount.intValue = intent.getIntExtra(FaceCaptureService.EXTRA_MALE_COUNT, 0)
            _femaleCount.intValue = intent.getIntExtra(FaceCaptureService.EXTRA_FEMALE_COUNT, 0)
            _allCount.intValue = intent.getIntExtra(FaceCaptureService.EXTRA_ALL_COUNT, 0)
            _logMessages.add(0, log)
            if (_logMessages.size > 50) _logMessages.removeAt(_logMessages.lastIndex)
        }
    }

    private val frameReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Live frame: shared memory থেকে পড়ো
            if (intent.getBooleanExtra(FaceCaptureService.EXTRA_LIVE_FRAME, false)) {
                FaceCaptureService.latestLiveFrame.get()?.let { _liveBitmap.value = it }
            }
            // Captured face: shared memory থেকে পড়ো
            if (intent.getBooleanExtra(FaceCaptureService.EXTRA_CAPTURED_FACE, false)) {
                FaceCaptureService.latestCapturedFace.get()?.let { _capturedBitmap.value = it }
            }
        }
    }

    private val countdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _countdownSeconds.intValue =
                intent.getIntExtra(FaceCaptureService.EXTRA_COUNTDOWN_SECONDS, 0)
        }
    }

    // ✅ Service যখন capture করতে বলে — camera takePicture call করো
    private val captureRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "captureRequestReceiver: triggered (isRunning=${_isRunning.value}, isServerOnly=${_isServerOnly.value}, cameraActive=$cameraActive)")
            if (_isRunning.value && !_isServerOnly.value) {
                takePictureAndSendToService()
            }
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bgColor = android.graphics.Color.parseColor("#0A0A0F")
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(bgColor),
            navigationBarStyle = SystemBarStyle.dark(bgColor)
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        _intervalSeconds.intValue = AppSettings.getCaptureInterval(this)
        _useFrontCamera.value = AppSettings.getUseFrontCamera(this)
        _selectedModel.value = AppSettings.getSelectedModel(this)
        _isRunning.value = FaceCaptureService.isRunning
        _isServerOnly.value = FaceCaptureService.isServerOnly
        _maleCount.intValue = FileManager.getMaleCount(this)
        _femaleCount.intValue = FileManager.getFemaleCount(this)
        _allCount.intValue = FileManager.getAllCount(this)
        _serverUrl.value = NetworkUtils.getServerUrl(this, AppSettings.getServerPort(this))

        setContent {
            FaceExtractionTheme {
                val isRunning by _isRunning
                val isServerOnly by _isServerOnly
                val logMessages = _logMessages.toList()
                val maleCount by _maleCount
                val femaleCount by _femaleCount
                val allCount by _allCount
                val serverUrl by _serverUrl
                val intervalSeconds by _intervalSeconds
                val useFrontCamera by _useFrontCamera
                val selectedModel by _selectedModel
                val liveBitmap by _liveBitmap
                val capturedBitmap by _capturedBitmap
                val countdownSeconds by _countdownSeconds
                val manualRotationDegrees by _manualRotationDegrees

                val galleryLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    if (uri != null) onGalleryImagePicked(uri)
                }

                MainScreen(
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                    isRunning = isRunning,
                    isServerOnly = isServerOnly,
                    logMessages = logMessages,
                    maleCount = maleCount,
                    femaleCount = femaleCount,
                    allCount = allCount,
                    serverUrl = serverUrl,
                    intervalSeconds = intervalSeconds,
                    useFrontCamera = useFrontCamera,
                    lastCapturedBitmap = capturedBitmap,
                    liveBitmap = liveBitmap,
                    countdownSeconds = countdownSeconds,
                    selectedModel = selectedModel,
                    manualRotationDegrees = manualRotationDegrees,
                    onStartStop = { toggleService() },
                    onIntervalChange = { newInterval ->
                        _intervalSeconds.intValue = newInterval
                        AppSettings.setCaptureInterval(this, newInterval)
                    },
                    onCameraToggle = { useFront ->
                        _useFrontCamera.value = useFront
                        AppSettings.setUseFrontCamera(this, useFront)
                        if (cameraActive) {
                            stopCamera()
                            startCamera()
                        }
                    },
                    onRotate = {
                        // 0 → 90 → 180 → 270 → 0 cycle
                        _manualRotationDegrees.intValue =
                            (_manualRotationDegrees.intValue + 90) % 360
                    },
                    onClearAll = {
                        FileManager.clearAll(this)
                        _maleCount.intValue = 0
                        _femaleCount.intValue = 0
                        _allCount.intValue = 0
                        _capturedBitmap.value = null
                        _logMessages.add(0, "All faces cleared")
                    },
                    onPickGalleryImage = { galleryLauncher.launch("image/*") },
                    onManualCapture = {
                        if (_isRunning.value && !_isServerOnly.value) {
                            takePictureAndSendToService()
                        }
                    },
                    onModelChange = { model ->
                        _selectedModel.value = model
                        AppSettings.setSelectedModel(this, model)
                        if (_isRunning.value) {
                            stopService()
                            _logMessages.add(
                                0,
                                "Model changed to ${if (model == "default") "Default" else model.uppercase()} — restart to apply"
                            )
                        }
                    }
                )
            }
        }
    }

    // ── Camera control ────────────────────────────────────────────────────────

    private fun startCamera() {
        val useFront = AppSettings.getUseFrontCamera(this)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            // ✅ Auto rotation বাদ — fixed ROTATION_0 use করা হচ্ছে
            // Manual rotation UI থেকে নেওয়া হবে
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build()

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(480, 640)) // portrait: width < height
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(android.view.Surface.ROTATION_0)
                .build().also { analysis ->
                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            val now = System.currentTimeMillis()
                            if (now - lastFrameTime >= frameIntervalMs) {
                                lastFrameTime = now
                                val bmp = yuv420ToBitmap(imageProxy)
                                if (bmp != null) {
                                    FaceCaptureService.latestLiveFrame.set(bmp)
                                    runOnUiThread { _liveBitmap.value = bmp }
                                }
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, selector, imageCapture, imageAnalysis)
                cameraActive = true
                Log.d(TAG, "Camera started (${if (useFront) "Front" else "Back"})")
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed: ${e.message}")
                _logMessages.add(0, "Camera failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraActive = false
        _liveBitmap.value = null
        FaceCaptureService.latestLiveFrame.set(null)
        // ✅ inBitmap cache clear করা — camera থামলে পুরনো bitmap hold করা দরকার নেই
        inBitmapCache?.recycle()
        inBitmapCache = null
    }

    // ── YUV → Bitmap conversion (preview — downscaled) ──────────────────────
    // ✅ FIX ②③④⑤ সব এখানে apply হয়েছে:
    //   ② nv21Buffer reuse (size অনুযায়ী lazy re-alloc)
    //   ③ reusableMatrix reset করে reuse
    //   ④ bitmapOptions + inBitmap reuse
    //   ⑤ front camera তে horizontal flip যোগ করা হয়েছে

    private fun yuv420ToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yPlane = imageProxy.planes[0]
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]

            val width  = imageProxy.width
            val height = imageProxy.height

            val yRowStride    = yPlane.rowStride
            val uvRowStride   = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            // ✅ FIX ②: ByteArray reuse — size바뀌면 re-allocate, 그 외엔 기존 사용
            if (nv21Width != width || nv21Height != height) {
                nv21Buffer = ByteArray(width * height * 3 / 2)
                nv21Width  = width
                nv21Height = height
            }
            val nv21 = nv21Buffer

            val yBuffer = yPlane.buffer
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }

            val uBuffer  = uPlane.buffer
            val vBuffer  = vPlane.buffer
            val uvHeight = height / 2
            val uvWidth  = width / 2
            val uvOffset = width * height

            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = uvOffset + row * width + col * 2
                    val bufPos  = row * uvRowStride + col * uvPixelStride
                    vBuffer.position(bufPos); nv21[uvIndex]     = vBuffer.get()
                    uBuffer.position(bufPos); nv21[uvIndex + 1] = uBuffer.get()
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            yuvBaos.reset()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 45, yuvBaos)
            val bytes = yuvBaos.toByteArray()

            val sampleSize = calculateInSampleSize(width, height, previewMaxDimension)

            // ✅ FIX ④: BitmapFactory.Options reuse + inBitmap
            // inBitmap থাকলে নতুন heap allocation বাদ দিয়ে পুরনো bitmap-এ overwrite করে
            bitmapOptions.inSampleSize = sampleSize
            bitmapOptions.inMutable    = true
            val candidateInBitmap = inBitmapCache
            if (candidateInBitmap != null && !candidateInBitmap.isRecycled) {
                bitmapOptions.inBitmap = candidateInBitmap
            } else {
                bitmapOptions.inBitmap = null
            }

            val rawBitmap = try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapOptions)
            } catch (e: IllegalArgumentException) {
                // inBitmap size mismatch — fallback দিয়ে নতুন allocate করো
                bitmapOptions.inBitmap = null
                inBitmapCache = null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bitmapOptions)
            } ?: return null

            // inBitmap cache আপডেট করো (এই bitmap-ই পরের frame এ reuse হবে)
            if (rawBitmap !== inBitmapCache) {
                inBitmapCache = rawBitmap
            }

            // ✅ FIX ③: reusableMatrix reset করে reuse
            // ✅ FIX ⑤: front camera তে horizontal flip যোগ করা হয়েছে
            val sensorRotation = imageProxy.imageInfo.rotationDegrees
            val useFront = AppSettings.getUseFrontCamera(this)
            val needsRotation = sensorRotation != 0
            val needsFlip     = useFront

            return if (!needsRotation && !needsFlip) {
                rawBitmap
            } else {
                reusableMatrix.reset()
                if (needsRotation) reusableMatrix.postRotate(sensorRotation.toFloat())
                if (needsFlip)     reusableMatrix.postScale(-1f, 1f, rawBitmap.width / 2f, rawBitmap.height / 2f)
                val rotated = Bitmap.createBitmap(
                    rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, reusableMatrix, true
                )
                // rawBitmap এখন inBitmapCache — recycle করবো না, পরের frame এ reuse হবে
                rotated
            }

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        var longer = maxOf(width, height)
        while (longer / 2 >= maxDimension) {
            sampleSize *= 2
            longer /= 2
        }
        return sampleSize
    }

    // ── Capture and send to service ───────────────────────────────────────────

    private fun takePictureAndSendToService() {
        val capture = imageCapture
        if (capture == null) {
            Log.e(TAG, "takePictureAndSendToService: imageCapture is null (camera not ready yet)")
            _logMessages.add(0, "Capture skipped — camera not ready")
            return
        }

        Log.d(TAG, "takePictureAndSendToService: calling takePicture()")

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    Log.d(TAG, "onCaptureSuccess")
                    // ✅ FIX ①: activityScope.launch → lifecycleScope.launch
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val bitmap = jpegImageProxyToBitmap(imageProxy)
                            imageProxy.close()
                            if (bitmap != null) {
                                // ✅ Pic তুলেই সাথে সাথে CAPTURED panel এ দেখাবে
                                // এবং Male/Female/Total 0 হবে (processing শুরুর আগে)
                                this@MainActivity.runOnUiThread {
                                    _capturedBitmap.value = bitmap
                                    _maleCount.intValue = 0
                                    _femaleCount.intValue = 0
                                    _allCount.intValue = 0
                                }
                                FaceCaptureService.pendingBitmapToProcess.set(bitmap)
                                val intent = Intent(this@MainActivity, FaceCaptureService::class.java).apply {
                                    action = FaceCaptureService.ACTION_PROCESS_BITMAP
                                }
                                startService(intent)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Capture error: ${e.message}")
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "ImageCapture failed: ${exception.message}", exception)
                    _logMessages.add(0, "Capture failed: ${exception.message}")
                }
            }
        )
    }

    private fun jpegImageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val bytes  = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null

            // ✅ Manual rotation apply করা হচ্ছে — auto sensor orientation নয়
            val rotation = _manualRotationDegrees.intValue
            val useFront = AppSettings.getUseFrontCamera(this)

            // Front camera তে horizontal flip করা হয় (mirror effect ঠিক করতে)
            ImageUtils.rotateBitmap(bitmap, rotation, flipHorizontal = useFront)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Gallery ───────────────────────────────────────────────────────────────

    private fun onGalleryImagePicked(uri: Uri) {
        if (!FaceCaptureService.isRunning) startServiceServerOnly()

        try {
            val stream: InputStream = contentResolver.openInputStream(uri) ?: return
            var originalBitmap = BitmapFactory.decodeStream(stream) ?: return
            stream.close()

            originalBitmap = ImageUtils.fixExifRotation(this, uri, originalBitmap)
            _capturedBitmap.value = originalBitmap
            // ✅ Gallery image pick করলেও count reset
            _maleCount.intValue = 0
            _femaleCount.intValue = 0
            _allCount.intValue = 0
            _logMessages.add(0, "Gallery image picked, processing...")

            // ✅ FIX ⑬: JPEG roundtrip সরানো হয়েছে।
            // আগে: Bitmap → compress JPEG → Intent extra → Service decode।
            // এখন: pendingBitmapToProcess AtomicReference দিয়ে সরাসরি pass করা হচ্ছে।
            FaceCaptureService.pendingBitmapToProcess.set(originalBitmap)
            val intent = Intent(this, FaceCaptureService::class.java).apply {
                action = FaceCaptureService.ACTION_PROCESS_BITMAP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            _logMessages.add(0, "Failed to load gallery image")
        }
    }

    // ── Service control ───────────────────────────────────────────────────────

    private fun toggleService() {
        if (_isRunning.value) stopService() else startService()
    }

    private fun startService() {
        _isRunning.value = true
        _isServerOnly.value = false
        _serverUrl.value = NetworkUtils.getServerUrl(this, AppSettings.getServerPort(this))
        _logMessages.add(0, "Starting...")

        startCamera()

        val intent = Intent(this, FaceCaptureService::class.java).apply {
            action = FaceCaptureService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun startServiceServerOnly() {
        _isRunning.value = true
        _isServerOnly.value = true
        _serverUrl.value = NetworkUtils.getServerUrl(this, AppSettings.getServerPort(this))
        _logMessages.add(0, "Server starting (gallery mode)...")
        val intent = Intent(this, FaceCaptureService::class.java).apply {
            action = FaceCaptureService.ACTION_START_SERVER_ONLY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopService() {
        _isRunning.value = false
        _isServerOnly.value = false
        _countdownSeconds.intValue = 0
        _logMessages.add(0, "Service stopped")

        stopCamera()

        val intent = Intent(this, FaceCaptureService::class.java).apply {
            action = FaceCaptureService.ACTION_STOP
        }
        startService(intent)
    }

    // ── Receiver registration ─────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        registerReceiver(statusReceiver, IntentFilter(FaceCaptureService.BROADCAST_STATUS), RECEIVER_NOT_EXPORTED)
        registerReceiver(frameReceiver, IntentFilter(FaceCaptureService.BROADCAST_FRAME), RECEIVER_NOT_EXPORTED)
        registerReceiver(countdownReceiver, IntentFilter(FaceCaptureService.BROADCAST_COUNTDOWN), RECEIVER_NOT_EXPORTED)
        registerReceiver(captureRequestReceiver, IntentFilter(FaceCaptureService.BROADCAST_REQUEST_CAPTURE), RECEIVER_NOT_EXPORTED)

        _maleCount.intValue = FileManager.getMaleCount(this)
        _femaleCount.intValue = FileManager.getFemaleCount(this)
        _allCount.intValue = FileManager.getAllCount(this)

        FaceCaptureService.latestLiveFrame.get()?.let { _liveBitmap.value = it }
        FaceCaptureService.latestCapturedFace.get()?.let { _capturedBitmap.value = it }

        if (FaceCaptureService.isRunning) {
            _isRunning.value = true
            _isServerOnly.value = FaceCaptureService.isServerOnly
            _serverUrl.value = NetworkUtils.getServerUrl(this, AppSettings.getServerPort(this))
            if (!FaceCaptureService.isServerOnly && !cameraActive) {
                startCamera()
            }
        } else if (!_isRunning.value) {
            _isRunning.value = false
            _isServerOnly.value = false
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(frameReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(countdownReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(captureRequestReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        stopCamera()
        cameraExecutor.shutdown()
        // ✅ FIX ①: lifecycleScope ব্যবহার করায় এখানে আলাদা cancel() দরকার নেই।
        // Activity destroy হলে lifecycleScope নিজেই cancel হয়।
        super.onDestroy()
    }
}
