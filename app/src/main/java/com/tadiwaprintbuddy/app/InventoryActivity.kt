package com.tadiwaprintbuddy.app

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tadiwaprintbuddy.app.data.AppDatabase
import com.tadiwaprintbuddy.app.data.PrintRepository
import com.tadiwaprintbuddy.app.data.StockItem
import com.tadiwaprintbuddy.app.databinding.ActivityInventoryBinding
import com.tadiwaprintbuddy.app.databinding.DialogAddStockBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class InventoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInventoryBinding
    private lateinit var repository: PrintRepository
    private lateinit var adapter: StockAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInventoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val database = AppDatabase.getDatabase(this)
        repository = PrintRepository(database.printDao())

        setupToolbar()
        setupRecyclerView()
        setupClickListeners()
        observeStock()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = StockAdapter { item -> showEditDialog(item) }
        binding.recyclerInventory.layoutManager = LinearLayoutManager(this)
        binding.recyclerInventory.adapter = adapter
    }

    private fun setupClickListeners() {
        binding.btnAddStock.setOnClickListener { showAddDialog() }
    }

    private fun observeStock() {
        lifecycleScope.launch {
            repository.getAllStockItemsFlow().collectLatest { items ->
                adapter.submitList(items)
            }
        }
    }

    private fun showAddDialog() {
        val dialogBinding = DialogAddStockBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_stock_item)
            .setView(dialogBinding.root)
            .setPositiveButton("Add") { _, _ ->
                val name = dialogBinding.editItemName.text.toString()
                val qty = dialogBinding.editQuantity.text.toString().toIntOrNull() ?: 0
                val threshold = dialogBinding.editThreshold.text.toString().toIntOrNull() ?: 10
                val unit = dialogBinding.editUnit.text.toString().ifBlank { "pcs" }

                if (name.isNotBlank()) {
                    lifecycleScope.launch {
                        repository.addOrUpdateStockItem(StockItem(name = name, currentQuantity = qty, lowStockThreshold = threshold, unit = unit))
                    }
                } else {
                    Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditDialog(item: StockItem) {
        val dialogBinding = DialogAddStockBinding.inflate(layoutInflater)
        dialogBinding.editItemName.setText(item.name)
        dialogBinding.editQuantity.setText(item.currentQuantity.toString())
        dialogBinding.editThreshold.setText(item.lowStockThreshold.toString())
        dialogBinding.editUnit.setText(item.unit)

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_stock_item)
            .setView(dialogBinding.root)
            .setPositiveButton("Update") { _, _ ->
                val qty = dialogBinding.editQuantity.text.toString().toIntOrNull() ?: 0
                val threshold = dialogBinding.editThreshold.text.toString().toIntOrNull() ?: 10
                
                lifecycleScope.launch {
                    repository.addOrUpdateStockItem(item.copy(currentQuantity = qty, lowStockThreshold = threshold))
                }
            }
            .setNeutralButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repository.deleteStockItem(item)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
