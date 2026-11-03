# Bo Sideload (Android)

Bo Sideload is an Android app for inspecting IPA packages and **re-signing them locally on supported Android ARM devices**. The signing engine is the upstream open-source [zsign](https://github.com/zhlynn/zsign), not Xcode or the old Cydia Impactor binary.

## Current Android features

- Pick an IPA using Android's document picker; display its name and size.
- Inspect ZIP structure, path safety, `Payload/<App>.app/Info.plist`, and common app metadata.
- Detect `CodeResources` and `embedded.mobileprovision` entries.
- Keep a recent IPA list and share source files.
- Select a `.p12` signing identity and `.mobileprovision` profile.
- Enter the P12 password for one signing operation; the app does not save it in history or preferences.
- Open and edit Swift / plist / entitlements text files in the lightweight source workspace (up to 2 MB per source file); export edited source files.
- Run zsign locally on Android and export the resulting signed IPA with Android's document picker.
- Package verified zsign binaries for `arm64-v8a` and `armeabi-v7a` in the APK.

## Signing requirements and limitations

- Supply a valid P12 certificate/private key and a provisioning profile that authorizes the target Bundle ID, device(s), entitlements, and signing identity.
- The app currently signs with the supplied P12 and profile. It does **not** obtain Apple certificates, create profiles, authenticate to an Apple Developer account, or bypass Apple's signing rules.
- A successful zsign process means an output IPA was generated; it does not guarantee iOS will accept or launch it. The iPhone validates Apple's signature chain and provisioning authorization.
- **Direct installation to an iPhone from Android is not implemented yet.** A native device-transport research plan is in [docs/ANDROID_IPHONE_INSTALL_RESEARCH.md](docs/ANDROID_IPHONE_INSTALL_RESEARCH.md). The bundled zsign CLI's `-i` option relies on `ideviceinstaller`; this APK does not bundle a tested Android USB-mux / pairing / Installation Proxy stack. Do not interpret "signed" as "installed".
- This is not Xcode: the source workspace is a lightweight text editor, not a full IDE. Android cannot bundle Apple's proprietary Xcode, iOS SDK, or Apple build environment. Bo Sideload can edit source text and process/re-sign an existing IPA, but cannot compile a full native iOS project into an IPA on Android.
- Supported native engine ABIs: `arm64-v8a` and `armeabi-v7a`. Other CPU architectures are not packaged.

## Upstream engine and license

- Engine: [zhlynn/zsign](https://github.com/zhlynn/zsign)
- Version: `v1.1.2`
- License: MIT; see [third_party/zsign/LICENSE](third_party/zsign/LICENSE).
- Android release binaries are downloaded during GitHub Actions and checked against the published SHA-256 digests before being included in the APK. The binaries are not hand-modified.
- zsign supports IPA re-signing, P12 keys, provisioning profiles, metadata changes, and certificate inspection. See its upstream README for the full command reference.
- Bo Sideload is an independent project and is not affiliated with Apple or the zsign maintainers.

## GitHub Actions APK

1. Open [GitHub Actions](https://github.com/temu838655-dev/Bo-Sideload-Android/actions).
2. Open the newest successful run on `main`.
3. Download the `Bo-Sideload-debug` artifact and extract the APK.

The workflow downloads both Android zsign binaries, verifies their SHA-256 hashes, runs unit tests, and builds the debug APK. A GitHub Release is published only for tags matching `v*`.

## Build locally

Run the workflow's zsign download/verification step first so that `app/src/main/assets/zsign/<abi>/zsign` exists, then run:

```bash
gradle testDebugUnitTest assembleDebug
```


## USB device diagnostics

The Android app includes a USB Host diagnostics screen. It lists attached USB devices, vendor/product IDs, interface counts, and whether Android USB permission has been granted. After permission, it also displays interface class/subclass/protocol and endpoint address, direction, type, and max packet size, plus manufacturer/product names when supplied by Android. Apple vendor ID is only a preliminary hardware hint; it does not prove Apple Lockdown pairing.

**Not yet implemented:** usbmuxd transport, Lockdown pairing/trust-record management, Wi-Fi pairing, device-information queries, and IPA installation. Upstream [ideviceinstaller](https://github.com/libimobiledevice/ideviceinstaller) and [libimobiledevice](https://github.com/libimobiledevice/libimobiledevice) report Android support, but bundling requires building and testing their complete native dependency chain for Android ABIs. The installer is GPL-2.0, so its licensing and source obligations must be handled before bundling.


## Apple platform signing guidance (free Apple ID)

The app now includes an Apple Signing Center explaining the Personal Team workflow and platform boundaries for iPhone/iPad, macOS, and tvOS. It can parse the CMS-wrapped plist payload of a provisioning profile and display its name, UUID, Team ID, application identifier, platform, registered-device count, creation/expiry dates, and embedded developer-certificate count. It accepts `.mobileprovision`, `.provisionprofile`, and raw plist files up to 10 MB.

With a free Apple Account used as an Xcode Personal Team, Apple's current account guidance lists limits of 10 App IDs and 3 registered devices, both expiring after 7 days, and up to 3 installed apps per device. Re-provisioning is required after expiry. This is a personal development/testing workflow, not App Store or enterprise distribution. See [Apple's developer account overview](https://developer.apple.com/help/account/basics/about-your-developer-account/).

- iOS/iPadOS: personal-device development/testing through Xcode Personal Team; re-sign/reinstall when the profile expires.
- macOS: the app does not provide Developer ID signing or notarization; these require the appropriate Apple program capabilities and macOS tooling.
- tvOS: requires a tvOS target, compatible provisioning profile, and Xcode signing. A generic iOS profile is not interchangeable.
- Bo Sideload Android does not authenticate to Apple, create Apple-issued credentials, compile Xcode projects, or install to Apple devices directly.


### Provisioning profile inspection scope

Profile inspection is metadata extraction, **not cryptographic validation**. The app does not currently verify the CMS signature against Apple's trust chain, check certificate revocation, inspect the private key in a P12, prove that a target device is eligible, or guarantee that the profile can install an app. Platform and bundle identifiers are read from the supplied profile content and must be treated as untrusted until independently validated.


### Public Apple certificate inspection

The Apple Signing Center can also read public X.509 .cer and PEM certificates and show Subject, Issuer, serial number, validity dates, and SHA-256 fingerprint. It does not import or inspect .p12 private-key containers in this screen, request passwords, verify Apple's trust chain/revocation status, or prove that a certificate matches a profile's embedded private key.
