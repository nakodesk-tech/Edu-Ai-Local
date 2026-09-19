package com.nakodesk.eduaillocal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val MODEL_DIR = "models"
private const val CHAT_PREFS = "edu_ai_local_chats"
private const val CHAT_JSON = "sessions"

private data class CatalogModel(
    val name: String,
    val fileName: String,
    val quantization: String,
    val size: String,
    val description: String,
    val url: String
)

private val catalog = listOf(
    CatalogModel("Llama 3.2 3B Instruct", "llama-3.2-3b-instruct-q4_k_m.gguf", "Q4_K_M", "2.02 GB",
        "General purpose instruct model",
        "https://huggingface.co/hugging-quants/Llama-3.2-3B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.2-3b-instruct-q4_k_m.gguf?download=true"),
    CatalogModel("Gemma 3 1B Instruct", "gemma-3-1b-it-Q4_K_M.gguf", "Q4_K_M", "806 MB",
        "Small and lightweight model",
        "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf?download=true"),
    CatalogModel("Qwen 3 4B", "Qwen3-4B-Q4_K_M.gguf", "Q4_K_M", "2.50 GB",
        "Multilingual general-purpose model",
        "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf?download=true")
)

private data class ChatMessage(val role: String, val text: String)
private data class ChatSession(
    val id: String,
    var title: String,
    var messages: List<ChatMessage>,
    val updatedAt: Long = System.currentTimeMillis()
)

