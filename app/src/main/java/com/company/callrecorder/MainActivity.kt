package com.company.callrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.company.callrecorder.data.AppUser
import com.company.callrecorder.data.AuthRepository
import com.company.callrecorder.data.Recording
import com.company.callrecorder.service.FileWatcherService
import com.company.callrecorder.ui.theme.*
import com.company.callrecorder.util.FileUtils
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var authRepository: AuthRepository

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startFileWatcherService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        authRepository = AuthRepository(this)

        setContent {
            CallRecorderUploaderTheme {
                AppRoot(
                    authRepository = authRepository,
                    viewModel = viewModel,
                    onPlayRecording = { recording -> playRecording(recording) },
                    onStopPlaying = { stopPlaying() },
                    onDeleteRecording = { recording -> viewModel.deleteRecording(recording) },
                    onStartService = { checkAndRequestPermissions() }
                )
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startFileWatcherService()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startFileWatcherService() {
        val intent = Intent(this, FileWatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        viewModel.setServiceRunning(true)
    }

    private fun playRecording(recording: Recording) {
        try {
            stopPlaying()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(recording.filePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopPlaying() {
        mediaPlayer?.release()
        mediaPlayer = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlaying()
    }
}

@Composable
fun AppRoot(
    authRepository: AuthRepository,
    viewModel: MainViewModel,
    onPlayRecording: (Recording) -> Unit,
    onStopPlaying: () -> Unit,
    onDeleteRecording: (Recording) -> Unit,
    onStartService: () -> Unit
) {
    val currentUser by authRepository.currentUser.collectAsState()
    val appUser by authRepository.appUser.collectAsState()
    val isLoading by authRepository.isLoading.collectAsState()

    when {
        isLoading -> {
            LoadingScreen()
        }
        currentUser == null -> {
            LoginScreen(authRepository = authRepository)
        }
        appUser?.status == "pending" -> {
            PendingApprovalScreen(
                appUser = appUser,
                onLogout = { authRepository.signOut() }
            )
        }
        appUser?.status == "rejected" -> {
            RejectedScreen(
                onLogout = { authRepository.signOut() }
            )
        }
        appUser?.status == "approved" -> {
            LaunchedEffect(Unit) {
                onStartService()
            }
            MainApp(
                viewModel = viewModel,
                appUser = appUser,
                onPlayRecording = onPlayRecording,
                onStopPlaying = onStopPlaying,
                onDeleteRecording = onDeleteRecording,
                onLogout = { authRepository.signOut() }
            )
        }
        else -> {
            LoadingScreen()
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Primary)
            Spacer(modifier = Modifier.height(16.dp))
            Text("로딩중...", color = TextSecondary)
        }
    }
}

@Composable
fun LoginScreen(authRepository: AuthRepository) {
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Phone,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "통화녹음 업로더",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Google 계정으로 로그인하여\n녹음 파일을 업로드하세요",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            errorMessage = null
                            val result = authRepository.signInWithGoogle()
                            isLoading = false
                            if (result.isFailure) {
                                errorMessage = result.exceptionOrNull()?.message ?: "로그인 실패"
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    border = ButtonDefaults.outlinedButtonBorder,
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Primary
                        )
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("G", fontWeight = FontWeight.Bold, color = Color(0xFF4285F4), fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Google로 로그인",
                                color = TextPrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        errorMessage!!,
                        color = Error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun PendingApprovalScreen(appUser: AppUser?, onLogout: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.HourglassTop,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Warning
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "승인 대기 중",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "관리자의 승인을 기다리고 있습니다.\n승인 후 앱을 사용할 수 있습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                if (appUser != null) {
                    Spacer(modifier = Modifier.height(24.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Background),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (appUser.photoURL != null) {
                                AsyncImage(
                                    model = appUser.photoURL,
                                    contentDescription = "Profile",
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(Primary.copy(alpha = 0.1f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        appUser.displayName.firstOrNull()?.toString() ?: "?",
                                        color = Primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    appUser.displayName,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                                Text(
                                    appUser.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("로그아웃")
                }
            }
        }
    }
}

@Composable
fun RejectedScreen(onLogout: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Block,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = Error
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "접근 거부됨",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "관리자에 의해 접근이 거부되었습니다.\n문의가 필요하시면 관리자에게 연락하세요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("로그아웃")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    viewModel: MainViewModel,
    appUser: AppUser?,
    onPlayRecording: (Recording) -> Unit,
    onStopPlaying: () -> Unit,
    onDeleteRecording: (Recording) -> Unit,
    onLogout: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.FolderOpen, contentDescription = "파일선택") },
                    label = { Text("파일선택", fontSize = 11.sp) },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Primary,
                        selectedTextColor = Primary,
                        indicatorColor = PrimaryLight
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.CloudUpload, contentDescription = "업로드") },
                    label = { Text("업로드", fontSize = 11.sp) },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Primary,
                        selectedTextColor = Primary,
                        indicatorColor = PrimaryLight
                    )
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Person, contentDescription = "프로필") },
                    label = { Text("프로필", fontSize = 11.sp) },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Primary,
                        selectedTextColor = Primary,
                        indicatorColor = PrimaryLight
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (selectedTab) {
                0 -> FileSelectScreen(viewModel = viewModel)
                1 -> UploadedScreen(
                    viewModel = viewModel,
                    onPlayRecording = onPlayRecording,
                    onStopPlaying = onStopPlaying,
                    onDeleteRecording = onDeleteRecording
                )
                2 -> ProfileScreen(appUser = appUser, onLogout = onLogout)
            }
        }
    }
}

