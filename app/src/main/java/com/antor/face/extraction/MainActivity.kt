package com.antor.face.extraction

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.antor.face.extraction.service.FaceCaptureService
import com.antor.face.extraction.ui.MainScreen
import com.antor.face.extraction.ui.theme.FaceExtractionTheme
import com.antor.face.extraction.utils.AppSettings
import com.antor.face.extraction.utils.FileManager
import com.antor.face.extraction.utils.NetworkUtils
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MainActivity : ComponentActivity() {

    private val _isRunning = mutableStateOf(false)
    private val _isServerOnly = mutableStateOf(false)
    private val _logMessages = mutableStateListOf<String>()
    private val _maleCount = mutableIntStateOf(0)
    private val _femaleCount = mutableIntStateOf(0)
    private val _allCount = mutableIntStateOf(0)
    private val _serverUrl = mutableStateOf("")
    private val _intervalSeconds = mutableIntStateOf(AppSettings.DEFAULT_INTERVAL)
    private val _useFrontCamera = mutableStateOf(AppSettings.DEFAULT_USE_FRONT_CAMERA)
    private val _countdownSeconds = mutableIntStateOf(0)

    private val _liveBitmap = mutableStateOf<Bitmap?>(null)
    private val _capturedBitmap = mutableStateOf<Bitmap?>(null)

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
            intent.getByteArrayExtra(FaceCaptureService.EXTRA_LIVE_FRAME)?.let { bytes ->
                _liveBitmap.value = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            intent.getByteArrayExtra(FaceCaptureService.EXTRA_CAPTURED_FACE)?.let { bytes ->
                _capturedBitmap.value = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }

    private val countdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _countdownSeconds.intValue =
                intent.getIntExtra(FaceCaptureService.EXTRA_COUNTDOWN_SECONDS, 0)
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
                val liveBitmap by _liveBitmap
                val capturedBitmap by _capturedBitmap
                val countdownSeconds by _countdownSeconds

                // Gallery image picker launcher
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
                    onStartStop = { toggleService() },
                    onIntervalChange = { newInterval ->
                        _intervalSeconds.intValue = newInterval
                        AppSettings.setCaptureInterval(this, newInterval)
                    },
                    onCameraToggle = { useFront ->
                        _useFrontCamera.value = useFront
                        AppSettings.setUseFrontCamera(this, useFront)
                    },
                    onClearAll = {
                        FileManager.clearAll(this)
                        _maleCount.intValue = 0
                        _femaleCount.intValue = 0
                        _allCount.intValue = 0
                        _capturedBitmap.value = null
                        _logMessages.add(0, "All faces cleared")
                    },
                    onPickGalleryImage = {
                        galleryLauncher.launch("image/*")
                    },
                    onManualCapture = {
                        val intent = Intent(this, FaceCaptureService::class.java).apply {
                            action = FaceCaptureService.ACTION_MANUAL_CAPTURE
                        }
                        startService(intent)
                    }
                )
            }
        }
    }

    // ── Gallery image picked ──────────────────────────────────────────────────

    private fun onGalleryImagePicked(uri: Uri) {
        // Ensure service (server) is running — server-only mode
        if (!FaceCaptureService.isRunning) {
            startServiceServerOnly()
        }

        // Decode URI → JPEG bytes → send to service for processing
        try {
            val stream: InputStream = contentResolver.openInputStream(uri) ?: return
            val originalBitmap = BitmapFactory.decodeStream(stream) ?: return
            stream.close()

            // Update captured preview immediately
            _capturedBitmap.value = originalBitmap
            _logMessages.add(0, "Gallery image picked, processing...")

            // Compress to JPEG bytes for IPC
            val baos = ByteArrayOutputStream()
            originalBitmap.compress(Bitmap.CompressFormat.JPEG, 92, baos)
            val jpegBytes = baos.toByteArray()

            val intent = Intent(this, FaceCaptureService::class.java).apply {
                action = FaceCaptureService.ACTION_PROCESS_GALLERY
                putExtra(FaceCaptureService.EXTRA_GALLERY_JPEG, jpegBytes)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
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
        _logMessages.add(0, "Service starting...")
        val intent = Intent(this, FaceCaptureService::class.java).apply {
            action = FaceCaptureService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun startServiceServerOnly() {
        _isRunning.value = true
        _isServerOnly.value = true
        _serverUrl.value = NetworkUtils.getServerUrl(this, AppSettings.getServerPort(this))
        _logMessages.add(0, "Server starting (gallery mode)...")
        val intent = Intent(this, FaceCaptureService::class.java).apply {
            action = FaceCaptureService.ACTION_START_SERVER_ONLY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopService() {
        _isRunning.value = false
        _isServerOnly.value = false
        _liveBitmap.value = null
        _countdownSeconds.intValue = 0
        _logMessages.add(0, "Service stopped")
        val intent = Intent(this, FaceCaptureService::class.java).apply {
            action = FaceCaptureService.ACTION_STOP
        }
        startService(intent)
    }

    // ── Receiver registration ─────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        registerReceiver(
            statusReceiver,
            IntentFilter(FaceCaptureService.BROADCAST_STATUS),
            RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            frameReceiver,
            IntentFilter(FaceCaptureService.BROADCAST_FRAME),
            RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            countdownReceiver,
            IntentFilter(FaceCaptureService.BROADCAST_COUNTDOWN),
            RECEIVER_NOT_EXPORTED
        )
        _maleCount.intValue = FileManager.getMaleCount(this)
        _femaleCount.intValue = FileManager.getFemaleCount(this)
        _allCount.intValue = FileManager.getAllCount(this)
        // Only sync running state from service if service says it IS running,
        // or if our local state also says not running — prevents stomping
        // the _isRunning=true we set just before launching the gallery picker.
        if (FaceCaptureService.isRunning) {
            _isRunning.value = true
            _isServerOnly.value = FaceCaptureService.isServerOnly
            _serverUrl.value = NetworkUtils.getServerUrl(this, AppSettings.getServerPort(this))
        } else if (!_isRunning.value) {
            // Service is stopped and we agree — sync
            _isRunning.value = false
            _isServerOnly.value = false
        }
        // If _isRunning=true but service not yet started — leave _isRunning alone;
        // the pending startForegroundService will catch up shortly.
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(frameReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(countdownReceiver) } catch (_: Exception) {}
    }
}
