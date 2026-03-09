// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.appointments

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.demo.data.AppointmentRepository
import io.opentelemetry.android.demo.data.AppointmentRepository.ApiException
import io.opentelemetry.android.demo.data.model.Appointment
import io.opentelemetry.android.demo.data.model.AppointmentStatus
import kotlinx.coroutines.launch

class AppointmentsFragment : Fragment() {

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvError: TextView
    private lateinit var adapter: AppointmentAdapter

    // Local mutable copy so swipe status updates are reflected immediately.
    private val currentList = mutableListOf<Appointment>()

    private val bgPaint = Paint()
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.LEFT
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_appointments, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipeRefresh = view.findViewById(R.id.swipeRefresh)
        recyclerView = view.findViewById(R.id.appointmentsRecyclerView)
        tvError = view.findViewById(R.id.tvError)

        adapter = AppointmentAdapter()
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        attachSwipeActions()

        swipeRefresh.setOnRefreshListener { loadAppointments(triggeredByPull = true) }

        currentList.addAll(AppointmentRepository.getMockAppointments())
        adapter.submitList(currentList.toList())
        loadAppointments(triggeredByPull = false)
    }

    /**
     * Attaches ItemTouchHelper swipe gestures to the RecyclerView.
     *
     * Swipe left  → Cancel appointment  (red background, "✕ Cancel")
     * Swipe right → Confirm appointment (green background, "✓ Confirm")
     */
    private fun attachSwipeActions() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val position = holder.adapterPosition
                if (position == RecyclerView.NO_ID.toInt() || position >= currentList.size) return

                val appt = currentList[position]
                val isCancelling = direction == ItemTouchHelper.LEFT
                val newStatus = if (isCancelling) AppointmentStatus.CANCELLED else AppointmentStatus.CONFIRMED

                // Update list state and re-submit.
                currentList[position] = appt.copy(status = newStatus)
                adapter.submitList(currentList.toList())
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, holder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = holder.itemView
                    if (dX < 0) {
                        // Swiping left → red "Cancel" background
                        bgPaint.color = Color.parseColor("#D32F2F")
                        c.drawRect(
                            itemView.right.toFloat() + dX, itemView.top.toFloat(),
                            itemView.right.toFloat(), itemView.bottom.toFloat(), bgPaint
                        )
                        c.drawText(
                            "✕  Cancel",
                            itemView.right.toFloat() + dX + 24f,
                            itemView.top.toFloat() + (itemView.height / 2f) + 14f,
                            labelPaint
                        )
                    } else if (dX > 0) {
                        // Swiping right → green "Confirm" background
                        bgPaint.color = Color.parseColor("#388E3C")
                        c.drawRect(
                            itemView.left.toFloat(), itemView.top.toFloat(),
                            itemView.left.toFloat() + dX, itemView.bottom.toFloat(), bgPaint
                        )
                        c.drawText(
                            "✓  Confirm",
                            itemView.left.toFloat() + 24f,
                            itemView.top.toFloat() + (itemView.height / 2f) + 14f,
                            labelPaint
                        )
                    }
                }
                super.onChildDraw(c, rv, holder, dX, dY, actionState, isActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recyclerView)
    }

    private fun loadAppointments(triggeredByPull: Boolean) {
        swipeRefresh.isRefreshing = true
        tvError.isVisible = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val appointments = AppointmentRepository.fetchAppointments(requireContext())
                currentList.clear()
                currentList.addAll(appointments)
                adapter.submitList(currentList.toList())
                tvError.isVisible = false
            } catch (e: ApiException) {
                showError("Could not load appointments (HTTP ${e.httpStatus}): ${e.message}")
            } catch (e: Exception) {
                showError("Network error: ${e.message}")
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.isVisible = true
    }
}
