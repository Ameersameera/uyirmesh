# UyirMesh

**AI-Based Offline Smartphone Mesh Network for Disaster SOS**

Mini project · Department of ECE · Sona College of Technology, Salem
AMEER IBRAHIM S (61782323103701) · BAGAVATHI DAS T (61782323103703)
Mentor: Ms. M. AMUTHA, M.E., Ph.D., Assistant Professor / ECE

When mobile towers and internet are down, phones running UyirMesh form a mesh network over
Bluetooth, BLE and Wi-Fi (Google Nearby Connections). An SOS hops from phone to phone until it reaches
the rescue base. The base ranks every SOS with an on-phone Random Forest model, assigns rescue teams
and sends signed replies and alerts back through the same mesh.

Download the latest APK: **Releases → v1.0 → UyirMesh.apk** (Android 8.0 or newer).

## Modes

| Mode | Who | What it does |
|---|---|---|
| Public | everyone | SOS (form, voice "help help", shake, widget, quick tile, shortcut, lock-screen notification button), status timeline, voice note and photo, find-me tools (siren, flashlight SOS, screen strobe, vibration), I'm safe, group chat, alerts, missing persons, shelters and danger zones, citizen reports, volunteers, medical card QR, first aid (Tamil/English), relaying for others |
| Rescuer | teams with a base-signed ID QR + PIN | tasks from the base, SOS in radio range opened directly, accept / on the way / rescued, "Navigate in Google Maps" to the victim, best visit order, breadcrumb trail, position sent to the base about once a minute (signed with the team key), encrypted team chat, scan victim QR, auto-lock |
| Rescue base | holder of the base key file | AI priority with reasons, duplicate merge, DBSCAN hotspots, heat map, auto-assign with ETA, **live team tracking** (distance to the victim, getting closer / not moving / moving away, measured ETA, team trails on the map), signed alerts, shelters and supplies, rescuer IDs, topology and packet path trace, CSV/PDF reports, laptop web dashboard over the phone's hotspot (with Google Maps links), offline APK download page |

## How the mesh works (`core/MeshEngine.kt`)

* Controlled flooding with a **TTL** (hop limit, default 5) and a **duplicate filter** (random 16-byte packet IDs).
* **Store-and-forward**: packets wait in a buffer and go to every new neighbour until they expire.
* **QoS**: SOS > replies/alerts/tasks > chat/reports > voice/photo.
* **CRC-32** on every hop, **replay window**, per-source **rate limit**, relays drop **forged** base messages.
* Path trace: each relay appends its short ID, so the base sees the route.

## Security (`core/Crypto.kt`, `core/Keys.kt`, `core/Secure.kt`)

* SOS data: ECDH P-256 + HKDF-SHA256 + AES-256-GCM, sealed for the base and for verified rescuers only.
  Relays cannot read it. The payload is bound to the packet (AAD), so it cannot be moved into another packet.
* Replies, alerts, tasks: ECDSA P-256 signatures; the base public keys are pinned inside the APK.
* Rescuer IDs: certificates signed by the base (base → rescuer → message chain); the QR is encrypted with
  the PIN (PBKDF2-HMAC-SHA256 + AES-256-GCM).
* Team traffic: AES-256-GCM with a per-team key.
* Rescuer positions in heartbeats: HMAC-SHA256 with a key derived from the team key (`PositionAuth`), so only
  a real team member can move a rescuer on the base map; unsigned or wrongly signed positions are ignored.
* Base key file: private keys protected with a password (PBKDF2 200k + AES-256-GCM). **Never committed.**
* Phone storage: encrypted with an Android Keystore key. Device IDs are random (no IMEI, no SIM data).

## AI priority (`ml/train_priority.py`, `core/Priority.kt`)

Random Forest (30 trees, depth 8, 17 features) trained with scikit-learn on 20,000 synthetic SOS messages
labelled by a weighted triage policy, exported to JSON and evaluated on the phone. Each prediction is
explained with tree-path contributions ("Injured +22", "Flood water rising +14"). Waiting time and repeated
messages raise the score. Test accuracy 80.1 %, 99.8 % within one class — see `ml/model_report.md`.

## Live tracking and maps (`core/Tracking.kt`)

* Victim: position with the SOS, then every 2 min (5 min in battery saver) and a last one at 5 % battery.
* Rescuer: position about once a minute (3 min in battery saver) and with every status button.
* The base compares each rescuer's distance to the victim now and about 5 minutes earlier: getting closer,
  moving at the same distance, not moving for N minutes, moving away, or reached (within 60 m), with an ETA
  from the measured approach speed. It is near-live (about a minute), not second-by-second.
* Maps inside the app are offline radar maps (no tiles, no internet). "Navigate in Google Maps" and
  "Open pin in map app" hand the position to the phone's map app with a normal Android link (no API key).
  Without internet, Google Maps shows the map and driving routes only for areas downloaded beforehand
  (Google Maps > Offline maps); walking routes need internet.

## Build

GitHub Actions builds the signed APK on every push (`.github/workflows/build-apk.yml`):
core unit tests → offline speech model download (Vosk small Indian English) → `assembleRelease`, plus the
on-device smoke test on an Android 14 emulator → the release is published only when both pass.

Local build: Android Studio (JDK 17), run `scripts/fetch_voice_model.sh` once for the voice SOS model.
The pure-Kotlin core can be tested without Android: `localtest/run_core_tests.sh`.

## Limits (by design of Android)

* Android 8.0+ with Google Play services (Nearby Connections). No iPhone.
* Radio range between two phones is about 30–100 m depending on walls; more phones = more range.
* After a phone restart, open the app once so voice SOS and GPS can run in the background (Android rule).
* Voice SOS is English "help help" and may miss words in very loud places.
* SMS and 112 buttons open the phone/SMS apps and need mobile signal; the mesh itself never uses SMS.
