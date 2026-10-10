package com.example

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class OptionAdapter(
    private val onTest: (Option) -> Unit,
    private val onKill: (Option) -> Unit,
    private val onEdit: (Option) -> Unit,
    private val onDelete: (Option) -> Unit
) : ListAdapter<Option, OptionAdapter.OptionViewHolder>(OptionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OptionViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_option, parent, false)
        return OptionViewHolder(view)
    }

    override fun onBindViewHolder(holder: OptionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class OptionViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_option_name)
        private val tvSource: TextView = itemView.findViewById(R.id.tv_option_source)
        private val tvDestination: TextView = itemView.findViewById(R.id.tv_option_destination)
        private val btnTest: MaterialButton = itemView.findViewById(R.id.btn_test_option)
        private val btnKill: MaterialButton = itemView.findViewById(R.id.btn_kill_option)
        private val btnEdit: MaterialButton = itemView.findViewById(R.id.btn_edit_option)
        private val btnDelete: MaterialButton = itemView.findViewById(R.id.btn_delete_option)

        fun bind(option: Option) {
            tvName.text = option.name
            tvSource.text = option.source
            tvDestination.text = option.destination

            btnTest.setOnClickListener { onTest(option) }
            btnKill.setOnClickListener { onKill(option) }
            btnEdit.setOnClickListener { onEdit(option) }
            btnDelete.setOnClickListener { onDelete(option) }
        }
    }

    class OptionDiffCallback : DiffUtil.ItemCallback<Option>() {
        override fun areItemsTheSame(oldItem: Option, newItem: Option): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Option, newItem: Option): Boolean =
            oldItem == newItem
    }
}
