# Issue: Feature: Background Latency Logger for Thesis Testing

## Description
During field testing for the thesis, end-to-end (E2E) latency metrics need to be recorded consistently over running sessions to calculate statistics (average, min, max, standard deviation). Displaying the metrics in real-time on the UI is not sufficient for massive data analysis.

Therefore, a background logger system is needed to run without interrupting or overloading the main application performance, while still being able to export the required data.

## Implemented Solution
- **Added `LatencyLogger`**: Created an inner class in `StreamActivity.kt` specifically to handle latency logging.
- **Ring-Buffer (1000 Samples)**: For each processed data frame, its latency values (Sensor, Serial, Algorithm, TTS, Bluetooth, and Total) will be recorded into a temporary memory buffer.
- **5-Second Auto-Flush**: Implemented a coroutine timer to print a summary of the metrics periodically to Logcat (using the `LAT` tag) every 5 seconds to prevent memory leaks.
- **Final Flush**: Added a hook to the `akhiriProses()` method so the system prints the remaining overall latency buffer when the user closes the application.

## How to Extract Data
On a PC connected via data cable or Wi-Fi Debugging:
```powershell
adb logcat -s LAT > latency_session.txt
```
This data can then be directly opened and processed with Excel or Python for the thesis report.

## Modified Files
- `app/src/main/java/com/airi/vnetra/ui/StreamActivity.kt`
