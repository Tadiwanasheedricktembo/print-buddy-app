package com.tadiwaprintbuddy.app

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.data.Photo
import com.tadiwaprintbuddy.app.databinding.ItemPhotoBinding

class PhotoAdapter(private var photos: List<Photo>) : RecyclerView.Adapter<PhotoAdapter.ViewHolder>() {

    fun updatePhotos(newPhotos: List<Photo>) {
        photos = newPhotos
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(photos[position])
    }

    override fun getItemCount(): Int = photos.size

    inner class ViewHolder(private val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(photo: Photo) {
            val fullPath = StorageUtils.getFullImagePath(binding.root.context, photo.filePath)
            binding.imagePhoto.setImageURI(Uri.parse(fullPath))
        }
    }
}