private fun newSession(): ChatSession = ChatSession(
    id = UUID.randomUUID().toString(),
    title = "New Chat",
    messages = listOf(ChatMessage("assistant", "Welcome to Edu AI Local. Select a local model and start chatting offline."))
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EduAiLocalApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EduAiLocalApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var engine by remember { mutableStateOf<InferenceEngine?>(null) }
    var screen by rememberSaveable { mutableStateOf("chat") }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var darkTheme by rememberSaveable { mutableStateOf(false) }
    var installedModels by remember { mutableStateOf(emptyList<File>()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var loadingModel by remember { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf("Initializing local AI…") }
    var downloadName by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var sessions by remember { mutableStateOf(loadSessions(context)) }
    var currentChatId by rememberSaveable { mutableStateOf<String?>(null) }
    var renameDialog by remember { mutableStateOf(false) }
    var attachedFileName by rememberSaveable { mutableStateOf<String?>(null) }
    var attachedFileText by rememberSaveable { mutableStateOf<String?>(null) }

    if (sessions.isEmpty()) sessions = listOf(newSession())
    if (currentChatId == null) currentChatId = sessions.firstOrNull()?.id
    val currentSession = sessions.firstOrNull { it.id == currentChatId } ?: sessions.first()

    fun saveAll() {
        saveSessions(context, sessions)
    }

    fun updateCurrentMessages(newMessages: List<ChatMessage>) {
        val targetId = currentChatId ?: return
        sessions = sessions.map {
            if (it.id == targetId) {
                val title = if (it.title == "New Chat") {
                    newMessages.firstOrNull { m -> m.role == "user" }?.text
                        ?.replace(Regex("\\s+"), " ")
                        ?.take(42)
                        ?.ifBlank { it.title } ?: it.title
                } else it.title
                it.copy(title = title, messages = newMessages, updatedAt = System.currentTimeMillis())
            } else it
        }
        saveAll()
    }

    fun startNewChat() {
        val session = newSession()
        sessions = listOf(session) + sessions
        currentChatId = session.id
        screen = "chat"
        input = ""
        attachedFileName = null
        attachedFileText = null
        drawerOpen = false
        saveAll()
    }

    fun openChat(id: String) {
        currentChatId = id
        screen = "chat"
        input = ""
        attachedFileName = null
        attachedFileText = null
        drawerOpen = false
    }

    fun refreshModels() {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!dir.exists()) dir.mkdirs()
        installedModels = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("gguf", true) }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    LaunchedEffect(Unit) {
        refreshModels()
        try {
            engine = withContext(Dispatchers.Default) {
                AiChat.getInferenceEngine(context.applicationContext)
            }
            status = "Ready — choose a local model"
        } catch (e: Exception) {
            status = "Inference engine unavailable"
            errorMessage = e.message ?: "Could not initialize local inference."
        }
    }

    fun loadModel(file: File) {
        val localEngine = engine ?: run {
            errorMessage = "Local inference engine is not ready yet."
            return
        }
        if (loadingModel || generating) return
        scope.launch {
            try {
                loadingModel = true
                status = "Loading " + file.name + "…"
                withContext(Dispatchers.Default) {
                    localEngine.cleanUp()
                    localEngine.loadModel(file.absolutePath)
                }
                selectedFile = file
                status = "Model ready — offline"
            } catch (e: Exception) {
                status = "Model load failed"
                errorMessage = e.message ?: "Could not load model."
            } finally {
                loadingModel = false
            }
        }
    }

    fun sendMessage() {
        val localEngine = engine ?: return
        val raw = input.trim()
        if (raw.isEmpty() || generating || loadingModel || selectedFile == null) return

        val prompt = if (attachedFileText != null) {
            raw + "\n\n[Attached file: " + (attachedFileName ?: "file") + "]\n" + attachedFileText
        } else raw

        input = ""
        attachedFileName = null
        attachedFileText = null
        val next = currentSession.messages + ChatMessage("user", raw) + ChatMessage("assistant", "")
        updateCurrentMessages(next)
        generating = true
        status = "Generating locally…"

        scope.launch(Dispatchers.Default) {
            try {
                val result = StringBuilder()
                localEngine.sendUserPrompt(prompt).collect { token ->
                    result.append(token)
                    withContext(Dispatchers.Main) {
                        val latest = currentSession.messages
                        updateCurrentMessages(latest.dropLast(1) + ChatMessage("assistant", result.toString()))
                    }
                }
                withContext(Dispatchers.Main) {
                    status = "Model ready — offline"
                    generating = false
                    saveAll()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val latest = currentSession.messages
                    updateCurrentMessages(latest.dropLast(1) + ChatMessage("assistant", "Error: " + (e.message ?: "Generation failed.")))
                    status = "Generation failed"
                    generating = false
                }
            }
        }
    }

    fun importModel(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            try {
                val name = context.displayName(uri) ?: "imported-" + System.currentTimeMillis() + ".gguf"
                if (!name.lowercase().endsWith(".gguf")) throw IllegalArgumentException("Please select a GGUF model file.")
                val targetDir = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }
                val target = File(targetDir, name.replace(Regex("[/\\\\]"), "_"))
                withContext(Dispatchers.Main) { status = "Importing " + name + "…" }
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Unable to open selected file." }
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
                withContext(Dispatchers.Main) {
                    refreshModels()
                    status = "Imported successfully"
                    loadModel(target)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    status = "Import failed"
                    errorMessage = e.message ?: "Could not import model."
                }
            }
        }
    }

    fun downloadModel(model: CatalogModel) {
        if (downloadName != null) return
        scope.launch(Dispatchers.IO) {
            val targetDir = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }
            val target = File(targetDir, model.fileName)
            val temp = File(targetDir, model.fileName + ".part")
            try {
                withContext(Dispatchers.Main) {
                    downloadName = model.name
                    downloadProgress = 0f
                    status = "Downloading " + model.name + "…"
                }
                val connection = (URL(model.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 30_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Edu-AI-Local/0.3")
                }
                connection.connect()
                if (connection.responseCode !in 200..299) throw IllegalStateException("Download failed: HTTP " + connection.responseCode)
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) withContext(Dispatchers.Main) { downloadProgress = copied.toFloat() / total.toFloat() }
                        }
                    }
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                withContext(Dispatchers.Main) {
                    refreshModels()
                    downloadName = null
                    status = "Download complete"
                    loadModel(target)
                }
            } catch (e: Exception) {
                temp.delete()
                withContext(Dispatchers.Main) {
                    downloadName = null
                    status = "Download failed"
                    errorMessage = e.message ?: "Could not download model."
                }
            }
        }
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importModel(it) }
    }
    val chatFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val name = context.displayName(uri) ?: "Attached file"
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("Could not read this file.")
                    if (text.length > 120_000) throw IllegalArgumentException("This file is too large for the chat picker. Please choose a smaller text file.")
                    withContext(Dispatchers.Main) {
                        attachedFileName = name
                        attachedFileText = text
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { errorMessage = e.message ?: "Could not attach file." }
                }
            }
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    BackHandler(enabled = drawerOpen || screen != "chat") {
        if (drawerOpen) drawerOpen = false else screen = "chat"
    }
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) drawerState.open() else drawerState.close()
    }

    DisposableEffect(engine) {
        onDispose { engine?.destroy() }
    }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(Modifier.width(310.dp)) {
                    Spacer(Modifier.height(28.dp))
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Psychology, null, modifier = Modifier.size(34.dp), tint = Color(0xFF1689D7))
                        Spacer(Modifier.width(10.dp))
                        Text("Edu AI Local", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(22.dp))
                    DrawerItem("New Chat", Icons.Default.AddComment, false, ::startNewChat)
                    DrawerItem("Chats", Icons.Default.Chat, screen == "history") { screen = "history"; drawerOpen = false }
                    DrawerItem("Models", Icons.Default.Memory, screen == "models") { screen = "models"; drawerOpen = false }
                    DrawerItem("Import Model", Icons.Default.FolderOpen, false) {
                        drawerOpen = false
                        modelPicker.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                    DrawerItem("Settings", Icons.Default.Settings, screen == "settings") { screen = "settings"; drawerOpen = false }
                    DrawerItem("Appearance", Icons.Default.DarkMode, false) { darkTheme = !darkTheme }
                    DrawerItem("About", Icons.Default.Info, false) { errorMessage = "Edu AI Local\nOffline AI chat powered by llama.cpp." ; drawerOpen = false }
                    Spacer(Modifier.weight(1f))
                    Surface(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = if (darkTheme) Color(0xFF162A20) else Color(0xFFE9F8EF)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(Color(0xFF2FC66D), CircleShape))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Offline Mode", fontWeight = FontWeight.SemiBold)
                                Text("Inference stays on this device", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            if (screen == "chat") {
                                Column {
                                    Text(currentSession.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(selectedFile?.name ?: "No model loaded", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            } else {
                                Text(when (screen) {
                                    "models" -> "Models"
                                    "history" -> "Chats"
                                    "settings" -> "Settings"
                                    else -> "Edu AI Local"
                                }, fontWeight = FontWeight.Bold)
                            }
                        },
                        navigationIcon = { IconButton({ drawerOpen = true }) { Icon(Icons.Default.Menu, "Menu") } },
                        actions = {
                            if (screen == "chat") {
                                IconButton({ renameDialog = true }) { Icon(Icons.Default.Edit, "Rename chat") }
                                IconButton({ screen = "models" }) { Icon(Icons.Default.Memory, "Models") }
                            }
                        }
                    )
                }
            ) { padding ->
                when (screen) {
                    "chat" -> ChatScreen(
                        Modifier.padding(padding), selectedFile, installedModels, input, attachedFileName,
                        { input = it }, { attachedFileName = null; attachedFileText = null },
                        currentSession.messages, generating, loadingModel, status,
                        { loadModel(it) }, { chatFilePicker.launch(arrayOf("text/plain", "text/markdown", "text/csv", "application/json", "text/*")) },
                        { sendMessage() }, { copyText(context, it) }
                    )
                    "models" -> ModelsScreen(
                        Modifier.padding(padding), installedModels, selectedFile, downloadName, downloadProgress,
                        { modelPicker.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*")) },
                        { downloadModel(it) }, { loadModel(it) },
                        { file -> file.delete(); refreshModels(); if (selectedFile?.path == file.path) { selectedFile = null; status = "Model removed" } }
                    )
                    "history" -> ChatHistoryScreen(
                        Modifier.padding(padding), sessions, currentChatId,
                        { openChat(it) },
                        { id, title ->
                            sessions = sessions.map { if (it.id == id) it.copy(title = title) else it }
                            saveAll()
                        },
                        { id ->
                            sessions = sessions.filterNot { it.id == id }
                            if (sessions.isEmpty()) sessions = listOf(newSession())
                            if (currentChatId == id) currentChatId = sessions.first().id
                            saveAll()
                        }
                    )
                    else -> SettingsScreen(Modifier.padding(padding), darkTheme, { darkTheme = it }, selectedFile)
                }
            }

            if (renameDialog) {
                RenameChatDialog(
                    currentSession.title,
                    { title ->
                        if (title.isNotBlank()) {
                            sessions = sessions.map { if (it.id == currentSession.id) it.copy(title = title.trim()) else it }
                            saveAll()
                        }
                        renameDialog = false
                    },
                    { renameDialog = false }
                )
            }

            errorMessage?.let { message ->
                AlertDialog(
                    onDismissRequest = { errorMessage = null },
                    confirmButton = { TextButton({ errorMessage = null }) { Text("OK") } },
                    title = { Text("Edu AI Local") },
                    text = { Text(message) }
                )
            }
        }
    }
}

