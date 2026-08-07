# Third-Party Notices

SELENE 0.5.2 contains the following third-party components.

## Syncthing-Fork / Syncthing native core

- Wrapper source: <https://github.com/researchxxl/syncthing-android>
- Wrapper release: `v2.1.3.0`
- Wrapper commit: `af88060b7303b98b80c088cd1bcb27253860c951`
- Syncthing source commit: `946e2b83a1f6c6ae119427c09e0a5802940b82ff`
- Release APK SHA-256: `739f09a7904db4552155a0760894f73b65a975b662f1a8d93a628fada8a46963`
- License: Mozilla Public License 2.0

SELENE redistributes the unmodified ARM native executables named
`libsyncthingnative.so` from that verified release. The corresponding source
is available at the repositories and exact revisions above. A copy of the
MPL-2.0 is packaged in the Android application at
`assets/licenses/MPL-2.0.txt`.

## QRCoder

- Source: <https://github.com/codebude/QRCoder>
- Package version: `1.6.0`
- License: MIT

QRCoder is used by SELENE Windows to render the one-time enrollment code.

## ZXing Android Embedded

- Source: <https://github.com/journeyapps/zxing-android-embedded>
- Package version: `4.3.0`
- License: Apache License 2.0

ZXing Android Embedded is used only for local QR scanning. Pairing payloads
are processed on-device and are not sent to a barcode service.
