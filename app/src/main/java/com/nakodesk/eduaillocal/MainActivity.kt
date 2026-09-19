package com.nakodesk.eduaillocal

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val MODEL_DIR = "models"

private data class CatalogModel(
    val name: String,
    val fileName: String,
    val quantization: String,
    val size: String,
    val description: String,
    val url: String
)

private val catalog = listOf(
    CatalogModel(
        name = "Llama 3.2 3B Instruct",
        fileName = "llama-3.2-3b-instruct-q4_k_m.gguf",
        quantization = "Q4_K_M",
        size = "2.02 GB",
        description = "General purpose instruct model",
        url = "https://huggingface.co/hugging-quants/Llama-3.2-3B-Instruct-Q4_K_M-GGUF/resolve/main/llama-3.2-3b-instruct-q4_k_m.gguf?download=true"
    ),
    CatalogModel(
        name = "Gemma 3 1B Instruct",
        fileName = "gemma-3-1b-it-Q4_K_M.gguf",
        quantization = "Q4_K_M",
        size = "806 MB",
        description = "Small and lightweight model",
        url = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf?download=true"
    ),
    CatalogModel(
        name = "Qwen 3 4B",
        fileName = "Qwen3-4B-Q4_K_M.gguf",
        quantization = "Q4_K_M",
        size = "2.50 GB",
        description = "Multilingual general-purpose model",
        url = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf?download=true"
    )
)

private data class ChatMessage(val role: String, val text: String)

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
    var status by rememberSaveable { mutableStateOf("Initializing local AI…") }
    var downloadName by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var messages by remember {
        mutableStateOf(
            listOf(ChatMessage("assistant", "Welcome to Edu AI Local. Import or download a GGUF model to start chatting offline."))
        )
    }

    fun refreshModels() {
        val dir = File(context.filesDir, MODEL_DIR)
        if (!dir.exists()) dir.mkdirs()
        installedModels = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("gguf", true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
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
        scope.launch {
            try {
                generating = true
                status = "Loading " + file.name + "…"
                localEngine.loadModel(file.absolutePath)
                selectedFile = file
                status = "Model ready — offline"
            } catch (e: Exception) {
                status = "Model load failed"
                errorMessage = e.message ?: "Could not load model."
            } finally {
                generating = false
            }
        }
    }

    fun sendMessage() {
        val localEngine = engine ?: return
        val text = input.trim()
        if (text.isEmpty() || generating || selectedFile == null) return

        input = ""
        messages = messages + ChatMessage("user", text) + ChatMessage("assistant", "")
        generating = true
        status = "Generating locally…"

        scope.launch(Dispatchers.Default) {
            try {
                val result = StringBuilder()
                localEngine.sendUserPrompt(text).collect { token ->
                    result.append(token)
                    withContext(Dispatchers.Main) {
                        messages = messages.dropLast(1) + ChatMessage("assistant", result.toString())
                    }
                }
                withContext(Dispatchers.Main) {
                    status = "Model ready — offline"
                    generating = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    messages = messages.dropLast(1) + ChatMessage("assistant", "Error: " + (e.message ?: "Generation failed."))
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
                if (!name.lowercase().endsWith(".gguf")) {
                    throw IllegalArgumentException("Please select a GGUF model file.")
                }
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
                    setRequestProperty("User-Agent", "Edu-AI-Local/0.2")
                }

                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Download failed: HTTP " + connection.responseCode)
                }

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
                            if (total > 0) {
                                withContext(Dispatchers.Main) {
                                    downloadProgress = copied.toFloat() / total.toFloat()
                                }
                            }
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

    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Predictable Android back navigation:
    // 1) close the drawer first
    // 2) from any secondary screen return to Chat
    // 3) only the root Chat screen is allowed to exit the activity
    BackHandler(enabled = drawerOpen || screen != "chat") {
        if (drawerOpen) {
            drawerOpen = false
        } else {
            screen = "chat"
        }
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
                ModalDrawerSheet(Modifier.width(300.dp)) {
                    Spacer(Modifier.height(28.dp))
                    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Psychology, null, tint = Color(0xFF1689D7), modifier = Modifier.size(34.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Edu AI Local", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(24.dp))
                    DrawerItem("New Chat", Icons.Default.AddComment, screen == "chat") {
                        messages = listOf(ChatMessage("assistant", "New local conversation. Select a model and start chatting."))
                        screen = "chat"; drawerOpen = false
                    }
                    DrawerItem("Chats", Icons.Default.Chat, false) { screen = "chat"; drawerOpen = false }
                    DrawerItem("Models", Icons.Default.Memory, screen == "models") { screen = "models"; drawerOpen = false }
                    DrawerItem("Import Model", Icons.Default.FolderOpen, false) {
                        drawerOpen = false
                        modelPicker.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*"))
                    }
                    HorizontalDivider(Modifier.padding(vertical = 14.dp))
                    DrawerItem("Settings", Icons.Default.Settings, screen == "settings") { screen = "settings"; drawerOpen = false }
                    DrawerItem("Appearance", Icons.Default.DarkMode, false) { darkTheme = !darkTheme }
                    DrawerItem("About", Icons.Default.Info, false) { drawerOpen = false }
                    Spacer(Modifier.weight(1f))
                    Surface(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = if (darkTheme) Color(0xFF162A20) else Color(0xFFE9F8EF)
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(Color(0xFF2FC66D), RoundedCornerShape(50)))
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
                        title = { Text("Edu AI Local", fontWeight = FontWeight.Bold) },
                        navigationIcon = { IconButton({ drawerOpen = true }) { Icon(Icons.Default.Menu, "Menu") } },
                        actions = {
                            if (screen == "chat") IconButton({ screen = "models" }) { Icon(Icons.Default.Memory, "Models") }
                        }
                    )
                }
            ) { padding ->
                when (screen) {
                    "chat" -> ChatScreen(
                        Modifier.padding(padding), selectedFile, installedModels, input,
                        { input = it }, messages, generating, status, { loadModel(it) }, { sendMessage() }
                    )
                    "models" -> ModelsScreen(
                        Modifier.padding(padding), installedModels, downloadName, downloadProgress,
                        { modelPicker.launch(arrayOf("application/octet-stream", "application/x-gguf", "*/*")) },
                        { downloadModel(it) }, { loadModel(it) },
                        { file -> file.delete(); refreshModels(); if (selectedFile?.path == file.path) selectedFile = null }
                    )
                    else -> SettingsScreen(Modifier.padding(padding), darkTheme, { darkTheme = it }, selectedFile)
                }
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
        label = { Text(label) }, selected = selected, onClick = onClick,
        icon = { Icon(icon, null) }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
    )
}

@Composable
private fun ChatScreen(
    modifier: Modifier, selectedFile: File?, installed: List<File>, input: String,
    onInput: (String) -> Unit, messages: List<ChatMessage>, generating: Boolean, status: String,
    onLoad: (File) -> Unit, onSend: () -> Unit
) {
    Column(modifier.fillMaxSize()) {
        if (installed.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().padding(16.dp), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("No local model installed", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(5.dp))
                    Text("Open Models to import a GGUF file or download a supported model.")
                }
            }
        } else {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(if (selectedFile != null) Color(0xFF2FC66D) else Color.Gray, RoundedCornerShape(50)))
                        Spacer(Modifier.width(9.dp))
                        Text(selectedFile?.name ?: "Select a local model", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Default.KeyboardArrowDown, null)
                    }
                }
                DropdownMenu(expanded, { expanded = false }) {
                    installed.forEach { file ->
                        DropdownMenuItem(text = { Text(file.name) }, onClick = { onLoad(file); expanded = false })
                    }
                }
            }
        }

        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (selectedFile != null) Color(0xFF2FC66D) else Color.Gray, RoundedCornerShape(50)))
            Spacer(Modifier.width(7.dp))
            Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages) { message ->
                val user = message.role == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (user) Color(0xFF1976F3) else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 340.dp)
                    ) {
                        Text(
                            message.text.ifBlank { if (generating && !user) "…" else "" },
                            color = if (user) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(14.dp), lineHeight = 21.sp
                        )
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input, onValueChange = onInput,
                Modifier.weight(1f), placeholder = { Text("Message your local model…") },
                shape = RoundedCornerShape(22.dp), maxLines = 5, enabled = selectedFile != null && !generating
            )
            Spacer(Modifier.width(6.dp))
            FloatingActionButton(
                onClick = onSend, Modifier.size(52.dp),
                containerColor = Color(0xFF1976F3), contentColor = Color.White
            ) { Icon(Icons.Default.Send, "Send") }
        }
    }
}

