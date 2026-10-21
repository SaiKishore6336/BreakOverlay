package com.breakoverlay.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.breakoverlay.app.R
import com.breakoverlay.app.data.DailyLimit
import com.breakoverlay.app.databinding.ItemAppBinding
import com.breakoverlay.app.domain.DurationFormatter
import com.breakoverlay.app.domain.InstalledApp
import kotlin.math.roundToInt

data class AppRowModel(
    val app: InstalledApp,
    val usageMs: Long,
    val usageEstimated: Boolean,
    val usageAvailable: Boolean,
    val unavailableUsageTextRes: Int = R.string.app_usage_unavailable,
    val limit: DailyLimit?,
    val selected: Boolean,
) {
    val isLimitReached: Boolean
        get() = limit != null &&
            limit.enabled &&
            usageAvailable &&
            usageMs >= limit.minutes.toLong() * 60_000L
}

class AppListAdapter(
    private val listener: Listener,
) : ListAdapter<AppRowModel, AppListAdapter.AppViewHolder>(DiffCallback) {

    interface Listener {
        fun onSelectionChanged(packageName: String, selected: Boolean)
        fun onOpenLimit(packageName: String)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppViewHolder(
        private val binding: ItemAppBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: AppRowModel) {
            val context = binding.root.context
            binding.appLabel.text = row.app.label
            binding.appIcon.setImageDrawable(
                row.app.icon ?: ContextCompat.getDrawable(context, R.mipmap.ic_launcher),
            )

            bindUsage(context, row)
            bindLimitBadge(context, row)
            bindSelection(context, row)

            binding.itemAppCard.setOnClickListener {
                listener.onOpenLimit(row.app.packageName)
            }
            binding.itemAppCard.contentDescription = context.getString(
                R.string.app_open_limit_description,
                row.app.label,
            )
        }

        private fun bindUsage(context: Context, row: AppRowModel) {
            binding.appUsage.text = when {
                !row.usageAvailable -> context.getString(row.unavailableUsageTextRes)
                row.usageMs <= 0L -> context.getString(R.string.app_usage_none)
                row.usageEstimated -> context.getString(
                    R.string.app_usage_estimated,
                    DurationFormatter.compact(row.usageMs),
                )
                else -> context.getString(
                    R.string.app_usage_measured,
                    DurationFormatter.compact(row.usageMs),
                )
            }
            binding.appUsage.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (row.isLimitReached) R.color.error else R.color.text_secondary,
                ),
            )
        }

        private fun bindLimitBadge(context: Context, row: AppRowModel) {
            val limit = row.limit
            if (limit == null) {
                binding.appLimitBadge.visibility = android.view.View.GONE
                return
            }
            binding.appLimitBadge.visibility = android.view.View.VISIBLE
            when {
                !limit.enabled -> {
                    binding.appLimitBadge.text =
                        context.getString(R.string.app_limit_badge_paused)
                    setBadgeTone(
                        context,
                        R.drawable.bg_badge_neutral,
                        R.color.badge_neutral_text,
                    )
                }
                row.isLimitReached -> {
                    binding.appLimitBadge.text =
                        context.getString(R.string.app_limit_badge_reached)
                    setBadgeTone(
                        context,
                        R.drawable.bg_badge_error,
                        R.color.badge_error_text,
                    )
                }
                else -> {
                    binding.appLimitBadge.text =
                        context.getString(R.string.app_limit_badge, limit.minutes)
                    setBadgeTone(
                        context,
                        R.drawable.bg_badge_success,
                        R.color.badge_success_text,
                    )
                }
            }
        }

        private fun setBadgeTone(context: Context, backgroundRes: Int, textColorRes: Int) {
            binding.appLimitBadge.setBackgroundResource(backgroundRes)
            binding.appLimitBadge.setTextColor(ContextCompat.getColor(context, textColorRes))
        }

        private fun bindSelection(context: Context, row: AppRowModel) {
            binding.appCheckbox.setOnCheckedChangeListener(null)
            binding.appCheckbox.isChecked = row.selected
            binding.appCheckbox.contentDescription = context.getString(
                R.string.app_select_description,
                row.app.label,
            )
            binding.appCheckbox.setOnCheckedChangeListener { _, isChecked ->
                listener.onSelectionChanged(row.app.packageName, isChecked)
            }

            val selectedBackground = ContextCompat.getColor(context, R.color.selected_row)
            val normalBackground = ContextCompat.getColor(context, R.color.surface_variant)
            binding.itemAppCard.setCardBackgroundColor(
                if (row.selected) selectedBackground else normalBackground,
            )
            binding.itemAppCard.strokeColor = ContextCompat.getColor(
                context,
                if (row.selected) R.color.brand_primary else R.color.transparent,
            )
            binding.itemAppCard.strokeWidth = if (row.selected) context.dp(1f) else 0
        }
    }

    private companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<AppRowModel>() {
            override fun areItemsTheSame(oldItem: AppRowModel, newItem: AppRowModel): Boolean =
                oldItem.app.packageName == newItem.app.packageName

            override fun areContentsTheSame(oldItem: AppRowModel, newItem: AppRowModel): Boolean =
                oldItem == newItem
        }
    }
}

private fun Context.dp(value: Float): Int =
    (value * resources.displayMetrics.density).roundToInt()
