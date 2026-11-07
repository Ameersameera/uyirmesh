# Sensor box (optional hardware)

`esp32_water_sensor/` is a small program for an ESP32 board with an HC-SR04 ultrasonic sensor that measures the
water level and advertises it over Bluetooth LE. Phones running UyirMesh with **Home → Water and rain sensors →
Listen for sensors on this phone** switched on pick up the reading and pass it into the mesh, so the rescue base and people nearby
see rising water early.

**Status: not tested on real hardware.** The phone side (reading the advert, passing it on, showing it, the alarm
notification) is part of the app and covered by unit tests; the ESP32 program has only been written, not run.

| Part | Approx. cost |
|------|--------------|
| ESP32 development board | ₹350 |
| HC-SR04 ultrasonic sensor | ₹80 |
| 18650 Li-ion cell, holder, TP4056 charger, small solar panel (optional) | ₹400 |
| Resistors, wires, waterproof box | ₹100 |

Advert format (also in the source): company id `0xFFFF` (reserved for prototypes), then `"UM"`, version `1`,
kind (`1` water level cm, `2` rain %, `3` gas %), value (int16, big-endian), alarm flag, battery %, 4-byte sensor id.
Sensor readings cannot be signed (the box has no key), so the app labels them as unverified.