@Composable
private fun DrawerItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, null) },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

@Composable
private fun ChatScreen(
    modifier: Modifier,
    selectedFile: File?,
    installed: List<File>,
    input: String,
    attachedFileName: String?,
    onInput: (String) -> Unit,
    onClearAttachment: () -> Unit,
    messages: List<ChatMessage>,
    generating: Boolean,
    loadingModel: Boolean,
    status: String,
    onLoad: (File) -> Unit,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
    onCopy: (String) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        if (installed.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("No local model installed", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("Open Models to import a GGUF file or download a supported model.")
                }
            }
        } else {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { if (!generating && !loadingModel) expanded = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !generating && !loadingModel
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(if (selectedFile != null) Color(0xFF2FC66D) else Color.Gray, CircleShape))
                        Spacer(Modifier.width(9.dp))
                        Text(selectedFile?.name ?: "Select a local model", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (loadingModel) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.KeyboardArrowDown, null)
                    }
                }
                DropdownMenu(expanded, { expanded = false }) {
                    installed.forEach { file ->
                        DropdownMenuItem(
                            text = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(if (selectedFile?.path == file.path) Icons.Default.CheckCircle else Icons.Default.Memory, null) },
                            onClick = { onLoad(file); expanded = false }
                        )
                    }
                }
            }
        }

        Row(Modifier.padding(horizontal = 18.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (selectedFile != null) Color(0xFF2FC66D) else Color.Gray, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(if (loadingModel) "Loading model…" else status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(messages) { index, message ->
                val user = message.role == "user"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (user) Color(0xFF1976F3) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 350.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                message.text.ifBlank { if (generating && index == messages.lastIndex) "…" else "" },
                                color = if (user) Color.White else MaterialTheme.colorScheme.onSurface,
                                lineHeight = 21.sp
                            )
                            if (message.text.isNotBlank() && !(generating && index == messages.lastIndex)) {
                                Spacer(Modifier.height(5.dp))
                                TextButton(
                                    onClick = { onCopy(message.text) },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Copy", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (attachedFileName != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(attachedFileName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClearAttachment, Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Remove attachment", Modifier.size(18.dp)) }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onPickFile, enabled = selectedFile != null && !generating && !loadingModel) {
                Icon(Icons.Default.AttachFile, "Attach text file")
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                Modifier.weight(1f),
                placeholder = { Text("Message your local model…") },
                shape = RoundedCornerShape(22.dp),
                maxLines = 5,
                enabled = selectedFile != null && !generating && !loadingModel
            )
            Spacer(Modifier.width(6.dp))
            FloatingActionButton(
                onClick = onSend,
                Modifier.size(52.dp),
                containerColor = Color(0xFF1976F3),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Send, "Send")
            }
        }
    }
}

