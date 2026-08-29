# Air Posture Lab

> Experimental Android field toolkit for exploring posture sensing with a back-mounted Fitbit Air over Bluetooth Low Energy (BLE).

**Status:** v0.2.0 — protocol discovery / data collection build.  
**Repository:** public research workspace.  
**Important:** This project is unofficial, is not affiliated with or endorsed by Google or Fitbit, and is not a medical device.

## What works today

- BLE scan and candidate selection for nearby Fitbit Air devices.
- GATT service / characteristic / descriptor inventory.
- Subscription to standard `NOTIFY` / `INDICATE` characteristics through the standard CCCD only.
- Raw notification payload capture as hexadecimal bytes.
- RSSI, advertisement, MTU, connection state, and timing capture.
- High-precision timestamps on every event: wall-clock UTC milliseconds plus Android monotonic elapsed nanoseconds.
- Manual motion markers for neutral posture, forward/slouched posture, walking, and an official Fitbit-side alarm.
- Local-first JSONL diagnostics and explicit ZIP export through Android's share sheet.
- Session-scoped hashing of Bluetooth MAC addresses before anything is written to the diagnostic log.
- Android phone double-pulse haptic test.
- A posture state machine with hysteresis and dwell timing, ready to connect once the Air IMU packet format is verified.

## What is intentionally not implemented yet

The current build does **not** claim to understand Fitbit Air's proprietary IMU packet format. It does not guess arbitrary proprietary BLE writes, bypass pairing, extract account credentials, modify firmware, or send undocumented haptic commands to the Air.

The next engineering step is evidence-driven: capture labeled sessions, identify the characteristic(s) that contain motion data, infer packet framing / endianness / scale, validate against known body movements, then connect a verified decoder to `PostureEngine`.

## Download / build the test APK

GitHub Actions runs `test + assembleDebug` on the public source and uploads `AirPostureLab-debug-apk` as a workflow artifact after successful builds. The reference v0.2.0 APK built on 2026-08-29 has SHA-256:

`9879262e9b287a670e611cdf03271fe512746b78095e4436aa1728d645bc8932`

See [`apk/SHA256SUMS.txt`](apk/SHA256SUMS.txt). The APK is a **debug / field-test build**, not a production-signed Play Store release.

## Recommended capture procedure

1. Install the APK and grant Nearby devices / Bluetooth permissions.
2. Wear the Fitbit Air firmly on the upper back so it cannot rotate relative to the torso.
3. Tap `Fitbit AirをBLEスキャン・解析`.
4. Keep a neutral/good posture for about 10 seconds and tap `マーカー: 直立/良い姿勢` at the start.
5. Slouch or lean forward for about 10 seconds and tap `マーカー: 前傾/悪い姿勢` at the start.
6. Walk or move normally for 15–30 seconds and tap `マーカー: 歩行・日常動作` at the start.
7. If possible, trigger an official Fitbit silent alarm and tap `マーカー: Fitbit側アラームを今作動` at the same moment.
8. Tap `ログZIPを共有 / ChatGPTへ送る` and share the resulting ZIP for packet correlation analysis.

A useful session contains several repeats of each motion, not just one example.

## Diagnostics and privacy

Diagnostics are written into app-private storage. A shareable ZIP is created only after an explicit user action.

Exported JSONL does not contain the raw Bluetooth MAC address. The app creates a random per-session salt and stores only a short SHA-256-derived device token, so packets from the same device can be correlated inside one session without exposing a stable hardware identifier.

The app does not record Fitbit/Google account credentials. Diagnostic payloads can still contain device-protocol data, device model information, timestamps, and advertisement/GATT bytes, so review logs before publishing them publicly.

See [`docs/LOG_FORMAT.md`](docs/LOG_FORMAT.md) for the schema.

## Architecture

```text
Android BLE scan
    ↓
AirBleManager
    ↓
AirGattProbe ───────→ DiagnosticsLogger → JSONL → share ZIP
    ↓                                  ↑
verified IMU decoder (next)            │
    ↓                                  │
PostureEngine ← manual labeled markers ┘
    ↓
HapticSink
```

`PostureEngine` currently uses an enter/exit hysteresis of 12°/8°, a 4-second dwell before warning, and a 30-second repeat interval. These are experimental defaults rather than medical or ergonomic thresholds.

## Project layout

```text
app/src/main/java/dev/rambo/airposture/
  AirBleManager.kt       BLE scanning / connection orchestration
  AirGattProbe.kt        GATT discovery, subscription, raw packet capture
  DiagnosticsLogger.kt   local JSONL recorder, anonymization, ZIP export
  PostureEngine.kt       posture state machine
  Haptics.kt             phone haptic abstraction
  MainActivity.kt        field-test UI and manual labels
app/src/test/             posture engine tests
tools/ble_probe.py        desktop-side BLE exploration helper
docs/LOG_FORMAT.md        diagnostic schema
apk/                      published field-test APK + checksum
.github/workflows/        reproducible Android CI
```

## Build

Requirements:

- JDK 17
- Gradle 9.5
- Android SDK Platform 36
- Android Build Tools 36.0.0

```bash
gradle --no-daemon test assembleDebug
```

The public GitHub Actions workflow performs the same test/build path on every push and pull request and uploads the generated debug APK as a workflow artifact.

## Roadmap

- **v0.2.x:** collect diverse labeled BLE sessions and improve diagnostics.
- **v0.3:** identify motion-bearing characteristic(s), packet framing, sample rate, endianness, axes, and scale.
- **v0.4:** verified live IMU decoder → posture angle estimation → `PostureEngine`.
- **Later:** determine whether an owner-authorized, verified Fitbit-side haptic path exists; keep phone haptics as the safe fallback.

## Public-repository note

This repository is intentionally public. Do not commit personal diagnostic captures, raw identifiers, account credentials, secrets, or private device data. Use sanitized fixtures for reproducible tests.

No software license has been selected yet. Public visibility alone does not grant redistribution or modification rights beyond those provided by applicable law.
