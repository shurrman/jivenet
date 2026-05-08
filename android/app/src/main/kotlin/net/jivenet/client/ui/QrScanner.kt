package net.jivenet.client.ui

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Простой QR-сканер на базе CameraX + ML Kit Barcode Scanning.
 * Вызывает onScanned на первом же успешном декоде QR и тут же
 * останавливает анализатор, чтобы не спамить коллбэком.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanner(onScanned: (String) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        dragHandle = null,
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 500.dp)) {
            CameraPreview(onScanned = onScanned)
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) { Text("×", style = MaterialTheme.typography.headlineMedium) }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun CameraPreview(onScanned: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanned by remember { mutableStateOf(false) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val preview = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val previewUse = Preview.Builder().build().also {
                    it.setSurfaceProvider(preview.surfaceProvider)
                }

                val scanner = BarcodeScanning.getClient()
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analyzer.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null && !scanned) {
                        val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { codes ->
                                val qr = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                val value = qr?.rawValue
                                if (!value.isNullOrBlank() && !scanned) {
                                    scanned = true
                                    ContextCompat.getMainExecutor(ctx).execute { onScanned(value) }
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    previewUse,
                    analyzer
                )
            }, ContextCompat.getMainExecutor(ctx))
            preview
        }
    )
}
