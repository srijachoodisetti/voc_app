package com.example.vocabularyapp.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.vocabularyapp.data.db.WordEntity
import com.example.vocabularyapp.databinding.ItemWordBinding

class WordAdapter(
    private val onPlayAudio: (String) -> Unit,
    private val onToggleLearned: (WordEntity) -> Unit,
    private val onDelete: (WordEntity) -> Unit,
    private val onItemClick: (WordEntity) -> Unit
) : ListAdapter<WordEntity, WordAdapter.WordViewHolder>(DiffCallback) {

    inner class WordViewHolder(val binding: ItemWordBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: WordEntity) {
            binding.tvWord.text = item.word.replaceFirstChar { it.uppercase() }
            
            if (!item.phonetic.isNullOrBlank()) {
                binding.tvPhonetic.visibility = View.VISIBLE
                binding.tvPhonetic.text = item.phonetic
            } else {
                binding.tvPhonetic.visibility = View.GONE
            }

            if (!item.partOfSpeech.isNullOrBlank()) {
                binding.tvPartOfSpeech.visibility = View.VISIBLE
                binding.tvPartOfSpeech.text = item.partOfSpeech
            } else {
                binding.tvPartOfSpeech.visibility = View.GONE
            }

            if (!item.meaning.isNullOrBlank()) {
                binding.tvMeaning.visibility = View.VISIBLE
                binding.tvMeaning.text = item.meaning
            } else {
                binding.tvMeaning.visibility = View.GONE
            }

            if (!item.example.isNullOrBlank()) {
                binding.tvExample.visibility = View.VISIBLE
                binding.tvExample.text = "Example: ${item.example}"
            } else {
                binding.tvExample.visibility = View.GONE
            }

            if (!item.audioUrl.isNullOrBlank()) {
                binding.btnPlayAudio.visibility = View.VISIBLE
                binding.btnPlayAudio.setOnClickListener {
                    onPlayAudio(item.audioUrl)
                }
            } else {
                binding.btnPlayAudio.visibility = View.GONE
            }

            binding.switchLearned.setOnCheckedChangeListener(null)
            binding.switchLearned.isChecked = item.isLearned

            if (item.isLearned) {
                binding.chipStatus.text = "Learned"
                binding.chipStatus.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                binding.chipStatus.setTextColor(Color.parseColor("#2E7D32"))
            } else {
                binding.chipStatus.text = "Not Learned"
                binding.chipStatus.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                binding.chipStatus.setTextColor(Color.parseColor("#E65100"))
            }

            binding.switchLearned.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked != item.isLearned) {
                    onToggleLearned(item)
                }
            }

            binding.btnDelete.setOnClickListener {
                onDelete(item)
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WordViewHolder {
        val binding = ItemWordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WordViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object DiffCallback : DiffUtil.ItemCallback<WordEntity>() {
        override fun areItemsTheSame(oldItem: WordEntity, newItem: WordEntity): Boolean {
            return oldItem.word == newItem.word
        }

        override fun areContentsTheSame(oldItem: WordEntity, newItem: WordEntity): Boolean {
            return oldItem == newItem
        }
    }
}
