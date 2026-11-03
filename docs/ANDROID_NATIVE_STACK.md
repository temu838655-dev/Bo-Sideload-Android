# Android native Apple-device transport implementation plan

This document is the engineering gate for adding real iPhone/iPad communication. It is intentionally separate from the existing zsign IPA re-signing flow.

## Architecture

Keep the Android UI in Kotlin. Put the device protocol behind a small JNI boundary so that USB permission, Android lifecycle, cancellation, and progress remain controlled by the app.

```text
Kotlin DeviceConnectionActivity
  -> Android USB Host transport (UsbManager / UsbDeviceConnection)
  -> native transport adapter (JNI; no root; no shelling out)
  -> usbmux protocol
  -> Lockdown pairing and session
  -> service connection (InstallationProxy)
  -> install / query / verify app
```

The Android USB Host layer must own the already-authorized `UsbDeviceConnection`. Do not assume a desktop `usbmuxd` daemon can open Android USB devices itself: adapt its transport so it reads/writes through the Android-owned connection, or use a clearly isolated native bridge with the same ownership model.

## Dependency order

1. Pin source revisions and record license + NOTICE files for every dependency.
2. Cross-compile required libraries for each shipped ABI with a pinned Android NDK and CMake toolchain.
3. Build and test plist parsing and usbmux framing independently of physical devices.
4. Implement USB bulk/control transfers using Android USB Host endpoints and explicit timeouts/cancellation.
5. Implement usbmux connection negotiation and validate framing against captured, non-secret test fixtures.
6. Implement Lockdown pairing using Apple's normal user trust flow. Store pairing records only in app-private storage; never log pairing secrets or private keys.
7. Start InstallationProxy only after a trusted session exists.
8. Install a valid, already-signed test IPA; query the installed bundle identifier and version to verify success.

## ABI and build requirements

- Initial target ABIs: `arm64-v8a` and `armeabi-v7a`, matching the current zsign packaging.
- Use Android NDK + CMake; do not package host Linux/macOS binaries.
- Keep native libraries under `app/src/main/jniLibs/<abi>/`.
- Add a reproducible CI job that builds from pinned source revisions, checks ELF machine/ABI, runs native unit tests, and packages the APK.
- Fail CI if a required ABI is missing. Keep debug symbols as CI artifacts for diagnosing native crashes.

## Security requirements

- Never bypass the iPhone/iPad trust prompt or reuse pairing records from another device.
- Never print pairing records, escrow keys, P12 passwords, private keys, or full provisioning profile contents in logs.
- Validate all device-provided plist/XML/binary-plist data and impose size/time limits.
- Treat USB vendor/product IDs and interface descriptors as hints, not proof of an Apple device.
- Do not enable an install button until pairing, install, and post-install verification pass on a physical device.

## Licensing gate

Before adding a dependency to the APK, pin its exact revision and review the license of the dependency and every transitive component. The current research notes that the candidate installer stack includes GPL components; distribution obligations must be resolved before shipping. Preserve required license texts and source-offer/notice materials.

## Physical-device acceptance matrix

| Test | Required result |
|---|---|
| No device attached | Clear empty state; no crash |
| Non-Apple USB device | Diagnostics only; no Apple protocol claim |
| Apple device, permission denied | Clear denial; no protocol traffic |
| Apple device, permission granted but locked | Explain unlock/trust requirement |
| User rejects Trust prompt | Pairing fails safely; retry is possible |
| User accepts Trust prompt | Lockdown session established |
| Signed test IPA with matching profile | InstallationProxy reports success |
| Post-install query | Expected bundle ID/version is present |
| Cable removed during transfer | Cancel/cleanup; actionable error; no false success |
| Unsupported ABI / native library missing | Feature remains unavailable; app does not crash |

## Current status

The APK currently implements USB discovery, Android USB permission prompts, endpoint diagnostics, and shareable reports. It does **not** yet implement the native transport adapter, usbmux, Lockdown pairing, InstallationProxy, or direct IPA installation. A successful Android APK build is not evidence that iPhone installation works.
