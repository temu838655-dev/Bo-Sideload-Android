# Bo Sideload (Android)

Bo Sideload is an Android app for inspecting IPA packages and re-signing them locally on supported Android ARM devices. The signing engine is the upstream open-source [zsign](https://github.com/zhlynn/zsign).

## Current features

- Open IPA files with Android's document picker and inspect ZIP paths, archive safety, app metadata, Bundle ID and common signing files.
- Keep a recent IPA list and export/share files.
- Select a P12 signing identity and provisioning profile; enter the P12 password for that signing operation only.
- Run zsign locally on Android and export the signed IPA.
- Preflight-check Bundle ID, profile prefix, platform, expiry, certificate fingerprint/private-key availability and (when a verified device UDID is available) device authorization.
- Detect nested `.appex` and Watch app bundles during signing preflight, read their Bundle IDs from each `Info.plist`, and **block signing** when they are present because the current UI accepts only one provisioning profile; per-extension profile selection, entitlement comparison and validation are not implemented yet.
- Inspect Apple provisioning-profile metadata and public X.509 certificates.
- Edit Swift, plist and entitlements text files in a lightweight source workspace. This is not a full Xcode replacement.
- Discover Apple USB devices, request Android USB Host permission, monitor usbmux attach/detach events, and perform the ordinary Lockdown Pair exchange.
- Encrypt accepted pairing records with an Android Keystore-backed AES-256-GCM key.
- Run the USB installation flow: authenticated Lockdown TLS, AFC IPA staging, Installation Proxy install, progress reporting and best-effort staged-file cleanup.

## Signing and installation requirements

- Supply a valid P12 certificate/private key and provisioning profile that authorize the app's Bundle ID, entitlements, signing identity and target device.
- A free Apple Account / Personal Team has Apple's own limits and expiry rules. Bo Sideload does not create Apple-issued certificates or profiles, authenticate to Apple, bypass signing rules, or remove Apple's device authorization requirements.
- A successful zsign operation means an output IPA was generated; it does not guarantee that iOS will accept or launch it.
- USB installation requires a compatible OTG/data cable, Android USB permission, an unlocked iPhone, an accepted Apple trust/pairing record, and a profile that authorizes the target device.
- The USB pairing and installation implementation is present in the app, but it has **not yet been validated end-to-end on a physical Android + iPhone combination**. GitHub Actions success proves compilation and automated tests only; it does not prove real-device USB compatibility or successful installation. Treat the feature as experimental until that test is completed.
- Discover Apple Wi-Fi Sync Bonjour/mDNS advertisements and compare the short `authTag` against locally stored Lockdown `HostID` values. A match identifies a candidate only; it is **not device authentication**.
- For a previously paired device advertising legacy Wi-Fi Sync, let the user initiate Lockdown `StartSession` over the resolved Bonjour endpoint and authenticate TLS against the stored, pinned `DeviceCertificate`. The session is closed with the activity lifecycle. This compatibility path is not RemotePairing.
- Wi-Fi now includes Bonjour discovery, authTag candidate matching, user-triggered Lockdown TLS authentication, a Wi-Fi IPA-install button, reusable TCP service connections, paired service-TLS handling, and an AFC → Installation Proxy coordinator. The button uses the already-selected signed IPA. This source path has passed Android CI but has **not** been validated end-to-end on physical iPhone hardware; treat Wi-Fi installation as experimental until that test succeeds.
- CoreDevice RemotePairing/RSD remains separate and is not implemented.
- Supported native signing-engine ABIs: `arm64-v8a` and `armeabi-v7a`.

## Important security boundaries

- The app does not bypass the iPhone's trust prompt or provisioning restrictions.
- Pairing private material is stored only in the encrypted local pairing record; P12 passwords are not stored in history or preferences.
- Provisioning-profile preflight is a compatibility check, not a complete Apple CMS trust-chain or revocation validation.
- Do not share diagnostic reports or pairing files publicly; diagnostic reports should not contain private keys, but can include device identifiers.
- The Swift workspace is a lightweight text editor. Android does not include Apple's proprietary Xcode, iOS SDK or macOS build environment, so this app cannot compile a full native iOS project into an IPA.

## Upstream engine and license

- Engine: [zhlynn/zsign](https://github.com/zhlynn/zsign), version `v1.1.3`.
- License: MIT; see [third_party/zsign/LICENSE](third_party/zsign/LICENSE).
- GitHub Actions downloads the Android zsign binaries and verifies their SHA-256 digests before packaging.
- Bo Sideload is an independent project and is not affiliated with Apple or the zsign maintainers.

## GitHub Actions APK

- Open [GitHub Actions](https://github.com/temu838655-dev/Bo-Sideload-Android/actions) and select the newest successful run on `main`.
- Download the `Bo-Sideload-debug` artifact, or use the [nightly GitHub Release](https://github.com/temu838655-dev/Bo-Sideload-Android/releases/tag/nightly) when it has been updated by the workflow.
- The workflow runs unit tests and builds the debug APK. Check the individual run and release timestamp before installing a build.

## Build locally

Run the workflow's zsign download/verification step first so that `app/src/main/assets/zsign/<abi>/zsign` exists, then run:

```bash
gradle testDebugUnitTest assembleDebug
```
