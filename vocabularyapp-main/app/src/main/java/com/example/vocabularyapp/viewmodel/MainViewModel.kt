package com.example.vocabularyapp.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.lifecycle.*
import com.example.vocabularyapp.data.api.RetrofitInstance
import com.example.vocabularyapp.data.db.AppDatabase
import com.example.vocabularyapp.data.db.WordEntity
import com.example.vocabularyapp.data.repository.VocabularyRepository
import com.example.vocabularyapp.data.repository.WordDetail
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import android.graphics.BitmapFactory
import com.google.android.gms.auth.api.signin.GoogleSignIn
import java.net.URL

enum class FilterMode { ALL, LEARNED, NOT_LEARNED }

data class UserProfile(
    val displayName: String,
    val email: String,
    val photoUrl: Uri? = null
)

sealed class LookupUiState {
    object Idle : LookupUiState()
    object Loading : LookupUiState()
    data class Success(val detail: WordDetail, val isSaved: Boolean, val isLearned: Boolean) : LookupUiState()
    data class Error(val message: String) : LookupUiState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: VocabularyRepository
    private var auth: FirebaseAuth? = try { FirebaseAuth.getInstance() } catch (e: Exception) { null }

    private val _userProfile = MutableLiveData<UserProfile?>()
    val userProfile: LiveData<UserProfile?> = _userProfile

    private val _userAvatarBitmap = MutableLiveData<Bitmap?>()
    val userAvatarBitmap: LiveData<Bitmap?> = _userAvatarBitmap

    private val _scannedWords = MutableLiveData<List<String>>()
    val scannedWords: LiveData<List<String>> = _scannedWords

    private val _isRecognizingText = MutableLiveData<Boolean>(false)
    val isRecognizingText: LiveData<Boolean> = _isRecognizingText

    private val _lookupState = MutableLiveData<LookupUiState>(LookupUiState.Idle)
    val lookupState: LiveData<LookupUiState> = _lookupState

    private val _filterMode = MutableStateFlow(FilterMode.ALL)
    val filterMode: StateFlow<FilterMode> = _filterMode

    private val _isPlayingAudio = MutableLiveData<Boolean>(false)
    val isPlayingAudio: LiveData<Boolean> = _isPlayingAudio

    private var mediaPlayer: MediaPlayer? = null

    val savedWords: LiveData<List<WordEntity>> = _filterMode.flatMapLatest { mode ->
        when (mode) {
            FilterMode.ALL -> repository.getAllSavedWords()
            FilterMode.LEARNED -> repository.getSavedWordsByStatus(true)
            FilterMode.NOT_LEARNED -> repository.getSavedWordsByStatus(false)
        }
    }.asLiveData()

    private val prefs = application.getSharedPreferences("user_session", android.content.Context.MODE_PRIVATE)

    init {
        val dao = AppDatabase.getDatabase(application).wordDao()
        repository = VocabularyRepository(dao)

        loadSavedUserSession()
    }

    fun loadSavedUserSession() {
        val isLoggedIn = prefs.getBoolean("is_logged_in", false)
        if (isLoggedIn) {
            val name = prefs.getString("user_name", "User") ?: "User"
            val email = prefs.getString("user_email", "") ?: ""
            val photoUrlStr = prefs.getString("user_photo_url", null)
            val photoUri = photoUrlStr?.let { Uri.parse(it) }

            _userProfile.value = UserProfile(name, email, photoUri)
            if (photoUri != null) {
                loadAvatar(photoUri)
            }
        } else {
            val lastAccount = GoogleSignIn.getLastSignedInAccount(getApplication())
            if (lastAccount != null) {
                setUserAccount(lastAccount)
            } else {
                val user = auth?.currentUser
                if (user != null) {
                    saveUserSession(
                        name = user.displayName ?: user.email ?: "User",
                        email = user.email ?: "",
                        photoUri = user.photoUrl
                    )
                }
            }
        }
    }

    fun saveUserSession(name: String, email: String, photoUri: Uri? = null) {
        prefs.edit().apply {
            putBoolean("is_logged_in", true)
            putString("user_name", name)
            putString("user_email", email)
            putString("user_photo_url", photoUri?.toString())
            apply()
        }
        _userProfile.value = UserProfile(name, email, photoUri)
        if (photoUri != null) {
            loadAvatar(photoUri)
        } else {
            _userAvatarBitmap.value = null
        }
    }

    fun setUserAccount(account: GoogleSignInAccount) {
        val name = account.displayName ?: account.givenName ?: account.email ?: "User"
        val email = account.email ?: ""
        val photoUri = account.photoUrl

        saveUserSession(name, email, photoUri)

        val idToken = account.idToken
        val firebaseAuth = auth
        if (idToken != null && firebaseAuth != null) {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            firebaseAuth.signInWithCredential(credential)
        }
    }

