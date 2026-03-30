package com.tadiwaprintbuddy.app

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.tadiwaprintbuddy.app.databinding.ItemServiceBinding

class ServiceAdapter(
    private val services: List<Service>,
    private val onAddClick: (Service, Double) -> Unit
) : RecyclerView.Adapter<ServiceAdapter.ViewHolder>() {

    class ViewHolder(private val binding: ItemServiceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(service: Service, onAddClick: (Service, Double) -> Unit) {
            binding.textViewServiceName.text = service.name
            // Pre-fill the EditText with the default price
            binding.editServicePrice.setText(service.price.toString())

            binding.buttonAdd.setOnClickListener {
                val price = binding.editServicePrice.text.toString().toDoubleOrNull()
                if (price == null || price < 0) {
                    Toast.makeText(binding.root.context, "Please enter a valid price", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                Toast.makeText(
                    binding.root.context,
                    "Added: ${service.name}",
                    Toast.LENGTH_SHORT
                ).show()

                onAddClick(service, price)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemServiceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(services[position], onAddClick)
    }

    override fun getItemCount(): Int = services.size
}
