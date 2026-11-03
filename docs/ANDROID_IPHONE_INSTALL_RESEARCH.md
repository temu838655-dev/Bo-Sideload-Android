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

These are protocol building blocks, not proof of a trusted connection. The app now also has bounded plist handling, an Android USB bulk transport and Listen monitor, Lockdown request/response framing, certificate-generation and Pair-request builders, and encrypted pairing-record storage. The Connect-to-Lockdown socket path and an end-to-end physical-device Pair exchange are still not complete. No UI should describe USB detection or a Listen acknowledgement as a trusted iPhone connection.

The next implementation gate is a real transport adapter plus plist request/response handling, followed by a pairing flow tested with a physical iPhone and explicit user approval. Keep pairing records protected by Android Keystore-backed encryption and never log pairing secrets.

### XML Lockdown plist groundwork

A bounded XML plist codec has now been added for the Lockdown message layer. It supports dictionaries, arrays, strings, integers, real numbers, booleans, and base64 data. Parsing accepts Apple's standard plist DOCTYPE while disabling external entity and external DTD access; payload size, nesting depth, and parsed value count are limited. Unit tests cover round trips, standard Apple XML, XML escaping, malformed dictionaries, duplicate keys, unsupported values, size limits, and external-entity isolation.

This remains a serialization layer only. It does not authenticate the host or establish a trusted session. The decoder also accepts binary plist and date values as described in the later Binary plist and date support section.


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


### Lockdown request/response groundwork

`LockdownRequests` now builds XML-plist envelopes for the non-mutating `QueryType` and `GetValue` requests, plus a `StartSession` request when the caller supplies HostID and SystemBUID. `LockdownResponseParser` distinguishes successful replies from explicit Lockdown errors and preserves Value, SessionID, and EnableSessionSSL fields. Unit tests cover request fields, selector validation, session identifiers, and error replies.

This is protocol serialization only. The app now generates Root/Host/Device certificate material from the device's Lockdown DevicePublicKey, builds the public-certificate-only Pair request, and preserves an EscrowBag from a successful Pair response. It still does not open a Lockdown service socket, send the Pair request to a real device, receive a real PairRecord, perform TLS session negotiation, or establish a trusted connection. Do not send StartSession until a real user-approved Pair exchange has succeeded and the resulting record has been encrypted in Android Keystore-backed storage.


### Lockdown framed session channel

`LockdownSessionChannel` now provides a synchronous request/response exchange over an already-open byte stream. It validates that outgoing payloads are plist dictionaries containing a Request name, applies the bounded 4-byte big-endian framing, parses the reply, preserves explicit Lockdown failures, and rejects a reply whose Request name conflicts with the outstanding request. Unit tests cover successful exchange, explicit service errors, mismatched replies, malformed requests, and use after close.

This channel is not yet connected to the Android USBMux transport and does not create the Lockdown service socket. It must only be used after the service connection and normal Apple trust/pairing workflow are implemented. It does not enable StartSession, TLS, Installation Proxy, or IPA installation by itself.


### Binary plist and date support

The Lockdown plist decoder now detects Apple's `bplist00` signature and uses the already bundled dd-plist parser for binary input. Parsed Java values are normalized into dictionaries/lists and supported scalar types, including byte arrays and dates, with depth and node-count checks. XML date values are also decoded and encoded. A fixed binary fixture and date round-trip tests were added. This improves compatibility with Lockdown replies that use binary plist or date fields, but does not change the trust or installation status: no physical device exchange, Pair flow, TLS session, or IPA installation has been validated.

 
### Encrypted pairing-record storage groundwork

A versioned pairing-record model and bounded serializer have been added for the device/host identifiers, host and root certificates, corresponding host/root key material, device certificate, and optional escrow bag. The Android storage layer encrypts the serialized record with a per-device AES-256-GCM key held by Android Keystore; the key is non-exportable, the device identifier is authenticated as GCM additional data, and only the IV plus ciphertext is stored in private app preferences. Removing a record also removes its Keystore alias. If the Keystore key is missing or authentication fails, the store reports an error rather than silently accepting or replacing the record.

The certificate generator and Pair request builder are now present, and a helper refuses to assemble a persistable PairRecord unless the parsed response is a successful Pair reply. However, the application still does not send Pair over a live Lockdown socket or receive a real PairRecord, and the store is not yet wired into a completed pairing workflow. The record must be populated only from a successful, user-approved Apple pairing exchange. No pairing bypass is implemented.


The record serializer has round-trip and malformed-input unit tests. Android Keystore encryption itself still needs instrumentation testing on supported Android devices; JVM unit tests cannot prove hardware-backed key behavior or recovery after device backup/restore.


### Apple Lockdown pairing material and Pair request groundwork

`ApplePairingMaterialGenerator` now parses the device's PEM-encoded `DevicePublicKey` (including SubjectPublicKeyInfo and PKCS#1 RSA public-key encodings), generates separate 2048-bit RSA Root and Host key pairs, and creates a self-signed Root certificate plus Host and Device certificates signed by that Root. Certificate signatures and public/private key matching are covered by JVM tests. The generator does not contact a device and does not bypass the iOS trust prompt.

`LockdownRequests.pair` builds the Pair plist with DeviceCertificate, HostCertificate, RootCertificate, HostID, SystemBUID and protocol version 2; private keys are deliberately omitted from the request. `LockdownResponseParser` preserves an optional EscrowBag. The material-to-record helper rejects failure replies and only assembles a PairRecord after a successful parsed Pair response. The caller must then persist it with `EncryptedPairRecordStore`.

This remains unconnected protocol groundwork: there is no working usbmux Connect-to-port-62078 stream, no live GetValue(DevicePublicKey) exchange, no physical iPhone trust prompt test, and no verified TLS StartSession. These are explicit remaining gates before any trusted-connection or IPA-install feature can be enabled.
