# Android Apple-device transport: current implementation and remaining work

## Current Kotlin implementation

The app keeps its Android UI and USB ownership in Kotlin. The following pieces are present in the repository:

- Android USB Host discovery, permission requests and endpoint diagnostics.
- USB usbmux Listen/Connect framing and device attach/detach handling.
- Ordinary Apple Lockdown QueryType/GetValue/Pair exchange.
- Encrypted pairing-record storage backed by Android Keystore AES-256-GCM.
- Lockdown TLS service sessions with paired-host identity validation.
- AFC staging and Installation Proxy install orchestration for an already-signed IPA.
- Bonjour/mDNS discovery of the legacy Apple Wi-Fi Sync service `_apple-mobdev2._tcp.`.

The Wi-Fi discovery component treats Bonjour records as untrusted hints. It compares the advertised authentication tag with locally stored Lockdown HostID values, then requires a user-initiated Lockdown StartSession and TLS peer-certificate pinning before enabling the Wi-Fi install path. This legacy Wi-Fi Sync path is experimental and has not been validated on physical hardware.

## Current transport boundary

```text
Kotlin DeviceConnectionActivity
  |-- USB Host -> usbmux -> Lockdown -> TLS -> AFC -> Installation Proxy
  |
  |-- Wi-Fi NSD/mDNS -> authTag candidate match -> Lockdown TLS pinning
                         -> AFC -> Installation Proxy (experimental)
```

The current USB path is implemented in Kotlin rather than delegated to a desktop `usbmuxd` process. Android's `UsbDeviceConnection` remains owned by the app. Do not introduce a desktop daemon assumption that cannot access Android USB Host handles.

## Wi-Fi implementation sequence

1. (Done) Discover `_apple-mobdev2._tcp.` services using Android NSD and hold a Wi-Fi multicast lock only while scanning.
2. (Implemented; physical test pending) Match a discovered service to a locally stored pairing record, validate its advertised authentication tag, and require user-initiated Lockdown TLS authentication with the saved DeviceCertificate pinned.
3. (Implemented; physical test pending) Reuse authenticated Wi-Fi TCP service connections for AFC staging and Installation Proxy installation.
4. Add iOS 27+ device-initiated Remote Pairing host advertisement and PIN handoff using a reviewed, pinned protocol implementation.
5. Establish the authenticated RemotePairing TLS-PSK tunnel, software TCP tunnel and Remote Service Discovery (RSD) session.
6. Add cancellation, bounded parsing, timeout handling, integration tests and physical-device validation.

Do not connect to a discovered IP address merely because its TCP port is open. Do not treat Lockdown TCP port 62078 as a general-purpose first-pairing interface. Remote Pairing and ordinary Lockdown pairing are distinct protocols and must not be conflated.

## Build and release requirements

- Keep native libraries, if introduced, pinned by exact version/revision and reviewed for license compatibility.
- Build every shipped Android ABI with a pinned NDK toolchain; verify ELF machine types before packaging.
- Keep pairing secrets, private keys, escrow material and P12 passwords out of logs.
- Keep all pairing records encrypted at rest with Android Keystore-backed keys.
- CI compilation and unit tests do not prove interoperability with a physical iPhone.

## Physical-device acceptance matrix

| Test | Required result |
|---|---|
| No USB device | Clear empty state; no crash |
| USB permission denied | No protocol traffic; clear denial |
| USB pairing rejected | Safe failure and retry |
| USB pairing accepted | Pair record encrypted and Lockdown TLS identity verified |
| Signed test IPA | Installation Proxy success and post-install bundle/version verification |
| Wi-Fi Sync service found | Candidate displayed as untrusted; no automatic socket connection |
| Spoofed mDNS service | Rejected by pairing-record authentication before connecting |
| iOS Remote Pairing | PIN exchange and authenticated tunnel verified on supported real devices |
| Wi-Fi install | IPA transfer, installation and post-install verification succeed on a real device |
| Network loss | Cancel and clean up without false success |

## Current status

USB pairing and the USB IPA installation pipeline are wired in the app but still require end-to-end validation on physical Android/iPhone hardware. Wi-Fi Bonjour discovery is now implemented as a preliminary discovery feature. Wi-Fi pairing, authenticated network transport, IPA transfer and installation are still pending.
