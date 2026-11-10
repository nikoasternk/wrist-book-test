package com.band.wristbook

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.band.wristbook.transport.TransferError
import com.band.wristbook.transport.WearableTransferClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var transferClient: WearableTransferClient
    private var selectedFileName by mutableStateOf<String?>(null)
    private var selectedFileContent by mutableStateOf<String?>(null)
    private var connectedBandName by mutableStateOf<String?>(null)
    private var isCheckingConnection by mutableStateOf(true)
    private var isSending by mutableStateOf(false)
    private var sendProgress by mutableFloatStateOf(0f)
    private var languageMode by mutableStateOf("auto")

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) loadPickedFile(uri)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("wristbook_prefs", Context.MODE_PRIVATE)
        languageMode = prefs.getString("lang_mode", "auto") ?: "auto"

        transferClient = WearableTransferClient(this, lifecycleScope)
        refreshDeviceConnection()

        setContent {
            val localizedContext = remember(languageMode) { createLocalizedContext(this, languageMode) }
            CompositionLocalProvider(LocalContext provides localizedContext) {
                WristBookTheme {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        topBar = {
                            CenterAlignedTopAppBar(
                                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                                actions = {
                                    TextButton(onClick = {
                                        languageMode = when (languageMode) {
                                            "auto" -> "en"
                                            "en" -> "ru"
                                            else -> "auto"
                                        }
                                        prefs.edit().putString("lang_mode", languageMode).apply()
                                    }) {
                                        Text(languageMode.uppercase(Locale.ROOT), fontWeight = FontWeight.Bold)
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        val bandName = connectedBandName
                        val bandStatusText = when {
                            isCheckingConnection -> stringResource(R.string.status_checking)
                            bandName != null -> stringResource(R.string.status_connected, bandName)
                            else -> stringResource(R.string.status_disconnected)
                        }
                        val defaultPrefix = stringResource(R.string.default_note_prefix)

                        CompanionScreen(
                            modifier = Modifier.padding(innerPadding),
                            bandStatus = bandStatusText,
                            pickedFileName = selectedFileName,
                            isSending = isSending,
                            progress = sendProgress,
                            onRefresh = { refreshDeviceConnection() },
                            onPickFile = { filePicker.launch(arrayOf("text/plain")) },
                            onSendFile = {
                                val name = selectedFileName
                                val text = selectedFileContent
                                if (name != null && !text.isNullOrBlank()) {
                                    beginBookTransfer(localizedContext, BookText.toFileName(name, BOOK_FALLBACK_NAME), text)
                                }
                            },
                            onSendNote = { title, text ->
                                val fallback = "${defaultPrefix}_" +
                                    SimpleDateFormat("dd.MM_HH-mm", Locale.getDefault()).format(Date())
                                beginBookTransfer(localizedContext, BookText.toFileName(title, fallback), BookText.normalize(text))
                            }
                        )
                    }
                }
            }
        }
    }

    private fun refreshDeviceConnection() {
        isCheckingConnection = true
        lifecycleScope.launch {
            val result = transferClient.queryConnectedBand()
            connectedBandName = result.getOrNull()
            isCheckingConnection = false
        }
    }

    private fun loadPickedFile(uri: Uri) {
        selectedFileName = null
        selectedFileContent = null
        val localized = createLocalizedContext(this, languageMode)
        lifecycleScope.launch {
            when (val picked = withContext(Dispatchers.IO) { readPickedFile(uri) }) {
                is PickedFile.Loaded -> {
                    selectedFileName = picked.name
                    selectedFileContent = picked.text
                }
                PickedFile.TooLarge ->
                    Toast.makeText(localized, localized.getString(R.string.error_file_too_large), Toast.LENGTH_LONG).show()
                PickedFile.Unreadable ->
                    Toast.makeText(localized, localized.getString(R.string.error_read_file), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun readPickedFile(uri: Uri): PickedFile {
        var displayName: String? = null
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        if (size > MAX_BOOK_BYTES) return PickedFile.TooLarge

        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return PickedFile.Unreadable
        if (bytes.size > MAX_BOOK_BYTES) return PickedFile.TooLarge

        val text = BookText.decode(bytes)
        if (text.isBlank()) return PickedFile.Unreadable
        return PickedFile.Loaded(displayName ?: BOOK_FALLBACK_NAME, text)
    }

    private fun beginBookTransfer(ctx: Context, name: String, content: String) {
        if (isSending || content.isBlank()) return
        isSending = true
        sendProgress = 0f
        Toast.makeText(ctx, ctx.getString(R.string.toast_sending), Toast.LENGTH_SHORT).show()

        transferClient.transferBook(
            fileName = name,
            content = content,
            onProgress = { progress -> sendProgress = progress },
            onSuccess = {
                isSending = false
                Toast.makeText(ctx, ctx.getString(R.string.toast_success), Toast.LENGTH_SHORT).show()
            },
            onError = { error ->
                isSending = false
                val msgId = when (error) {
                    TransferError.BAND_NOT_FOUND -> R.string.error_no_band
                    TransferError.WATCH_UNRESPONSIVE -> R.string.error_watch_unresponsive
                    TransferError.FILE_INIT_FAILED -> R.string.error_file_init
                    TransferError.PACKET_SEND_FAILED -> R.string.error_packet_send
                    TransferError.BLOCK_ACK_TIMEOUT -> R.string.error_block_timeout
                }
                Toast.makeText(ctx, ctx.getString(msgId), Toast.LENGTH_LONG).show()
            }
        )
    }

    private sealed interface PickedFile {
        data class Loaded(val name: String, val text: String) : PickedFile
        object TooLarge : PickedFile
        object Unreadable : PickedFile
    }

    private companion object {
        const val MAX_BOOK_BYTES = 12L * 1024 * 1024
        const val BOOK_FALLBACK_NAME = "book"
    }

    private fun createLocalizedContext(base: Context, mode: String): Context {
        val targetLocale = when (mode) {
            "en" -> Locale.ENGLISH
            "ru" -> Locale("ru")
            else -> {
                val sysLang = Locale.getDefault().language.lowercase(Locale.ROOT)
                if (sysLang.startsWith("ru")) Locale("ru") else Locale.ENGLISH
            }
        }
        val config = Configuration(base.resources.configuration)
        config.setLocale(targetLocale)
        return base.createConfigurationContext(config)
    }
}

@Composable
fun WristBookTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun CompanionScreen(
    modifier: Modifier = Modifier,
    bandStatus: String,
    pickedFileName: String?,
    isSending: Boolean,
    progress: Float,
    onRefresh: () -> Unit,
    onPickFile: () -> Unit,
    onSendFile: () -> Unit,
    onSendNote: (String, String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var noteTitle by remember { mutableStateOf("") }
    var noteBody by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.device_status), style = MaterialTheme.typography.labelMedium)
                    Text(bandStatus, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = onRefresh) {
                    Text(stringResource(R.string.btn_refresh))
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text(stringResource(R.string.tab_text)) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text(stringResource(R.string.tab_file)) }
            )
        }

        if (isSending) {
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.sending_progress, (progress * 100).toInt()))
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (selectedTab == 0) {
            OutlinedTextField(
                value = noteTitle,
                onValueChange = { noteTitle = it },
                label = { Text(stringResource(R.string.label_title)) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = noteBody,
                onValueChange = { noteBody = it },
                label = { Text(stringResource(R.string.label_content)) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            Button(
                onClick = { onSendNote(noteTitle, noteBody) },
                enabled = noteBody.isNotBlank() && !isSending,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(stringResource(R.string.btn_send_text))
            }
        } else {
            Card(
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = pickedFileName ?: stringResource(R.string.no_file_selected),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    FilledTonalButton(
                        onClick = onPickFile,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.btn_choose_file))
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onSendFile,
                enabled = pickedFileName != null && !isSending,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(stringResource(R.string.btn_send_file))
            }
        }
    }
}
