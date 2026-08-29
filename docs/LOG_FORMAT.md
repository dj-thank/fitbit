# Diagnostic log format

Air Posture Lab v0.2 writes newline-delimited JSON (`.jsonl`). Each line is one event.

## Common fields

| Field | Meaning |
| --- | --- |
| `schema` | Log schema version. Currently `1`. |
| `session_id` | Random diagnostic session identifier. |
| `utc_ms` | Wall-clock Unix timestamp in milliseconds. |
| `elapsed_ns` | Android monotonic `elapsedRealtimeNanos()` timestamp for precise relative timing. |
| `type` | Event type. |

`elapsed_ns` should be used for packet interval and latency analysis because it is monotonic. `utc_ms` is useful for correlating the session with an external observation or video.

## Session metadata

`session_start` includes app version, Android SDK level, device manufacturer/model, and Android device codename.

## BLE identity handling

Raw Bluetooth MAC addresses are not exported. Before logging, the app computes a SHA-256 digest from a random per-session salt plus the address and keeps only the first eight digest bytes as hexadecimal text.

This means `device_token` is useful for correlation **within one session** but is intentionally not stable across sessions.

## Useful event families

Typical events include scanning/advertisement observations, connection state, MTU, GATT discovery, characteristic/descriptor inventory, notification payloads, errors, and export lifecycle events.

Manual ground-truth markers are:

- `marker_neutral`
- `marker_forward`
- `marker_walk`
- `marker_fitbit_alarm`

These markers are the main supervision signal for protocol discovery. Analysis can compare characteristic update frequency, entropy, byte deltas, periodicity, and value changes before/after each labeled transition.

## Sharing

A share ZIP contains the active JSONL file and a small `README.txt`. Export occurs only when the user explicitly taps the share button.

Even though stable MAC addresses and health-account credentials are not recorded, payload bytes and timestamps are still device data. Do not publish captures automatically; inspect them first.
