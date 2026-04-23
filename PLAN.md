# Implementation Plan: New Settings & Photo Sync (Android)

## 1. Requirements Overview
- **User Authentication:** Display name/email, Logout/Login buttons.
- **Website Link:** [Visit Website](https://eyedeeaphotos.eyediatech.com/)
- **Photo Queue (Android Only):**
    - Local queue for photos shared with the app.
    - Persistent storage for the queue (Room DB).
    - Periodic background upload (Hourly) via WorkManager.
    - Manual upload trigger ("Upload Now").
    - "Next Sync Time" indicator.
- **API Integration:**
    - Quota Pre-check (`GET /quota-summary`)
    - Batch Upload (`POST /upload` with `scanAfterUpload=true`)
    - Manual Scan Trigger (`POST /scan`)

## 2. UI/UX Design (Superb Experience)
- **Modern Layout:** Use Material 3 inspired cards and layouts.
- **Visuals:**
    - **User Profile Card:** Large avatar/initials, clean typography for name and email.
    - **Sync Dashboard Card:** 
        - Real-time status (Idle, Syncing, Quota Full).
        - Progress bar for active uploads.
        - "Next Sync" countdown or timestamp.
        - "Sync Now" prominent button.
    - **Queued Photos Section:** 
        - Grid view of thumbnails.
        - Badge showing the total count.
        - Option to remove items from the queue.
    - **Quick Actions:** Stylish buttons for "Visit Website" and "Logout" (red themed).
- **Feedback:** Haptic feedback on button clicks, smooth item animations in the list, and toast/snackbar notifications.

## 3. Technical Tasks

### Phase 1: Data Layer & APIs
- [ ] **Dependency Update:** Add Room and WorkManager to `app/build.gradle.kts`.
- [ ] **API Model Updates:** Update models to match the new detailed quota and upload responses.
- [ ] **ApiService Update:** 
    - Refine `getQuotaSummary` to return the new nested structure.
    - Update `uploadPhotos` with `scanAfterUpload` and `relativePaths`.
    - Update `triggerScan` query parameters.
- [ ] **Room Database:**
    - `QueuedPhoto` Entity (id, originalUri, internalPath, fileName, addedTimestamp, status).
    - `PhotoDao` for CRUD operations.
    - `AppDatabase` setup.

### Phase 2: Background Sync Logic
- [ ] **SyncWorker:**
    - Auth & Quota validation.
    - Batch processing logic.
    - Internal file management (cleaning up after upload).
    - Scan API trigger.
- [ ] **WorkManager Setup:** Schedule `PeriodicWorkRequest` (1 hour) with constraints (Battery not low, Network connected).

### Phase 3: UI Implementation
- [ ] **SettingsActivity & ViewModel:** 
    - Observe Room DB for the queue list.
    - Handle "Sync Now" which triggers a `OneTimeWorkRequest`.
    - Format "Next Sync Time" from WorkManager's `WorkInfo`.
- [ ] **Custom UI Components:** 
    - Custom progress view for sync.
    - Image thumbnail loader (using Glide or Coil).

### Phase 4: Share Flow Redirection
- [ ] **ShareActivity Update:** 
    - Intercept `ACTION_SEND` / `ACTION_SEND_MULTIPLE`.
    - Copy shared files to app's cache/internal dir (so they persist).
    - Add entries to Room DB.
    - Success animation and redirection to Settings.

## 4. API Details (Reference)
- **Quota Precheck:** `GET /api/v1/{householdId}/sources/{sourceId}/browse/upload/quota-summary`
- **Upload:** `POST /api/v1/{householdId}/sources/{sourceId}/browse/upload` (Form: `folderPath=raw`, `scanAfterUpload=true`)
- **Scan:** `POST /api/v1/{householdId}/sources/{sourceId}/scan?folder_name=raw` (Params: `process=background`, `process_count=4`)
