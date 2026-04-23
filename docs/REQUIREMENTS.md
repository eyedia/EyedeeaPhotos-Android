# Eyedeea Photos - Android Requirements

## Settings Layout & UI
- **Panel 1: Identity & Brand**
  - Integrated header featuring the Eyedeea logo.
  - User identity section showing profile avatar, name, and email.
  - A vertical separator between branding and user identity.
  - Logout functionality represented by a standard "exit" icon.
  - Logo acts as a direct link to the official website.
- **Panel 2: Queue Management**
  - **Dynamic Messaging:** 
    - Empty state: Instructions for adding photos via system share or in-app picker.
    - Active state: Summary of photos pending in queue.
  - **Scheduler Information:** Real-time display of the next automated sync execution time.
  - **Visual Queue:** 3-column thumbnail grid of pending photos.
  - **Interactions:**
    - Long-press on any thumbnail to remove it from the queue.
    - "Add Photos" button to pick from local gallery (disabled during sync).
    - "Sync Now" button for manual override.
- **Panel 3: Support Footer**
  - Dedicated support section with contact email: `support@eyediatech.com`.

## Background Functionalities
- **Native Share Integration:**
  - Specialized handler for system-wide "Share" actions.
  - Strict filtering: Accepts only `image/*` MIME types, rejecting videos and other documents.
  - Seamlessly queues shared items into the local database for background processing.
- **Automated Scheduler:**
  - Robust background syncing executed every 1 hour via `WorkManager`.
  - Automatic retry logic and connectivity awareness.
- **Sync & Communication:**
  - Handles batch uploads to the server (default batch size: 20).
  - Triggers server-side processing scans after successful uploads.
  - Comprehensive error handling and status persistence (Pending, Uploading, Failed).
- **Branding & Theme:**
  - Consistent use of branding blue: `#0EA5E9`.
  - Fully compatible with light and dark system themes (Material Components).
  - Verified visibility on Samsung Galaxy S22 and other flagship devices.
