# Android-to-iPhone installation research

## Candidate upstream stack

- [libimobiledevice](https://github.com/libimobiledevice/libimobiledevice): protocol library with app install/manage services; upstream states it is tested on Android and supports Wi-Fi sync when enabled.
- [usbmuxd](https://github.com/libimobiledevice/usbmuxd): USB multiplexing daemon; upstream states Android is a tested platform.
- [libusbmuxd](https://github.com/libimobiledevice/libusbmuxd): client library for usbmux connections; upstream states Android is supported/tested.
- [ideviceinstaller](https://github.com/libimobiledevice/ideviceinstaller): installation utility built on libimobiledevice; its dependency chain includes libimobiledevice, libplist and libzip.

## Why this is not enabled yet

Android support in an upstream C project does not automatically provide an Android-ready APK module. Bo Sideload still needs NDK builds for supported ABIs, an Android USB Host transport, device pairing/trust-record storage, protocol integration, and tests against physical iPhones. Wi-Fi installation also requires a previously trusted/pairable device and a supported network service.

## Planned implementation

1. Build the dependency stack for arm64-v8a and armeabi-v7a using a pinned NDK/toolchain.
2. Implement Android USB Host permission handling and safe device discovery.
3. Pair through the normal iPhone trust prompt; never bypass pairing or device authorization.
4. Start Lockdown/AFC/Installation Proxy services and install only an IPA that has already been signed with credentials authorized for that device.
5. Report install progress and device errors; verify installation by querying the device and app bundle identifier.
6. Add Wi-Fi pairing/installation only after USB pairing is proven on real devices.

## Acceptance gate

Do not expose an enabled "Install to iPhone" action until the Android native stack builds, device discovery works without root on supported Android versions, the user can accept the trust prompt, and a valid signed test IPA installs and launches on a real iPhone. A successful APK build alone is not sufficient.

## Licensing

Review each upstream component's license before bundling. libimobiledevice/libusbmuxd use LGPL-family licensing; usbmuxd and ideviceinstaller have separate GPL licensing. Keep each license and notice with the corresponding bundled component and review distribution obligations before release.