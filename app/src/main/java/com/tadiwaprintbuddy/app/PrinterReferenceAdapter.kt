package com.tadiwaprintbuddy.app

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.PrinterReference
import com.tadiwaprintbuddy.app.databinding.ItemPrinterReferenceBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PrinterReferenceAdapter(
    private var references: List<PrinterReference>,
    private val onItemClicked: (Int) -> Unit // Pass position
) : RecyclerView.Adapter<PrinterReferenceAdapter.ViewHolder>() {

    fun updateReferences(newReferences: List<PrinterReference>) {
        references = newReferences
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPrinterReferenceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(references[position])
    }

    override fun getItemCount(): Int = references.size

    inner class ViewHolder(private val binding: ItemPrinterReferenceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(reference: PrinterReference) {
            val fullPath = StorageUtils.getFullImagePath(binding.root.context, reference.imagePath)
            binding.imageThumbnail.setImageURI(Uri.parse(fullPath))
            binding.textTitle.text = reference.title
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            binding.textDate.text = sdf.format(Date(reference.timestamp))

            itemView.setOnClickListener {
                onItemClicked(adapterPosition)
            }
        }
    }
}
