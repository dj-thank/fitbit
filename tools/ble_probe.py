#!/usr/bin/env python3
"""Passive BLE GATT probe for a Fitbit Air you own.

Usage:
    pip install bleak
    python tools/ble_probe.py

This script enumerates services/characteristics and subscribes to characteristics
that advertise NOTIFY/INDICATE. It does not perform arbitrary writes.
"""
import asyncio
from datetime import datetime
from bleak import BleakClient, BleakScanner

async def main():
    print("Scanning for Fitbit/Air devices...")
    devices = await BleakScanner.discover(timeout=8.0, return_adv=True)
    candidates = []
    for addr, (dev, adv) in devices.items():
        name = (dev.name or adv.local_name or "").lower()
        if "fitbit" in name or "air" in name:
            candidates.append(dev)
            print(f"[{len(candidates)-1}] {dev.name} {dev.address} RSSI={adv.rssi if hasattr(adv,'rssi') else '?'}")
    if not candidates:
        print("No obvious Fitbit/Air device found. Put the tracker near the adapter and retry.")
        return
    dev = candidates[0]
    print(f"Connecting to {dev.name} {dev.address}")
    async with BleakClient(dev) as client:
        for service in client.services:
            print("SERVICE", service.uuid)
            for c in service.characteristics:
                print("  CHAR", c.uuid, c.properties)
                if "notify" in c.properties or "indicate" in c.properties:
                    try:
                        await client.start_notify(c, lambda ch, data: print(datetime.now().isoformat(), ch.uuid, bytes(data).hex()))
                        print("    subscribed")
                    except Exception as exc:
                        print("    subscribe failed:", exc)
        print("Capturing notifications for 60 seconds. Move the device through known poses.")
        await asyncio.sleep(60)

if __name__ == "__main__":
    asyncio.run(main())