    private fun loadAvatar(photoUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL(photoUri.toString())
                val connection = url.openConnection()
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val inputStream = connection.getInputStream()
                val bitmap = BitmapFactory.decodeStream(inputStream)
                _userAvatarBitmap.postValue(bitmap)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error loading user avatar", e)
            }
        }
    }

    fun signOut() {
        prefs.edit().clear().apply()
        try {
            auth?.signOut()
        } catch (e: Exception) {
            Log.e("MainViewModel", "Sign out error", e)
        }
        _userProfile.value = null
        _userAvatarBitmap.value = null
    }

    fun processImageUri(uri: Uri) {
        _isRecognizingText.value = true
        try {
            val image = InputImage.fromFilePath(getApplication(), uri)
            runTextRecognition(image)
        } catch (e: Exception) {
            _isRecognizingText.value = false
            _lookupState.value = LookupUiState.Error("Failed to load image: ${e.localizedMessage}")
        }
    }

    fun processImageBitmap(bitmap: Bitmap) {
        _isRecognizingText.value = true
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            runTextRecognition(image)
        } catch (e: Exception) {
            _isRecognizingText.value = false
            _lookupState.value = LookupUiState.Error("Failed to process photo: ${e.localizedMessage}")
        }
    }

    private fun runTextRecognition(image: InputImage) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                _isRecognizingText.value = false
                val wordsList = mutableListOf<String>()
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        for (element in line.elements) {
                            val clean = element.text.trim()
                                .trim('.', ',', '!', '?', ':', ';', '"', '\'', '(', ')', '[', ']', '{', '}')
                            if (clean.length > 1 && clean.all { it.isLetter() || it == '-' }) {
                                if (!wordsList.contains(clean.lowercase())) {
                                    wordsList.add(clean.lowercase())
                                }
                            }
                        }
                    }
                }
                _scannedWords.value = wordsList
            }
            .addOnFailureListener { e ->
                _isRecognizingText.value = false
                _lookupState.value = LookupUiState.Error("Text recognition failed: ${e.localizedMessage}")
            }
    }

    fun clearLookup() {
        _lookupState.value = LookupUiState.Idle
    }

    fun lookupWord(word: String) {
        val clean = word.trim().lowercase()
        if (clean.isBlank()) {
            clearLookup()
            return
        }

        _lookupState.value = LookupUiState.Loading

        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.fetchWordDefinition(clean)
            val savedEntity = repository.getSavedWord(clean)
            
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { detail ->
                        _lookupState.value = LookupUiState.Success(
                            detail = detail,
                            isSaved = savedEntity != null,
                            isLearned = savedEntity?.isLearned ?: false
                        )
                    },
                    onFailure = { error ->
                        if (savedEntity != null) {
                            val localDetail = WordDetail(
                                word = savedEntity.word,
                                phonetic = savedEntity.phonetic,
                                partOfSpeech = savedEntity.partOfSpeech,
                                meaning = savedEntity.meaning,
                                example = savedEntity.example,
                                audioUrl = savedEntity.audioUrl
                            )
                            _lookupState.value = LookupUiState.Success(
                                detail = localDetail,
                                isSaved = true,
                                isLearned = savedEntity.isLearned
                            )
                        } else {
                            _lookupState.value = LookupUiState.Error(
                                error.message ?: "Could not find definition for '$word'"
                            )
                        }
                    }
                )
            }
        }
    }

    fun saveWord(detail: WordDetail, isLearned: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val entity = WordEntity(
                word = detail.word.lowercase(),
                phonetic = detail.phonetic,
                partOfSpeech = detail.partOfSpeech,
                meaning = detail.meaning,
                example = detail.example,
                audioUrl = detail.audioUrl,
                isLearned = isLearned,
                timestamp = System.currentTimeMillis()
            )
            repository.saveWord(entity)
            withContext(Dispatchers.Main) {
                _lookupState.value = LookupUiState.Success(
                    detail = detail,
                    isSaved = true,
                    isLearned = isLearned
                )
            }
        }
    }

    fun toggleSavedWordLearned(entity: WordEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleLearnedState(entity)
        }
    }

    fun deleteSavedWord(entity: WordEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteWord(entity)
            withContext(Dispatchers.Main) {
                val currentLookup = _lookupState.value
                if (currentLookup is LookupUiState.Success && currentLookup.detail.word.equals(entity.word, ignoreCase = true)) {
                    _lookupState.value = currentLookup.copy(isSaved = false, isLearned = false)
                }
            }
        }
    }

    fun setFilterMode(mode: FilterMode) {
        _filterMode.value = mode
    }

    fun playAudio(audioUrl: String?) {
        if (audioUrl.isNullOrBlank()) return

        stopAudio()

        try {
            _isPlayingAudio.value = true
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(audioUrl)
                setOnPreparedListener {
                    start()
                }
                setOnCompletionListener {
                    _isPlayingAudio.postValue(false)
                    stopAudio()
                }
                setOnErrorListener { _, _, _ ->
                    _isPlayingAudio.postValue(false)
                    stopAudio()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Audio playback failed", e)
            _isPlayingAudio.value = false
            stopAudio()
        }
    }

    fun stopAudio() {
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error releasing MediaPlayer", e)
            }
        }
        mediaPlayer = null
    }

    override fun onCleared() {
        super.onCleared()
        stopAudio()
    }
}
