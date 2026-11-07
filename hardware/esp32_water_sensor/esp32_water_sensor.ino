/*
 * UyirMesh water-level sensor node: ESP32 + HC-SR04 ultrasonic distance sensor.
 *
 * NOT TESTED ON REAL HARDWARE. This program was written for the project to show how a cheap sensor box can feed
 * readings into the UyirMesh phone mesh. Check the pins, the mounting height and the alarm level before using it.
 *
 * How it works
 *  - The sensor hangs above a canal, river bank or street, pointing down, at MOUNT_HEIGHT_CM above the dry ground.
 *  - Every few seconds it measures the distance to the water surface; water level = mount height - distance.
 *  - It only advertises over Bluetooth LE (no pairing, no connection). Any phone with UyirMesh and
 *    "Listen for sensors" switched on hears it and passes the reading into the mesh.
 *
 * Advert (manufacturer specific data, 14 bytes):
 *   0xFF 0xFF        company id 0xFFFF (reserved by Bluetooth for tests and prototypes), little-endian
 *   'U' 'M'          UyirMesh sensor
 *   0x01             format version
 *   0x01             kind: 1 water level (cm), 2 rain (%), 3 gas (%)
 *   value            int16, big-endian (water level in cm; -1 = no echo)
 *   alarm            1 when the level is at or above ALARM_LEVEL_CM
 *   battery          0-100 %, 255 = not measured
 *   id               4 bytes, different for every box
 *
 * Wiring (HC-SR04 runs on 5 V; its ECHO pin gives 5 V, so use a divider, for example 1 kOhm + 2 kOhm, to bring it
 * down to about 3.3 V for the ESP32):
 *   HC-SR04 VCC  -> 5V (VIN)
 *   HC-SR04 GND  -> GND
 *   HC-SR04 TRIG -> GPIO 5
 *   HC-SR04 ECHO -> divider -> GPIO 18
 *   Battery (optional): battery + -> 100 kOhm -> GPIO 34 -> 100 kOhm -> GND
 *
 * Build with the Arduino IDE and the "esp32" boards package (2.x or 3.x), board "ESP32 Dev Module".
 */

#include <BLEDevice.h>
#include <BLEAdvertising.h>

const int TRIG_PIN = 5;
const int ECHO_PIN = 18;
const int BATTERY_PIN = 34;          // set to -1 if no battery divider is fitted
const float MOUNT_HEIGHT_CM = 300.0;  // sensor height above the dry ground
const int ALARM_LEVEL_CM = 100;       // water this high raises the alarm
const uint32_t SENSOR_ID = 0x0001A2B3; // change for every sensor box

const uint32_t NORMAL_EVERY_MS = 30000; // one reading every 30 s
const uint32_t ALARM_EVERY_MS = 5000;   // every 5 s while the alarm is on

float readDistanceOnceCm() {
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);
  unsigned long us = pulseIn(ECHO_PIN, HIGH, 30000UL); // about 5 m at most
  if (us == 0) return -1;
  return us * 0.0343f / 2.0f;
}

/** Median of 5 readings, so one bad echo (a bird, a wave) does not raise the alarm. */
float readDistanceCm() {
  float v[5];
  int n = 0;
  for (int i = 0; i < 5; i++) {
    float d = readDistanceOnceCm();
    if (d > 0) v[n++] = d;
    delay(60);
  }
  if (n == 0) return -1;
  for (int i = 1; i < n; i++) {
    float x = v[i];
    int j = i - 1;
    while (j >= 0 && v[j] > x) { v[j + 1] = v[j]; j--; }
    v[j + 1] = x;
  }
  return v[n / 2];
}

uint8_t readBatteryPercent() {
  if (BATTERY_PIN < 0) return 255;
  // Divider halves the voltage; a Li-ion cell is about 3.3 V empty and 4.2 V full.
  float volts = analogReadMilliVolts(BATTERY_PIN) * 2.0f / 1000.0f;
  float pct = (volts - 3.3f) / (4.2f - 3.3f) * 100.0f;
  if (pct < 0) pct = 0;
  if (pct > 100) pct = 100;
  return (uint8_t)pct;
}

void advertise(int16_t levelCm, bool alarm, uint8_t battery) {
  uint8_t b[14] = {
    0xFF, 0xFF, 'U', 'M', 0x01, 0x01,
    (uint8_t)((levelCm >> 8) & 0xFF), (uint8_t)(levelCm & 0xFF),
    (uint8_t)(alarm ? 1 : 0), battery,
    (uint8_t)((SENSOR_ID >> 24) & 0xFF), (uint8_t)((SENSOR_ID >> 16) & 0xFF),
    (uint8_t)((SENSOR_ID >> 8) & 0xFF), (uint8_t)(SENSOR_ID & 0xFF),
  };
#if defined(ESP_ARDUINO_VERSION_MAJOR) && ESP_ARDUINO_VERSION_MAJOR >= 3
  String data((const char *)b, sizeof(b));
#else
  std::string data((const char *)b, sizeof(b));
#endif
  BLEAdvertisementData adv;
  adv.setFlags(0x06); // general discoverable, no classic Bluetooth
  adv.setManufacturerData(data);
  BLEAdvertising *a = BLEDevice::getAdvertising();
  a->stop();
  a->setAdvertisementData(adv);
  a->start();
}

void setup() {
  Serial.begin(115200);
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  BLEDevice::init("UyirMesh-Sensor");
}

void loop() {
  float d = readDistanceCm();
  int16_t level = d < 0 ? -1 : (int16_t)(MOUNT_HEIGHT_CM - d);
  bool alarm = level >= ALARM_LEVEL_CM;
  uint8_t battery = readBatteryPercent();
  advertise(level, alarm, battery);
  Serial.printf("distance %.1f cm, water level %d cm, alarm %d, battery %u\n", d, level, alarm, battery);
  delay(alarm ? ALARM_EVERY_MS : NORMAL_EVERY_MS);
}
