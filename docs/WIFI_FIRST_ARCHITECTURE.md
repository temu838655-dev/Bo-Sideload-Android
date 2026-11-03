# Wi-Fi-first connection plan

Bo Sideload is intended to use Wi-Fi as its primary transport. USB remains a fallback and a possible one-time bootstrap path for devices and iOS versions that require an existing trusted pairing.

## Protocol paths

### 1. Device-initiated Remote Pairing (primary for supported iPhones)

- Target iOS/iPadOS 27 and later.
- Android advertises a pairable-host service using Bonjour/mDNS:
  `_remotepairing-pairable-host._tcp.local.`
- The iPhone discovers the Android host and initiates the pairing conversation.
- Bo Sideload displays the short setup PIN; the user enters it on the iPhone.
- Implement Apple's Remote Pairing protocol (RPPairing), persist only the required protected pairing material, and discover the paired device's remote-pairing service.
- Establish the authenticated TLS-PSK tunnel, then the software TCP tunnel and Remote Service Discovery (RSD) connection.
- Use RSD services for IPA transfer and installation only where the device advertises and authorizes the required services.

### 2. Existing Wi-Fi Sync pairing (compatibility path)

- For older supported iOS versions, first establish the trusted Lockdown pairing through Apple's supported USB/Finder/iTunes workflow.
- Enable Wi-Fi sync / wireless debugging where supported by the device.
- Discover the device through Bonjour/mDNS and validate the advertised authentication tag against the existing pairing record before connecting.
- Do not treat a guessed IP address or an open TCP port as proof of device identity.

## Security requirements

- Never accept an unverified device based only on its IP address.
- Verify mDNS authentication tags and pairing identities before opening services.
- Keep pairing secrets encrypted at rest using Android Keystore-backed storage.
- Use authenticated encrypted tunnels; do not downgrade to plaintext when TLS or RPPairing validation fails.
- Do not bypass Apple signing, provisioning, Developer Mode, or user-consent requirements.
- Keep USB as a clearly labelled fallback.

## Implementation order

1. Replace the experimental direct-IP Lockdown pairing attempt with protocol-correct Wi-Fi-first guidance. (Done)
2. Add Android NSD/mDNS discovery for the legacy `_apple-mobdev2._tcp.` Wi-Fi Sync service. (Implemented)
3. Match discovered services to encrypted pairing records, then let the user initiate a bounded TCP connection and authenticate the Lockdown TLS session against the pinned DeviceCertificate. (Implemented; requires physical-device validation)
4. Add iOS 27+ device-initiated RPPairing host advertisement, PIN handoff, and a reviewed, pinned protocol backend. (Not implemented)
5. Build the authenticated RPPairing TLS-PSK/software TCP tunnel and RSD provider.
6. Implement IPA upload/install over the services actually exposed by the connected iPhone.
7. Add integration tests, then validate on physical iPhones across supported iOS versions and Wi-Fi routers.
8. Promote Wi-Fi to the default screen and keep USB under “Other connection methods”.

## Current status

The existing Kotlin USB pairing and USB installation path is retained. Legacy Wi-Fi Sync now supports Bonjour discovery, local authTag candidate matching, and a user-triggered Lockdown StartSession/TLS handshake that pins the server certificate to the stored DeviceCertificate. A successful TLS session is held until the screen is destroyed. This is not RemotePairing and does not yet expose AFC or Installation Proxy over Wi-Fi, so Wi-Fi IPA transfer/installation is not implemented or claimed as working. The direct-IP first-pairing flow remains removed. CI success proves compilation and automated checks only; physical iPhone interoperability still needs validation.
