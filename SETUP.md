# USB Camera Controller — Setup Guide


## Prerequisites

| Item | Details |
|------|---------|
| Android phone | Android 10+ with USB debugging enabled |
| USB cable | **USB-C to USB-C** (data-capable) |
| Laptop | Python 3.10+, ADB installed, Android Studio |
| No WiFi needed | Communication is 100% over USB |


## Installation

### 1  Enable USB Debugging on the Phone

1. Open **Settings → About phone**.
2. Tap **Build number** seven times to unlock Developer options.
3. Go to **Settings → Developer options**.
4. Enable **USB debugging**.
5. Connect the phone to the laptop via USB-C cable.
6. Confirm the "Allow USB debugging?" prompt on the phone.

### 2  Install the Android App

Open this project in **Android Studio** and run the app on the connected device
(or build a release APK and install via `adb install`).

The first launch will ask for Camera permission — grant it.

### 3  Set Up ADB Port Forwarding

Open a terminal on the laptop and run:

```bash
adb forward tcp:5555 tcp:5555
adb forward tcp:5556 tcp:5556
```

This forwards two ports through the USB cable:

| Port | Purpose |
|------|---------|
| 5555 | Frame streaming (Android → Python) |
| 5556 | Command channel (Python → Android) |

> **Note:** Run these commands every time you reconnect the USB cable.

### 4  Install Python Dependencies

```bash
cd CameraControllerClient
pip install -r requirements.txt
```

### 5  Run the Python Client

```bash
cd CameraControllerClient
python camera_client.py
```

An OpenCV window will open showing the live camera feed.


## Keyboard Controls (in the OpenCV window)

| Key | Action |
|-----|--------|
| `e` | Cycle exposure time |
| `i` | Cycle ISO sensitivity |
| `f` | Cycle focus distance |
| `a` | Toggle auto-exposure on/off |
| `d` | Toggle auto-focus on/off |
| `w` | Cycle white-balance mode |
| `t` | Toggle torch / flash |
| `p` | Capture photo (saves JPEG locally) |
| `h` | Print help to terminal |
| `q` | Quit |


## Programmatic Controls

You can import the client in your own scripts:

```python
from camera_client import CameraClient

client = CameraClient()
client.connect()

# Set manual exposure to 10 ms
client.send_command("set_exposure", 10_000_000)

# Set ISO 800
client.send_command("set_iso", 800)

# Change resolution
client.send_command("set_resolution", "1280x720")

# Receive and process frames
frame = client.receive_frame()  # returns a numpy BGR array
```

### Full Command Reference

| Command | Value | Description |
|---------|-------|-------------|
| `set_exposure` | `int` (nanoseconds) | Manual exposure time |
| `set_iso` | `int` | Sensor sensitivity |
| `set_focus` | `float` (diopters, 0 = ∞) | Manual focus distance |
| `set_fps` | `int` | Target frame rate |
| `set_resolution` | `"WxH"` string | Change capture resolution |
| `enable_auto_exposure` | — | Turn AE on |
| `disable_auto_exposure` | — | Turn AE off |
| `enable_auto_focus` | — | Turn AF on (continuous) |
| `disable_auto_focus` | — | Turn AF off |
| `set_white_balance` | `int` (Camera2 AWB mode) | 1=Auto 2=Incandescent 3=Fluorescent 4=WarmFluorescent 5=Daylight |
| `set_torch` | `bool` or `int` | Enable/disable flashlight |
| `capture_photo` | — | Trigger still capture |
 

## Troubleshooting

| Problem | Solution |
|---------|----------|
| "Connection refused" | Re-run `adb forward` commands; confirm app is running |
| No frames arriving | Check USB cable is data-capable (not charge-only) |
| Low FPS | Lower resolution: `client.send_command("set_resolution", "1280x720")` |
| Camera permission error | Uninstall and reinstall the app, grant permission on first launch |
| `adb` not found | Add Android SDK `platform-tools` to your system PATH |
