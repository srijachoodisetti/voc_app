package com.example.vocabularyapp.data.api

import com.google.gson.annotations.SerializedName

data class DictionaryEntry(
    val word: String? = null,
    val phonetic: String? = null,
    val phonetics: List<PhoneticDto>? = null,
    val meanings: List<MeaningDto>? = null
)

data class PhoneticDto(
    val text: String? = null,
    val audio: String? = null
)

data class MeaningDto(
    val partOfSpeech: String? = null,
    val definitions: List<DefinitionDto>? = null
)

data class DefinitionDto(
    val definition: String? = null,
    val example: String? = null
)

data class ApiErrorResponse(
    val title: String? = null,
    val message: String? = null,
    val resolution: String? = null
)
