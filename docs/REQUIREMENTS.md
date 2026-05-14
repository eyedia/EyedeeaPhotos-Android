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
  - Must NOT alter or prefix original file names (e.g., no "queued_" or "photo_" prefixes) to maintain original naming during uploads.
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

## App Store Compliance (Google Play & Android)
- **Model:** Operates strictly as a "Companion / Reader App" to the Eyedeea web platform.
- **Monetization / Billing:** 
  - NO native platform billing (Google Play Billing or Amazon IAP) is implemented.
  - NO links, buttons, or prompts suggesting users upgrade, subscribe, or purchase inside the app.
- **Authentication Flow:**
  - Login via browser/Custom Tabs or QR code to `eyedeeaphotos.com/activate`.
  - The `/activate` web page MUST be a dead-end with no links to checkout, pricing, or the main homepage (the logo must not be clickable).
- **Error Handling (Storage/Subscription):** 
  - If a user's web subscription is inactive or storage limits are reached, the app fails gracefully (e.g., "Subscription Required" or "Upload Failed") without upselling or linking to checkout.
- **Android Specifics:** 
  - Photo uploads must use modern Android photo selection (Photo Picker or `ACTION_SEND` intents) rather than requesting broad, general storage permissions.
  - Provide an Account Deletion link via Google Play Console (Data Safety section) as per Google policy.
