# USB Camera Controller

Turn any Android phone into a **USB‑tethered, fully scriptable camera** you can control from a laptop with Python. The phone runs an Android app that streams camera frames and accepts control commands; the laptop runs a lightweight Python client that shows a live preview and lets you drive exposure, ISO, focus, white balance, torch, and still capture – all **without WiFi**, over a single USB‑C cable via ADB port forwarding.


## Problem Statement

Many camera‑heavy workflows (computer‑vision prototyping, hardware testing, lab setups, robotics, etc.) need:

- A **good image sensor** with manual control (exposure, ISO, focus, WB, torch)
- **Low‑latency preview** on a laptop for debugging / visualization
- **Scriptable control** from Python (e.g. sweep exposure, log sensor response, collect datasets)
- **No dependency on WiFi or the local network** (labs, production lines, air‑gapped machines)

Dedicated industrial cameras are expensive and often have clunky SDKs. This project reuses a phone you already have and exposes a simple, low‑latency, USB‑based control surface that Python can drive.


## Solution Overview

- An **Android app** (CameraControllerApp) uses the Camera2 API to:
	- Capture frames from the phone’s camera
	- Encode them as JPEG
	- Stream them over a TCP socket (port 5555)
	- Listen for JSON commands on another TCP socket (port 5556)
	- Apply commands by updating the active `CaptureRequest` (exposure, ISO, focus, WB, torch, etc.)

- A **Python client** (CameraControllerClient/camera_client.py) running on the laptop:
	- Uses **ADB port forwarding** to reach the phone over USB
	- Connects to the frame and command sockets
	- Receives the latest JPEG frame with a low‑latency receiver thread
	- Decodes frames with OpenCV and displays a live preview window
	- Sends commands from keyboard shortcuts or from your own Python code

Communication is 100% over USB; no network configuration is required.


## Architecture Overview

At a high level, the system looks like this:

```text
┌──────────────────────────────────────────────────┐
│                 ANDROID PHONE                    │
│                                                  │
│  Camera2 API  →  ImageReader (YUV_420_888)       │
│                       │                          │
│              YUV → JPEG encoder                  │
│                       │                          │
│              FrameStreamer (queue)               │
│                       │                          │
│         TCP Server (localhost:5555)              │
│              ─────────┼──────────                │
│         TCP Server (localhost:5556)              │
│                       │                          │
│              CommandServer (JSON)                │
│                       │                          │
│              CameraController                    │
│          (updates CaptureRequest)                │
└──────────────────────┬───────────────────────────┘
											 │  USB‑C cable
							ADB forward tcp:5555/5556
											 │
┌──────────────────────┴───────────────────────────┐
│                    LAPTOP                        │
│                                                  │
│  camera_client.py                                │
│    ├── Frame receiver  (port 5555)               │
│    │     → struct.unpack → cv2.imdecode          │
│    │     → cv2.imshow / numpy processing         │
│    └── Command sender  (port 5556)               │
│          → json.dumps → struct.pack → send       │
└──────────────────────────────────────────────────┘
```

The ADB `forward` commands make the phone’s localhost ports appear as `127.0.0.1:5555` and `127.0.0.1:5556` on the laptop, so the Python code can talk to the phone as if it were a local TCP server.


## Repository Layout

- `CameraControllerApp/` – Android Studio project for the on‑device Camera2 controller app
	- `app/` – Android app module
- `CameraControllerClient/` – Python client that connects over USB via ADB forwarding
	- `camera_client.py` – main client script and Python API
	- `requirements.txt` – Python dependencies
- `SETUP.md` – More focused setup/how‑to guide for day‑to‑day use


## Keyboard Controls (Default Client)

In the OpenCV window, the following keys are handled:

| Key | Action |
|-----|--------|
| `e` | Cycle exposure time through predefined shutter speeds |
| `i` | Cycle ISO sensitivity presets |
| `f` | Cycle focus distance presets (diopters) |
| `a` | Toggle auto‑exposure on/off |
| `d` | Toggle auto‑focus on/off |
| `w` | Cycle white‑balance mode |
| `t` | Toggle torch / flash |
| `p` | Capture photo (also saved locally as a JPEG) |
| `h` | Print help banner to the terminal |
| `q` | Quit the client |

The HUD overlay in the preview shows current FPS, AE/AF/torch state, exposure, ISO, focus, and WB mode.

## Python Controls

You can also import and control the camera programmatically from any Python code:

```python
from camera_client import CameraClient

client = CameraClient()
client.connect()

# Example: set manual exposure to 10 ms
client.send_command("set_exposure", 10_000_000)  # nanoseconds

# Example: set ISO 800
client.send_command("set_iso", 800)

# Example: change resolution
client.send_command("set_resolution", "1280x720")

# Fetch the latest frame (BGR numpy array) for custom processing
frame = client.get_frame()
```

The `CameraClient` maintains a background receiver thread that always keeps **only the latest frame** in memory, so your processing loop never falls behind even if the camera is streaming faster than your code can process frames.

### Command Reference

Commands are sent as JSON of the form:

```json
{"cmd": "set_exposure", "value": 10000000}
```

The client hides this and exposes a simple `send_command(cmd, value=None)` API, but the logical commands are:

| Command | Value | Description |
|---------|-------|-------------|
| `set_exposure` | `int` (nanoseconds) | Set manual exposure time |
| `set_iso` | `int` | Sensor ISO gain |
| `set_focus` | `float` (diopters; 0 = ∞) | Manual focus distance |
| `set_fps` | `int` | Target frame rate (if supported) |
| `set_resolution` | `"WxH"` string | Change capture resolution, e.g. `"1920x1080"` |
| `enable_auto_exposure` | — | Enable auto‑exposure (AE) |
| `disable_auto_exposure` | — | Disable AE and use manual exposure |
| `enable_auto_focus` | — | Enable auto‑focus (AF) |
| `disable_auto_focus` | — | Disable AF and use manual focus |
| `set_white_balance` | `int` (Camera2 AWB mode) | 1 = Auto, 2 = Incandescent, 3 = Fluorescent, 4 = Warm Fluorescent, 5 = Daylight |
| `set_torch` | `bool` or `int` | Enable/disable flashlight / torch |
| `capture_photo` | — | Trigger still capture on the phone |

You can extend both the Android app and the Python client with new commands if you need additional controls (zoom, different camera, metering regions, etc.).


## Prerequisites

**Hardware**

- Android phone running **Android 10+** with Camera2 support
- **USB‑C ↔ USB‑C** (or suitable) **data‑capable cable**
- Laptop/desktop running Linux/macOS/Windows

**On the laptop**

- **Python** 3.10 or newer
- **ADB** (Android Debug Bridge)
- **Android Studio** (for building/running the Android app)

No WiFi or network connectivity is required once ADB is working.


## Contributing / Next Steps

- Yet to be filled

