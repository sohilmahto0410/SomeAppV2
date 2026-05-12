package com.sohil.icaibatchmonitor

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecyclerView adapter that shows each monitored config as a card.
 */
class MonitoredConfigAdapter(
    private val onDelete: (MonitorConfig) -> Unit
) : RecyclerView.Adapter<MonitoredConfigAdapter.ViewHolder>() {

    private val items = mutableListOf<MonitorConfig>()

    fun submitList(newItems: List<MonitorConfig>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_monitored_config, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCourse: TextView = itemView.findViewById(R.id.tvCourse)
        private val tvLocation: TextView = itemView.findViewById(R.id.tvLocation)
        private val tvLastChecked: TextView = itemView.findViewById(R.id.tvLastChecked)
        private val tvBatchCount: TextView = itemView.findViewById(R.id.tvBatchCount)
        private val btnDelete: Button = itemView.findViewById(R.id.btnDelete)

        fun bind(config: MonitorConfig) {
            tvCourse.text = config.courseLabel
            tvLocation.text = "${config.pouLabel} · ${config.regionLabel}"
            tvBatchCount.text = if (config.lastKnownBatchKeys.isEmpty()) {
                "Not checked yet"
            } else {
                "${config.lastKnownBatchKeys.size} batches tracked"
            }

            tvLastChecked.text = if (config.lastCheckedAt == 0L) {
                "Never checked"
            } else {
                val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
                "Last checked: ${sdf.format(Date(config.lastCheckedAt))}"
            }

            btnDelete.setOnClickListener { onDelete(config) }
        }
    }
}
