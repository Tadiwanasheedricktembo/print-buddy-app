package com.tadiwaprintbuddy.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import com.tadiwaprintbuddy.app.data.PrinterReference
import java.io.File

class FullScreenPhotoAdapter(private val references: List<PrinterReference>) : RecyclerView.Adapter<FullScreenPhotoAdapter.PhotoViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val photoView = LayoutInflater.from(parent.context).inflate(R.layout.item_fullscreen_photo, parent, false) as PhotoView
        return PhotoViewHolder(photoView)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        holder.bind(references[position])
    }

    override fun getItemCount(): Int = references.size

    inner class PhotoViewHolder(private val photoView: PhotoView) : RecyclerView.ViewHolder(photoView) {
        fun bind(reference: PrinterReference) {
            val fullPath = StorageUtils.getFullImagePath(photoView.context, reference.imagePath)
            photoView.setImageURI(Uri.fromFile(File(fullPath)))
        }
    }
}
