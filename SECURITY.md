# Security and privacy

This repository is a public experimental BLE research project.

Please do not open a public issue containing account credentials, raw personal diagnostic captures, stable device identifiers, private keys, access tokens, or other secrets.

The Android logger is intentionally local-first and anonymizes Bluetooth MAC addresses with a per-session random salt before export. The probe is designed to avoid arbitrary proprietary writes: v0.2 only writes the standard CCCD when enabling notifications/indications.

If you discover a security issue in this project, report the minimum information required to reproduce it and remove personal/device-identifying data from examples.
