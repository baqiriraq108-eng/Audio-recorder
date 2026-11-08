package com.example.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.AudioFormatUtils
import com.example.data.DriveFolderItem
import com.example.data.DriveQuotaInfo
import com.example.data.GoogleDriveAuthStepResult
import com.example.ui.components.ImportCloudWithArrowIcon
import com.example.ui.components.UnifiedTrashConfirmDialog
import com.example.ui.components.UploadCloudWithArrowIcon
import com.example.data.RecorderSettings
import com.example.data.RecordingEntity
import com.example.data.StorageDestination
import com.example.ui.theme.DestructiveCrimson
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.NavActiveContent
import com.example.ui.theme.NavPillIndicator
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.OnPeachAccent
import com.example.ui.theme.OutlineWarm
import com.example.ui.theme.PeachAccent
import com.example.ui.theme.PrimaryText
import com.example.ui.theme.SecondaryText
import com.example.ui.theme.SuccessSage
import com.example.ui.theme.TabularMonoStyle
import com.example.ui.theme.TonalDarkBrown
import com.example.ui.theme.WarmSecondarySurface
import com.example.ui.theme.m3PressScale

@Immutable
data class FolderBreadcrumb(
    val id: String,
    val name: String,
    val path: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(
    settings: RecorderSettings,
    localRecordings: List<RecordingEntity>,
    cloudRecordings: List<RecordingEntity>,
    driveFolders: List<DriveFolderItem>,
    currentBrowseFolder: FolderBreadcrumb,
    folderBreadcrumbs: List<FolderBreadcrumb>,
    localPhoneFolders: List<DriveFolderItem>,
    currentLocalBrowseFolder: FolderBreadcrumb,
    localFolderBreadcrumbs: List<FolderBreadcrumb>,
    isFolderLoading: Boolean,
    driveQuota: DriveQuotaInfo,
    isAuthenticatingDrive: Boolean,
    isImportingDriveFiles: Boolean,
    pendingDriveConsent: GoogleDriveAuthStepResult.NeedsConsentResolution?,
    pendingDriveActivityIntent: GoogleDriveAuthStepResult.NeedsActivityIntentResolution?,
    syncingProgressMap: Map<Long, Float>,
    onSelectDefaultDestination: (StorageDestination, Context) -> Unit,
    onConnectDriveWithGoogle: (Context) -> Unit,
    onDriveConsentResult: (Context, Boolean, Intent?) -> Unit,
    onDriveActivityIntentResult: (Context, Boolean, Intent?) -> Unit,
    onDisconnectDrive: () -> Unit,
    onToggleAutoSync: (Boolean) -> Unit,
    onBrowseDriveFolder: (FolderBreadcrumb) -> Unit,
    onCreateDriveFolder: (String, Boolean) -> Unit,
    onSelectTargetDriveFolder: (String, String, String) -> Unit,
    onSelectDriveDocumentTree: (Uri) -> Unit,
    onBrowseLocalFolder: (FolderBreadcrumb) -> Unit,
    onCreateLocalFolder: (String, Boolean) -> Unit,
    onSelectTargetLocalFolder: (String, String) -> Unit,
    onSelectLocalDocumentTree: (Context, Uri) -> Unit = { _, _ -> },
    onResetLocalFolderToDefault: (Context) -> Unit = {},
    onRefreshDriveFolders: () -> Unit,
    onImportFromDrive: (List<Uri>) -> Unit,
    onSyncRecordingToCloud: (RecordingEntity) -> Unit = {},
    onSyncAllLocalToCloud: () -> Unit,
    onPlayRecording: (RecordingEntity) -> Unit = {},
    onPrepareExploreDriveFolder: () -> Unit = {},
    onPrepareChooseDriveFolder: () -> Unit = {},
    onPrepareExploreLocalFolder: () -> Unit = {},
    onPrepareChooseLocalFolder: () -> Unit = {}
) {
    val context = LocalContext.current
    var showAccountSheet by remember { mutableStateOf(false) }
    var showFolderSheet by remember { mutableStateOf(false) }
    var showDisconnectConfirmDialog by remember { mutableStateOf(false) }
    var showDriveFolderPickerModal by remember { mutableStateOf(false) }
    var showLocalFolderPickerModal by remember { mutableStateOf(false) }
    var isFolderExploreMode by remember { mutableStateOf(false) }

    val isAuthenticated = settings.driveConnected && settings.driveAccountEmail.isNotBlank()

    // Native Android System Account Picker ("com.google") launcher
    val activityIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        onDriveActivityIntentResult(context, granted, result.data)
    }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val granted = result.resultCode == Activity.RESULT_OK
        onDriveConsentResult(context, granted, result.data)
    }

    // Native Document Tree Picker integrated with Google Drive provider (OpenDocumentTree)
    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            onSelectDriveDocumentTree(treeUri)
        }
    }

    // Native Storage Access Framework (SAF) Directory Picker for Local Device Storage
    val openLocalDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            onSelectLocalDocumentTree(context, treeUri)
        }
    }

    // Native Multi-Audio Document Picker for importing audio files from Google Drive
    val importAudioFromDriveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            onImportFromDrive(uris)
        }
    }

    LaunchedEffect(pendingDriveConsent) {
        if (pendingDriveConsent != null) {
            onDriveConsentResult(context, false, null)
            openDocumentTreeLauncher.launch(null)
        }
    }

    LaunchedEffect(pendingDriveActivityIntent) {
        if (pendingDriveActivityIntent != null) {
            if (pendingDriveActivityIntent.stepType == com.example.data.AuthIntentStepType.OAUTH_CONSENT) {
                onDriveActivityIntentResult(context, false, null)
                openDocumentTreeLauncher.launch(null)
            } else {
                try {
                    activityIntentLauncher.launch(pendingDriveActivityIntent.intent)
                } catch (_: Exception) {
                    onDriveActivityIntentResult(context, false, null)
                }
            }
        }
    }

    val localBytes = localRecordings.sumOf { it.sizeBytes }
    val cloudBytes = cloudRecordings.sumOf { it.sizeBytes }
    val isLocalSelected = !isAuthenticated || settings.defaultStorageDestination == StorageDestination.LOCAL
    val isCloudSelected = isAuthenticated && settings.defaultStorageDestination == StorageDestination.CLOUD

    // Strict Layout Order according to User Requirements:
    // 1. Screen header
    // 2. Save Location selector
    // 3. Google Account card
    // 4. Google Drive Folder card
    // 5. Storage Summary
    // 6. Upload and Import action cards
    // 7. Auto Sync row
    // 8. Bottom spacing (and fixed bottom navigation bar). Absolutely NO Recordings section.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("storage_screen_root")
    ) {
        val minScreenHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp)
                .heightIn(min = minScreenHeight),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Screen Header (Top)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Storage & Drive",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp
                    ),
                    color = PrimaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Cloud & Local Storage",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Upper Containers: Save Location, Account Info, Folder Selector, and Export/Import buttons
            // Spaced proportionally with Arrangement.SpaceEvenly to fill vertical screen space cleanly
            Column(
                modifier = Modifier
                    .weight(1f, fill = true)
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                // 2. Save Location Selector (Side-by-side equal-width pills: Device vs. Google Drive)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Save Location",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = PrimaryText,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Option 1: Device
                        val deviceBorder = if (isLocalSelected) BorderStroke(1.5.dp, PeachAccent) else null
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isLocalSelected) ElevatedSurface else WarmSecondarySurface,
                            border = deviceBorder,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onSelectDefaultDestination(StorageDestination.LOCAL, context) }
                                .testTag("save_location_device_option")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhoneAndroid,
                                        contentDescription = "Device",
                                        tint = if (isLocalSelected) PeachAccent else SecondaryText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Device",
                                        style = com.example.ui.theme.StorageLocationOptionLabelStyle,
                                        color = if (isLocalSelected) PrimaryText else SecondaryText,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }
                                Icon(
                                    imageVector = if (isLocalSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = if (isLocalSelected) "Selected" else "Unselected",
                                    tint = if (isLocalSelected) PeachAccent else SecondaryText.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Option 2: Google Drive
                        val driveBorder = if (isCloudSelected) BorderStroke(1.5.dp, PeachAccent) else null
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isCloudSelected) ElevatedSurface else WarmSecondarySurface,
                            border = driveBorder,
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    if (isAuthenticated) {
                                        onSelectDefaultDestination(StorageDestination.CLOUD, context)
                                    } else {
                                        onConnectDriveWithGoogle(context)
                                    }
                                }
                                .testTag("save_location_drive_option")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Cloud,
                                        contentDescription = "Google Drive",
                                        tint = if (isCloudSelected) PeachAccent else SecondaryText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Google Drive",
                                        style = com.example.ui.theme.StorageLocationOptionLabelStyle,
                                        color = if (isCloudSelected) PrimaryText else SecondaryText,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }
                                Icon(
                                    imageVector = if (isCloudSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = if (isCloudSelected) "Selected" else "Unselected",
                                    tint = if (isCloudSelected) PeachAccent else SecondaryText.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // 3. Google Account Card (Avatar, Name, Email, "Connected" badge with green dot, Chevron right)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = WarmSecondarySurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            if (isAuthenticated) {
                                showAccountSheet = true
                            } else {
                                onConnectDriveWithGoogle(context)
                            }
                        }
                        .testTag("google_account_card")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        // Circular Avatar / Initial
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(ElevatedSurface)
                        ) {
                            val initial = settings.driveAccountName
                                .ifBlank { settings.driveAccountEmail }
                                .trim()
                                .firstOrNull()
                                ?.uppercaseChar()
                                ?.toString()
                            if (isAuthenticated && !initial.isNullOrBlank()) {
                                Text(
                                    text = initial,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = PeachAccent
                                )
                            } else {
                                Icon(
                                    imageVector = if (isAuthenticated) Icons.Default.AccountCircle else Icons.Default.CloudOff,
                                    contentDescription = "Google Account",
                                    tint = if (isAuthenticated) PeachAccent else SecondaryText,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isAuthenticated) {
                                    settings.driveAccountName.ifBlank { "Google Account" }
                                } else {
                                    "Connect Google Account"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = PrimaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isAuthenticated) {
                                    settings.driveAccountEmail
                                } else {
                                    "Tap to sign in with Google"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = SecondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(3.dp))

                            // Status Row: Semantic Green dot + "Connected" status
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isAuthenticated) SuccessSage else SecondaryText.copy(alpha = 0.5f))
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isAuthenticated) "Connected" else "Disconnected",
                                    style = TabularMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                                    color = if (isAuthenticated) SuccessSage else SecondaryText
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open account sheet",
                            tint = SecondaryText,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // 4. Google Drive Folder Card (Folder icon in rounded box, Folder Name, Path, Chevron, Choose & Open buttons)
                val folderTitle = if (isCloudSelected) settings.driveFolderName else settings.localFolderName
                val folderPath = if (isCloudSelected) settings.driveFolderPath else settings.localFolderPath

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = WarmSecondarySurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .testTag("google_drive_folder_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // Folder Header Row (Tapping opens Folder Sheet)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showFolderSheet = true }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(ElevatedSurface)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "Folder",
                                    tint = PeachAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = folderTitle,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = PrimaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = folderPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SecondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Folder options",
                                tint = SecondaryText,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Action buttons row: "Choose Folder" and "Open Folder"
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = ElevatedSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        isFolderExploreMode = false
                                        if (isCloudSelected) {
                                            openDocumentTreeLauncher.launch(null)
                                        } else {
                                            openLocalDocumentTreeLauncher.launch(null)
                                        }
                                    }
                                    .testTag("choose_folder_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = "Choose Folder",
                                        tint = PeachAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Choose Folder",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                        color = PrimaryText,
                                        maxLines = 1
                                    )
                                }
                            }

                            if (!isCloudSelected && settings.customStorageTreeUri.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = ElevatedSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            onResetLocalFolderToDefault(context)
                                        }
                                        .testTag("reset_folder_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "Reset Default",
                                            tint = PeachAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Reset Default",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                            color = PrimaryText,
                                            maxLines = 1
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = ElevatedSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (isCloudSelected) {
                                                onPrepareExploreDriveFolder()
                                                showDriveFolderPickerModal = true
                                            } else {
                                                openLocalDocumentTreeLauncher.launch(null)
                                            }
                                        }
                                        .testTag("open_folder_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            imageVector = if (isCloudSelected) Icons.Default.OpenInNew else Icons.Default.FolderOpen,
                                            contentDescription = if (isCloudSelected) "Open Folder" else "Change Folder",
                                            tint = PeachAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isCloudSelected) "Open Folder" else "Change Folder",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                            color = PrimaryText,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. Export and Import Action Cards (Side-by-side: Left card Export / To Drive; Right card Import / From Drive)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Left Card: Export (Matching visual style and color of Import card)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = WarmSecondarySurface,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSyncAllLocalToCloud() }
                            .testTag("storage_action_export_card")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(ElevatedSurface)
                            ) {
                                Icon(
                                    imageVector = UploadCloudWithArrowIcon,
                                    contentDescription = "Export",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Text(
                                text = "Export",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = PrimaryText,
                                maxLines = 1
                            )
                        }
                    }

                    // Right Card: Import (Dark secondary surface card)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = WarmSecondarySurface,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(enabled = !isImportingDriveFiles) {
                                importAudioFromDriveLauncher.launch(arrayOf("audio/*"))
                            }
                            .testTag("storage_action_import_card")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(ElevatedSurface)
                            ) {
                                if (isImportingDriveFiles) {
                                    CircularProgressIndicator(
                                        color = PeachAccent,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Icon(
                                        imageVector = ImportCloudWithArrowIcon,
                                        contentDescription = "Import",
                                        tint = Color.Unspecified,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Text(
                                text = "Import",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = PrimaryText,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Bottom Section: Auto Sync Row anchored at the bottom of the layout
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = WarmSecondarySurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .testTag("storage_auto_sync_row")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(ElevatedSurface)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Autorenew,
                                    contentDescription = "Auto Sync",
                                    tint = PeachAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Auto Sync",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = PrimaryText,
                                    maxLines = 1
                                )
                                val targetDestLabel = if (isCloudSelected) "Google Drive" else "Device"
                                val targetDestFolder = if (isCloudSelected) settings.driveFolderName else settings.localFolderName
                                Text(
                                    text = "Sync New Recordings to $targetDestLabel ($targetDestFolder)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SecondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Switch(
                            checked = settings.driveAutoSync,
                            onCheckedChange = onToggleAutoSync,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OnPeachAccent,
                                checkedTrackColor = PeachAccent,
                                uncheckedThumbColor = SecondaryText,
                                uncheckedTrackColor = ElevatedSurface,
                                checkedBorderColor = Color.Transparent,
                                uncheckedBorderColor = Color.Transparent
                            ),
                            modifier = Modifier.testTag("auto_sync_toggle")
                        )
                    }
                }
            }
        }
    }

    // Submenu 1: Google Account Bottom Sheet (Matching Panel 2 & 4 of Reference Image)
    if (showAccountSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAccountSheet = false },
            sheetState = sheetState,
            containerColor = WarmSecondarySurface,
            contentColor = PrimaryText,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(SecondaryText.copy(alpha = 0.4f))
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
                    .testTag("google_account_bottom_sheet")
            ) {
                // Sheet Header: Title & Close 'X' Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Google Account",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = PrimaryText
                    )
                    IconButton(onClick = { showAccountSheet = false }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close sheet",
                            tint = PeachAccent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (isAuthenticated) {
                    // Active Account Card (highlighted with PeachAccent border)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ElevatedSurface,
                        border = BorderStroke(1.5.dp, PeachAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(ObsidianBackground)
                            ) {
                                val initial = settings.driveAccountName
                                    .ifBlank { settings.driveAccountEmail }
                                    .trim()
                                    .firstOrNull()
                                    ?.uppercaseChar()
                                    ?.toString() ?: "G"
                                Text(
                                    text = initial,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = PeachAccent
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = settings.driveAccountName.ifBlank { "Google Account" },
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = PrimaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = settings.driveAccountEmail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SecondaryText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Active account",
                                tint = SuccessSage,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // "+ Add account" Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                showAccountSheet = false
                                onConnectDriveWithGoogle(context)
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                            .testTag("sheet_add_account_row")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(ElevatedSurface)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add account",
                                tint = PeachAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Add account",
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryText
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = OutlineWarm)
                    Spacer(modifier = Modifier.height(10.dp))

                    // "Switch account" Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                showAccountSheet = false
                                onConnectDriveWithGoogle(context)
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                            .testTag("sheet_switch_account_row")
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Switch account",
                            tint = PeachAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Switch account",
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryText
                        )
                    }

                    // "Manage accounts" Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                showAccountSheet = false
                                try {
                                    val intent = Intent(Settings.ACTION_SYNC_SETTINGS)
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    try {
                                        val intent = Intent(Settings.ACTION_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                            .testTag("sheet_manage_accounts_row")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Manage accounts",
                            tint = PeachAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Manage accounts",
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryText
                        )
                    }

                    // "Disconnect account" Row (Distinct, themed, requires confirmation)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                showDisconnectConfirmDialog = true
                            }
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                            .testTag("sheet_disconnect_account_row")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Logout,
                            contentDescription = "Disconnect account",
                            tint = PeachAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = "Disconnect account",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                            color = PrimaryText
                        )
                    }
                } else {
                    Text(
                        text = "No Google account is currently connected. Sign in to sync your audio files with Google Drive.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SecondaryText
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            showAccountSheet = false
                            onConnectDriveWithGoogle(context)
                        },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PeachAccent,
                            contentColor = OnPeachAccent
                        ),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("Connect Google Drive", maxLines = 1)
                    }
                }
            }
        }
    }

    // Submenu 2: Folder Bottom Sheet / Submenu (Matching Panel 3 of Reference Image)
    if (showFolderSheet) {
        val folderSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val folderTitle = if (isCloudSelected) settings.driveFolderName else settings.localFolderName
        val folderPath = if (isCloudSelected) settings.driveFolderPath else settings.localFolderPath

        ModalBottomSheet(
            onDismissRequest = { showFolderSheet = false },
            sheetState = folderSheetState,
            containerColor = WarmSecondarySurface,
            contentColor = PrimaryText,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 4.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(SecondaryText.copy(alpha = 0.4f))
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
                    .testTag("folder_bottom_sheet")
            ) {
                // Header: Folder icon + "Folder" title + 'X' close button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(ElevatedSurface)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = "Folder",
                                tint = PeachAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Folder",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = PrimaryText
                        )
                    }
                    IconButton(onClick = { showFolderSheet = false }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close sheet",
                            tint = PeachAccent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Current Folder card
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ElevatedSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianBackground)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "Active folder",
                                tint = PeachAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = folderTitle,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = PrimaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = folderPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = SecondaryText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action Row 1: "Choose folder"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            showFolderSheet = false
                            isFolderExploreMode = false
                            if (isCloudSelected) {
                                onPrepareChooseDriveFolder()
                                onRefreshDriveFolders()
                                showDriveFolderPickerModal = true
                            } else {
                                onPrepareChooseLocalFolder()
                                showLocalFolderPickerModal = true
                            }
                        }
                        .padding(vertical = 14.dp, horizontal = 4.dp)
                        .testTag("sheet_choose_folder_row")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Choose folder",
                            tint = PeachAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Choose folder",
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryText
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = SecondaryText,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Action Row 2: "Open folder"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            showFolderSheet = false
                            isFolderExploreMode = true
                            if (isCloudSelected) {
                                onPrepareExploreDriveFolder()
                                showDriveFolderPickerModal = true
                            } else {
                                onPrepareExploreLocalFolder()
                                showLocalFolderPickerModal = true
                            }
                        }
                        .padding(vertical = 14.dp, horizontal = 4.dp)
                        .testTag("sheet_open_folder_row")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "Open folder",
                            tint = PeachAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Open folder",
                            style = MaterialTheme.typography.titleMedium,
                            color = PrimaryText
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = SecondaryText,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    // Confirmation Dialog before Disconnecting
    if (showDisconnectConfirmDialog) {
        UnifiedTrashConfirmDialog(
            title = "Disconnect Account?",
            message = "Are you sure you want to disconnect ${settings.driveAccountEmail}? Your local recordings will remain on this device, and you can reconnect or switch accounts at any time.",
            confirmText = "Confirm",
            onConfirm = {
                showDisconnectConfirmDialog = false
                showAccountSheet = false
                onDisconnectDrive()
            },
            onDismiss = { showDisconnectConfirmDialog = false },
            icon = Icons.Default.CloudOff
        )
    }

    // Interactive Google Drive Folder Picker / Browser Modal
    if (showDriveFolderPickerModal) {
        val driveTitle = if (isFolderExploreMode) "Browse Google Drive" else "Select Google Drive Folder"
        val driveSubtitle = if (isFolderExploreMode) {
            "Exploring ${currentBrowseFolder.name} • ${currentBrowseFolder.path}"
        } else {
            "Choose or create a folder in ${settings.driveAccountEmail.ifBlank { "Google Drive" }}"
        }
        DriveFolderPickerDialog(
            title = driveTitle,
            subtitle = driveSubtitle,
            currentFolder = currentBrowseFolder,
            breadcrumbs = folderBreadcrumbs,
            subfolders = driveFolders,
            selectedFolderId = settings.driveFolderId,
            isLoading = isFolderLoading,
            isExploreMode = isFolderExploreMode,
            onNavigateBreadcrumb = { crumb -> onBrowseDriveFolder(crumb) },
            onOpenSubfolder = { folder ->
                onBrowseDriveFolder(
                    FolderBreadcrumb(
                        id = folder.id,
                        name = folder.name,
                        path = folder.path
                    )
                )
            },
            onCreateNewFolder = { folderName ->
                onCreateDriveFolder(folderName, false)
            },
            onConfirmFolder = { folderId, folderName, folderPath ->
                onSelectTargetDriveFolder(folderId, folderName, folderPath)
                showDriveFolderPickerModal = false
            },
            onRefresh = onRefreshDriveFolders,
            onDismiss = { showDriveFolderPickerModal = false }
        )
    }

    // Internal Phone Storage Folder Picker / Browser Modal
    if (showLocalFolderPickerModal) {
        val localTitle = if (isFolderExploreMode) "Browse Device Storage" else "Select Device Folder"
        val localSubtitle = if (isFolderExploreMode) {
            "Exploring ${currentLocalBrowseFolder.name} • ${currentLocalBrowseFolder.path}"
        } else {
            "Internal device storage folders only"
        }
        DriveFolderPickerDialog(
            title = localTitle,
            subtitle = localSubtitle,
            currentFolder = currentLocalBrowseFolder,
            breadcrumbs = localFolderBreadcrumbs,
            subfolders = localPhoneFolders,
            selectedFolderId = settings.localFolderPath,
            isLoading = false,
            isExploreMode = isFolderExploreMode,
            onNavigateBreadcrumb = { crumb -> onBrowseLocalFolder(crumb) },
            onOpenSubfolder = { folder ->
                onBrowseLocalFolder(
                    FolderBreadcrumb(
                        id = folder.id,
                        name = folder.name,
                        path = folder.path
                    )
                )
            },
            onCreateNewFolder = { folderName ->
                onCreateLocalFolder(folderName, false)
            },
            onConfirmFolder = { _, folderName, folderPath ->
                onSelectTargetLocalFolder(folderName, folderPath)
                showLocalFolderPickerModal = false
            },
            onRefresh = { onBrowseLocalFolder(currentLocalBrowseFolder) },
            onDismiss = { showLocalFolderPickerModal = false }
        )
    }
}

