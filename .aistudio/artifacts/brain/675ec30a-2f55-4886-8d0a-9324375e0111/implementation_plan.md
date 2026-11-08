# Local Auto Sync: Direct Device Folder Persistence for New Recordings

Fixes the Auto Sync pipeline when **Device (Local Storage)** is selected as the Save Location so that new recordings created while Auto Sync is enabled are physically written into the user's chosen device folder, without prompting for Google Drive authentication or retroactively copying older recordings.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following product behaviors were confirmed during clarification and govern this implementation:

- **Confirmed Decision 1 — Sync to Selected Device Folder Only When Auto Sync Is Active**: When Save Location is set to **Device** and **Auto Sync** is enabled, newly recorded audio files are automatically written into the user's selected device folder (both SAF-selected external device folders and custom local folders) in addition to appearing in the Local library tab. When Auto Sync is disabled, recordings remain strictly in the internal app library without syncing to external folders or cloud storage.
- **Confirmed Decision 2 — New Recordings Only (No Retroactive Bulk Copy)**: Enabling Auto Sync applies exclusively to new recordings captured after the toggle is turned on. Existing prior recordings are not retroactively copied when toggling Auto Sync on.

---

## 1. Overview & Core Concept

- **What It Does**: Connects the **Auto Sync** toggle directly to the active **Save Location** (`Device` vs. `Google Drive`) and its selected target folder. When `Device` is selected and Auto Sync is turned on, finishing a new recording automatically writes the audio file into the exact folder selected by the user (including folders chosen via Android's system file picker).
- **Target Audience / Persona**: Users who want their newly captured voice recordings automatically exported into a specific folder on their phone's storage whenever Auto Sync is active, without being forced to sign in to Google Drive.
- **Key Value**: Eliminates the bug where local recordings only stayed inside the app's private sandbox directory instead of being written to the user-selected device folder (`DocumentFile` tree URI) and removes the unintended Google Drive sign-in prompt when enabling Auto Sync in Device mode.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **Selecting Device Save Location & Folder**: On the Storage screen, the user selects **Device** under Save Location and picks a target folder (via **Choose Folder** using Android's system directory picker or the local folder selector).
  2. **Enabling Auto Sync in Device Mode**: The user toggles **Auto Sync** on. Because **Device** is selected, the toggle activates immediately without requesting Google Drive login, and the subtitle confirms: `Sync New Recordings to Device ([Folder Name])`.
  3. **Capturing a New Recording**: When the user finishes a new recording while Auto Sync is active, the app saves the recording to the Local library and automatically writes the physical audio file (`.m4a` / `.wav`) into the selected device folder, displaying a confirmation snackbar showing the target device folder name.
  4. **Auto Sync Disabled Behavior**: When Auto Sync is turned off, new recordings are saved only to the app's internal Local library without writing to external device folders or uploading to Google Drive.
- **Visual Identity & Theme**:
  - Preserves the centralized warm obsidian and peach semantic color hierarchy (`ObsidianBackground`, `WarmSecondarySurface`, `ElevatedSurface`, `PeachAccent`, `PrimaryText`, `SecondaryText`, `OutlineWarm`).

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Real Storage Access Framework (`DocumentFile`) Stream Writing**
  - *Chosen Approach*: When a user selects a device folder via the system folder picker (`OpenDocumentTree`), the persisted `customStorageTreeUri` is used via `DocumentFile.fromTreeUri` and `ContentResolver.openOutputStream` to write the finalized audio file directly into that physical directory whenever Auto Sync is enabled.
  - *Why*: Previously, `selectLocalDocumentTreeFolder` saved `customStorageTreeUri` in settings, but the recording save pipeline only wrote to `context.filesDir` and never wrote to `customStorageTreeUri`. Writing through `ContentResolver` ensures the file physically appears in the user's selected folder in any File Manager app.
  - *Alternatives Considered*: Using legacy raw file paths (`/sdcard/...`) alone fails on modern Android versions due to Scoped Storage restrictions unless written through the granted SAF `DocumentFile` tree URI.
- **Decision 2: Decoupling Local Auto Sync from Google Drive Auth & Retroactive Sync**
  - *Chosen Approach*: Toggling Auto Sync on when `Save Location` is `Device` enables the setting immediately without invoking Google Drive sign-in, and removes automatic retroactive syncing of old recordings on startup or toggle change.
  - *Why*: Aligns strictly with the user's requirement that Auto Sync respects the selected Save Location and applies only to new recordings captured after activation.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:

```
┌───────────────────────────────────────────────────────────────────────────┐
│                         StorageScreen (Compose UI)                        │
│  • Save Location Selector: [Device] vs. [Google Drive]                    │
│  • Folder Selector: Sets customStorageTreeUri / localFolderName           │
│  • Auto Sync Toggle: Enables automatic sync for new recordings            │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │ User finishes a new recording
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                    RecorderViewModel (Save Pipeline)                      │
│  • Checks settings.driveAutoSync (Auto Sync enabled)                      │
│  • Branches by settings.defaultStorageDestination:                        │
│     ├─ If CLOUD: Uploads new recording to selected Google Drive folder    │
│     └─ If LOCAL: Syncs new recording to selected Device folder            │
└─────────────────────────────────────┬─────────────────────────────────────┘
                                      │
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│               RecordingRepository (Local Folder Sync Engine)              │
│  • If customStorageTreeUri is set (SAF DocumentFile Tree):                │
│     - Resolves DocumentFile.fromTreeUri(context, treeUri)                 │
│     - Creates audio document with matching MIME type & filename           │
│     - Streams audio bytes via ContentResolver.openOutputStream            │
│  • If internal custom subfolder is set:                                   │
│     - Writes file into target local subfolder under internal storage      │
└───────────────────────────────────────────────────────────────────────────┘
```

- **Data Model & State**:
  - `RecorderSettings.driveAutoSync`: Controls whether new recordings are automatically synced to the active Save Location's target folder.
  - `RecorderSettings.defaultStorageDestination`: Determines whether Auto Sync targets `StorageDestination.LOCAL` (Device folder) or `StorageDestination.CLOUD` (Google Drive folder).
  - `RecorderSettings.customStorageTreeUri`, `localFolderName`, `localFolderPath`: Identify the exact target device directory for Local Auto Sync.
- **Interactive Component & State Mapping**:
  - **Auto Sync Toggle (`toggleAutoSync`)**: Updates `driveAutoSync` in `RecorderSettings` and persists it. Only prompts for Google Drive authentication if `defaultStorageDestination == StorageDestination.CLOUD` and Drive is not yet connected. Does not trigger retroactive uploads of older recordings.
  - **New Recording Finalization (`finalizeRecordingSession` & `confirmSaveSession`)**:
    - Always saves the base recording in the app's Local library database so it is immediately playable inside the app.
    - When `driveAutoSync` is `true` and `defaultStorageDestination == StorageDestination.LOCAL`, invokes `repository.syncRecordingToSelectedLocalFolder(...)` to write the recording into the selected SAF `customStorageTreeUri` folder (or selected local subfolder) and displays a confirmation snackbar naming the target local folder.
    - When `driveAutoSync` is `true` and `defaultStorageDestination == StorageDestination.CLOUD`, uploads the new recording to the selected Google Drive folder.
