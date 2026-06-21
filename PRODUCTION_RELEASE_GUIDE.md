# PAVEL IPTV — Production Release & Deployment Guide

This document provides a comprehensive operational handbook for compiling, signing, optimizing, and deploying the **PAVEL IPTV** system to production environments, including Google Play, Android TV devices, set-top boxes, and high-DPI stream terminals.

---

## 🚀 1. Production Build Optimizations

We have pre-configured highly efficient compiled parameters inside `/app/build.gradle.kts` and `/app/proguard-rules.pro` to ensure minimum system footprint and maximum performance on slow TV hardware:

*   **R8 Code Shrinking (Minification):** Controlled by `isMinifyEnabled = true` in the `release` build block. This shrinks unused classes, aggregates methods, and heavily obfuscates compiled code to protect enterprise intellectual property.
*   **PNG Image Crunching (`isCrunchPngs = true`):** Automatically decompresses and losslessly compresses image drawables to keep initial APK bundle weight at an absolute minimum of ~1–2MB.
*   **ProGuard WebView Preservation (`/app/proguard-rules.pro`):** Configured to prevent R8 from stripping away `@JavascriptInterface` annotated bindings (specifically `WebAppInterface` in `com.example.MainActivity`) which keep web audio-visual and back-key callback controls perfectly responsive.

---

## 🔑 2. Enterprise Release Signing Setup

For an official production release, you must sign the compilation using a secure, custom release signing keystore rather than the default debug keys.

### Step 2.1: Generate a Custom Key Store File
Open your native developer terminal and execute the standard JDK `keytool` command:
```bash
keytool -genkey -v -keystore my-upload-key.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```
This produces `my-upload-key.jks` in your target directory containing the encrypted public-private developer signature.

### Step 2.2: Configure Environment Variables or Secrets Block
To sign the app without hardcoding passwords in the public Gradle file, configure the following parameters on your build server (e.g., GitHub Actions, Google Cloud Build pipeline, or local environment shell):

*   `KEYSTORE_PATH`: Absolute location of your `my-upload-key.jks` file.
*   `STORE_PASSWORD`: The master keystore entry password.
*   `KEY_PASSWORD`: The alias password for compiling the specific developer key.

Inside `/app/build.gradle.kts`, our built-in production signing configurations dynamically bind these parameters securely:
```kotlin
signingConfigs {
    create("release") {
        val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
        storeFile = file(keystorePath)
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
    }
}
```

---

## 📦 3. Compiling APK and App Bundle (AAB)

### Method A: Native Compiler Generation (AI Studio App Settings)
1. In the **Google AI Studio** workspace sidebar, open your project options.
2. Select **Generate Signable APK / Export ZIP** to automatically gather raw system builds, signed using the verified container parameters.

### Method B: Gradle Terminal Assembly
If you export the workspace to work offline or via local CI/CD setups, compile using standard Gradle CLI scripts:

*   **Generate standard signed multi-architecture release APK:**
    ```bash
    gradle :app:assembleRelease
    ```
    *Output location:* `/app/build/outputs/apk/release/app-release.apk`

*   **Generate an Android App Bundle (AAB) for Google Play Store registration:**
    ```bash
    gradle :app:bundleRelease
    ```
    *Output location:* `/app/build/outputs/bundle/release/app-release.aab`

---

## 🛠️ 4. Advanced IPTV Production Maintenance

PAVEL IPTV incorporates a designated **System Settings Dashboard** directly inside the TV interface. To open it, click the **SETTINGS** button in the header bar with your remote or touch screen.

### 4.1 Production Configurations Breakdown & Latency Profiles:
*   🔑 **Custom Playlists Persistence (SQLite):** Users can paste an enterprise M3U format playlist link directly inside the settings input box. Saving the URL automatically updates the local SQLite transaction storage, recycles the player, and loads the active remote categories list asynchronously.
*   ⚡ **HLS Hardware Buffering latency profiles:**
    *   **Low Latency (5s buffer):** For high-speed connections requiring near-real-time channel switching and immediate stream updates.
    *   **Standard (10s segment buffer):** The default recommended balance ensuring zero micro-stuttering on standard setups.
    *   **Legacy/Safe Buffer (25s segment processing):** An ultra-resilient profile designed to recover and parse data from slow networks, bad satellite links, and low-end TV hardware.
*   🧹 **Cache Recycling & Storage Maintenance:** Under heavy use, video player rendering caches consume significant RAM/disk. The "CLEAR WEB VIEW CACHE & MEMORY" action programmatically purges temporary Chromium cache directories and fully recycles high-density media layout allocations.
