package com.example.faceauth

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var statusBanner: TextView
    private lateinit var labelView: TextView
    private lateinit var confidenceView: TextView
    private lateinit var lockoutOverlay: RelativeLayout
    private lateinit var manifestLogView: TextView

    private var tflite: Interpreter? = null
    private lateinit var cameraExecutor: ExecutorService

    // Rolling average prediction smoothing
    private val predictionHistory = ArrayList<Float>()
    private val HISTORY_SIZE = 5

    private val CLASS_B_THRESHOLD = 0.85f
    private val CLASS_A_THRESHOLD = 0.50f

    private var lastClass: String? = null
    private var lastEventTime: Long = 0
    private var lastDebugFrameTime: Long = 0
    private val EVENT_COOLDOWN = 5000 // 5 seconds cooldown

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        statusBanner = findViewById(R.id.statusBanner)
        labelView = findViewById(R.id.labelView)
        confidenceView = findViewById(R.id.confidenceView)
        lockoutOverlay = findViewById(R.id.lockoutOverlay)
        manifestLogView = findViewById(R.id.manifestLogView)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Load TensorFlow Lite model
        try {
            tflite = Interpreter(loadModelFile())
            Log.d(TAG, "TFLite model loaded successfully.")
            statusBanner.text = "MODEL READY. SCANNING FACE..."
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TFLite model", e)
            statusBanner.text = "ERROR LOADING MODEL: ${e.message}"
            statusBanner.setBackgroundColor(0xFFD32F2F.toInt()) // Red
        }

        // Request camera permissions
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview UseCase
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            // ImageAnalysis UseCase
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer())
                }

            // Select front camera
            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                // Unbind all use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun loadModelFile(): ByteBuffer {
        try {
            // Try direct assets load (works if aaptOptions noCompress is fully active)
            val fileDescriptor = assets.openFd("face_classifier.tflite")
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.d(TAG, "Direct assets load failed (compressed/unmapped), falling back to cache file copy: ${e.message}")
            // Fallback: Copy raw asset stream (always works even if compressed) to app cache directory
            val cacheFile = File(cacheDir, "face_classifier.tflite")
            assets.open("face_classifier.tflite").use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            val inputStream = FileInputStream(cacheFile)
            val fileChannel = inputStream.channel
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, cacheFile.length())
        }
    }

    private inner class FaceAnalyzer : ImageAnalysis.Analyzer {

        override fun analyze(imageProxy: ImageProxy) {
            val bitmap = imageProxy.toBitmapCustom()
            if (bitmap == null) {
                Log.e(TAG, "Analysis skipped: converted bitmap is null")
            }
            if (tflite == null) {
                Log.e(TAG, "Analysis skipped: TFLite interpreter is null")
            }
            if (bitmap != null && tflite != null) {
                // Center-crop the frame to a square focusing on the face region (70% of minimum dimension)
                // This removes wide backgrounds and matches the headshot framing used during model training.
                val width = bitmap.width
                val height = bitmap.height
                val cropSize = (Math.min(width, height) * 0.70).toInt()
                val startX = (width - cropSize) / 2
                val startY = (height - cropSize) / 2
                val croppedBitmap = Bitmap.createBitmap(bitmap, startX, startY, cropSize, cropSize)

                // Resize cropped bitmap to model target size (224x224)
                val resizedBitmap = Bitmap.createScaledBitmap(croppedBitmap, 224, 224, true)

                // Preprocess and allocate ByteBuffer (1 * 224 * 224 * 3 channels * 4 bytes/float)
                val inputBuffer = ByteBuffer.allocateDirect(1 * 224 * 224 * 3 * 4)
                inputBuffer.order(ByteOrder.nativeOrder())

                val intValues = IntArray(224 * 224)
                resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)

                for (pixelValue in intValues) {
                    val r = ((pixelValue shr 16) and 0xFF).toFloat()
                    val g = ((pixelValue shr 8) and 0xFF).toFloat()
                    val b = (pixelValue and 0xFF).toFloat()

                    inputBuffer.putFloat(r)
                    inputBuffer.putFloat(g)
                    inputBuffer.putFloat(b)
                }

                // Output buffer for binary classification result (sigmoid output)
                val outputBuffer = ByteBuffer.allocateDirect(1 * 1 * 4)
                outputBuffer.order(ByteOrder.nativeOrder())

                // Save debug frame to inspect orientation and cropping (throttled to once every 2 seconds)
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastDebugFrameTime >= 2000) {
                    try {
                        val debugFile = File(getExternalFilesDir(null), "debug_frame.jpg")
                        val outStream = FileOutputStream(debugFile)
                        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outStream)
                        outStream.close()
                        lastDebugFrameTime = currentTime
                        Log.d(TAG, "Saved debug frame to: ${debugFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save debug frame", e)
                    }
                }

                // Run inference
                tflite?.run(inputBuffer, outputBuffer)
                outputBuffer.rewind()
                val probability = outputBuffer.float

                runOnUiThread {
                    processPrediction(probability)
                }
            }
            imageProxy.close()
        }
    }

    private fun processPrediction(probability: Float) {
        // Add to history and get rolling average
        predictionHistory.add(probability)
        if (predictionHistory.size > HISTORY_SIZE) {
            predictionHistory.removeAt(0)
        }
        val smoothedProbability = predictionHistory.average().toFloat()

        confidenceView.text = String.format(Locale.US, "P(Class B) = %.3f", smoothedProbability)

        var label = "UNKNOWN"
        var confidence = smoothedProbability
        var currentClass = "UNKNOWN"

        if (smoothedProbability >= CLASS_B_THRESHOLD) {
            label = "CLASS B"
            confidence = smoothedProbability
            currentClass = "CLASS_B"
        } else if (smoothedProbability <= CLASS_A_THRESHOLD) {
            label = "CLASS A"
            confidence = 1.0f - smoothedProbability
            currentClass = "CLASS_A"
        } else {
            confidence = Math.max(smoothedProbability, 1.0f - smoothedProbability)
        }

        labelView.text = String.format(Locale.US, "Classification: %s (%.1f%%)", label, confidence * 100)

        val currentTime = System.currentTimeMillis()
        if (currentClass != lastClass || currentTime - lastEventTime >= EVENT_COOLDOWN) {
            if (currentClass == "CLASS_A") {
                handleClassA(confidence)
            } else if (currentClass == "CLASS_B") {
                handleClassB(confidence)
            }
            lastClass = currentClass
            lastEventTime = currentTime
        }
    }

    private fun handleClassA(confidence: Float) {
        statusBanner.text = "ACCESS GRANTED: AUTHENTICATION SUCCESSFUL"
        statusBanner.setBackgroundColor(0xFF2E7D32.toInt()) // Solid Green

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] AUTHENTICATION SUCCESSFUL (confidence=$confidence)")

        // Append to authentication.log
        try {
            val logFile = File(getExternalFilesDir(null), "authentication.log")
            val outputStream = FileOutputStream(logFile, true)
            val logLine = String.format(Locale.US, "%s,CLASS_A,AUTHENTICATION_SUCCESSFUL,%.4f\n", timestamp, confidence)
            outputStream.write(logLine.toByteArray())
            outputStream.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write authentication log", e)
        }
    }

    private fun handleClassB(confidence: Float) {
        // Lock UI overlay
        lockoutOverlay.visibility = View.VISIBLE

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] CLASS B DETECTED - SIMULATED LOCKOUT TRIGGERED")

        // Append to security_events.log
        try {
            val logFile = File(getExternalFilesDir(null), "security_events.log")
            val outputStream = FileOutputStream(logFile, true)
            val logLine = String.format(Locale.US, "%s,CLASS_B,DETECTED,%.4f\n", timestamp, confidence)
            outputStream.write(logLine.toByteArray())
            outputStream.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write security event log", e)
        }

        // Write simulation ransomware manifest
        try {
            val manifestFile = File(getExternalFilesDir(null), "ransomware_manifest.txt")
            val manifestStream = FileOutputStream(manifestFile)
            val manifestContent = """
                ====================================================
                SIMULATED TARGET PERSONA ENGAGEMENT MANIFEST
                ====================================================
                Timestamp: $timestamp
                Target: Class B (Designated Professor Profile)
                Simulation Action: Payload Deployed
                
                [SIMULATED ENCRYPTED FILES]
                - /storage/emulated/0/DCIM/Camera/IMG_20260822_001.jpg
                - /storage/emulated/0/DCIM/Camera/IMG_20260822_002.jpg
                - /storage/emulated/0/Documents/Invoice_August.pdf
                - /storage/emulated/0/Download/LectureNotes_Week1.pdf
                
                Status: SIMULATION SUCCESSFUL (No actual files modified)
            """.trimIndent()
            manifestStream.write(manifestContent.toByteArray())
            manifestStream.close()
            manifestLogView.text = "File Manifest Created at:\n" + manifestFile.absolutePath
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write manifest", e)
        }
    }

    private fun ImageProxy.toBitmapCustom(): Bitmap? {
        try {
            // Handle JPEG format directly (often returned by emulator webcams)
            if (this.format == android.graphics.ImageFormat.JPEG) {
                val buffer = this.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val rawBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                return rotateBitmap(rawBitmap, this.imageInfo.rotationDegrees)
            }

            // Default YUV_420_888 format
            val nv21 = yuv420ToNv21(this)
            val yuvImage = android.graphics.YuvImage(
                nv21,
                android.graphics.ImageFormat.NV21,
                this.width,
                this.height,
                null
            )
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height), 100, out)
            val imageBytes = out.toByteArray()
            val rawBitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            return rotateBitmap(rawBitmap, this.imageInfo.rotationDegrees)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting image proxy to bitmap", e)
            return null
        }
    }

    private fun rotateBitmap(bitmap: Bitmap?, rotationDegrees: Int): Bitmap? {
        if (bitmap == null) return null
        // If metadata reports 0 rotation but emulator is sideways, default to 90 degrees clockwise.
        // Otherwise, use the non-zero rotation reported by the system (e.g. 270 degrees).
        val totalRotation = if (rotationDegrees == 0) 90 else rotationDegrees
        val matrix = Matrix()
        matrix.postRotate(totalRotation.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V

        val rowStride = image.planes[0].rowStride
        val pixelStride = image.planes[1].pixelStride

        var pos = 0

        if (rowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            var yBufferPos = 0
            for (row in 0 until height) {
                yBuffer.position(yBufferPos)
                yBuffer.get(nv21, pos, width)
                pos += width
                yBufferPos += rowStride
            }
        }

        // Interleave U and V components
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vuPos = ySize + row * width + col * 2
                vBuffer.position(row * image.planes[2].rowStride + col * pixelStride)
                uBuffer.position(row * image.planes[1].rowStride + col * pixelStride)
                nv21[vuPos] = vBuffer.get()
                nv21[vuPos + 1] = uBuffer.get()
            }
        }
        return nv21
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                statusBanner.text = "Permissions not granted by the user."
            }
        }
    }

    companion object {
        private const val TAG = "FaceAuthSecure"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA
        )
    }
}
