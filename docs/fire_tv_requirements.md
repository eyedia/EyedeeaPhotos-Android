# Eyedeea Photos - Fire TV Requirements

## App Store Compliance (Amazon Appstore)
- **Model:** Operates strictly as a "Companion / Reader App" (Consumption-only).
- **In-App Purchases (IAP):** None. The app relies entirely on the user having an existing web subscription.
- **No Upselling:** The app must not contain any text, buttons, or links prompting users to buy a subscription, upgrade, or visit the website to make a purchase.

## Authentication & Device Registration
- **Device Code Flow:**
  - App generates a unique device code and displays a QR code on the TV screen.
  - QR code points strictly to: `https://www.eyedeeaphotos.com/activate`.
- **The `/activate` Web Page Rules (CRITICAL for App Approval):**
  - Must be a standalone "island" page used solely for device linking.
  - **No Headers/Footers:** Remove all standard website navigation.
  - **No Checkout Links:** Absolutely no links to "Pricing", "Plans", "Sign Up", or "Subscribe".
  - **Logo:** The brand logo is allowed but **MUST NOT** be a clickable anchor to the main homepage (to prevent reviewers from navigating to a checkout page).
  - **Unsubscribed Users:** If a user logs in but has no active subscription, the page must show a strict error (e.g., "This account does not have an active subscription.") and provide no links to buy one.

## App Functionality
- **Viewing Only:** Fire TV app is strictly for consuming (viewing) uploaded photos. No upload or sharing functionalities exist on the TV version.
- **Device Support:** In the Amazon Developer Console, ensure **only Fire TV devices** are selected. Fire Tablets should be unchecked to avoid rejection based on lacking tablet-interactive features (like system sharing).

## UI/UX Guidelines
- **Text & Copy:** Keep error messages strictly informational. E.g., use "It looks like no one entered the code. Tap Refresh to try again." Avoid directional language like "Go to our site to buy a plan."
- **Styling:** Adhere to standard TV design paradigms (D-Pad focusable elements, clear selection states). Use modern Title Case for buttons (e.g., "Refresh").
