package com.antor.face.extraction.ui

import android.Manifest
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    isRunning: Boolean,
    logMessages: List<String>,
    maleCount: Int,
    femaleCount: Int,
    serverUrl: String,
    intervalSeconds: Int,
    useFrontCamera: Boolean,
    lastCapturedBitmap: Bitmap?,
    liveBitmap: Bitmap?,
    countdownSeconds: Int,
    onStartStop: () -> Unit,
    onIntervalChange: (Int) -> Unit,
    onCameraToggle: (Boolean) -> Unit,
    onClearAll: () -> Unit,
) {
    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    val permissionState = rememberMultiplePermissionsState(permissions)

    val bgGradient = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A0A0F), Color(0xFF0F0F18))
    )

    Box(modifier = modifier.background(bgGradient)) {
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
                AppHeader(isRunning = isRunning, countdownSeconds = countdownSeconds, intervalSeconds = intervalSeconds)

                CameraPreviewRow(
                    isRunning = isRunning,
                    lastCapturedBitmap = lastCapturedBitmap,
                    liveBitmap = liveBitmap,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                StatsAndServerRow(
                    maleCount = maleCount,
                    femaleCount = femaleCount,
                    serverUrl = serverUrl,
                    isRunning = isRunning
                )

                SettingsRow(
                    isRunning = isRunning,
                    intervalSeconds = intervalSeconds,
                    useFrontCamera = useFrontCamera,
                    onIntervalChange = onIntervalChange,
                    onCameraToggle = onCameraToggle
                )

                BottomRow(
                    isRunning = isRunning,
                    logMessages = logMessages,
                    onStartStop = onStartStop,
                    onClearAll = onClearAll
                )
            }
        }
    }
}

@Composable
fun AppHeader(isRunning: Boolean, countdownSeconds: Int, intervalSeconds: Int) {
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
                color = Color(0xFF555566),
                letterSpacing = 1.5.sp
            )
        }

        // Status badge with countdown
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isRunning) Color(0xFF0D2B0D) else Color(0xFF1A1A22),
            border = BorderStroke(
                1.dp,
                if (isRunning) Color(0xFF2A5A2A) else Color(0xFF2A2A35)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Pulsing dot
                val infiniteTransition = rememberInfiniteTransition(label = "dot")
                val dotAlpha by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (isRunning) 0.25f else 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dotAlpha"
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            (if (isRunning) Color(0xFF4CAF50) else Color(0xFF444455))
                                .copy(alpha = if (isRunning) dotAlpha else 1f)
                        )
                )

                Text(
                    text = if (isRunning) "LIVE" else "IDLE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isRunning) Color(0xFF4CAF50) else Color(0xFF555566),
                    letterSpacing = 1.sp
                )

                // Countdown — only when running
                if (isRunning && intervalSeconds > 0) {
                    // Thin vertical divider
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(12.dp)
                            .background(Color(0xFF2A5A2A))
                    )

                    // Progress arc + number
                    CountdownBadge(
                        secondsRemaining = countdownSeconds,
                        totalSeconds = intervalSeconds
                    )
                }
            }
        }
    }
}

@Composable
fun CountdownBadge(secondsRemaining: Int, totalSeconds: Int) {
    val progress = if (totalSeconds > 0) secondsRemaining.toFloat() / totalSeconds else 0f

    // Color shifts green → yellow → red as time runs out
    val badgeColor = when {
        progress > 0.5f -> Color(0xFF4CAF50)
        progress > 0.25f -> Color(0xFFFFB300)
        else -> Color(0xFFE24A4A)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Circular progress ring
        androidx.compose.foundation.Canvas(modifier = Modifier.size(18.dp)) {
            val stroke = 2.5f
            val diameter = size.minDimension - stroke
            val topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2)
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)

            // Background track
            drawArc(
                color = Color(0xFF2A3A2A),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
            // Progress arc — sweeps from full → 0
            drawArc(
                color = badgeColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke)
            )
        }

        Text(
            text = "${secondsRemaining}s",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = badgeColor,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.sp
        )
    }
}

@Composable
fun CameraPreviewRow(
    isRunning: Boolean,
    lastCapturedBitmap: Bitmap?,
    liveBitmap: Bitmap?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PreviewPanel(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            label = "CAPTURED",
            labelColor = Color(0xFF4A90E2),
            bitmap = lastCapturedBitmap,
            emptyIcon = Icons.Default.Face,
            emptyText = "Last Capture",
            borderColor = Color(0xFF2A2A35)
        )

        PreviewPanel(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            label = if (isRunning) "LIVE" else "CAMERA",
            labelColor = if (isRunning) Color(0xFF4CAF50) else Color(0xFF555566),
            bitmap = liveBitmap,
            emptyIcon = Icons.Default.Videocam,
            emptyText = if (isRunning) "Waiting..." else "Press Start",
            borderColor = if (isRunning) Color(0xFF2A4A2A) else Color(0xFF2A2A35)
        )
    }
}