@Composable
private fun ChatHistoryScreen(
    modifier: Modifier,
    sessions: List<ChatSession>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit
) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Chat history", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("Your conversations are stored on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
        }
        items(sessions) { session ->
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(session.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            session.messages.lastOrNull { it.role == "user" }?.text ?: "No messages yet",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton({ onOpen(session.id) }) { Icon(if (session.id == currentId) Icons.Default.CheckCircle else Icons.Default.OpenInNew, "Open") }
                    IconButton({
                        onRename(session.id, session.title)
                    }) { Icon(Icons.Default.Edit, "Rename") }
                    IconButton({ onDelete(session.id) }) { Icon(Icons.Default.DeleteOutline, "Delete") }
                }
            }
        }
    }
}

@Composable
private fun RenameChatDialog(initial: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var title by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Rename chat") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Chat name") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton({ onSave(title) }) { Text("Save") } },
        dismissButton = { TextButton(onCancel) { Text("Cancel") } }
    )
}

@Composable
private fun ModelsScreen(
    modifier: Modifier,
    installed: List<File>,
    selectedFile: File?,
    downloadName: String?,
    progress: Float,
    onImport: () -> Unit,
    onDownload: (CatalogModel) -> Unit,
    onLoad: (File) -> Unit,
    onDelete: (File) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Models", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Install, load and manage GGUF models", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onImport, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(4.dp))
                Text("Import")
            }
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Installed", "Available", "Downloads").forEachIndexed { index, label ->
                Tab(tab == index, { tab = index }, text = { Text(label) })
            }
        }
        if (downloadName != null) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Downloading " + downloadName, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text((progress * 100).toInt().toString() + "%", fontSize = 12.sp)
                }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (tab) {
                0 -> if (installed.isEmpty()) item { EmptyModelsCard() }
                else items(installed) { file -> InstalledModelCard(file, selectedFile?.path == file.path, { onLoad(file) }, { onDelete(file) }) }
                1 -> items(catalog) { model ->
                    val exists = installed.any { it.name == model.fileName }
                    ModelCatalogCard(model, exists) { if (!exists) onDownload(model) }
                }
                2 -> item {
                    if (downloadName == null) Text("No active downloads.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EmptyModelsCard() {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Memory, null, Modifier.size(44.dp))
            Spacer(Modifier.height(8.dp))
            Text("No models installed", fontWeight = FontWeight.Bold)
            Text("Import a GGUF file or choose a model from Available.")
        }
    }
}

