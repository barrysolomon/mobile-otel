// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.debug

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.mobile.OTelMobile
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RingBufferActivity : AppCompatActivity() {

    private lateinit var tvLastUpdated: TextView
    private lateinit var tvRamStats: TextView
    private lateinit var pbRam: ProgressBar
    private lateinit var tvDiskStats: TextView
    private lateinit var pbDisk: ProgressBar
    private lateinit var llEventTypes: LinearLayout
    private lateinit var llHistogram: LinearLayout
    private lateinit var tvFlushLog: TextView
    private lateinit var tbAutoRefresh: ToggleButton

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshData()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val flushHistory = mutableListOf<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var autoRefreshEnabled = true

    companion object {
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val BUCKET_SIZE_MS = 5 * 60 * 1000L // 5 minutes
        private const val HISTORY_MINUTES = 60
        private const val BUCKET_COUNT = 12
        private const val MAX_EVENT_TYPES = 12
        private const val RAM_CAPACITY = 5000
        private const val DISK_CAPACITY_MB = 50f
        private const val DB_NAME = "otel_log_buffer.db"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ring_buffer)

        bindViews()
        setupBackButton()
        setupAutoRefreshToggle()
        setupActionButtons()

        refreshData()
        scheduleAutoRefresh()
    }

    private fun bindViews() {
        tvLastUpdated = findViewById(R.id.tvLastUpdated)
        tvRamStats = findViewById(R.id.tvRamStats)
        pbRam = findViewById(R.id.pbRam)
        tvDiskStats = findViewById(R.id.tvDiskStats)
        pbDisk = findViewById(R.id.pbDisk)
        llEventTypes = findViewById(R.id.llEventTypes)
        llHistogram = findViewById(R.id.llHistogram)
        tvFlushLog = findViewById(R.id.tvFlushLog)
        tbAutoRefresh = findViewById(R.id.tbAutoRefresh)
    }

    private fun setupBackButton() {
        findViewById<TextView>(R.id.tvBack).setOnClickListener {
            finish()
        }
    }

    private fun setupAutoRefreshToggle() {
        tbAutoRefresh.isChecked = autoRefreshEnabled
        tbAutoRefresh.setOnCheckedChangeListener { _, isChecked ->
            autoRefreshEnabled = isChecked
            if (isChecked) {
                scheduleAutoRefresh()
            } else {
                handler.removeCallbacks(refreshRunnable)
            }
        }
    }

    private fun setupActionButtons() {
        findViewById<MaterialButton>(R.id.btnForceFlush).setOnClickListener {
            performFlush("Force Flush") {
                try {
                    OTelMobile.getLoggerProvider().getMobileProcessor().forceFlush()
                } catch (e: Exception) {
                    null
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnFlush5min).setOnClickListener {
            performFlush("Flush 5min") {
                try {
                    OTelMobile.getLoggerProvider().getMobileProcessor().flushWindow(5)
                } catch (e: Exception) {
                    null
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnFlush2min).setOnClickListener {
            performFlush("Flush 2min") {
                try {
                    OTelMobile.getLoggerProvider().getMobileProcessor().flushWindow(2)
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun performFlush(label: String, action: () -> Any?) {
        val timestamp = timeFmt.format(Date())
        action()
        val entry = "$timestamp  $label  → done"
        flushHistory.add(0, entry)
        if (flushHistory.size > 20) flushHistory.removeLast()
        updateFlushLog()
        refreshData()
    }

    private fun scheduleAutoRefresh() {
        handler.removeCallbacks(refreshRunnable)
        if (autoRefreshEnabled) {
            handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
        }
    }

    private fun refreshData() {
        tvLastUpdated.text = "Updated: ${timeFmt.format(Date())}"

        updateRamStats()
        updateDiskStats()
        updateEventTypes()
        updateHistogram()
        updateFlushLog()
    }

    private fun getBufferStats() = try {
        OTelMobile.getLoggerProvider().getMobileProcessor().getBufferStats()
    } catch (e: Exception) {
        null
    }

    private fun updateRamStats() {
        val stats = getBufferStats()
        if (stats != null) {
            val count = stats.ramBufferSize
            val capacity = if (stats.ramBufferCapacity > 0) stats.ramBufferCapacity else RAM_CAPACITY
            tvRamStats.text = "RAM: $count / $capacity events"
            pbRam.max = capacity
            pbRam.progress = count
        } else {
            tvRamStats.text = "RAM: unavailable"
            pbRam.progress = 0
        }
    }

    private fun updateDiskStats() {
        try {
            val stats = getBufferStats()
            val diskCount = queryDiskEventCount()
            val diskFile = getDiskDbFile()
            val diskSizeMb = if (diskFile != null && diskFile.exists()) {
                diskFile.length().toFloat() / (1024 * 1024)
            } else 0f

            val capacityMb = stats?.diskBufferCapacityMb ?: 50
            val capacityInt = (capacityMb * 100).coerceAtLeast(1)
            val usedInt = (diskSizeMb * 100).toInt()

            tvDiskStats.text = "Disk: $diskCount events  |  %.2f / %d MB".format(diskSizeMb, capacityMb)
            pbDisk.max = capacityInt
            pbDisk.progress = usedInt
        } catch (e: Exception) {
            tvDiskStats.text = "Disk: error"
            pbDisk.progress = 0
        }
    }

    private fun getDiskDbFile(): File? {
        return try {
            getDatabasePath(DB_NAME)
        } catch (e: Exception) {
            null
        }
    }

    private fun queryDiskEventCount(): Int {
        return queryDb { db ->
            val cursor = db.rawQuery("SELECT COUNT(*) FROM log_records", null)
            cursor.use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
        } ?: 0
    }

    private fun updateEventTypes() {
        llEventTypes.removeAllViews()

        val rows = queryDb { db ->
            val cursor = db.rawQuery(
                "SELECT body, COUNT(*) as cnt FROM log_records GROUP BY body ORDER BY cnt DESC LIMIT $MAX_EVENT_TYPES",
                null
            )
            val result = mutableListOf<Pair<String, Int>>()
            cursor.use {
                while (it.moveToNext()) {
                    result.add(it.getString(0) to it.getInt(1))
                }
            }
            result
        }

        if (rows == null || rows.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No data"
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, 8, 0, 8)
            }
            llEventTypes.addView(empty)
            return
        }

        val maxCount = rows.maxOfOrNull { it.second } ?: 1

        rows.forEach { (body, count) ->
            val inflater = layoutInflater
            val row = inflater.inflate(R.layout.item_buffer_event_type, llEventTypes, false)
            row.findViewById<TextView>(R.id.tvEventType).text = body
            val pb = row.findViewById<ProgressBar>(R.id.pbEventBar)
            pb.max = maxCount
            pb.progress = count
            row.findViewById<TextView>(R.id.tvEventCount).text = count.toString()
            llEventTypes.addView(row)
        }
    }

    private fun updateHistogram() {
        llHistogram.removeAllViews()

        val nowMs = System.currentTimeMillis()
        val startMs = nowMs - HISTORY_MINUTES * 60 * 1000L

        val buckets = queryDb { db ->
            val cursor = db.rawQuery(
                "SELECT (timestampMs / ?) * ? as bucket, COUNT(*) as cnt " +
                    "FROM log_records WHERE timestampMs >= ? " +
                    "GROUP BY bucket",
                arrayOf(
                    BUCKET_SIZE_MS.toString(),
                    BUCKET_SIZE_MS.toString(),
                    startMs.toString()
                )
            )
            val map = mutableMapOf<Long, Int>()
            cursor.use {
                while (it.moveToNext()) {
                    map[it.getLong(0)] = it.getInt(1)
                }
            }
            map
        } ?: emptyMap()

        // Build 12 evenly-spaced buckets
        val bucketStarts = (0 until BUCKET_COUNT).map { i ->
            val bucketIndex = (startMs / BUCKET_SIZE_MS) + i
            bucketIndex * BUCKET_SIZE_MS
        }

        val counts = bucketStarts.map { bs -> buckets[bs] ?: 0 }
        val maxCount = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
        val maxBarHeightDp = 60
        val density = resources.displayMetrics.density
        val maxBarHeightPx = (maxBarHeightDp * density).toInt()
        val minBarHeightPx = (2 * density).toInt()

        val timeLabelFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

        bucketStarts.forEachIndexed { i, bucketStart ->
            val count = counts[i]
            val barHeightPx = if (count == 0) minBarHeightPx
            else ((count.toFloat() / maxCount) * maxBarHeightPx).toInt().coerceAtLeast(minBarHeightPx)

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(2, 0, 2, 0)
            }

            // Count label (top)
            val countLabel = TextView(this).apply {
                text = if (count > 0) count.toString() else ""
                textSize = 8f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_secondary))
            }
            col.addView(countLabel)

            // Bar
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    barHeightPx
                )
                val alpha = if (count == 0) 0.2f else 0.85f
                setBackgroundColor(Color.argb((alpha * 255).toInt(), 99, 102, 241)) // primary indigo
            }
            col.addView(bar)

            // Time label
            val timeLabel = TextView(this).apply {
                text = timeLabelFmt.format(Date(bucketStart))
                textSize = 7f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_secondary))
            }
            col.addView(timeLabel)

            llHistogram.addView(col)
        }
    }

    private fun updateFlushLog() {
        tvFlushLog.text = if (flushHistory.isEmpty()) {
            "No flushes this session"
        } else {
            flushHistory.joinToString("\n")
        }
    }

    private fun <T> queryDb(block: (SQLiteDatabase) -> T): T? {
        val dbFile = getDiskDbFile() ?: return null
        if (!dbFile.exists()) return null
        return try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
            db.use { block(it) }
        } catch (e: SQLiteException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(refreshRunnable)
    }
}
