package com.example.vocabularyapp

import android.Manifest
import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vocabularyapp.data.repository.WordDetail
import com.example.vocabularyapp.databinding.ActivityMainBinding
import com.example.vocabularyapp.ui.WordAdapter
import com.example.vocabularyapp.viewmodel.FilterMode
import com.example.vocabularyapp.viewmodel.LookupUiState
import com.example.vocabularyapp.viewmodel.MainViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var wordAdapter: WordAdapter
    private var currentWordDetail: WordDetail? = null
    private var tempPhotoUri: Uri? = null

    private var searchRunnable: Runnable? = null
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            dispatchTakePictureIntent()
        } else {
            Toast.makeText(this, "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoUri != null) {
            binding.ivPreview.visibility = View.VISIBLE
            binding.ivPreview.setImageURI(tempPhotoUri)
            viewModel.processImageUri(tempPhotoUri!!)
        } else {
            try {
                takePhotoPreviewLauncher.launch(null)
            } catch (_: Exception) {
                Toast.makeText(this, "Failed to capture photo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val takePhotoPreviewLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            binding.ivPreview.visibility = View.VISIBLE
            binding.ivPreview.setImageBitmap(bitmap)
            viewModel.processImageBitmap(bitmap)
        }
    }

    private val pickGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            binding.ivPreview.visibility = View.VISIBLE
            binding.ivPreview.setImageURI(uri)
            viewModel.processImageUri(uri)
        }
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data != null && handleSignInResult(data)) {
            return@registerForActivityResult
        }
        launchSystemAccountPicker()
    }

    private val accountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            if (!email.isNullOrBlank()) {
                val name = email.substringBefore("@").replaceFirstChar { it.uppercase() }
                viewModel.saveUserSession(name, email, null)
                Toast.makeText(this, "Signed in as $email", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
        }
        showManualSignInDialog()
    }

    private fun handleSignInResult(intent: Intent?): Boolean {
        if (intent == null) return false

        // 1. Standard GoogleSignIn task result
        val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account != null && !account.email.isNullOrBlank()) {
                viewModel.setUserAccount(account)
                Toast.makeText(this, "Signed in as ${account.email}", Toast.LENGTH_SHORT).show()
                return true
            }
        } catch (e: Exception) {
            Log.d("MainActivity", "GoogleSignIn task exception, checking intent fallback: ${e.message}")
        }

        // 2. Extra GoogleSignInAccount from intent
        val accountFromExtra = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("googleSignInAccount", GoogleSignInAccount::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("googleSignInAccount")
            }
        } catch (_: Exception) { null }

        if (accountFromExtra != null && !accountFromExtra.email.isNullOrBlank()) {
            viewModel.setUserAccount(accountFromExtra)
            Toast.makeText(this, "Signed in as ${accountFromExtra.email}", Toast.LENGTH_SHORT).show()
            return true
        }

        // 3. android.accounts.Account extra ("authAccount")
        val androidAccount = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("authAccount", android.accounts.Account::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("authAccount")
            }
        } catch (_: Exception) { null }

        if (androidAccount != null && androidAccount.name.isNotBlank()) {
            val email = androidAccount.name
            val name = email.substringBefore("@").replaceFirstChar { it.uppercase() }
            viewModel.saveUserSession(name, email, null)
            Toast.makeText(this, "Signed in as $email", Toast.LENGTH_SHORT).show()
            return true
        }

        // 4. String extra for account name
        val accountName = intent.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            ?: intent.getStringExtra("authAccount")
        if (!accountName.isNullOrEmpty() && accountName.contains("@")) {
            val name = accountName.substringBefore("@").replaceFirstChar { it.uppercase() }
            viewModel.saveUserSession(name, accountName, null)
            Toast.makeText(this, "Signed in as $accountName", Toast.LENGTH_SHORT).show()
            return true
        }

        // 5. Last signed in account from SDK
        val lastAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (lastAccount != null && !lastAccount.email.isNullOrBlank()) {
            viewModel.setUserAccount(lastAccount)
            Toast.makeText(this, "Signed in as ${lastAccount.email}", Toast.LENGTH_SHORT).show()
            return true
        }

        return false
    }

    private fun launchGoogleSignIn() {
        try {
            val signInIntent = googleSignInClient.signInIntent
            googleSignInLauncher.launch(signInIntent)
        } catch (_: Exception) {
            launchSystemAccountPicker()
        }
    }

    private fun launchSystemAccountPicker() {
        try {
            val intent = AccountManager.newChooseAccountIntent(
                null, null, arrayOf("com.google"), null, null, null, null
            )
            accountPickerLauncher.launch(intent)
        } catch (_: Exception) {
            showManualSignInDialog()
        }
    }

    private fun dispatchTakePictureIntent() {
        try {
            val photoFile = File.createTempFile(
                "JPEG_${System.currentTimeMillis()}_",
                ".jpg",
                cacheDir
            )
            val photoUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                photoFile
            )
            tempPhotoUri = photoUri
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error taking photo via FileProvider", e)
            try {
                takePhotoPreviewLauncher.launch(null)
            } catch (ex: Exception) {
                Toast.makeText(this, "Could not open camera: ${ex.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showManualSignInDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
        }

        val etEmail = android.widget.EditText(this).apply {
            hint = "Enter Email Address (e.g. user@gmail.com)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        val etName = android.widget.EditText(this).apply {
            hint = "Enter Display Name (e.g. John Doe)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        layout.addView(etEmail)
        layout.addView(etName)

        AlertDialog.Builder(this)
            .setTitle("Sign In to Vocabulary Builder")
            .setMessage("Enter your email address and name to sign in:")
            .setView(layout)
            .setPositiveButton("Sign In") { _, _ ->
                val email = etEmail.text.toString().trim()
                var name = etName.text.toString().trim()
                if (name.isEmpty() && email.contains("@")) {
                    name = email.substringBefore("@").replaceFirstChar { it.uppercase() }
                }
                if (name.isEmpty()) name = "User"

                if (email.isNotEmpty()) {
                    viewModel.saveUserSession(name, email, null)
                    Toast.makeText(this, "Signed in as $email", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Please enter a valid email address", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupGoogleSignIn()
        setupUI()
        setupSavedWordsRecyclerView()
        observeViewModel()
    }

    private fun setupGoogleSignIn() {
        val webClientId = getString(R.string.default_web_client_id)
        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            
        if (webClientId.isNotBlank() && webClientId != "your_web_client_id_here") {
            gsoBuilder.requestIdToken(webClientId)
        }
        
        googleSignInClient = GoogleSignIn.getClient(this, gsoBuilder.build())
    }

    private fun setupUI() {
        // Auth button
        binding.btnAuth.setOnClickListener {
            if (viewModel.userProfile.value != null) {
                try {
                    googleSignInClient.signOut()
                } catch (_: Exception) {}
                viewModel.signOut()
                Toast.makeText(this, "Signed out successfully", Toast.LENGTH_SHORT).show()
            } else {
                launchGoogleSignIn()
            }
        }

        // Camera & Gallery buttons
        binding.btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                dispatchTakePictureIntent()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        binding.btnPickGallery.setOnClickListener {
            pickGalleryLauncher.launch("image/*")
        }

        // Search bar
        binding.btnSearch.setOnClickListener {
            performSearch()
        }

        binding.etSearchWord.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)
            ) {
                performSearch()
                true
            } else {
                false
            }
        }

        binding.etSearchWord.doAfterTextChanged { text ->
            val query = text?.toString()?.trim().orEmpty()
            searchRunnable?.let { searchHandler.removeCallbacks(it) }
            if (query.isEmpty()) {
                viewModel.clearLookup()
            } else if (query.length >= 2) {
                searchRunnable = Runnable {
                    viewModel.lookupWord(query)
                }
                searchHandler.postDelayed(searchRunnable!!, 500)
            }
        }

        // Tabs
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        binding.containerScanTab.visibility = View.VISIBLE
                        binding.containerSavedTab.visibility = View.GONE
                    }
                    1 -> {
                        binding.containerScanTab.visibility = View.GONE
                        binding.containerSavedTab.visibility = View.VISIBLE
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // Detail card Save button
        binding.btnSaveWord.setOnClickListener {
            currentWordDetail?.let { detail ->
                val isLearned = binding.switchDetailLearned.isChecked
                viewModel.saveWord(detail, isLearned)
                Toast.makeText(this, "'${detail.word}' saved to your vocabulary!", Toast.LENGTH_SHORT).show()
            }
        }

        // Filter chips for saved list
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipFilterLearned -> viewModel.setFilterMode(FilterMode.LEARNED)
                R.id.chipFilterNotLearned -> viewModel.setFilterMode(FilterMode.NOT_LEARNED)
                else -> viewModel.setFilterMode(FilterMode.ALL)
            }
        }
    }

    private fun performSearch() {
        searchRunnable?.let { searchHandler.removeCallbacks(it) }
        val word = binding.etSearchWord.text.toString().trim()
        if (word.isNotEmpty()) {
            viewModel.lookupWord(word)
        } else {
            viewModel.clearLookup()
        }
    }

    private fun setupSavedWordsRecyclerView() {
        wordAdapter = WordAdapter(
            onPlayAudio = { audioUrl -> viewModel.playAudio(audioUrl) },
            onToggleLearned = { entity -> viewModel.toggleSavedWordLearned(entity) },
            onDelete = { entity -> 
                viewModel.deleteSavedWord(entity)
                Toast.makeText(this, "'${entity.word}' deleted", Toast.LENGTH_SHORT).show()
            },
            onItemClick = { entity ->
                // Switch to search tab & load word into search bar
                binding.tabLayout.getTabAt(0)?.select()
                binding.etSearchWord.setText(entity.word)
                binding.etSearchWord.setSelection(entity.word.length)
                viewModel.lookupWord(entity.word)
            }
        )

        binding.rvSavedWords.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = wordAdapter
        }
    }

    private fun observeViewModel() {
        // Auth user profile (Name, Email & Avatar)
        viewModel.userProfile.observe(this) { profile ->
            if (profile != null) {
                binding.tvUserName.text = profile.displayName
                binding.tvUserEmail.text = profile.email
                binding.tvUserEmail.visibility = if (profile.email.isNotBlank()) View.VISIBLE else View.GONE
                binding.btnAuth.text = "Sign Out"
                binding.ivUserAvatar.visibility = View.VISIBLE
            } else {
                binding.tvUserName.text = "Vocabulary App"
                binding.tvUserEmail.text = "Guest Mode"
                binding.tvUserEmail.visibility = View.VISIBLE
                binding.btnAuth.text = "Sign In"
                binding.ivUserAvatar.visibility = View.GONE
            }
        }

        viewModel.userAvatarBitmap.observe(this) { bitmap ->
            if (bitmap != null) {
                binding.ivUserAvatar.setImageBitmap(bitmap)
            } else {
                binding.ivUserAvatar.setImageResource(android.R.drawable.ic_menu_myplaces)
            }
        }

        // Text recognition loading
        viewModel.isRecognizingText.observe(this) { isLoading ->
            binding.layoutOcrLoading.visibility = if (isLoading) View.VISIBLE else View.GONE
        }

        // Scanned words
        viewModel.scannedWords.observe(this) { words ->
            binding.chipGroupWords.removeAllViews()
            if (words.isNullOrEmpty()) {
                binding.tvWordsHeader.visibility = View.GONE
            } else {
                binding.tvWordsHeader.visibility = View.VISIBLE
                for (word in words) {
                    val chip = Chip(this).apply {
                        text = word
                        isClickable = true
                        isCheckable = false
                        setOnClickListener {
                            binding.etSearchWord.setText(word)
                            binding.etSearchWord.setSelection(word.length)
                            viewModel.lookupWord(word)
                        }
                    }
                    binding.chipGroupWords.addView(chip)
                }
            }
        }

        // Dictionary Lookup state
        viewModel.lookupState.observe(this) { state ->
            when (state) {
                is LookupUiState.Idle -> {
                    binding.cardLookupResult.visibility = View.GONE
                }
                is LookupUiState.Loading -> {
                    binding.cardLookupResult.visibility = View.VISIBLE
                    binding.pbLookup.visibility = View.VISIBLE
                    binding.tvLookupError.visibility = View.GONE
                    binding.layoutLookupContent.visibility = View.GONE
                }
                is LookupUiState.Error -> {
                    binding.cardLookupResult.visibility = View.VISIBLE
                    binding.pbLookup.visibility = View.GONE
                    binding.tvLookupError.visibility = View.VISIBLE
                    binding.tvLookupError.text = state.message
                    binding.layoutLookupContent.visibility = View.GONE
                }
                is LookupUiState.Success -> {
                    currentWordDetail = state.detail
                    binding.cardLookupResult.visibility = View.VISIBLE
                    binding.pbLookup.visibility = View.GONE
                    binding.tvLookupError.visibility = View.GONE
                    binding.layoutLookupContent.visibility = View.VISIBLE

                    val detail = state.detail
                    binding.tvDetailWord.text = detail.word.replaceFirstChar { it.uppercase() }

                    if (!detail.phonetic.isNullOrBlank()) {
                        binding.tvDetailPhonetic.visibility = View.VISIBLE
                        binding.tvDetailPhonetic.text = detail.phonetic
                    } else {
                        binding.tvDetailPhonetic.visibility = View.GONE
                    }

                    if (!detail.partOfSpeech.isNullOrBlank()) {
                        binding.tvDetailPartOfSpeech.visibility = View.VISIBLE
                        binding.tvDetailPartOfSpeech.text = detail.partOfSpeech
                    } else {
                        binding.tvDetailPartOfSpeech.visibility = View.GONE
                    }

                    if (!detail.meaning.isNullOrBlank()) {
                        binding.tvDetailMeaning.visibility = View.VISIBLE
                        binding.tvDetailMeaning.text = detail.meaning
                    } else {
                        binding.tvDetailMeaning.visibility = View.GONE
                    }

                    if (!detail.example.isNullOrBlank()) {
                        binding.tvDetailExample.visibility = View.VISIBLE
                        binding.tvDetailExample.text = "Example: ${detail.example}"
                    } else {
                        binding.tvDetailExample.visibility = View.GONE
                    }

                    if (!detail.audioUrl.isNullOrBlank()) {
                        binding.btnDetailPlayAudio.visibility = View.VISIBLE
                        binding.btnDetailPlayAudio.setOnClickListener {
                            viewModel.playAudio(detail.audioUrl)
                        }
                    } else {
                        binding.btnDetailPlayAudio.visibility = View.GONE
                    }

                    binding.switchDetailLearned.isChecked = state.isLearned
                    binding.btnSaveWord.text = if (state.isSaved) "Saved ✓" else "Save Word"
                }
            }
        }

        // Saved words list
        viewModel.savedWords.observe(this) { list ->
            wordAdapter.submitList(list)
            if (list.isNullOrEmpty()) {
                binding.layoutEmptyState.visibility = View.VISIBLE
                binding.rvSavedWords.visibility = View.GONE
            } else {
                binding.layoutEmptyState.visibility = View.GONE
                binding.rvSavedWords.visibility = View.VISIBLE
            }
        }
    }
}
