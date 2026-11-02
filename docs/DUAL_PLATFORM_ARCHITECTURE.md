# Bo Sideload — Android-only architecture

## Product scope

Bo Sideload is an Android APK that imports, inspects, modifies within supported signing-tool capabilities, and re-signs existing IPA packages on Android ARM devices. It is not a port of Apple's proprietary Xcode and does not include the iOS SDK.

## Core modules

1. **IPA library:** document picker, recent files, metadata and export.
2. **IPA inspector:** ZIP path validation, size/entry limits, Info.plist parsing and signing-file presence checks.
3. **Signing engine:** upstream zsign v1.1.2 CLI for Android arm64-v8a and armeabi-v7a.
4. **Credential input:** user-selected P12 and provisioning profile; P12 password entered per operation and not persisted.
5. **Output manager:** run zsign in app-private storage and export the resulting IPA with Android's document picker.
6. **Device install (future):** USB/Wi-Fi pairing, lockdown/usbmux transport, AFC and Installation Proxy require a separate Android-compatible implementation and real-device tests.

## Upstream and license

- zsign: https://github.com/zhlynn/zsign
- Version: v1.1.2
- License: MIT; included at third_party/zsign/LICENSE.
- Android binaries are fetched during GitHub Actions and SHA-256 verified before APK packaging.
- The project is independent and is not affiliated with Apple or the zsign maintainers.

## Platform limits

- Android can run the cross-compiled zsign binary and re-sign an existing IPA with compatible credentials.
- Android cannot run Xcode or ship Apple's proprietary iOS SDK.
- Successful signing does not guarantee installation; iOS enforces Apple-issued code-signing and provisioning requirements.
- Direct installation to iPhone from Android is not yet implemented.
- No exploit, TrollStore integration, or universal/permanent install claim is part of this Android-only project.

## Roadmap

- [x] Android APK base and CI.
- [x] IPA structure and metadata inspection.
- [x] Add Android zsign signing engine for ARM ABIs.
- [x] Add P12/profile selection and signed IPA export UI.
- [ ] Validate the engine on physical Android devices.
- [ ] Add certificate and provisioning profile preflight inspection.
- [ ] Add optional bundle metadata editor with profile compatibility checks.
- [ ] Research and implement iPhone pairing/installation transport if technically viable.
- [ ] Add end-to-end tests on physical iPhones with properly provisioned apps.
