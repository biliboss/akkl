package com.cdp.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
    }

    val latestFrame = AtomicReference<ByteArray>(ByteArray(0))
    private val isRunning = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null
    private var useFrontCamera = false
    private val executor = Executors.newSingleThreadExecutor()

    fun start(front: Boolean) {
        if (isRunning.getAndSet(true)) return
        useFrontCamera = front
        bindCamera()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        latestFrame.set(ByteArray(0))
        val provider = cameraProvider ?: return
        ContextCompat.getMainExecutor(context).execute {
            provider.unbindAll()
        }
    }

    fun switchCamera() {
        useFrontCamera = !useFrontCamera
        if (isRunning.get()) {
            val provider = cameraProvider ?: return
            ContextCompat.getMainExecutor(context).execute {
                provider.unbindAll()
                bindCameraInternal(provider)
            }
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            bindCameraInternal(cameraProvider!!)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraInternal(provider: ProcessCameraProvider) {
        if (!isRunning.get()) return

        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                       else CameraSelector.DEFAULT_BACK_CAMERA

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()

        analysis.setAnalyzer(executor) { imageProxy ->
            processImage(imageProxy)
        }

        try {
            provider.unbindAll()
            // CameraX needs a LifecycleOwner; use the service context if it implements it,
            // otherwise we need a ProcessCameraProvider-based approach
            if (context is LifecycleOwner) {
                provider.bindToLifecycle(context as LifecycleOwner, selector, analysis)
            } else {
                // For Service context, we use a workaround: get lifecycle from the provider
                // CameraX requires LifecycleOwner, so we'll use a custom one
                val lifecycle = ServiceLifecycleOwner()
                lifecycle.start()
                provider.bindToLifecycle(lifecycle, selector, analysis)
            }
            Log.d(TAG, "Camera bound: ${if (useFrontCamera) "front" else "back"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind camera", e)
            isRunning.set(false)
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val baos = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 60, baos)

            // Handle rotation
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val bitmap = BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                baos.reset()
                rotated.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                bitmap.recycle()
                if (rotated !== bitmap) rotated.recycle()
            }

            latestFrame.set(baos.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Error processing camera frame", e)
        } finally {
            imageProxy.close()
        }
    }
}
