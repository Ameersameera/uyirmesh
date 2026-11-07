# UyirMesh

**AI-Based Offline Smartphone Mesh Network for Disaster SOS**

Mini project · Department of ECE · Sona College of Technology, Salem
AMEER IBRAHIM S (61782323103701) · BAGAVATHI DAS T (61782323103703)
Mentor: Ms. M. AMUTHA, M.E., Ph.D., Assistant Professor / ECE

When mobile towers and internet are down, phones running UyirMesh form a mesh network over
Bluetooth, BLE and Wi-Fi (Google Nearby Connections). An SOS hops from phone to phone until it reaches
the rescue base. The base ranks every SOS with an on-phone Random Forest model, assigns rescue teams
and sends signed replies and alerts back through the same mesh.

Download the latest APK: **Releases → v2.0 → UyirMesh.apk** (Android 8.0 or newer). Version 1.0 stays available
under Releases → v1.0.

## What is new in version 2.0

| For | New |
|---|---|
| People in danger | fall / crash detection (30 s countdown), "check in or SOS" timer, picture SOS and simple mode for children and the elderly, fill the SOS form by voice (offline), silent SOS, SOS for someone else, animals count, replies read aloud and vibration codes, CPR beat helper, compass to the nearest shelter, SOS screens in Hindi, Telugu, Malayalam and Kannada (plus English and Tamil) |
| Rescuers | man-down alarm, push-to-talk voice to the team, mark hazards (bridge down, live wire...) signed with the rescuer ID, rescue outcome report |
| Rescue base | polls and roll calls, revoke lost rescuer IDs, hazard markers, several bases in step (case sync and hand-over), optimal "assign all" plan (Hungarian method), ETAs learned from finished rescues, danger words in the note raise the score, warning flags for possible fake SOS, better duplicate finding, password-protected (AES-256 ZIP) export, blood requests |
| Mesh | base beacon (phones see "base 3 hops away, heard 2 min ago, 1.2 km"), battery-aware relaying, relay mode for old phones on a charger, smaller voice/photo on a weak link, internet relay (`server/gateway_server.py`) when any phone gets internet, crowd count |
| Community | blood donor finder, missing-person matches, lost-child QR, be-ready checklist, practice game, Bluetooth water/rain sensor boxes (`hardware/`, not tested on real hardware), automatic deletion of old data |

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
* Base beacon every 10 minutes (signed): every phone knows how many hops away the base is and when it last heard it.
* Battery-aware relaying (`PowerPolicy`): below 20 % battery a phone stops relaying voice/photo for others, below
  10 % it relays only SOS, replies and alerts. Its own SOS always goes out.
* Internet relay (`mesh/Gateway.kt`): a phone that gets internet uploads waiting packets to a small relay server
  and downloads packets for the mesh. Packets stay encrypted and signed end to end; the server only stores bytes.

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
* Lost or stolen rescuer phone: the base revokes its ID (signed list, repeated every 30 minutes); every phone then
  rejects messages signed by that ID.
* Export for officials: cases CSV and PDF report in a password-protected ZIP (WinZip AES-256, opens in 7-Zip or
  WinRAR) — `core/SecureZip.kt`, checked against a standard AES-ZIP reader.
* Optional automatic deletion of old messages, logs and finished cases (Settings).

## AI priority (`ml/train_priority.py`, `core/Priority.kt`)

Random Forest (30 trees, depth 8, 17 features) trained with scikit-learn on 20,000 synthetic SOS messages
labelled by a weighted triage policy, exported to JSON and evaluated on the phone. Each prediction is
explained with tree-path contributions ("Injured +19", "1 child +4"). Waiting time and repeated
messages raise the score. Test accuracy 80.1 %, 99.8 % within one class — see `ml/model_report.md`.

Version 2 adds plain rules on top of the model, each shown as its own reason: danger words in the free-text note
(`core/NoteScan.kt`: "not breathing", "bleeding", "நெஞ்சு வலி"..., negation aware, at most +15) and animals (at most +4).
Warning flags (many SOS from one phone, no GPS, impossible position jump, wrong clock, repeated cancels) are shown
to the dispatcher but never lower a score. Dispatch uses the Hungarian method to give waiting cases to teams with
free places (`core/Dispatch.kt`), and ETAs learn from finished rescues (median pace blended with the set speed).

## Live tracking and maps (`core/Tracking.kt`)

* Victim: position with the SOS, then every 2 min (5 min in battery saver) and a last one at 5 % battery.
* Rescuer: position about once a minute (3 min in battery saver) and with every status button.
* The base compares each rescuer's distance to the victim now and about 5 minutes earlier: getting closer,
  moving at the same distance, not moving for N minutes, moving away, or reached (within 60 m), with an ETA
  from the measured approach speed. It is near-live (about a minute), not second-by-second.
* The victim sees "Help is coming: Karthik is 600 m away, about 7 min": the rescuer's phone sends a signed
  distance update (`PROGRESS` packet, kept one hour by relays) every 2 minutes while on the way, and at once
  within 150 m, when the victim is asked to shout or use the siren and flashlight.
* Precise positions only: Android's "approximate location" (moved 1-3 km on purpose) and rough network
  positions are detected and shown as rough with a one-tap fix (turn on precise location / high-accuracy
  location). A rough position never replaces a recent GPS fix (`FixChoice`), rescuers send only precise
  positions, and an SOS sent with a rough position is corrected as soon as a precise fix arrives.
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

The "App screenshots" workflow runs the same smoke test on a larger emulator screen and saves a screenshot of
each main screen to the `screenshots` branch.

## Mesh simulation

`localtest/run_mesh_sim.sh` runs the measurements used in the project report with the real `MeshEngine`,
packet format and cryptography and many virtual phones (links within 60 m, at most 6 per phone):
a line of phones (TTL limit), random deployments of 20-100 phones in 400 m x 400 m, lossy links with retries,
and store-and-forward with walking phones in 1 km x 1 km. Radio links are idealised, so the results show how
the mesh logic behaves, not real radio ranges.

## Limits (by design of Android)

* Android 8.0+ with Google Play services (Nearby Connections). No iPhone.
* Radio range between two phones is about 30–100 m depending on walls; more phones = more range.
* After a phone restart, open the app once so voice SOS and GPS can run in the background (Android rule).
* Voice SOS is English "help help" and may miss words in very loud places. Filling the form by voice also
  understands English only (offline model).
* Hindi, Telugu, Malayalam and Kannada cover the SOS screens; other screens fall back to English.
* Sensor boxes cannot sign their readings, so they are shown as unverified. The ESP32 program in `hardware/` has not
  been tested on real hardware.
* SMS and 112 buttons open the phone/SMS apps and need mobile signal; the mesh itself never uses SMS.