@Composable
private fun InstalledModelCard(file: File, loaded: Boolean, onLoad: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, null, modifier = Modifier.size(42.dp), tint = Color(0xFF1689D7))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(formatBytes(file.length()), fontSize = 12.sp)
                }
                if (loaded) AssistChip(onClick = {}, label = { Text("Loaded") }, leadingIcon = { Icon(Icons.Default.Check, null) })
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoad, modifier = Modifier.weight(1f), enabled = !loaded) { Text(if (loaded) "Loaded" else "Load") }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun ModelCatalogCard(model: CatalogModel, installed: Boolean, onDownload: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, null, modifier = Modifier.size(42.dp), tint = Color(0xFF1689D7))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(model.quantization + " • " + model.size, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(model.description, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onDownload, enabled = !installed, modifier = Modifier.fillMaxWidth()) {
                Icon(if (installed) Icons.Default.Check else Icons.Default.Download, null)
                Spacer(Modifier.width(6.dp))
                Text(if (installed) "Downloaded" else "Download")
            }
        }
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier, darkTheme: Boolean, onDarkTheme: (Boolean) -> Unit, selectedFile: File?) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Local model and app preferences", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Spacer(Modifier.height(18.dp)); SettingRow("Current model", selectedFile?.name ?: "None", Icons.Default.Memory) }
        item { SettingRow("Offline inference", "Always on device", Icons.Default.WifiOff) }
        item {
            ListItem(
                headlineContent = { Text("Dark theme") },
                leadingContent = { Icon(Icons.Default.DarkMode, null) },
                trailingContent = { Switch(checked = darkTheme, onCheckedChange = onDarkTheme) }
            )
        }
        item { SettingRow("Chat history", "Stored locally on this device", Icons.Default.History) }
        item { SettingRow("App version", "0.3.0", Icons.Default.Info) }
    }
}

@Composable
private fun SettingRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(value) }, leadingContent = { Icon(icon, null) })
}

private fun loadSessions(context: Context): List<ChatSession> {
    return try {
        val raw = context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE).getString(CHAT_JSON, null) ?: return listOf(newSession())
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val messages = buildList {
                    val msgArray = obj.optJSONArray("messages") ?: JSONArray()
                    for (j in 0 until msgArray.length()) {
                        val m = msgArray.getJSONObject(j)
                        add(ChatMessage(m.optString("role"), m.optString("text")))
                    }
                }
                add(ChatSession(obj.optString("id"), obj.optString("title", "New Chat"), messages, obj.optLong("updatedAt", System.currentTimeMillis())))
            }
        }.ifEmpty { listOf(newSession()) }
    } catch (_: Exception) {
        listOf(newSession())
    }
}

private fun saveSessions(context: Context, sessions: List<ChatSession>) {
    val array = JSONArray()
    sessions.sortedByDescending { it.updatedAt }.forEach { session ->
        val obj = JSONObject()
        obj.put("id", session.id)
        obj.put("title", session.title)
        obj.put("updatedAt", session.updatedAt)
        val msgs = JSONArray()
        session.messages.forEach {
            msgs.put(JSONObject().put("role", it.role).put("text", it.text))
        }
        obj.put("messages", msgs)
        array.put(obj)
    }
    context.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(CHAT_JSON, array.toString())
        .apply()
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Edu AI Local", text))
}

private fun Context.displayName(uri: Uri): String? {
    var cursor: Cursor? = null
    return try {
        cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        if (cursor?.moveToFirst() == true) cursor.getString(0) else null
    } finally {
        cursor?.close()
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L * 1024L) return bytes.toString() + " B"
    if (bytes < 1024L * 1024L * 1024L) return "%.1f MB".format(bytes / (1024f * 1024f))
    return "%.2f GB".format(bytes / (1024f * 1024f * 1024f))
}
