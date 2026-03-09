// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.android.mobile.OTelMobile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity for managing telemetry logs and buffers.
 *
 * Features:
 * - View buffer status (RAM and disk usage)
 * - Force send all logs immediately
 * - Export logs to file for debugging
 * - View recent events in buffer
 * - Clear all buffers
 */
class LogsActivity : AppCompatActivity() {

    private val TAG = "LogsActivity"

    private lateinit var bufferStatusText: TextView
    private lateinit var recentEventsCard: CardView
    private lateinit var recentEventsText: TextView
    private lateinit var btnSendLogs: Button
    private lateinit var btnExportLogs: Button
    private lateinit var btnViewRecent: Button
    private lateinit var btnClearBuffer: Button

    private lateinit var loggerProvider: MobileLoggerProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        // Set up toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        // Initialize UI
        bufferStatusText = findViewById(R.id.bufferStatusText)
        recentEventsCard = findViewById(R.id.recentEventsCard)
        recentEventsText = findViewById(R.id.recentEventsText)
        btnSendLogs = findViewById(R.id.btnSendLogs)
        btnExportLogs = findViewById(R.id.btnExportLogs)
        btnViewRecent = findViewById(R.id.btnViewRecent)
        btnClearBuffer = findViewById(R.id.btnClearBuffer)

        // Initialize OpenTelemetry
        loggerProvider = OTelMobile.getLoggerProvider()

        // Set up button listeners
        setupButtons()