@Composable
fun FileSelectScreen(viewModel: MainViewModel) {
    val deviceFiles by viewModel.deviceFiles.collectAsState()
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val uploadMessage by viewModel.uploadMessage.collectAsState()

    LaunchedEffect(uploadMessage) {
        if (uploadMessage != null) {
            kotlinx.coroutines.delay(2000)
            viewModel.clearUploadMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(16.dp)
    ) {
        Text(
            text = "녹음 파일 선택",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Text(
            text = "업로드할 파일을 선택하세요",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 스캔 버튼
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.scanDeviceFiles() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                enabled = !isScanning
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isScanning) "스캔중..." else "파일 스캔")
            }

            if (deviceFiles.isNotEmpty()) {
                OutlinedButton(
                    onClick = { viewModel.toggleSelectAll() },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("전체선택")
                }
            }
        }

        if (uploadMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uploadMessage!!.contains("완료")) Success.copy(alpha = 0.1f)
                    else Error.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = uploadMessage!!,
                    modifier = Modifier.padding(12.dp),
                    color = if (uploadMessage!!.contains("완료")) Success else Error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 선택한 파일 업로드 버튼
        if (selectedFiles.isNotEmpty()) {
            Button(
                onClick = { viewModel.addAndUploadSelected() },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Success)
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("선택한 ${selectedFiles.size}개 파일 업로드")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 파일 목록
        if (deviceFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextMuted
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "파일 스캔 버튼을 눌러\n녹음 파일을 불러오세요",
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Text(
                text = "총 ${deviceFiles.size}개 파일",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(deviceFiles) { deviceFile ->
                    DeviceFileItem(
                        deviceFile = deviceFile,
                        isSelected = selectedFiles.contains(deviceFile.file.absolutePath),
                        onToggle = { viewModel.toggleFileSelection(deviceFile.file.absolutePath) }
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceFileItem(
    deviceFile: DeviceFile,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !deviceFile.isAlreadyAdded) { onToggle() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                deviceFile.isAlreadyAdded -> SurfaceVariant
                isSelected -> PrimaryLight
                else -> Color.White
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 체크박스
            if (!deviceFile.isAlreadyAdded) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(checkedColor = Primary)
                )
            } else {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "업로드됨",
                    tint = Success,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 통화 유형 아이콘
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (deviceFile.callType == "incoming")
                            IncomingCall.copy(alpha = 0.1f)
                        else
                            OutgoingCall.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (deviceFile.callType == "incoming") Icons.Default.CallReceived
                    else Icons.Default.CallMade,
                    contentDescription = null,
                    tint = if (deviceFile.callType == "incoming") IncomingCall else OutgoingCall,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceFile.phoneNumber,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = if (deviceFile.isAlreadyAdded) TextMuted else TextPrimary
                )
                Text(
                    text = FileUtils.formatDateTime(deviceFile.recordedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (deviceFile.isAlreadyAdded) {
                Surface(
                    color = Success.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "업로드됨",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Success
                    )
                }
            }
        }
    }
}

@Composable
fun UploadedScreen(
    viewModel: MainViewModel,
    onPlayRecording: (Recording) -> Unit,
    onStopPlaying: () -> Unit,
    onDeleteRecording: (Recording) -> Unit
) {
    val recordings by viewModel.recordings.collectAsState()
    val uploadingIds by viewModel.uploadingIds.collectAsState()
    val uploadMessage by viewModel.uploadMessage.collectAsState()
    var currentlyPlaying by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uploadMessage) {
        if (uploadMessage != null) {
            kotlinx.coroutines.delay(2000)
            viewModel.clearUploadMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "업로드 현황",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "총 ${recordings.size}개의 녹음",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }

            val pendingCount = recordings.count { it.uploadStatus == "pending" || it.uploadStatus == "failed" }
            if (pendingCount > 0) {
                Button(
                    onClick = { viewModel.uploadAllPending() },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("재시도 ($pendingCount)", fontSize = 12.sp)
                }
            }
        }

        if (uploadMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (uploadMessage!!.contains("완료")) Success.copy(alpha = 0.1f)
                    else Error.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = uploadMessage!!,
                    modifier = Modifier.padding(12.dp),
                    color = if (uploadMessage!!.contains("완료")) Success else Error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (recordings.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = TextMuted
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "업로드된 녹음이 없습니다\n파일선택 탭에서 파일을 선택해주세요",
                        color = TextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(recordings) { recording ->
                    RecordingItemFull(
                        recording = recording,
                        isPlaying = currentlyPlaying == recording.id,
                        isUploading = uploadingIds.contains(recording.id),
                        onPlay = {
                            if (currentlyPlaying == recording.id) {
                                onStopPlaying()
                                currentlyPlaying = null
                            } else {
                                onPlayRecording(recording)
                                currentlyPlaying = recording.id
                            }
                        },
                        onDelete = { onDeleteRecording(recording) },
                        onUpload = { viewModel.uploadRecording(recording) }
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(appUser: AppUser?, onLogout: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(20.dp)
    ) {
        Text(
            text = "프로필",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (appUser != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (appUser.photoURL != null) {
                        AsyncImage(
                            model = appUser.photoURL,
                            contentDescription = "Profile",
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                appUser.displayName.firstOrNull()?.toString() ?: "?",
                                color = Primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 32.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        appUser.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )

                    Text(
                        appUser.email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        color = Success.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            "승인됨",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            color = Success,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "앱 정보",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    InfoRow(label = "버전", value = "1.0.0")
                    InfoRow(label = "사용자 ID", value = appUser.uid.take(8) + "...")
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onLogout,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Error)
            ) {
                Icon(Icons.Default.Logout, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("로그아웃", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextSecondary)
        Text(value, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusChip(status: String) {
    val (color, text) = when (status) {
        "done" -> Success to "완료"
        "uploading" -> Warning to "업로드중"
        "failed" -> Error to "실패"
        else -> TextMuted to "대기"
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

@Composable
fun RecordingItemFull(
    recording: Recording,
    isPlaying: Boolean,
    isUploading: Boolean = false,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onUpload: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (recording.callType == "incoming")
                            IncomingCall.copy(alpha = 0.1f)
                        else
                            OutgoingCall.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (recording.callType == "incoming") Icons.Default.CallReceived
                    else Icons.Default.CallMade,
                    contentDescription = null,
                    tint = if (recording.callType == "incoming") IncomingCall else OutgoingCall,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recording.phoneNumber,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = "${FileUtils.formatDateTime(recording.recordedAt)} · ${FileUtils.formatDuration(recording.duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            }

            StatusChip(status = if (isUploading) "uploading" else recording.uploadStatus)

            Spacer(modifier = Modifier.width(4.dp))

            if (recording.uploadStatus == "pending" || recording.uploadStatus == "failed") {
                IconButton(
                    onClick = onUpload,
                    enabled = !isUploading,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Primary
                        )
                    } else {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = "업로드",
                            tint = Primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            IconButton(
                onClick = onPlay,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) Primary else SurfaceVariant)
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "정지" else "재생",
                    tint = if (isPlaying) Color.White else TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "삭제",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
