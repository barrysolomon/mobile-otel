package com.mobile.observability.demo.network

import android.util.Log
import com.google.gson.Gson
import com.mobile.observability.demo.data.EventEntity
import com.mobile.observability.demo.workflow.DSLConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

class GatewayClient(
    private val baseUrl: String,
    private val authToken: String? = null
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .apply {
            // Add auth token interceptor if token is provided
            if (authToken != null) {
                addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $authToken")
                        .build()
                    chain.proceed(request)
                }
            }
        }
        .build()

    suspend fun getConfig(appId: String, deviceId: String): Result<DSLConfig> {
        return try {
            val url = "$baseUrl/config?app_id=$appId&device_id=$deviceId"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val config = gson.fromJson(body, DSLConfig::class.java)
                    Result.success(config)
                } else {
                    Result.failure(Exception("Empty response body"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get config", e)
            Result.failure(e)
        }
    }

    suspend fun ingestEvents(events: List<EventEntity>): Result<Unit> {
        return try {
            val mobileEvents = events.map { entity ->
                MobileEvent(
                    eventName = entity.eventName,
                    sessionId = entity.sessionId,
                    deviceId = entity.deviceId,
                    triggerId = entity.triggerId,
                    configVersion = entity.configVersion,
                    timestamp = entity.timestamp,
                    attributes = parseAttributes(entity.attributesJson)
                )
            }

            val requestBody = IngestRequest(mobileEvents)
            val json = gson.toJson(requestBody)

            val request = Request.Builder()
                .url("$baseUrl/ingest")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "Successfully ingested ${events.size} events")
                Result.success(Unit)
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ingest events", e)
            Result.failure(e)
        }
    }

    suspend fun sendHeartbeat(heartbeat: Heartbeat): Result<Unit> {
        return try {
            val json = gson.toJson(heartbeat)

            val request = Request.Builder()
                .url("$baseUrl/status")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "Heartbeat sent successfully")
                Result.success(Unit)
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send heartbeat", e)
            Result.failure(e)
        }
    }

    private fun parseAttributes(json: String): Map<String, Any> {
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(json, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse attributes JSON", e)
            emptyMap()
        }
    }

    companion object {
        private const val TAG = "GatewayClient"
    }
}

data class MobileEvent(
    val eventName: String,
    val sessionId: String,
    val deviceId: String,
    val triggerId: String? = null,
    val configVersion: Int,
    val timestamp: Long,
    val attributes: Map<String, Any>
)

data class IngestRequest(
    val events: List<MobileEvent>
)

data class Heartbeat(
    val deviceId: String,
    val appId: String,
    val sessionId: String,
    val bufferUsageMb: Double,
    val lastTriggers: List<String>,
    val configVersion: Int
)
