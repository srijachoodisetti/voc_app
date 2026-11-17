package com.example.vocabularyapp.data.repository

import com.example.vocabularyapp.data.api.DictionaryEntry
import com.example.vocabularyapp.data.api.RetrofitInstance
import com.example.vocabularyapp.data.db.WordDao
import com.example.vocabularyapp.data.db.WordEntity
import kotlinx.coroutines.flow.Flow

data class WordDetail(
    val word: String,
    val phonetic: String?,
    val partOfSpeech: String?,
    val meaning: String?,
    val example: String?,
    val audioUrl: String?
)

class VocabularyRepository(private val wordDao: WordDao) {

    suspend fun fetchWordDefinition(word: String): Result<WordDetail> {
        val cleanWord = word.trim().lowercase().filter { it.isLetter() || it == '-' }
        if (cleanWord.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter a valid word"))
        }

        return try {
            val response = RetrofitInstance.api.getWordDefinition(cleanWord)
            if (response.isSuccessful) {
                val entries: List<DictionaryEntry>? = response.body()
                if (!entries.isNullOrEmpty()) {
                    var phonetic: String? = null
                    var audioUrl: String? = null
                    var partOfSpeech: String? = null
                    var example: String? = null
                    val definitionsList = mutableListOf<String>()

                    for (entry in entries) {
                        if (phonetic.isNullOrBlank()) {
                            if (!entry.phonetic.isNullOrBlank()) {
                                phonetic = entry.phonetic
                            } else {
                                phonetic = entry.phonetics?.firstOrNull { !it.text.isNullOrBlank() }?.text
                            }
                        }

                        if (audioUrl.isNullOrBlank()) {
                            val audio = entry.phonetics?.firstOrNull { !it.audio.isNullOrBlank() }?.audio
                            if (!audio.isNullOrBlank()) {
                                audioUrl = if (audio.startsWith("//")) "https:$audio" else audio
                            }
                        }

                        for (meaningDto in entry.meanings.orEmpty()) {
                            val pos = meaningDto.partOfSpeech
                            if (partOfSpeech.isNullOrBlank() && !pos.isNullOrBlank()) {
                                partOfSpeech = pos
                            }

                            for (defDto in meaningDto.definitions.orEmpty()) {
                                val defText = defDto.definition?.trim()
                                if (!defText.isNullOrBlank()) {
                                    val formattedDef = if (!pos.isNullOrBlank()) "($pos) $defText" else defText
                                    if (!definitionsList.contains(formattedDef)) {
                                        definitionsList.add(formattedDef)
                                    }
                                }
                                if (example.isNullOrBlank() && !defDto.example.isNullOrBlank()) {
                                    example = defDto.example
                                }
                            }
                        }
                    }

                    if (definitionsList.isNotEmpty()) {
                        val combinedMeaning = if (definitionsList.size == 1) {
                            definitionsList.first()
                        } else {
                            definitionsList.take(3).mapIndexed { idx, d -> "${idx + 1}. $d" }.joinToString("\n")
                        }

                        val detail = WordDetail(
                            word = entries.firstOrNull()?.word ?: cleanWord,
                            phonetic = phonetic,
                            partOfSpeech = partOfSpeech,
                            meaning = combinedMeaning,
                            example = example,
                            audioUrl = audioUrl
                        )
                        Result.success(detail)
                    } else {
                        Result.failure(Exception("No definition found for '$cleanWord'"))
                    }
                } else {
                    Result.failure(Exception("Word '$cleanWord' not found"))
                }
            } else if (response.code() == 404) {
                Result.failure(Exception("Word '$cleanWord' not found in dictionary"))
            } else {
                Result.failure(Exception("Error fetching definition (${response.code()})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getAllSavedWords(): Flow<List<WordEntity>> = wordDao.getAllWords()

    fun getSavedWordsByStatus(isLearned: Boolean): Flow<List<WordEntity>> =
        wordDao.getWordsByStatus(isLearned)

    suspend fun isWordSaved(word: String): Boolean = wordDao.getWord(word) != null

    suspend fun getSavedWord(word: String): WordEntity? = wordDao.getWord(word)

    suspend fun saveWord(wordEntity: WordEntity) {
        wordDao.insertOrUpdate(wordEntity)
    }

    suspend fun toggleLearnedState(wordEntity: WordEntity) {
        val updated = wordEntity.copy(isLearned = !wordEntity.isLearned)
        wordDao.insertOrUpdate(updated)
    }

    suspend fun deleteWord(wordEntity: WordEntity) {
        wordDao.delete(wordEntity)
    }
}