@Composable
private fun ModelsScreen(
    modifier: Modifier, installed: List<File>, downloadName: String?, progress: Float,
    onImport: () -> Unit, onDownload: (CatalogModel) -> Unit,
    onLoad: (File) -> Unit, onDelete: (File) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Models", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(
                onClick = onImport,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Import")
            }
        }

        TabRow(selectedTabIndex = tab) {
            listOf("Installed", "Available", "Downloads").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
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

        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (tab) {
                0 -> items(installed) { file ->
                    InstalledModelCard(file, { onLoad(file) }, { onDelete(file) })
                }
                1 -> items(catalog) { model ->
                    val exists = installed.any { it.name == model.fileName }
                    ModelCatalogCard(model, exists) { if (!exists) onDownload(model) }
                }
                2 -> if (downloadName == null) item {
                    Text("No active downloads.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InstalledModelCard(file: File, onLoad: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, null, tint = Color(0xFF1689D7), modifier = Modifier.size(42.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, fontWeight = FontWeight.Bold)
                    Text(formatBytes(file.length()), fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoad, Modifier.weight(1f)) { Text("Load") }
                OutlinedButton(onClick = onDelete, Modifier.weight(1f)) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun ModelCatalogCard(model: CatalogModel, installed: Boolean, onDownload: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Psychology, null, tint = Color(0xFF1689D7), modifier = Modifier.size(42.dp))
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
        item { SettingRow("App version", "0.2.0", Icons.Default.Info) }
    }
}

@Composable
private fun SettingRow(title: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        leadingContent = { Icon(icon, null) }
    )
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
