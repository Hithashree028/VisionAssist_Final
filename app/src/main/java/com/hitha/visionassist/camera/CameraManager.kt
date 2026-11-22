package com.hitha.visionassist.camera

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

/**
 * Manages CameraX lifecycle, rear-camera selection, and hands-free automatic capture.
 */
class CameraManager(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var isCameraBound: Boolean = false

    /**
     * Binds CameraX to the specified lifecycle owner and optional preview view.
     */
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView? = null,
        onBound: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                imageCapture = capture

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()

                if (previewView != null) {
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, capture)
                } else {
                    provider.bindToLifecycle(lifecycleOwner, cameraSelector, capture)
                }

                isCameraBound = true
                onBound?.invoke()
            } catch (e: Exception) {
                isCameraBound = false
                onError?.invoke("Camera binding error: ${e.localizedMessage ?: "Unknown"}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Automatically takes a fresh photo without user touch.
     */
    fun capturePhoto(
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit
    ) {
        val capture = imageCapture
        if (capture == null || !isCameraBound) {
            onError("Camera is not ready for capture.")
            return
        }

        val cacheDir = File(context.cacheDir, "visionassist_captures")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val photoFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onSuccess(photoFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    onError("Capture failed: ${exception.localizedMessage ?: "Please retry"}")
                }
            }
        )
    }

    /**
     * Unbinds camera from provider.
     */
    fun unbind() {
        try {
            cameraProvider?.unbindAll()
            isCameraBound = false
        } catch (_: Exception) {}
    }
}