        // Load initial buffer status
        updateBufferStatus()
    }

    private fun setupButtons() {
        btnSendLogs.setOnClickListener {
            sendAllLogs()
        }

        btnExportLogs.setOnClickListener {
            exportLogsToFile()
        }

        btnViewRecent.setOnClickListener {
            toggleRecentEvents()
        }

        btnClearBuffer.setOnClickListener {
            confirmClearBuffer()
        }
    }

    /**
     * Updates the buffer status display with current RAM and disk usage.
     */
    private fun updateBufferStatus() {
        Thread {
            try {
                // Get buffer statistics from disk
                val diskBufferDir = File(filesDir, "otel-buffer")
                val diskFiles = diskBufferDir.listFiles() ?: emptyArray()
                val diskSizeMb = diskFiles.sumOf { it.length() } / (1024.0 * 1024.0)
                val diskFileCount = diskFiles.size

                // Build status text
                val status = buildString {
                    appendLine("💾 Disk Buffer:")
                    appendLine("  Files: $diskFileCount")
                    appendLine("  Size: %.2f MB".format(diskSizeMb))
                    appendLine()
                    appendLine("🔧 Configuration:")

                    val config = ConfigManager.loadConfig(this@LogsActivity)
                    appendLine("  Endpoint: ${config.collectorEndpoint}")
                    appendLine("  Export Mode: ${config.exportMode}")
                    appendLine("  Max Disk: ${config.diskBufferMb} MB")
                    appendLine("  Max RAM: ${config.ramBufferSize} events")
                    appendLine("  TTL: ${config.diskBufferTtlHours}h")
                }

                runOnUiThread {
                    bufferStatusText.text = status
                }

                Log.i(TAG, "Buffer status updated: $diskFileCount files, %.2f MB".format(diskSizeMb))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get buffer status", e)
                runOnUiThread {
                    bufferStatusText.text = "❌ Failed to load buffer status\n\n${e.message}"
                }
            }
        }.start()
    }

    /**
     * Forces an immediate send of all buffered logs.
     */
    private fun sendAllLogs() {
        btnSendLogs.isEnabled = false
        btnSendLogs.text = "📤 Sending..."

        Toast.makeText(this, "Sending all logs...", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Force flush requested from Logs screen")

        Thread {
            try {
                val result = loggerProvider.forceFlush(30)

                runOnUiThread {
                    btnSendLogs.isEnabled = true
                    btnSendLogs.text = "📤 Send All Logs Now"

                    if (result.isSuccess) {
                        Toast.makeText(
                            this,
                            "✅ All logs sent successfully!",
                            Toast.LENGTH_LONG
                        ).show()
                        Log.i(TAG, "Force flush succeeded")

                        // Refresh buffer status after send
                        updateBufferStatus()
                    } else {
                        Toast.makeText(
                            this,
                            "❌ Failed to send some logs. Check network connection.",
                            Toast.LENGTH_LONG
                        ).show()
                        Log.e(TAG, "Force flush failed")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during force flush", e)
                runOnUiThread {
                    btnSendLogs.isEnabled = true
                    btnSendLogs.text = "📤 Send All Logs Now"
                    Toast.makeText(
                        this,
                        "❌ Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /**
     * Exports buffered logs to a file for debugging.
     */
    private fun exportLogsToFile() {
        btnExportLogs.isEnabled = false
        btnExportLogs.text = "💾 Exporting..."

        Thread {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val exportDir = File(getExternalFilesDir(null), "otel-exports")
                exportDir.mkdirs()

                val exportFile = File(exportDir, "otel_logs_$timestamp.txt")
                val diskBufferDir = File(filesDir, "otel-buffer")

                exportFile.bufferedWriter().use { writer ->
                    writer.write("OpenTelemetry Mobile Demo - Log Export\n")
                    writer.write("Exported: ${Date()}\n")
                    writer.write("=" .repeat(60) + "\n\n")

                    // Write buffer summary
                    val diskFiles = diskBufferDir.listFiles() ?: emptyArray()
                    writer.write("Buffer Summary:\n")
                    writer.write("  Total files: ${diskFiles.size}\n")
                    writer.write("  Total size: %.2f MB\n".format(
                        diskFiles.sumOf { it.length() } / (1024.0 * 1024.0)
                    ))
                    writer.write("\n")

                    // List buffered files
                    writer.write("Buffered Files:\n")
                    diskFiles.sortedByDescending { it.lastModified() }.forEach { file ->
                        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                            .format(Date(file.lastModified()))
                        writer.write("  - ${file.name} (${file.length()} bytes, $date)\n")
                    }

                    writer.write("\n")
                    writer.write("Note: Binary telemetry data not included in export.\n")
                    writer.write("Use logcat for detailed event inspection.\n")
                }

                Log.i(TAG, "Logs exported to: ${exportFile.absolutePath}")

                runOnUiThread {
                    btnExportLogs.isEnabled = true
                    btnExportLogs.text = "💾 Export to File"

                    // Show share dialog
                    AlertDialog.Builder(this)
                        .setTitle("Export Complete")
                        .setMessage("Logs exported to:\n${exportFile.absolutePath}\n\nWould you like to share this file?")
                        .setPositiveButton("Share") { _, _ ->
                            shareExportedFile(exportFile)
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export logs", e)
                runOnUiThread {
                    btnExportLogs.isEnabled = true
                    btnExportLogs.text = "💾 Export to File"
                    Toast.makeText(
                        this,
                        "❌ Export failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /**
     * Shares an exported log file via system share sheet.
     */
    private fun shareExportedFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OpenTelemetry Logs Export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share log export"))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share file", e)
            Toast.makeText(this, "Failed to share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Toggles the visibility of recent events display.
     */
    private fun toggleRecentEvents() {
        if (recentEventsCard.visibility == android.view.View.VISIBLE) {
            recentEventsCard.visibility = android.view.View.GONE
            btnViewRecent.text = "👁️ View Recent Events"
        } else {
            recentEventsCard.visibility = android.view.View.VISIBLE
            btnViewRecent.text = "🙈 Hide Recent Events"
            loadRecentEvents()
        }
    }

    /**
     * Loads and displays recent events from logcat.
     */
    private fun loadRecentEvents() {
        recentEventsText.text = "Loading recent events..."

        Thread {
            try {
                // Get recent logcat entries for our app
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-d", "-t", "50", "OTELDemoApp:*", "*:S")
                )
                val events = process.inputStream.bufferedReader().use { it.readText() }

                runOnUiThread {
                    if (events.isNotEmpty()) {
                        recentEventsText.text = events
                    } else {
                        recentEventsText.text = "No recent events found in logcat.\n\nGenerate some events from the main screen."
                    }
                }

                Log.i(TAG, "Loaded recent events from logcat")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load recent events", e)
                runOnUiThread {
                    recentEventsText.text = "❌ Failed to load events:\n${e.message}"
                }
            }
        }.start()
    }

    /**
     * Shows confirmation dialog before clearing buffers.
     */
    private fun confirmClearBuffer() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Buffers?")
            .setMessage("This will permanently delete all buffered telemetry data from RAM and disk. This action cannot be undone.\n\nAre you sure?")
            .setPositiveButton("Clear") { _, _ ->
                clearAllBuffers()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Clears all telemetry buffers (RAM and disk).
     */
    private fun clearAllBuffers() {
        btnClearBuffer.isEnabled = false
        btnClearBuffer.text = "🗑️ Clearing..."

        Toast.makeText(this, "Clearing all buffers...", Toast.LENGTH_SHORT).show()
        Log.w(TAG, "Clearing all telemetry buffers")

        Thread {
            try {
                // Clear disk buffer
                val diskBufferDir = File(filesDir, "otel-buffer")
                val deletedFiles = diskBufferDir.listFiles()?.count { file ->
                    file.delete()
                } ?: 0

                Log.i(TAG, "Deleted $deletedFiles buffer files")

                runOnUiThread {
                    btnClearBuffer.isEnabled = true
                    btnClearBuffer.text = "🗑️ Clear All Buffers"

                    Toast.makeText(
                        this,
                        "✅ Cleared $deletedFiles buffer files",
                        Toast.LENGTH_LONG
                    ).show()

                    // Refresh buffer status
                    updateBufferStatus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear buffers", e)
                runOnUiThread {
                    btnClearBuffer.isEnabled = true
                    btnClearBuffer.text = "🗑️ Clear All Buffers"
                    Toast.makeText(
                        this,
                        "❌ Failed to clear buffers: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        // Refresh buffer status when returning to this screen
        updateBufferStatus()
    }
}
