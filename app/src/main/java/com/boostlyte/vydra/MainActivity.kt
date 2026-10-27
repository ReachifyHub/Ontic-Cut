package com.boostlyte.vydra

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.os.Parcelable
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.boostlyte.vydra.databinding.ActivityMainBinding
import com.boostlyte.vydra.utils.ErrorCode
import com.boostlyte.vydra.utils.setBounceClickListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectAdapter: ProjectAdapter
    private val authStateListener: (Boolean, String?) -> Unit = { _, _ ->
        runOnUiThread {
            updateAccountSettingsUI()
        }
    }
    private val selectVideoLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri>? ->
            if (!uris.isNullOrEmpty()) {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                for (uri in uris) {
                    try {
                        contentResolver.takePersistableUriPermission(uri, takeFlags)
                    } catch (e: Exception) {
                        Log.e("VideoSelection", "Could not take persistable permission", e)
                    }
                }
                Log.d("VideoSelection", "Media selected: ${uris.size} item(s)")
                navigateToEditingScreen(uris)
            } else {
                Log.e("VideoSelectionError", "No video selected")
            }
        }

    private val openProjectLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    Log.e("ProjectSelection", "Could not take persistable permission for project URI", e)
                }
                Log.d("ProjectSelection", "Project selected: $uri")
                val intent = Intent(this, ProjectImportActivity::class.java).apply {
                    putExtra("PROJECT_URI", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                startActivity(intent)
            } else {
                Log.e("ProjectSelectionError", "No project selected")
            }
        }

    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences("vydra_prefs", MODE_PRIVATE)
                    prefs.edit().putString("export_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentExportFolder, R.string.str_default_movies_librecuts)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    private val selectAudioFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences("vydra_prefs", MODE_PRIVATE)
                    prefs.edit().putString("export_audio_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentAudioExportFolder, R.string.str_default_music_librecuts)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    private val selectSnapshotFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)

                    val prefs = getSharedPreferences("vydra_prefs", MODE_PRIVATE)
                    prefs.edit().putString("export_snapshot_directory_uri", uri.toString()).apply()
                    updateExportFolderUI(uri, binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_librecuts)
                } catch (e: Exception) {
                    Log.e("FolderSelectionError", "Error securing permission for URI", e)
                    showToast(getString(R.string.toast_failed_to_set_export_folder))
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupProjectsList()

        binding.btnImport.setBounceClickListener {
            Log.d("ButtonClick", "Launching video selection.")
            selectVideo()
        }

        binding.btnOpenProject.setBounceClickListener {
            Log.d("ButtonClick", "Launching project selection.")
            openProjectLauncher.launch(arrayOf("*/*"))
        }

        // Initialize bottom navigation tab backgrounds
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val ta = obtainStyledAttributes(attrs)
        val inactiveBg = ta.getDrawable(0)
        ta.recycle()
        binding.tabSettings.background = inactiveBg
        binding.tabAbout.background = inactiveBg

        // Setup bottom navigation tab switching
        binding.tabHome.setBounceClickListener {
            switchTab(0)
        }
        
        binding.tabSettings.setBounceClickListener {
            switchTab(1)
        }

        binding.tabAbout.setBounceClickListener {
            switchTab(2)
        }
        
        // Setup Settings Actions
        binding.btnChangeExportFolder.setBounceClickListener {
            selectFolderLauncher.launch(null)
        }
        binding.btnChangeAudioExportFolder.setBounceClickListener {
            selectAudioFolderLauncher.launch(null)
        }
        binding.btnChangeSnapshotExportFolder.setBounceClickListener {
            selectSnapshotFolderLauncher.launch(null)
        }
        binding.btnChangeLanguage.setBounceClickListener {
            showLanguageDialog()
        }
        
        binding.btnCheckForUpdates.setBounceClickListener {
            checkForUpdates()
        }
        
        binding.btnOpenSourceLicenses.setBounceClickListener {
            com.mikepenz.aboutlibraries.LibsBuilder()
                .withActivityTitle(getString(R.string.str_open_source_licenses))
                .withSearchEnabled(true)
                .start(this)
        }
        
        // Initialize Settings UI
        val prefs = getSharedPreferences("vydra_prefs", MODE_PRIVATE)
        val savedUriString = prefs.getString("export_directory_uri", null)
        if (savedUriString != null) {
            updateExportFolderUI(Uri.parse(savedUriString), binding.tvCurrentExportFolder, R.string.str_default_movies_librecuts)
        } else {
            updateExportFolderUI(null, binding.tvCurrentExportFolder, R.string.str_default_movies_librecuts)
        }

        val savedAudioUriString = prefs.getString("export_audio_directory_uri", null)
        if (savedAudioUriString != null) {
            updateExportFolderUI(Uri.parse(savedAudioUriString), binding.tvCurrentAudioExportFolder, R.string.str_default_music_librecuts)
        } else {
            updateExportFolderUI(null, binding.tvCurrentAudioExportFolder, R.string.str_default_music_librecuts)
        }

        val savedSnapshotUriString = prefs.getString("export_snapshot_directory_uri", null)
        if (savedSnapshotUriString != null) {
            updateExportFolderUI(Uri.parse(savedSnapshotUriString), binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_librecuts)
        } else {
            updateExportFolderUI(null, binding.tvCurrentSnapshotExportFolder, R.string.str_default_pictures_librecuts)
        }

        updateLanguageUI()

        // Initialize Haptic Feedback preference
        updateHapticFeedbackUI()

        binding.btnToggleHapticFeedback.setBounceClickListener {
            val current = prefs.getBoolean("haptic_feedback", true)
            prefs.edit().putBoolean("haptic_feedback", !current).apply()
            updateHapticFeedbackUI()
        }

        // Initialize Fullscreen Editor preference
        updateFullscreenEditorUI()

        binding.btnToggleFullscreenEditor.setBounceClickListener {
            val current = prefs.getBoolean("fullscreen_editor", true)
            prefs.edit().putBoolean("fullscreen_editor", !current).apply()
            updateFullscreenEditorUI()
        }

        // Initialize Default Encoder preference
        updateEncoderUI()

        binding.btnChangeDefaultEncoder.setBounceClickListener {
            showEncoderDialog()
        }

        // Initialize Supabase Account UI in Settings
        com.boostlyte.vydra.transcription.SupabaseAuthManager.addAuthStateListener(authStateListener)
        updateAccountSettingsUI()
        lifecycleScope.launch {
            com.boostlyte.vydra.transcription.SupabaseAuthManager.restoreSessionOnAppStart(this@MainActivity)
        }

        // Set dynamic About version tag
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvAboutVersion.text = "v${pInfo.versionName}"
        } catch (e: Exception) {
            binding.tvAboutVersion.text = "v0.1.0"
        }

        // About screen buttons
        binding.btnAboutWhatsApp.setBounceClickListener {
            com.boostlyte.vydra.utils.AppUrls.openSafeUrl(this, com.boostlyte.vydra.utils.AppUrls.WHATSAPP_COMMUNITY_URL)
        }
        binding.btnAboutReportBug.setBounceClickListener {
            com.boostlyte.vydra.utils.AppUrls.openSafeUrl(this, com.boostlyte.vydra.utils.AppUrls.BUG_REPORT_URL)
        }
        binding.btnAboutPrivacyPolicy.setBounceClickListener {
            LegalActivity.start(this, LegalActivity.DOC_PRIVACY_POLICY)
        }
        binding.btnAboutTermsOfUse.setBounceClickListener {
            LegalActivity.start(this, LegalActivity.DOC_TERMS_OF_USE)
        }
        binding.btnAboutOpenSourceLicenses.setBounceClickListener {
            com.mikepenz.aboutlibraries.LibsBuilder()
                .withActivityTitle(getString(R.string.str_open_source_licenses))
                .withSearchEnabled(true)
                .start(this)
        }

        // Onboarding / Welcome Dialog
        val isFirstLaunch = prefs.getBoolean("first_launch_v1", true)
        if (isFirstLaunch) {
            showOnboardingDialog(prefs)
        }

        // Handle shared/intent videos
        handleIntent(intent)
    }

    private fun updateExportFolderUI(uri: Uri?, textView: TextView, defaultStringResId: Int) {
        if (uri == null) {
            textView.text = getString(defaultStringResId)
        } else {
            try {
                val path = uri.lastPathSegment?.split(":")?.lastOrNull()
                if (!path.isNullOrEmpty()) {
                    textView.text = path
                } else {
                    textView.text = getString(R.string.str_custom_directory)
                }
            } catch (e: Exception) {
                textView.text = getString(R.string.str_custom_directory)
            }
        }
    }

    private data class LanguageItem(
        val tag: String,
        val displayName: String
    )

    private fun getAvailableLanguages(): List<LanguageItem> {
        val result = mutableListOf<LanguageItem>()
        result.add(LanguageItem("", getString(R.string.str_system_default)))

        val tags = mutableSetOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val localeConfig = android.app.LocaleConfig(this)
                val locales = localeConfig.supportedLocales
                if (locales != null) {
                    for (i in 0 until locales.size()) {
                        val locale = locales.get(i)
                        if (locale != null) {
                            tags.add(locale.toLanguageTag())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "LocaleConfig error", e)
            }
        }

        if (tags.isEmpty()) {
            try {
                val resId = resources.getIdentifier("_generated_res_locale_config", "xml", packageName)
                if (resId != 0) {
                    val parser = resources.getXml(resId)
                    var eventType = parser.eventType
                    while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                        if (eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "locale") {
                            val name = parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
                            if (!name.isNullOrEmpty()) {
                                tags.add(name)
                            }
                        }
                        eventType = parser.next()
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "XmlParser _generated_res_locale_config error", e)
            }
        }

        if (tags.isEmpty()) {
            tags.addAll(listOf("en", "de", "et", "sk", "pt-BR"))
        }

        val items = tags.map { tag ->
            val locale = java.util.Locale.forLanguageTag(tag)
            val name = when (tag.lowercase()) {
                "pt-br" -> "Português (Brasil)"
                "zh-cn" -> "中文 (简体)"
                "zh-tw" -> "中文 (繁體)"
                else -> locale.getDisplayName(locale).replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            }
            LanguageItem(tag, name)
        }.sortedBy { it.displayName.lowercase() }

        result.addAll(items)
        return result
    }

    private fun updateLanguageUI() {
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        if (currentLocales.isEmpty) {
            binding.tvCurrentLanguage.text = getString(R.string.str_system_default)
        } else {
            val locale = currentLocales.get(0)
            val tag = locale?.toLanguageTag() ?: ""
            val name = when (tag.lowercase()) {
                "pt-br" -> "Português (Brasil)"
                else -> locale?.getDisplayName(locale)?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
            }
            binding.tvCurrentLanguage.text = name ?: getString(R.string.str_system_default)
        }
    }

    private fun updateHapticFeedbackUI() {
        val prefs = getSharedPreferences("vydra_prefs", MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("haptic_feedback", true)
        binding.tvCurrentHapticFeedback.text = if (isEnabled) {
            getString(R.string.str_haptic_enabled_desc)
        } else {
            getString(R.string.str_haptic_disabled_desc)
        }
    }

    private fun updateFullscreenEditorUI() {
        val prefs = getSharedPreferences("vydra_prefs", MODE_PRIVATE)
        val isFullscreen = prefs.getBoolean("fullscreen_editor", true)
        binding.tvCurrentFullscreenEditor.text = if (isFullscreen) {
            getString(R.string.str_fullscreen_enabled_desc)
        } else {
            getString(R.string.str_fullscreen_disabled_desc)
        }
    }

    private fun updateEncoderUI() {
        val prefs = getSharedPreferences("vydra_prefs", MODE_PRIVATE)
        val defaultEncoder = prefs.getString("default_encoder", "hardware") ?: "hardware"
        if (defaultEncoder == "software") {
            binding.tvCurrentDefaultEncoder.text = getString(R.string.str_encoder_software)
        } else {
            binding.tvCurrentDefaultEncoder.text = getString(R.string.str_encoder_hardware)
        }
    }

    private fun showEncoderDialog() {
        val prefs = getSharedPreferences("vydra_prefs", MODE_PRIVATE)
        val currentEncoder = prefs.getString("default_encoder", "hardware") ?: "hardware"
        val options = arrayOf(
            getString(R.string.str_encoder_hardware),
            getString(R.string.str_encoder_software)
        )
        val selectedIndex = if (currentEncoder == "software") 1 else 0

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.str_default_encoder)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                val chosenEncoder = if (which == 1) "software" else "hardware"
                prefs.edit().putString("default_encoder", chosenEncoder).apply()
                updateEncoderUI()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.language_bottom_sheet_dialog, null)
        dialog.setContentView(view)

        view.findViewById<View>(R.id.btnCloseSheet)?.setBounceClickListener {
            dialog.dismiss()
        }

        val container = view.findViewById<android.widget.LinearLayout>(R.id.layoutLanguageContainer)
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentLocales.isEmpty) "" else (currentLocales.get(0)?.toLanguageTag() ?: "")

        val availableLanguages = getAvailableLanguages()

        for (item in availableLanguages) {
            val itemView = layoutInflater.inflate(R.layout.item_language_selection, container, false)
            val tvName = itemView.findViewById<TextView>(R.id.tvLanguageName)
            val ivCheck = itemView.findViewById<android.widget.ImageView>(R.id.ivCheckLanguage)

            tvName.text = item.displayName

            val isSelected = if (item.tag.isEmpty()) {
                currentTag.isEmpty()
            } else {
                currentTag.equals(item.tag, ignoreCase = true) ||
                (item.tag.length == 2 && currentTag.startsWith(item.tag, ignoreCase = true))
            }

            ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            itemView.setBounceClickListener {
                val appLocale = if (item.tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(item.tag)
                }
                AppCompatDelegate.setApplicationLocales(appLocale)
                updateLanguageUI()
                dialog.dismiss()
            }

            container?.addView(itemView)
        }

        dialog.show()
    }

    private fun switchTab(tabIndex: Int) {
        val activeBg = ContextCompat.getDrawable(this, R.drawable.bg_nav_active_pill)
        val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        val ta = obtainStyledAttributes(attrs)
        val inactiveBg = ta.getDrawable(0)
        ta.recycle()

        val activeColor = ContextCompat.getColor(this, R.color.colorPrimary)
        val inactiveColor = ContextCompat.getColor(this, R.color.inactiveTool)

        // Reset all tabs to inactive
        binding.layoutHomeContent.visibility = View.GONE
        binding.layoutSettingsContent.visibility = View.GONE
        binding.layoutAboutContent.visibility = View.GONE

        binding.tabHome.background = inactiveBg
        binding.ivHome.setColorFilter(inactiveColor)
        binding.tvHomeLabel.setTextColor(inactiveColor)

        binding.tabSettings.background = inactiveBg
        binding.ivSettings.setColorFilter(inactiveColor)
        binding.tvSettingsLabel.setTextColor(inactiveColor)

        binding.tabAbout.background = inactiveBg
        binding.ivAbout.setColorFilter(inactiveColor)
        binding.tvAboutLabel.setTextColor(inactiveColor)

        when (tabIndex) {
            0 -> {
                binding.layoutHomeContent.visibility = View.VISIBLE
                binding.tabHome.background = activeBg
                binding.ivHome.setColorFilter(activeColor)
                binding.tvHomeLabel.setTextColor(activeColor)
            }
            1 -> {
                binding.layoutSettingsContent.visibility = View.VISIBLE
                binding.tabSettings.background = activeBg
                binding.ivSettings.setColorFilter(activeColor)
                binding.tvSettingsLabel.setTextColor(activeColor)
            }
            2 -> {
                binding.layoutAboutContent.visibility = View.VISIBLE
                binding.tabAbout.background = activeBg
                binding.ivAbout.setColorFilter(activeColor)
                binding.tvAboutLabel.setTextColor(activeColor)
            }
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            showToast("Unable to open link")
        }
    }





    private fun selectVideo() {
        Log.d("VideoSelection", "Launching video picker.")
        val picker = com.boostlyte.vydra.customviews.MediaPickerBottomSheet().apply {
            initialMediaType = com.boostlyte.vydra.customviews.MediaPickerBottomSheet.MediaType.VIDEO
            showCategoryTabs = true
            showAudioTab = false
            onMediaSelectedListener = { uri ->
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (e: Exception) {
                    Log.d("VideoSelection", "Could not take persistable permission: ${e.message}")
                }
                navigateToEditingScreen(uri)
            }
            onMultipleMediaSelectedListener = { uris ->
                if (uris.isNotEmpty()) {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    for (u in uris) {
                        try {
                            contentResolver.takePersistableUriPermission(u, takeFlags)
                        } catch (e: Exception) {
                            Log.d("VideoSelection", "Could not take persistable permission: ${e.message}")
                        }
                    }
                    navigateToEditingScreen(uris)
                }
            }
            onBrowseSystemFoldersRequested = {
                selectVideoLauncher.launch(arrayOf("video/*", "image/*"))
            }
        }
        picker.show(supportFragmentManager, "MediaPickerBottomSheet")
    }

    private fun navigateToEditingScreen(videoUri: Uri) {
        navigateToEditingScreen(listOf(videoUri))
    }

    private fun navigateToEditingScreen(uris: List<Uri>) {
        if (uris.isEmpty()) return
        Log.d("Navigation", "Navigating to editing screen with ${uris.size} media URI(s)")
        val firstUri = uris.first()
        val intent = Intent(this, VideoEditingActivity::class.java).apply {
            putExtra("VIDEO_URI", firstUri)
            if (uris.size > 1) {
                putParcelableArrayListExtra("EXTRA_MEDIA_URIS", ArrayList(uris.drop(1)))
            }
            data = firstUri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("video/") || type.startsWith("image/")) {
                (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uri ->
                    Log.d("SharedVideo", "Received SEND intent with media URI: $uri")
                    navigateToEditingScreen(uri)
                }
            }
        } else if (Intent.ACTION_VIEW == action || Intent.ACTION_EDIT == action) {
            val uri = intent.data
            val uriPath = uri?.path ?: uri?.toString() ?: ""
            if (uri != null && (uriPath.endsWith(".vdprj", ignoreCase = true) || uriPath.endsWith(".lcprj", ignoreCase = true))) {
                val importIntent = Intent(this, ProjectImportActivity::class.java).apply {
                    putExtra("PROJECT_URI", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                startActivity(importIntent)
                return
            } else if (type != null && (type.startsWith("video/") || type.startsWith("image/"))) {
                intent.data?.let { mediaUri ->
                    Log.d("SharedVideo", "Received VIEW/EDIT intent with media URI: $mediaUri")
                    navigateToEditingScreen(mediaUri)
                }
            }
        }
    }

    private fun showOnboardingDialog(prefs: android.content.SharedPreferences) {
        val dialog = android.app.Dialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_welcome_onboarding, null)
        dialog.setContentView(view)
        dialog.setCancelable(false)

        dialog.window?.let { window ->
            // Make dialog window background transparent so our custom layout's background card and shape render perfectly
            window.setBackgroundDrawableResource(android.R.color.transparent)
            
            // Set size parameters
            val lp = window.attributes
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            window.attributes = lp
        }

        // Set version dynamically
        val tvVersion = view.findViewById<TextView>(R.id.tvOnboardingVersion)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            tvVersion.text = "Version ${pInfo.versionName}"
        } catch (e: Exception) {
            tvVersion.text = "Version 0.1.0"
        }

        view.findViewById<View>(R.id.btnOnboardingGetStarted)?.setBounceClickListener {
            prefs.edit().putBoolean("first_launch_v1", false).apply()
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.btnOnboardingWhatsApp)?.setBounceClickListener {
            com.boostlyte.vydra.utils.AppUrls.openSafeUrl(this, com.boostlyte.vydra.utils.AppUrls.WHATSAPP_COMMUNITY_URL)
        }

        view.findViewById<View>(R.id.tvOnboardingPrivacyPolicy)?.setOnClickListener {
            LegalActivity.start(this, LegalActivity.DOC_PRIVACY_POLICY)
        }

        view.findViewById<View>(R.id.tvOnboardingTermsOfUse)?.setOnClickListener {
            LegalActivity.start(this, LegalActivity.DOC_TERMS_OF_USE)
        }

        dialog.show()
    }

    private fun showToast(message: String) {
        Log.d("ToastMessage", "Showing toast: $message")
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun checkForUpdates() {
        showToast("You are running the latest version of Vydra (v0.1.0)")
    }

    private fun setupProjectsList() {
        projectAdapter = ProjectAdapter(
            onProjectClick = { item ->
                openSavedProject(item)
            },
            onProjectMenuClick = { anchorView, item ->
                showProjectOptionsMenu(anchorView, item)
            }
        )
        binding.rvProjects.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
            adapter = projectAdapter
        }
        loadSavedProjects()
    }

    private fun showProjectOptionsMenu(anchorView: View, item: com.boostlyte.vydra.utils.ProjectManager.SavedProjectItem) {
        val popup = androidx.appcompat.widget.PopupMenu(this, anchorView)
        popup.menuInflater.inflate(R.menu.menu_project_item, popup.menu)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_rename -> {
                    showRenameProjectDialog(item)
                    true
                }
                R.id.action_duplicate -> {
                    duplicateProject(item)
                    true
                }
                R.id.action_delete -> {
                    showDeleteProjectDialog(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showRenameProjectDialog(item: com.boostlyte.vydra.utils.ProjectManager.SavedProjectItem) {
        val input = android.widget.EditText(this).apply {
            setText(item.name)
            setSelection(item.name.length)
            setSingleLine(true)
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.textColor))
        }

        val container = android.widget.FrameLayout(this).apply {
            val hPadding = (24 * resources.displayMetrics.density).toInt()
            val vPadding = (8 * resources.displayMetrics.density).toInt()
            setPadding(hPadding, vPadding, hPadding, vPadding)
            addView(input)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_rename_title)
            .setView(container)
            .setPositiveButton(R.string.done) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotBlank() && newName != item.name) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val renamedFile = com.boostlyte.vydra.utils.ProjectManager.renameProject(
                            this@MainActivity,
                            item.file,
                            newName
                        )
                        withContext(Dispatchers.Main) {
                            if (renamedFile != null) {
                                showToast(getString(R.string.toast_project_renamed))
                                loadSavedProjects()
                            } else {
                                showToast(getString(R.string.toast_rename_failed))
                            }
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun duplicateProject(item: com.boostlyte.vydra.utils.ProjectManager.SavedProjectItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val duplicated = com.boostlyte.vydra.utils.ProjectManager.duplicateProject(
                this@MainActivity,
                item.file
            )
            withContext(Dispatchers.Main) {
                if (duplicated != null) {
                    showToast(getString(R.string.toast_project_duplicated))
                    loadSavedProjects()
                }
            }
        }
    }

    private fun showDeleteProjectDialog(item: com.boostlyte.vydra.utils.ProjectManager.SavedProjectItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(R.string.dialog_delete_message, item.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val deleted = com.boostlyte.vydra.utils.ProjectManager.deleteProject(
                        this@MainActivity,
                        item.file
                    )
                    withContext(Dispatchers.Main) {
                        if (deleted) {
                            showToast(getString(R.string.toast_project_deleted))
                            loadSavedProjects()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun loadSavedProjects() {
        lifecycleScope.launch(Dispatchers.IO) {
            val projects = com.boostlyte.vydra.utils.ProjectManager.listSavedProjects(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (projects.isEmpty()) {
                    binding.layoutProjectsEmpty.visibility = View.VISIBLE
                    binding.rvProjects.visibility = View.GONE
                    binding.tvProjectsCount.visibility = View.GONE
                } else {
                    binding.layoutProjectsEmpty.visibility = View.GONE
                    binding.rvProjects.visibility = View.VISIBLE
                    binding.tvProjectsCount.visibility = View.VISIBLE
                    binding.tvProjectsCount.text = "${projects.size} ${if (projects.size == 1) "project" else "projects"}"
                    projectAdapter.submitList(projects)
                }
            }
        }
    }

    private fun openSavedProject(item: com.boostlyte.vydra.utils.ProjectManager.SavedProjectItem) {
        val uri = com.boostlyte.vydra.utils.ProjectManager.getProjectUri(this, item.file)
        val intent = Intent(this, VideoEditingActivity::class.java).apply {
            putExtra("PROJECT_URI", uri)
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun updateAccountSettingsUI() {
        val isLoggedIn = com.boostlyte.vydra.transcription.SupabaseAuthManager.isLoggedIn(this)
        val email = com.boostlyte.vydra.transcription.SupabaseAuthManager.getCurrentUserEmail(this)

        if (isLoggedIn && !email.isNullOrBlank()) {
            binding.tvAccountEmail.text = email
            binding.tvAccountSubtitle.text = getString(R.string.auth_account_settings_desc_signed_in)
            binding.tvAccountBadge.text = "Cloud Sync Active"
            binding.tvAccountBadge.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
            binding.ivAccountAvatar.setColorFilter(ContextCompat.getColor(this, R.color.colorPrimary))
            binding.btnAccountAction.text = getString(R.string.auth_btn_sign_out)
            binding.btnAccountAction.setBackgroundResource(R.drawable.rounded_button_dark)
            binding.btnAccountAction.setTextColor(ContextCompat.getColor(this, R.color.textColor))
            binding.btnAccountAction.setBounceClickListener {
                showSignOutConfirmDialog()
            }

            // Show and populate Transcription Credits Section
            binding.layoutCreditsSection.visibility = View.VISIBLE
            val cachedCredits = com.boostlyte.vydra.auth.CreditsManager.getCachedCredits(this)
            displayCredits(cachedCredits, email)

            binding.btnRefreshCredits.setBounceClickListener {
                binding.tvCreditsBalance.text = getString(R.string.credits_loading)
                lifecycleScope.launch {
                    val fresh = com.boostlyte.vydra.auth.CreditsManager.fetchCredits(this@MainActivity)
                    displayCredits(fresh, email)
                    Toast.makeText(this@MainActivity, "Credits updated: $fresh mins", Toast.LENGTH_SHORT).show()
                }
            }

            lifecycleScope.launch {
                val fresh = com.boostlyte.vydra.auth.CreditsManager.fetchCredits(this@MainActivity)
                displayCredits(fresh, email)
            }
        } else {
            binding.tvAccountEmail.text = "Not Signed In"
            binding.tvAccountSubtitle.text = getString(R.string.auth_account_settings_desc_signed_out)
            binding.tvAccountBadge.text = "Offline Mode"
            binding.tvAccountBadge.setTextColor(ContextCompat.getColor(this, R.color.iconSecondary))
            binding.ivAccountAvatar.setColorFilter(ContextCompat.getColor(this, R.color.iconSecondary))
            binding.btnAccountAction.text = getString(R.string.auth_btn_sign_in)
            binding.btnAccountAction.setBackgroundResource(R.drawable.rounded_button)
            binding.btnAccountAction.setTextColor(ContextCompat.getColor(this, R.color.colorOnPrimary))
            binding.btnAccountAction.setBounceClickListener {
                showAuthDialog()
            }

            // Hide Credits Section when signed out
            binding.layoutCreditsSection.visibility = View.GONE
            binding.btnBuyCredits.visibility = View.GONE
        }
    }

    private fun displayCredits(credits: Int, email: String?) {
        binding.tvCreditsBalance.text = when (credits) {
            0 -> getString(R.string.credits_balance_zero)
            1 -> getString(R.string.credits_balance_single)
            else -> getString(R.string.credits_balance_format, credits)
        }
        if (credits <= com.boostlyte.vydra.auth.CreditsManager.LOW_CREDIT_THRESHOLD_MINUTES) {
            binding.tvCreditsBalance.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            binding.btnBuyCredits.visibility = View.VISIBLE
            binding.btnBuyCredits.setBounceClickListener {
                com.boostlyte.vydra.utils.AppUrls.openWhatsAppPurchase(this, email)
            }
        } else {
            binding.tvCreditsBalance.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
            binding.btnBuyCredits.visibility = View.GONE
        }
    }

    private fun showSignOutConfirmDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.auth_dialog_sign_out_title)
            .setMessage(R.string.auth_dialog_sign_out_message)
            .setPositiveButton(R.string.auth_btn_sign_out) { _, _ ->
                lifecycleScope.launch {
                    com.boostlyte.vydra.auth.CreditsManager.invalidateCache(this@MainActivity)
                    com.boostlyte.vydra.transcription.SupabaseAuthManager.signOut(this@MainActivity)
                    Toast.makeText(this@MainActivity, R.string.auth_signed_out_toast, Toast.LENGTH_SHORT).show()
                    updateAccountSettingsUI()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAuthDialog() {
        com.boostlyte.vydra.auth.AuthBottomSheetDialog(
            context = this,
            onAuthenticated = {
                updateAccountSettingsUI()
            }
        ).show()
    }

    override fun onResume() {
        super.onResume()
        loadSavedProjects()
        updateAccountSettingsUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        com.boostlyte.vydra.transcription.SupabaseAuthManager.removeAuthStateListener(authStateListener)
    }
}