@Composable
fun DriveFolderPickerDialog(
    title: String = "Select Google Drive Folder",
    subtitle: String = "Browse folders or create a new directory",
    currentFolder: FolderBreadcrumb,
    breadcrumbs: List<FolderBreadcrumb>,
    subfolders: List<DriveFolderItem>,
    selectedFolderId: String,
    isLoading: Boolean,
    isExploreMode: Boolean = false,
    onNavigateBreadcrumb: (FolderBreadcrumb) -> Unit,
    onOpenSubfolder: (DriveFolderItem) -> Unit,
    onCreateNewFolder: (String) -> Unit,
    onConfirmFolder: (String, String, String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var showCreateFolderInput by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = WarmSecondarySurface,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .testTag("drive_folder_picker_dialog")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Dialog Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = PrimaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = SecondaryText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh folders",
                            tint = PeachAccent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Interactive Breadcrumb Bar with back/up button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianBackground)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    if (breadcrumbs.size > 1) {
                        IconButton(
                            onClick = { onNavigateBreadcrumb(breadcrumbs[breadcrumbs.size - 2]) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Up to parent folder",
                                tint = PeachAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    breadcrumbs.forEachIndexed { index, crumb ->
                        val isLast = index == breadcrumbs.lastIndex
                        Text(
                            text = crumb.name,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (isLast) PeachAccent else SecondaryText,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onNavigateBreadcrumb(crumb) }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                        if (!isLast) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = SecondaryText,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Inline New Folder Creation Bar
                AnimatedVisibility(visible = showCreateFolderInput) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ElevatedSurface)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "Create New Folder in \"${currentFolder.name}\"",
                            style = MaterialTheme.typography.labelLarge,
                            color = PrimaryText,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = newFolderName,
                                onValueChange = { newFolderName = it },
                                placeholder = { Text("Folder name…", maxLines = 1) },
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PeachAccent,
                                    unfocusedBorderColor = OutlineWarm,
                                    cursorColor = PeachAccent,
                                    focusedContainerColor = ObsidianBackground,
                                    unfocusedContainerColor = ObsidianBackground,
                                    focusedTextColor = PrimaryText,
                                    unfocusedTextColor = PrimaryText,
                                    focusedPlaceholderColor = SecondaryText,
                                    unfocusedPlaceholderColor = SecondaryText
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("new_drive_folder_name_input")
                            )
                            Button(
                                onClick = {
                                    if (newFolderName.isNotBlank()) {
                                        onCreateNewFolder(newFolderName.trim())
                                        newFolderName = ""
                                        showCreateFolderInput = false
                                    }
                                },
                                shape = CircleShape,
                                elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PeachAccent,
                                    contentColor = OnPeachAccent
                                ),
                                modifier = Modifier
                                    .height(46.dp)
                                    .testTag("confirm_create_drive_folder_button")
                            ) {
                                Text("Create", maxLines = 1)
                            }
                        }
                    }
                }

                // Folder List Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 260.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ElevatedSurface)
                        .padding(8.dp)
                ) {
                    if (isLoading) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            CircularProgressIndicator(
                                color = PeachAccent,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    } else if (subfolders.isEmpty()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "Empty folder",
                                    tint = SecondaryText,
                                    modifier = Modifier.size(34.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "No subfolders in \"${currentFolder.name}\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SecondaryText
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(items = subfolders, key = { it.id }) { folder ->
                                val isSelected = folder.id == selectedFolderId || folder.path == selectedFolderId
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (isSelected) NavPillIndicator else ObsidianBackground)
                                        .clickable { onOpenSubfolder(folder) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                        .testTag("drive_folder_row_${folder.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = folder.name,
                                        tint = PeachAccent,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = folder.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = PrimaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = folder.path,
                                            style = TabularMonoStyle.copy(fontSize = 10.sp),
                                            color = SecondaryText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    if (isExploreMode) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = "Open subfolder",
                                            tint = SecondaryText,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Button(
                                            onClick = {
                                                onConfirmFolder(folder.id, folder.name, folder.path)
                                            },
                                            shape = CircleShape,
                                            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = PeachAccent,
                                                contentColor = OnPeachAccent
                                            ),
                                            modifier = Modifier
                                                .height(32.dp)
                                                .testTag("select_drive_folder_${folder.id}")
                                        ) {
                                            Text(
                                                text = "Select",
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Dialog Footer Action Buttons ("New Folder", "Cancel"/"Close", "Select")
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = { showCreateFolderInput = !showCreateFolderInput },
                        shape = CircleShape,
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TonalDarkBrown,
                            contentColor = PrimaryText
                        ),
                        modifier = Modifier
                            .height(42.dp)
                            .testTag("toggle_new_folder_input_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CreateNewFolder,
                            contentDescription = "New Folder",
                            tint = PeachAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "New Folder",
                            style = MaterialTheme.typography.labelLarge,
                            color = PrimaryText,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (isExploreMode) {
                        Button(
                            onClick = onDismiss,
                            shape = CircleShape,
                            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TonalDarkBrown,
                                contentColor = PrimaryText
                            ),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("close_drive_folder_explore_button")
                        ) {
                            Text(
                                text = "Close",
                                style = MaterialTheme.typography.labelLarge,
                                color = PrimaryText,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Button(
                            onClick = {
                                onConfirmFolder(currentFolder.id, currentFolder.name, currentFolder.path)
                            },
                            shape = CircleShape,
                            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PeachAccent,
                                contentColor = OnPeachAccent
                            ),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("set_destination_drive_folder_button")
                        ) {
                            Text(
                                text = "Select",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            shape = CircleShape,
                            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = TonalDarkBrown,
                                contentColor = PrimaryText
                            ),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("cancel_drive_folder_button")
                        ) {
                            Text(
                                text = "Cancel",
                                style = MaterialTheme.typography.labelLarge,
                                color = PrimaryText,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Button(
                            onClick = {
                                onConfirmFolder(currentFolder.id, currentFolder.name, currentFolder.path)
                            },
                            shape = CircleShape,
                            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PeachAccent,
                                contentColor = OnPeachAccent
                            ),
                            modifier = Modifier
                                .height(42.dp)
                                .testTag("use_current_drive_folder_button")
                        ) {
                            Text(
                                text = "Select",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

