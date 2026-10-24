# BLE RSSI Distance Tracker with Kalman Filtering

An ESP32-based proximity detection system that uses a 1D Kalman Filter to stabilize noisy RSSI (Received Signal Strength Indicator) values for accurate distance estimation.

## The Engineering Challenge
RSSI values in the 2.4GHz band fluctuate wildly due to multi-path fading and interference. This project addresses that by:
1. **Dynamic Calibration:** Sampling the environment for 100 cycles to determine the specific variance (R) of the current RF environment.
2. **Recursive Filtering:** Implementing a Kalman Filter to provide a smoothed signal without the lag of a standard moving average.
3. **Log-Distance Path Loss Model:** Converting the filtered signal into a physical distance in meters.

## Features in this Code
* **Active BLE Scanning:** Targets a specific device name .
* **Environment-Aware:** Calculates measurement noise dynamically during the calibration phase.
* **Low Memory Footprint:** Efficient C implementation suitable for real-time embedded loops.

## How to Run
1. Developed using **ESP-IDF**.
2. Flash the code to an ESP32.
3. Open your Serial Monitor (115200 baud).
4. Keep the target device at 1 meter during the **"CALIBRARE"** phase for accurate results.
