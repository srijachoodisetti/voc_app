package com.example.vocabularyapp.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "words")
data class WordEntity(
    @field:PrimaryKey val word: String,
    val phonetic: String? = null,
    val partOfSpeech: String? = null,
    val meaning: String? = null,
    val example: String? = null,
    val audioUrl: String? = null,
    val isLearned: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
