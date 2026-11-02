# Android implementation assessment

## Product decision

Bo Sideload is an **Android-only APK**. The app processes existing IPA packages on the Android device. It does not attempt to embed Apple's proprietary Xcode or iOS SDK.

## Implemented foundation

- Native Android file picker and recent IPA list.
- Lightweight Swift/plist/entitlements source text editor with bounded file reads and export (not a compiler or full IDE).
- ZIP safety validation and Info.plist metadata extraction.
- Detection of common signing-related files.
- Upstream zsign v1.1.2 Android CLI packaged for arm64-v8a and armeabi-v7a.
- GitHub Actions downloads the two upstream release archives and checks their SHA-256 digests before APK packaging.
- Kotlin adapter copies the selected IPA, P12 and mobileprovision into app-private temporary storage, invokes zsign without a shell, enforces a ten-minute process timeout, and exports the signed IPA through Android's document picker.
- P12 password is requested for each signing run and is not written to app preferences or history.

## Signing rules and security

A P12 identity and provisioning profile must be valid and mutually compatible with the app's Bundle ID, entitlements, device authorization and certificate. The app does not generate Apple credentials, bypass Apple's validation, or guarantee that the signed IPA will launch.

Temporary copies of the P12 and profile are deleted after the signing attempt. The signing password is held only for the current operation. Do not log it or persist it.

## Remaining work

1. Add a real-device smoke test for the native zsign executable on arm64 and armv7 Android hardware.
2. Add certificate/profile inspection UI and preflight checks before signing.
3. Add optional Bundle ID/name editing with a warning that profile authorization must match.
4. Add a project tree, tabbed source editing and syntax highlighting; keep clear that compilation needs Apple's iOS SDK.
5. Add signing progress/cancellation and stronger temporary-file cleanup.
6. Investigate an Android-compatible iPhone pairing/USB-mux/Installation Proxy stack. Direct iPhone installation is not implemented.
7. A native Swift source editor/project manager can be added, but compiling a full iOS app requires Apple's iOS SDK and signing environment; this Android APK cannot provide Xcode's complete build pipeline.

## Acceptance criteria

Do not call this a full Xcode replacement or complete iPhone sideloader until an Android device test proves the signing engine runs, a valid P12/profile pair produces an output accepted by iOS, and a separately implemented device transport successfully installs and launches it on a real iPhone.
