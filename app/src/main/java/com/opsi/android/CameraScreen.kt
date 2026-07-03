package com.opsi.android

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> ListenableFuture<T>.awaitResult(executor: Executor): T =
    suspendCancellableCoroutine { continuation ->
        addListener({
            try {
                continuation.resume(get())
            } catch (e: Throwable) {
                continuation.resumeWithException(e)
            }
        }, executor)
    }

@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val scope = rememberCoroutineScope()
    val mainExecutor = remember { context.mainExecutor }

    var hasCameraPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasCameraPermission) {
        PermissionRequest { permissionLauncher.launch(Manifest.permission.CAMERA) }
        return
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val preview = remember { Preview.Builder().build() }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    val shutterSound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { shutterSound.release() } }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var extensionsManager by remember { mutableStateOf<ExtensionsManager?>(null) }
    var enhanceMode by remember { mutableStateOf<Int?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    var isFrontCamera by remember { mutableStateOf(false) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var frameRatio by remember { mutableStateOf(FrameRatio.RATIO_4_3) }
    var photoFormat by remember { mutableStateOf(PhotoFormat.HEIC) }
    var enhanceEnabled by remember { mutableStateOf(false) }
    var lastThumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.getInstance(context).awaitResult(mainExecutor)
        cameraProvider = provider
        val manager = ExtensionsManager.getInstanceAsync(context, provider).awaitResult(mainExecutor)
        extensionsManager = manager
        val back = CameraSelector.DEFAULT_BACK_CAMERA
        enhanceMode = listOf(ExtensionMode.AUTO, ExtensionMode.HDR, ExtensionMode.NIGHT)
            .firstOrNull { manager.isExtensionAvailable(back, it) }
    }

    LaunchedEffect(cameraProvider, isFrontCamera, enhanceEnabled) {
        val provider = cameraProvider ?: return@LaunchedEffect
        val manager = extensionsManager
        val mode = enhanceMode
        val base = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val useExtension = enhanceEnabled && manager != null && mode != null && manager.isExtensionAvailable(base, mode)
        val selector = if (useExtension) manager!!.getExtensionEnabledCameraSelector(base, mode!!) else base
        camera = try {
            provider.unbindAll()
            val bound = provider.bindToLifecycle(activity, selector, preview, imageCapture)
            preview.surfaceProvider = previewView.surfaceProvider
            bound
        } catch (e: Exception) {
            try {
                provider.unbindAll()
                val bound = provider.bindToLifecycle(activity, base, preview, imageCapture)
                preview.surfaceProvider = previewView.surfaceProvider
                bound
            } catch (e2: Exception) {
                null
            }
        }
    }

    LaunchedEffect(flashMode) { imageCapture.flashMode = flashMode }

    fun takePhoto() {
        if (isCapturing) return
        isCapturing = true
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        imageCapture.takePicture(
            mainExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val raw = image.toBitmap()
                    image.close()
                    val ratio = frameRatio.widthOverHeight
                    val format = photoFormat
                    val mirror = isFrontCamera
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val upright = rotateUpright(raw, rotation)
                            val oriented = if (mirror) mirrorHorizontally(upright) else upright
                            val framed = cropToRatio(oriented, ratio)
                            val uri = capturePhoto(context, framed, format)
                            uri to loadGalleryThumbnail(context, uri)
                        }
                        lastUri = result.first
                        lastThumbnail = result.second
                        isCapturing = false
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(frameRatio.widthOverHeight)
                .align(Alignment.TopCenter)
                .pointerInput(camera) {
                    detectTransformGestures { _, _, zoom, _ ->
                        val cam = camera ?: return@detectTransformGestures
                        val state = cam.cameraInfo.zoomState.value
                        val current = state?.zoomRatio ?: 1f
                        val min = state?.minZoomRatio ?: 1f
                        val max = state?.maxZoomRatio ?: 1f
                        cam.cameraControl.setZoomRatio((current * zoom).coerceIn(min, max))
                    }
                }
                .pointerInput(camera) {
                    detectTapGestures { offset ->
                        val cam = camera ?: return@detectTapGestures
                        val point = previewView.meteringPointFactory.createPoint(offset.x, offset.y)
                        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    }
                }
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        }

        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            LabelPill(
                text = photoFormat.name,
                onClick = { photoFormat = if (photoFormat == PhotoFormat.HEIC) PhotoFormat.JPEG else PhotoFormat.HEIC },
                modifier = Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 8.dp)
            )

            FlashButton(
                flashMode = flashMode,
                onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
            )

            if (enhanceMode != null) {
                EnhancePill(
                    enabled = enhanceEnabled,
                    onClick = { enhanceEnabled = !enhanceEnabled },
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 8.dp)
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 28.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                ThumbnailButton(
                    thumbnail = lastThumbnail,
                    onClick = { lastUri?.let { openInGallery(context, it) } }
                )

                ShutterButton(enabled = !isCapturing, onClick = { takePhoto() })

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    LabelPill(
                        text = frameRatio.label,
                        onClick = {
                            frameRatio = when (frameRatio) {
                                FrameRatio.RATIO_4_3 -> FrameRatio.RATIO_16_9
                                FrameRatio.RATIO_16_9 -> FrameRatio.RATIO_1_1
                                FrameRatio.RATIO_1_1 -> FrameRatio.RATIO_4_3
                            }
                        }
                    )
                    CircleIconButton(onClick = { isFrontCamera = !isFrontCamera }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_cameraswitch),
                            contentDescription = "Switch camera",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(targetValue = if (enabled) 1f else 0.86f, label = "shutter")
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ring = 5.dp.toPx()
            val outerRadius = size.minDimension / 2f
            drawCircle(color = Color.White, radius = outerRadius, style = Stroke(width = ring))
            drawCircle(color = Color.White, radius = (outerRadius - ring * 1.9f) * scale)
        }
    }
}

@Composable
private fun CircleIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun FlashButton(flashMode: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val icon = when (flashMode) {
        ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
        ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
        else -> R.drawable.ic_flash_off
    }
    Box(modifier = modifier) {
        CircleIconButton(onClick = onClick) {
            Icon(
                painter = painterResource(icon),
                contentDescription = "Flash",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun EnhancePill(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val background = if (enabled) Color.White else Color.Black.copy(alpha = 0.35f)
    val foreground = if (enabled) Color.Black else Color.White
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text = "Enhance", color = foreground, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LabelPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ThumbnailButton(thumbnail: Bitmap?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(width = 1.5.dp, color = Color.White.copy(alpha = 0.85f), shape = RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(enabled = thumbnail != null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = "Open last photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
            )
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(text = "Camera access is required", color = Color.White, fontSize = 16.sp)
            LabelPill(text = "Grant permission", onClick = onRequest)
        }
    }
}
