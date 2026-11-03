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

## Implemented protocol groundwork (2026-11-03)

The Android app now contains small, bounded framing helpers with unit tests:

- `UsbMuxPacketHeader`: validates the 16-byte little-endian usbmux packet header.
- `UsbMuxPacketCodec`: reads/writes complete usbmux packets and rejects oversized or truncated packets.
- `LockdownPacketCodec`: reads/writes the 4-byte big-endian length-prefixed Lockdown payload framing and rejects negative, oversized, or truncated payloads.

These are protocol building blocks only. They do not yet serialize/parse Apple plist messages, open an Android USB bulk transport, complete the Lockdown StartSession/Pair flow, create or store a pairing record, start the Installation Proxy service, or install an application. No UI should describe USB detection as a trusted iPhone connection.

The next implementation gate is a real transport adapter plus plist request/response handling, followed by a pairing flow tested with a physical iPhone and explicit user approval. Keep pairing records protected by Android Keystore-backed encryption and never log pairing secrets.

### XML Lockdown plist groundwork

A bounded XML plist codec has now been added for the Lockdown message layer. It supports dictionaries, arrays, strings, integers, real numbers, booleans, and base64 data. Parsing accepts Apple's standard plist DOCTYPE while disabling external entity and external DTD access; payload size, nesting depth, and parsed value count are limited. Unit tests cover round trips, standard Apple XML, XML escaping, malformed dictionaries, duplicate keys, unsupported values, size limits, and external-entity isolation.

This remains a serialization layer only. It does not send a Lockdown request, authenticate the host, or establish a trusted session. Binary plist and date values are not supported by this codec yet; responses using those values must be handled before claiming general Lockdown compatibility.


### usbmux plist request groundwork

`UsbMuxPlistRequests` now builds protocol-v1 plist packets for `Listen` and `Connect`, including client metadata fields used by libusbmuxd. Connect validates the device ID and TCP port and applies the required `htons`-style byte swap to `PortNumber`. Unit tests decode the generated plist payloads and verify the packet message type and tag.

At the time this section was first written, the request builders were not integrated with USB. The subsequent transport and monitor groundwork below now sends Listen and parses replies, but no physical-device validation has been completed.


### usbmux response parsing groundwork

`UsbMuxResponseParser` now validates plist packet message type and parses `Result`, `Attached`, and `Detached` messages. Attached-device metadata is read from the Properties dictionary with optional serial number, connection type, and product ID. Unknown message types are preserved rather than treated as successful connections. Unit tests cover success, attach/detach, unknown messages, optional metadata, and malformed responses.

The parser is now used by the USB Listen monitor described below. A parsed Attached message is not proof of Lockdown pairing or installation capability.


### Android USB bulk transport groundwork

`AndroidUsbMuxTransport` now checks Android's USB permission, locates Apple's USBMux vendor-specific interface (class FF/subclass FE/protocol 02), claims it, and reads/writes complete usbmux packets over bulk IN/OUT endpoints with bounded packet lengths and a finite I/O timeout. `UsbMuxListenSession` sends the Listen plist request and exposes parsed responses/events from the transport. The device diagnostics screen now offers an explicit “Thử kết nối usbmux” action after Android USB permission is granted. The listener runs off the UI thread, handles Listen Result and attach/detach events, and closes the USB connection when stopped. Bulk I/O is chunked to 16 KiB for compatibility with older Android USB Host implementations; a 30-second read timeout ends the monitor rather than retrying a partially consumed packet.

The code has only been compile-checked by GitHub Actions; no physical iPhone/Android OTG test has been performed. USB interface descriptors can vary by device/OS and the transport may need device-specific handling. A successful USB bulk exchange or Listen acknowledgement is not equivalent to a trusted Lockdown pairing.


Descriptor matching was checked against the upstream usbmuxd interface definitions: class 255 (0xFF), subclass 254 (0xFE), protocol 2 (0x02). See [upstream usb.h](https://github.com/libimobiledevice/usbmuxd/blob/master/src/usb.h) and [USB discovery implementation](https://github.com/libimobiledevice/usbmuxd/blob/master/src/usb.c). The Android adapter uses exact matching rather than the permissive OR condition found in an older upstream discovery path.
