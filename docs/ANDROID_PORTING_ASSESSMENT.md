# Android porting assessment

## Current app status

The Android app currently provides a native file picker and displays selected-file metadata. It does not yet sign an IPA, authenticate with Apple, pair with an iPhone, or install an app.

## Upstream library review

The upstream project is [nab138/isideload](https://github.com/nab138/isideload), an MIT-licensed Rust library. Its README requires calling `isideload::init()`. The crate exposes authentication, developer-account, sideload, and device communication modules.

The upstream install implementation explicitly installs an **already signed app bundle directory** through AFC and Installation Proxy. It is not a simple API that accepts an arbitrary IPA byte stream and installs it unchanged.

## Android integration blockers

1. **Rust-to-Kotlin bridge:** build a small JNI layer (or another maintained Rust/Kotlin bridge) with a stable, narrow API. Do not expose Rust internal types directly to the UI.
2. **Android targets:** cross-compile and test the Rust dependency graph for supported Android ABIs using the Android NDK. The upstream crate has native dependencies and platform-specific storage features; a successful desktop build does not prove Android compatibility.
3. **Persistent secrets:** do not use the upstream desktop keyring backend on Android without validating its support. Implement an Android Keystore-backed storage adapter for refresh/session material, and never log passwords or tokens.
4. **Device pairing and transport:** implement and test the supported Apple device pairing path on Android. USB host support and Wi-Fi pairing have different permission and transport requirements.
5. **Background work and cancellation:** expose progress, cancellation, and useful error messages to Kotlin without blocking the main thread.
6. **IPA processing:** inspect the archive safely, extract the app bundle into private app storage, preserve required metadata, and validate the resulting bundle before signing.

## Safe implementation sequence

- [x] Establish Android project and CI APK build.
- [x] Select a file and display its name and size.
- [ ] Validate IPA ZIP structure and report clear errors.
- [ ] Add private-storage extraction with size and path-traversal limits.
- [ ] Add Rust Android build matrix and a minimal JNI smoke test.
- [ ] Add secure Android storage and Apple authentication UI.
- [ ] Add pairing/device discovery and test on real iPhones.
- [ ] Add signing, provisioning, installation, and end-to-end tests.

## Acceptance criteria

Do not label the app as a working sideloader until a real-device test demonstrates: Apple authentication, valid signing/provisioning, device pairing, successful installation, and launching the installed app. Test failures must be shown clearly and must not silently claim success.
