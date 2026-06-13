package com.antor.face.extraction.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

// ─────────────────────────────────────────────────────────────
//  Design Tokens  (border-free, color-matched dark theme)
// ─────────────────────────────────────────────────────────────
private val BG           = Color(0xFF0A0A0F)
private val SURFACE      = Color(0xFF111118)
private val SURFACE2     = Color(0xFF161620)
private val MALE         = Color(0xFF4A90E2)
private val FEMALE       = Color(0xFFE24A90)
private val TOTAL        = Color(0xFF9A6AE2)
private val ALL          = Color(0xFF4AE2A0)
private val LIVE         = Color(0xFF4CAF50)
private val GALLERY_CLR  = Color(0xFFE2C44A)
private val IDLE         = Color(0xFF555566)
private val DIM          = Color(0xFF333344)
private val DIM2         = Color(0xFF2A2A38)
private val LABEL_BG     = Color(0xCC0A0A0F)
private val ERR          = Color(0xFFE24A4A)

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    isRunning: Boolean,
    isServerOnly: Boolean,
    logMessages: List<String>,
    maleCount: Int,
    femaleCount: Int,
    allCount: Int,
    serverUrl: String,
    intervalSeconds: Int,
    useFrontCamera: Boolean,
    lastCapturedBitmap: Bitmap?,
    liveBitmap: Bitmap?,
    countdownSeconds: Int,
    selectedModel: String,
    manualRotationDegrees: Int,
    onStartStop: () -> Unit,
    onIntervalChange: (Int) -> Unit,
    onCameraToggle: (Boolean) -> Unit,
    onRotate: () -> Unit,
    onClearAll: () -> Unit,
    onPickGalleryImage: () -> Unit,
    onManualCapture: () -> Unit,
    onModelChange: (String) -> Unit,
) {
    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    val permissionState = rememberMultiplePermissionsState(permissions)

    Box(modifier = modifier.background(BG)) {
        if (!permissionState.allPermissionsGranted) {
            PermissionScreen(onRequest = { permissionState.launchMultiplePermissionRequest() })
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
                    .padding(top = 16.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppHeader(
                    isRunning       = isRunning,
                    isServerOnly    = isServerOnly,
                    countdownSeconds = countdownSeconds,
                    intervalSeconds = intervalSeconds,
                    selectedModel   = selectedModel,
                    onModelChange   = onModelChange
                )

                CameraPreviewRow(
                    isRunning           = isRunning,
                    isServerOnly        = isServerOnly,
                    lastCapturedBitmap  = lastCapturedBitmap,
                    liveBitmap          = liveBitmap,
                    manualRotationDegrees = manualRotationDegrees,
                    onLongPressCaptured = onPickGalleryImage,
                    onManualCapture     = onManualCapture,
                    onRotate            = onRotate,
                    modifier            = Modifier.fillMaxWidth().weight(1f)
                )

                StatsAndServerRow(
                    maleCount   = maleCount,
                    femaleCount = femaleCount,
                    allCount    = allCount,
                    serverUrl   = serverUrl,
                    isRunning   = isRunning
                )

                SettingsRow(
                    isRunning       = isRunning,
                    intervalSeconds = intervalSeconds,
                    useFrontCamera  = useFrontCamera,
                    onIntervalChange = onIntervalChange,
                    onCameraToggle  = onCameraToggle
                )

                BottomRow(
                    isRunning    = isRunning,
                    logMessages  = logMessages,
                    onStartStop  = onStartStop,
                    onClearAll   = onClearAll
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  AppHeader
// ─────────────────────────────────────────────────────────────
@Composable
fun AppHeader(
    isRunning: Boolean,
    isServerOnly: Boolean,
    countdownSeconds: Int,
    intervalSeconds: Int,
    selectedModel: String,
    onModelChange: (String) -> Unit
) {
    var showModelDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Face Extraction",
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "Detect · Classify · Serve",
                fontSize = 11.sp,
                color = IDLE,
                letterSpacing = 1.5.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = { showModelDialog = true },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Model Settings",
                    tint = IDLE,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Status pill — no border
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = when {
                    isRunning && isServerOnly -> Color(0xFF1A1A0A)
                    isRunning                 -> Color(0xFF0D2B0D)
                    else                      -> SURFACE
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "dot")
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue  = if (isRunning) 0.25f else 1f,
                        animationSpec = infiniteRepeatable(
                            animation  = tween(900, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dotAlpha"
                    )
                    val dotColor = when {
                        isRunning && isServerOnly -> GALLERY_CLR
                        isRunning                 -> LIVE
                        else                      -> Color(0xFF444455)
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(dotColor.copy(alpha = if (isRunning) dotAlpha else 1f))
                    )
                    Text(
                        text = when {
                            isRunning && isServerOnly -> "SERVER"
                            isRunning                 -> "LIVE"
                            else                      -> "IDLE"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isRunning && isServerOnly -> GALLERY_CLR
                            isRunning                 -> LIVE
                            else                      -> IDLE
                        },
                        letterSpacing = 1.sp
                    )

                    if (isRunning && !isServerOnly && intervalSeconds > 0) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(12.dp)
                                .background(Color(0xFF2A5A2A))
                        )
                        CountdownBadge(
                            secondsRemaining = countdownSeconds,
                            totalSeconds     = intervalSeconds
                        )
                    }
                }
            }
        }
    }

    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            containerColor   = Color(0xFF1A1A22),
            title  = { Text("Select Model", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text   = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "default" to "Default",
                        "smuct"   to "SMUCT",
                        "utkface" to "UTKFace"
                    ).forEach { (value, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onModelChange(value); showModelDialog = false }
                                .padding(vertical = 8.dp)
                        ) {
                            RadioButton(
                                selected = selectedModel == value,
                                onClick  = { onModelChange(value); showModelDialog = false },
                                colors   = RadioButtonDefaults.colors(selectedColor = MALE)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(label, color = Color.White, fontSize = 14.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showModelDialog = false },
                    colors  = ButtonDefaults.buttonColors(containerColor = SURFACE2)
                ) { Text("Close", color = Color.White) }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  CountdownBadge
// ─────────────────────────────────────────────────────────────
@Composable
fun CountdownBadge(secondsRemaining: Int, totalSeconds: Int) {
    val progress   = if (totalSeconds > 0) secondsRemaining.toFloat() / totalSeconds else 0f
    val badgeColor = when {
        progress > 0.5f  -> LIVE
        progress > 0.25f -> Color(0xFFFFB300)
        else             -> ERR
    }

    Row(
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(18.dp)) {
            val stroke   = 2.5f
            val diameter = size.minDimension - stroke
            val topLeft  = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2)
            val arcSize  = androidx.compose.ui.geometry.Size(diameter, diameter)
            drawArc(color = Color(0xFF2A3A2A), startAngle = -90f, sweepAngle = 360f,
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
            drawArc(color = badgeColor, startAngle = -90f, sweepAngle = 360f * progress,
                useCenter = false, topLeft = topLeft, size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
        }
        Text(
            text = "${secondsRemaining}s",
            fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = badgeColor, fontFamily = FontFamily.Monospace, letterSpacing = 0.sp
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  CameraPreviewRow
// ─────────────────────────────────────────────────────────────
@Composable
fun CameraPreviewRow(
    isRunning: Boolean,
    isServerOnly: Boolean,
    lastCapturedBitmap: Bitmap?,
    liveBitmap: Bitmap?,
    manualRotationDegrees: Int,
    onLongPressCaptured: () -> Unit,
    onManualCapture: () -> Unit,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PreviewPanel(
            modifier    = Modifier.weight(1f).fillMaxHeight(),
            label       = "CAPTURED",
            labelColor  = MALE,
            bitmap      = lastCapturedBitmap,
            bitmapRotationDegrees = 0f,
            emptyIcon   = Icons.Default.Face,
            emptyText   = "Long-press to pick",
            emptySubText = "from Gallery",
            imageContentScale = ContentScale.Fit,
            onLongPress = onLongPressCaptured
        )

        LiveCameraPanel(
            modifier    = Modifier.weight(1f).fillMaxHeight(),
            isRunning   = isRunning,
            isServerOnly = isServerOnly,
            liveBitmap  = liveBitmap,
            manualRotationDegrees = manualRotationDegrees,
            onManualCapture = onManualCapture,
            onRotate    = onRotate
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  LiveCameraPanel  (no border)
// ─────────────────────────────────────────────────────────────
@Composable
fun LiveCameraPanel(
    modifier: Modifier = Modifier,
    isRunning: Boolean,
    isServerOnly: Boolean,
    liveBitmap: Bitmap?,
    manualRotationDegrees: Int,
    onManualCapture: () -> Unit,
    onRotate: () -> Unit
) {
    val label = when {
        isRunning && !isServerOnly -> "LIVE"
        isRunning && isServerOnly  -> "GALLERY"
        else                       -> "CAMERA"
    }
    val labelColor = when {
        isRunning && !isServerOnly -> LIVE
        isRunning && isServerOnly  -> GALLERY_CLR
        else                       -> IDLE
    }
    // Panel background subtly tinted to match state
    val panelColor = when {
        isRunning && !isServerOnly -> Color(0xFF0E140E)
        isRunning && isServerOnly  -> Color(0xFF14140A)
        else                       -> SURFACE
    }

    val deg              = manualRotationDegrees % 360
    val indicatorAtTop   = deg == 0
    val indicatorAtLeft  = deg == 90
    val indicatorAtBottom = deg == 180
    val indicatorAtRight = deg == 270

    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(16.dp),
        color    = panelColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isRunning && !isServerOnly)
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(onTap = { onManualCapture() })
                        }
                    else Modifier
                )
        ) {
            if (liveBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = liveBitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        if (isServerOnly) Icons.Default.PhotoLibrary else Icons.Default.Videocam,
                        contentDescription = null,
                        tint = DIM,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when {
                            isServerOnly -> "Gallery mode"
                            isRunning    -> "Tap to capture"
                            else         -> "Press Start"
                        },
                        fontSize = 11.sp, color = DIM, letterSpacing = 0.5.sp, textAlign = TextAlign.Center
                    )
                    if (isServerOnly) {
                        Text(
                            text = "Long-press Captured →",
                            fontSize = 9.sp, color = DIM2, letterSpacing = 0.3.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // Yellow TOP edge indicator
            val indColor = Color(0xFFFFD700)
            if (indicatorAtTop)
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).background(indColor))
            if (indicatorAtBottom)
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(indColor))
            if (indicatorAtLeft)
                Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(2.dp).background(indColor))
            if (indicatorAtRight)
                Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(2.dp).background(indColor))

            // Label badge (bottom left)
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                shape    = RoundedCornerShape(6.dp),
                color    = LABEL_BG
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    color = labelColor, letterSpacing = 1.sp
                )
            }

            // Rotate button (bottom right) — no border
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clickable { onRotate() },
                shape = RoundedCornerShape(8.dp),
                color = Color(0xDD111122)
            ) {
                Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Icon(
                        Icons.Default.RotateRight,
                        contentDescription = "Rotate",
                        tint = Color(0xFF9090CC),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Tap hint (top right) — running only
            if (isRunning && !isServerOnly) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape    = RoundedCornerShape(6.dp),
                    color    = LABEL_BG
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, tint = LIVE, modifier = Modifier.size(10.dp))
                        Text("TAP", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = LIVE, letterSpacing = 0.8.sp)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  PreviewPanel  (no border)
// ─────────────────────────────────────────────────────────────
@Composable
fun PreviewPanel(
    modifier: Modifier = Modifier,
    label: String,
    labelColor: Color,
    bitmap: Bitmap?,
    bitmapRotationDegrees: Float = 0f,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyText: String,
    emptySubText: String? = null,
    imageContentScale: ContentScale = ContentScale.Fit,
    showTapHint: Boolean = false,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier,
        shape    = RoundedCornerShape(16.dp),
        color    = SURFACE
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (onTap != null || onLongPress != null)
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onTap       = { onTap?.invoke() },
                                onLongPress = { onLongPress?.invoke() }
                            )
                        }
                    else Modifier
                )
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .graphicsLayer { rotationZ = bitmapRotationDegrees },
                    contentScale = imageContentScale
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(emptyIcon, contentDescription = null, tint = Color(0xFF2A2A40), modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(emptyText, fontSize = 11.sp, color = DIM, letterSpacing = 0.5.sp, textAlign = TextAlign.Center)
                    if (emptySubText != null)
                        Text(emptySubText, fontSize = 9.sp, color = DIM2, letterSpacing = 0.3.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                }
            }

            // Label badge
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                shape    = RoundedCornerShape(6.dp),
                color    = LABEL_BG
            ) {
                Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 9.sp, fontWeight = FontWeight.Bold, color = labelColor, letterSpacing = 1.sp)
            }

            // Long-press indicator on CAPTURED panel
            if (onLongPress != null) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape    = RoundedCornerShape(6.dp),
                    color    = LABEL_BG
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = MALE, modifier = Modifier.size(10.dp))
                        Text("HOLD", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = MALE, letterSpacing = 0.8.sp)
                    }
                }
            }

            // Tap-to-capture indicator
            if (showTapHint) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                    shape    = RoundedCornerShape(6.dp),
                    color    = LABEL_BG
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, tint = LIVE, modifier = Modifier.size(10.dp))
                        Text("TAP", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = LIVE, letterSpacing = 0.8.sp)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  StatsAndServerRow  (no borders on chips)
// ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StatsAndServerRow(
    maleCount: Int,
    femaleCount: Int,
    allCount: Int,
    serverUrl: String,
    isRunning: Boolean
) {
    val clipboardManager = LocalClipboardManager.current
    val context          = LocalContext.current

    fun copyAndToast(text: String) {
        clipboardManager.setText(AnnotatedString(text))
        Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
    }
    fun openInBrowser(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { StatChip(label = "Male",   value = maleCount.toString(),                   color = MALE) }
        item { StatChip(label = "Female", value = femaleCount.toString(),                 color = FEMALE) }
        item { StatChip(label = "Total",  value = (maleCount + femaleCount).toString(),   color = TOTAL) }
        item { StatChip(label = "All",    value = allCount.toString(),                    color = ALL) }

        if (isRunning && serverUrl.isNotEmpty()) {
            item {
                Surface(
                    shape    = RoundedCornerShape(10.dp),
                    color    = SURFACE,
                    modifier = Modifier.combinedClickable(
                        onClick     = { copyAndToast(serverUrl) },
                        onLongClick = { openInBrowser(serverUrl) }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = MALE, modifier = Modifier.size(14.dp))
                        Text(serverUrl.removePrefix("http://"), fontSize = 11.sp, color = MALE, fontFamily = FontFamily.Monospace)
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = DIM, modifier = Modifier.size(12.dp))
                    }
                }
            }
            item {
                val peopleUrl = "$serverUrl/people.json"
                Surface(
                    shape    = RoundedCornerShape(10.dp),
                    color    = SURFACE,
                    modifier = Modifier.combinedClickable(
                        onClick     = { copyAndToast(peopleUrl) },
                        onLongClick = { openInBrowser(peopleUrl) }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("people.json", fontSize = 11.sp, color = ALL, fontFamily = FontFamily.Monospace)
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = DIM, modifier = Modifier.size(11.dp))
                    }
                }
            }
            item {
                val allUrl = "$serverUrl/all.json"
                Surface(
                    shape    = RoundedCornerShape(10.dp),
                    color    = SURFACE,
                    modifier = Modifier.combinedClickable(
                        onClick     = { copyAndToast(allUrl) },
                        onLongClick = { openInBrowser(allUrl) }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("all.json", fontSize = 11.sp, color = TOTAL, fontFamily = FontFamily.Monospace)
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = DIM, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  StatChip  (no border — color fills background subtly)
// ─────────────────────────────────────────────────────────────
@Composable
fun StatChip(label: String, value: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.10f)   // tinted background matches accent color
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(text = label, fontSize = 10.sp, color = IDLE, letterSpacing = 0.5.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  SettingsRow  (no border)
// ─────────────────────────────────────────────────────────────
@Composable
fun SettingsRow(
    isRunning: Boolean,
    intervalSeconds: Int,
    useFrontCamera: Boolean,
    onIntervalChange: (Int) -> Unit,
    onCameraToggle: (Boolean) -> Unit
) {
    var intervalText by remember(intervalSeconds) { mutableStateOf(intervalSeconds.toString()) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        color    = SURFACE
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "INTERVAL (sec)", fontSize = 9.sp, color = Color(0xFF444455), letterSpacing = 1.sp)
                OutlinedTextField(
                    value    = intervalText,
                    onValueChange = { v ->
                        if (!isRunning) {
                            intervalText = v.filter { it.isDigit() }.take(4)
                            intervalText.toIntOrNull()?.let { num ->
                                if (num in 1..9999) onIntervalChange(num)
                            }
                        }
                    },
                    enabled        = !isRunning,
                    singleLine     = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        color = Color.White, fontFamily = FontFamily.Monospace
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = MALE,
                        unfocusedBorderColor = Color(0xFF2A2A40),
                        disabledBorderColor  = Color(0xFF1A1A28),
                        disabledTextColor    = Color(0xFF666677),
                        cursorColor          = MALE
                    ),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape    = RoundedCornerShape(8.dp)
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(50.dp)
                    .background(Color(0xFF2A2A3A))
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "CAMERA", fontSize = 9.sp, color = Color(0xFF444455), letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CamToggleButton("Back",  !useFrontCamera, !isRunning) { onCameraToggle(false) }
                    CamToggleButton("Front",  useFrontCamera, !isRunning) { onCameraToggle(true) }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  CamToggleButton  (no border — color fills selected state)
// ─────────────────────────────────────────────────────────────
@Composable
fun CamToggleButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape  = RoundedCornerShape(7.dp),
        color  = if (selected) MALE else Color(0xFF0F0F18),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text  = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else IDLE
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  BottomRow  (no border on log surface)
// ─────────────────────────────────────────────────────────────
@Composable
fun BottomRow(
    isRunning: Boolean,
    logMessages: List<String>,
    onStartStop: () -> Unit,
    onClearAll: () -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick  = onStartStop,
            modifier = Modifier.height(46.dp).width(110.dp),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFF8B2020) else Color(0xFF1E4A8A)
            )
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isRunning) "Stop" else "Start",
                fontWeight = FontWeight.Bold, fontSize = 14.sp
            )
        }

        // Clear button — no border, color-only
        Surface(
            shape    = RoundedCornerShape(12.dp),
            color    = Color(0xFF1A1A22),
            modifier = Modifier
                .height(46.dp)
                .clickable { showClearDialog = true }
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = IDLE, modifier = Modifier.size(18.dp))
            }
        }

        // Log surface — no border, darker background
        Surface(
            modifier = Modifier.weight(1f).height(46.dp),
            shape    = RoundedCornerShape(12.dp),
            color    = Color(0xFF0D0D14)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                val latest = logMessages.firstOrNull() ?: "No activity yet"
                Text(
                    text = latest,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp),
                    fontSize = 11.sp,
                    color = when {
                        latest.contains("error", ignoreCase = true) ||
                                latest.contains("failed", ignoreCase = true) -> ERR
                        latest.contains("Saved") || latest.contains("Found") -> LIVE
                        else -> MALE
                    },
                    fontFamily = FontFamily.Monospace,
                    maxLines   = 1
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor   = Color(0xFF1A1A22),
            title    = { Text("Clear All?", color = Color.White) },
            text     = { Text("All saved face images will be deleted.", color = Color(0xFF888899)) },
            confirmButton = {
                TextButton(onClick = { onClearAll(); showClearDialog = false }) {
                    Text("Delete", color = ERR)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = Color(0xFF888899))
                }
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────
//  PermissionScreen  (no border on card)
// ─────────────────────────────────────────────────────────────
@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            shape    = RoundedCornerShape(20.dp),
            colors   = CardDefaults.cardColors(containerColor = Color(0xFF1A1A22))
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = MALE, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Permissions Required", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Camera access needed for face extraction",
                    fontSize = 13.sp, color = Color(0xFF666677),
                    textAlign = TextAlign.Center, lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onRequest,
                    shape   = RoundedCornerShape(12.dp),
                    colors  = ButtonDefaults.buttonColors(containerColor = MALE)
                ) {
                    Text("Grant Permissions", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}