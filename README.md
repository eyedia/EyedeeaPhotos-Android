# Eyedeea Photos — Android & Fire TV Apps

This folder contains the Eyedeea Photos Android and Fire TV applications. Both apps share the same codebase with two build variants:

- firetv: Fire TV build variant
- mobile: Android phone/tablet build variant

You can open this folder (the Android project) directly in Android Studio and run or build the apps.

## Build Instructions

1. Update `local.properties` with any required secrets/passwords used by your build tasks.
2. From this folder (D:\Work\EyedeeaPhotos\apps\android), run the following commands:

```
./gradlew app:assembleFiretvRelease
./gradlew app:assembleMobileRelease
```

3. To rename and copy the generated APKs to the top-level `/release` folder, run:

```
./gradlew buildAndCopyApks
```

Notes:
- These commands assume a Unix-like shell; on Windows you may prefer `gradlew.bat` equivalents.
- The `buildAndCopyApks` task will place the release APKs under the repository `release/` folder for distribution.
