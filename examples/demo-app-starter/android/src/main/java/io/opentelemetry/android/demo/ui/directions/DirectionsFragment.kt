// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.directions

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.demo.data.api.SchedulingApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.random.Random

class DirectionsFragment : Fragment() {

    private lateinit var btnGetDirections: Button
    private lateinit var progressIndicator: CircularProgressIndicator
    private lateinit var tvStatus: TextView
    private lateinit var tvOrigin: TextView
    private lateinit var tvDestination: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvSteps: TextView
    private lateinit var cardResults: View
    private lateinit var tvError: TextView

    // Default to San Francisco when location permission is unavailable
    private var currentLat = 37.7749
    private var currentLon = -122.4194

    private val locationPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                      permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) tryGetLocation()
        fetchDirections()  // proceed regardless — fall back to default coords
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_directions, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnGetDirections  = view.findViewById(R.id.btnGetDirections)
        progressIndicator = view.findViewById(R.id.progressIndicator)
        tvStatus          = view.findViewById(R.id.tvStatus)
        tvOrigin          = view.findViewById(R.id.tvOrigin)
        tvDestination     = view.findViewById(R.id.tvDestination)
        tvDistance        = view.findViewById(R.id.tvDistance)
        tvDuration        = view.findViewById(R.id.tvDuration)
        tvSteps           = view.findViewById(R.id.tvSteps)
        cardResults       = view.findViewById(R.id.cardResults)
        tvError           = view.findViewById(R.id.tvError)

        btnGetDirections.setOnClickListener { onGetDirectionsClicked() }
    }

    private fun onGetDirectionsClicked() {
        cardResults.isVisible = false
        tvError.isVisible = false
        val hasPermission = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            tryGetLocation()
            fetchDirections()
        } else {
            locationPermissionRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun tryGetLocation() {
        try {
            val lm = requireContext().getSystemService(android.content.Context.LOCATION_SERVICE) as LocationManager
            val location: Location? =
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (location != null) {
                currentLat = location.latitude
                currentLon = location.longitude
            }
        } catch (_: SecurityException) { /* use default */ }
    }

    /**
     * Fetches directions using two real HTTP calls:
     *  1. Nominatim (OpenStreetMap) — find a nearby medical office
     *  2. OSRM routing — get driving directions to that office
     */
    private fun fetchDirections() {
        setLoading(true)
        tvStatus.text = "Finding nearby offices…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    fetchDirectionsOnIo()
                }

                setLoading(false)
                showResults(result)

            } catch (e: Exception) {
                setLoading(false)
                showError(e.message ?: "Could not get directions")
            }
        }
    }

    /**
     * Runs on the IO thread. Makes two real HTTP calls.
     */
    private fun fetchDirectionsOnIo(): DirectionsResult {
        val client = SchedulingApiClient.getInstance(requireContext())

        // Generate a random office location within ~30 miles of current position
        val latOffset = (Random.nextDouble() - 0.5) * 0.87   // ±0.43° ≈ ±30 miles
        val lonOffset = (Random.nextDouble() - 0.5) * 0.87
        val destLat = currentLat + latOffset
        val destLon = currentLon + lonOffset

        // HTTP Call 1: Nominatim search — finds the name of a real place near the destination.
        val nominatimUrl = "https://nominatim.openstreetmap.org/reverse" +
            "?lat=$destLat&lon=$destLon&format=json&zoom=16"
        val nominatimJson = try {
            client.getWithHeader(nominatimUrl, "User-Agent" to "Schedulr-Demo/1.0")
        } catch (e: Exception) {
            "{}"  // non-fatal — fall back to generic name
        }
        val officeName = parseOfficeName(nominatimJson, destLat, destLon)

        // HTTP Call 2: OSRM routing — returns driving distance and duration.
        val osrmUrl = "https://router.project-osrm.org/route/v1/driving" +
            "/$currentLon,$currentLat;$destLon,$destLat?overview=false&steps=true"
        val osrmJson = client.get(osrmUrl)

        return parseOsrmRoute(osrmJson, officeName, destLat, destLon)
    }

    private fun parseOfficeName(json: String, lat: Double, lon: Double): String {
        return try {
            val obj = JSONObject(json)
            val address = obj.optJSONObject("address")
            val name = obj.optString("name", "")
                .takeIf { it.isNotEmpty() }
                ?: address?.optString("road", "")?.takeIf { it.isNotEmpty() }
                ?: "Medical Office"
            val city = address?.optString("city", "")
                ?: address?.optString("town", "")
                ?: address?.optString("suburb", "")
            if (!city.isNullOrEmpty()) "$name, $city" else name
        } catch (_: Exception) {
            "Medical Office (${String.format("%.3f", lat)}, ${String.format("%.3f", lon)})"
        }
    }

    private fun parseOsrmRoute(json: String, officeName: String, destLat: Double, destLon: Double): DirectionsResult {
        return try {
            val routes = JSONObject(json).getJSONArray("routes")
            val route = routes.getJSONObject(0)
            val leg = route.getJSONArray("legs").getJSONObject(0)
            val distanceM = route.getDouble("distance").toLong()
            val durationS = route.getDouble("duration").toLong()

            val steps = mutableListOf<String>()
            val stepsArray: JSONArray = leg.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until minOf(stepsArray.length(), 5)) {
                val step = stepsArray.getJSONObject(i)
                val maneuver = step.optJSONObject("maneuver")
                val type = maneuver?.optString("type", "") ?: ""
                val modifier = maneuver?.optString("modifier", "") ?: ""
                val name = step.optString("name", "").takeIf { it.isNotEmpty() } ?: "road"
                val distM = step.optDouble("distance", 0.0).roundToInt()
                val instruction = when {
                    type == "depart" -> "Start on $name"
                    type == "arrive" -> "Arrive at destination"
                    modifier.isNotEmpty() -> "Turn $modifier onto $name ($distM m)"
                    else -> "Continue on $name ($distM m)"
                }
                steps.add(instruction)
            }

            DirectionsResult(
                officeName    = officeName,
                destLat       = destLat,
                destLon       = destLon,
                distanceMeters = distanceM,
                durationSeconds = durationS,
                steps         = steps
            )
        } catch (e: Exception) {
            // If OSRM parse fails, compute straight-line distance as fallback
            val dx = (destLon - currentLon) * 111_000 * Math.cos(Math.toRadians(currentLat))
            val dy = (destLat - currentLat) * 111_000
            val straightLine = Math.sqrt(dx * dx + dy * dy).toLong()
            DirectionsResult(
                officeName     = officeName,
                destLat        = destLat,
                destLon        = destLon,
                distanceMeters = straightLine,
                durationSeconds = straightLine / 10,
                steps          = listOf("Route details unavailable")
            )
        }
    }

    private fun setLoading(loading: Boolean) {
        btnGetDirections.isEnabled = !loading
        progressIndicator.isVisible = loading
        if (loading) tvStatus.isVisible = true
    }

    private fun showResults(result: DirectionsResult) {
        tvStatus.isVisible = false
        tvError.isVisible = false
        cardResults.isVisible = true

        val originLabel = if (currentLat == 37.7749 && currentLon == -122.4194)
            "San Francisco, CA (default)" else
            "%.4f, %.4f".format(currentLat, currentLon)

        tvOrigin.text      = "From: $originLabel"
        tvDestination.text = "To: ${result.officeName}"
        tvDistance.text    = formatDistance(result.distanceMeters)
        tvDuration.text    = formatDuration(result.durationSeconds)
        tvSteps.text       = result.steps.joinToString("\n")
    }

    private fun showError(msg: String) {
        tvStatus.isVisible = false
        tvError.text = "Error: $msg"
        tvError.isVisible = true
    }

    private fun formatDistance(meters: Long): String {
        return if (meters >= 1609) "%.1f mi".format(meters / 1609.0) else "$meters m"
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 -> "${hours}h ${minutes}min"
            else -> "${minutes} min"
        }
    }

    private data class DirectionsResult(
        val officeName: String,
        val destLat: Double,
        val destLon: Double,
        val distanceMeters: Long,
        val durationSeconds: Long,
        val steps: List<String>
    )
}
