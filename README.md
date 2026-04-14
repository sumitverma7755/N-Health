# N Health Watch Status (Android BLE MVP)

This project is a Kotlin Android app that:

- Scans nearby BLE devices.
- Pairs and connects to a selected watch from inside the app.
- Reads standard BLE GATT data if exposed:
  - Battery Service (0x180F / 0x2A19)
  - Device Information Service (0x180A)
- Shows a status dashboard:
  - Connection state
  - Device name
  - Battery level
  - Signal strength (RSSI)
  - Manufacturer / model / firmware
  - Last sync time
- Auto-reconnects after unexpected disconnects.

## Project Structure

- `app/src/main/java/com/nhealth/watchstatus/MainActivity.kt`
- `app/src/main/java/com/nhealth/watchstatus/BleManager.kt`
- `app/src/main/java/com/nhealth/watchstatus/WatchStatus.kt`
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/AndroidManifest.xml`

## Run

1. Open this folder in Android Studio.
2. Let Gradle sync and install missing SDK components if prompted.
3. Build and run on an Android phone with BLE.
4. Grant Bluetooth permissions when prompted.
5. Tap **Start Scan**, then tap your watch from the list to pair/connect.

## Notes

- Some OnePlus watch metrics are proprietary and may not be available over standard BLE characteristics.
- If your watch does not expose Battery or Device Info services, those fields stay "Not available".