@Composable
fun PreviewPanel(
    modifier: Modifier = Modifier,
    label: String,
    labelColor: Color,
    bitmap: Bitmap?,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    emptyText: String,
    borderColor: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF111118),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        emptyIcon,
                        contentDescription = null,
                        tint = Color(0xFF2A2A40),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = emptyText,
                        fontSize = 11.sp,
                        color = Color(0xFF333344),
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(6.dp),
                color = Color(0xCC0A0A0F)
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = labelColor,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun StatsAndServerRow(
    maleCount: Int,
    femaleCount: Int,
    serverUrl: String,
    isRunning: Boolean
) {
    val clipboardManager = LocalClipboardManager.current

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { StatChip(label = "Male", value = maleCount.toString(), color = Color(0xFF4A90E2)) }
        item { StatChip(label = "Female", value = femaleCount.toString(), color = Color(0xFFE24A90)) }
        item { StatChip(label = "Total", value = (maleCount + femaleCount).toString(), color = Color(0xFF9A6AE2)) }
        if (isRunning && serverUrl.isNotEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF111118),
                    border = BorderStroke(1.dp, Color(0xFF2A3A4A)),
                    modifier = Modifier.clickable { clipboardManager.setText(AnnotatedString(serverUrl)) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, tint = Color(0xFF4A90E2), modifier = Modifier.size(14.dp))
                        Text(text = serverUrl.removePrefix("http://"), fontSize = 11.sp, color = Color(0xFF4A90E2), fontFamily = FontFamily.Monospace)
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFF333344), modifier = Modifier.size(12.dp))
                    }
                }
            }
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF111118),
                    border = BorderStroke(1.dp, Color(0xFF2A3A2A)),
                    modifier = Modifier.clickable { clipboardManager.setText(AnnotatedString("$serverUrl/people.json")) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("people.json", fontSize = 11.sp, color = Color(0xFF4AE2A0), fontFamily = FontFamily.Monospace)
                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color(0xFF333344), modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun StatChip(label: String, value: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF111118),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = color)
            Text(text = label, fontSize = 10.sp, color = Color(0xFF555566), letterSpacing = 0.5.sp)
        }
    }
}

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
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF111118),
        border = BorderStroke(1.dp, Color(0xFF2A2A35))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "INTERVAL (sec)", fontSize = 9.sp, color = Color(0xFF444455), letterSpacing = 1.sp)
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { v ->
                        if (!isRunning) {
                            intervalText = v.filter { it.isDigit() }.take(4)
                            intervalText.toIntOrNull()?.let { num ->
                                if (num in 1..9999) onIntervalChange(num)
                            }
                        }
                    },
                    enabled = !isRunning,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF4A90E2),
                        unfocusedBorderColor = Color(0xFF2A2A35),
                        disabledBorderColor = Color(0xFF1A1A25),
                        disabledTextColor = Color(0xFF666677),
                        cursorColor = Color(0xFF4A90E2)
                    ),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(8.dp)
                )
            }

            Box(modifier = Modifier.width(1.dp).height(50.dp).background(Color(0xFF2A2A35)))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "CAMERA", fontSize = 9.sp, color = Color(0xFF444455), letterSpacing = 1.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CamToggleButton("Back", !useFrontCamera, !isRunning) { onCameraToggle(false) }
                    CamToggleButton("Front", useFrontCamera, !isRunning) { onCameraToggle(true) }
                }
            }
        }
    }
}

@Composable
fun CamToggleButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (selected) Color(0xFF4A90E2) else Color(0xFF0F0F18),
        border = BorderStroke(1.dp, if (selected) Color(0xFF4A90E2) else Color(0xFF2A2A35)),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Color.White else Color(0xFF555566)
        )
    }
}

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
            onClick = onStartStop,
            modifier = Modifier.height(46.dp).width(110.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFF8B2020) else Color(0xFF1E4A8A)
            )
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = if (isRunning) "Stop" else "Start", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        OutlinedButton(
            onClick = { showClearDialog = true },
            modifier = Modifier.height(46.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Color(0xFF2A2A35)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF555566))
        ) {
            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
        }

        Surface(
            modifier = Modifier.weight(1f).height(46.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0D0D14),
            border = BorderStroke(1.dp, Color(0xFF1A1A25))
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
                        latest.contains("failed", ignoreCase = true) -> Color(0xFFE24A4A)
                        latest.contains("Saved") || latest.contains("Found") -> Color(0xFF4CAF50)
                        else -> Color(0xFF4A90E2)
                    },
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = Color(0xFF1A1A22),
            title = { Text("Clear All?", color = Color.White) },
            text = { Text("All saved face images will be deleted.", color = Color(0xFF888899)) },
            confirmButton = {
                TextButton(onClick = { onClearAll(); showClearDialog = false }) {
                    Text("Delete", color = Color(0xFFE24A4A))
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

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A22)),
            border = BorderStroke(1.dp, Color(0xFF2A2A35))
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null, tint = Color(0xFF4A90E2), modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("Permissions Required", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Camera access needed for face extraction",
                    fontSize = 13.sp,
                    color = Color(0xFF666677),
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onRequest,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A90E2))
                ) {
                    Text("Grant Permissions", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
