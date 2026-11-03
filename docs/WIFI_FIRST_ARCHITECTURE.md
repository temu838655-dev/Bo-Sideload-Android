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
2. Add a Rust JNI backend pinned to a reviewed `idevice` crate version with only required features: `remote_pairing`, `mdns`, `tcp`, `rsd`, `core_device_proxy`, `tunnel_tcp_stack`, `afc`, and `installation_proxy`.
3. Implement Android mDNS advertisement, PIN handoff, and device-initiated RPPairing for iOS 27+.
4. Implement paired-device discovery and auth-tag validation for Wi-Fi Sync devices.
5. Build the RPPairing TLS-PSK/software TCP tunnel and RSD provider.
6. Implement IPA upload/install over the services actually exposed by the connected iPhone.
7. Add integration tests, then validate on physical iPhones across supported iOS versions and Wi-Fi routers.
8. Promote Wi-Fi to the default screen and keep USB under “Other connection methods”.

## Current status

The existing Kotlin USB pairing and USB installation path is retained. The direct TCP connection to port 62078 is not a valid general-purpose first-pairing method and has been removed from the UI. Wi-Fi IPA installation is not yet implemented or claimed as working. CI success only proves Android compilation and unit checks; it does not prove real iPhone interoperability.
