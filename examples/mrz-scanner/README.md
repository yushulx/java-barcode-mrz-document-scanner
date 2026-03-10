# LiteCam MRZ Scanner

A desktop MRZ (Machine-Readable Zone) scanner built with Java, [LiteCam](https://github.com/yushulx/java-jni-barcode-qrcode-reader/tree/main/examples/barcode-scanner), and the [Dynamsoft Capture Vision SDK](https://www.dynamsoft.com/capture-vision/docs/server/programming/java/). Detects and parses MRZ data from passports and travel documents in real time via camera or from image files.

https://github.com/user-attachments/assets/0724348a-1331-478b-93f2-536d78b5cfd0

---

## Features

- **Camera mode** — continuous, low-latency MRZ detection from a live camera feed (~30 FPS)
- **File mode** — load images via file picker or drag-and-drop
- **Parsed MRZ fields** — document type, ID / passport number, surname, given names, nationality, issuing state, date of birth, date of expiry, sex
- **Visual overlay** — green polygon drawn around the detected MRZ zone (when location data is available), plus an on-screen banner showing the document type and ID
- **Supported document types** — ICAO TD3 passports (`MRTD_TD3_PASSPORT`), TD1/TD2 IDs, and other ICAO-compliant travel documents

---


## Dynamsoft License

The application requires a Dynamsoft license key.

The default key in `MRZScanner.java` is a **public trial key** that requires an active internet connection. For offline use or production deployment, obtain a dedicated key from the [Dynamsoft Customer Portal](https://www.dynamsoft.com/customer/license/trialLicense/?product=dcv&package=cross-platform).

Replace the key in `main()`:

```java
LicenseManager.initLicense("YOUR_LICENSE_KEY_HERE");
```

---

## Build

```bash
# Windows
.\build.ps1

# Linux / macOS
./build.sh
```

This installs the local LiteCam JAR, downloads all Maven dependencies, and produces a fat JAR at:

```
target/litecam-mrz-scanner-1.0.0.jar
```

---

## Run

```bash
# Windows (camera mode — default)
.\run.ps1

# Windows (file mode — no camera required)
.\run.ps1 --file

# Linux / macOS (camera mode)
./run.sh

# Linux / macOS (file mode)
./run.sh --file
```

Or run the fat JAR directly:

```bash
java -jar target/litecam-mrz-scanner-1.0.0.jar
java -jar target/litecam-mrz-scanner-1.0.0.jar --file
```

---

## Usage

### Camera Mode (default)

1. Launch the application. The camera feed appears on the left panel.
2. Hold a passport or travel document in front of the camera with the MRZ strip visible.
3. Detected MRZ fields appear automatically in the **MRZ Results** panel on the right.
4. Use **Switch to File Mode** to switch modes without restarting.

### File Mode

1. Launch with `--file`, or click **Switch to File Mode** while running.
2. Click **Load Image File** or drag and drop an image onto the display area.
3. Supported formats: JPEG, PNG, BMP, TIFF.
4. Parsed fields are displayed immediately in the **MRZ Results** panel.

---

## Supported Document Types

| Code type | Description |
|---|---|
| `MRTD_TD3_PASSPORT` | ICAO TD3 passport (2-line, 44 characters per line) |
| `MRTD_TD1_ID` | ICAO TD1 ID card (3-line) |
| `MRTD_TD2_ID` | ICAO TD2 ID card (2-line) |

---

## Blog
[How to Build a Java MRZ Scanner Desktop App with Dynamsoft Capture Vision](https://www.dynamsoft.com/codepool/build-java-mrz-scanner-desktop-app-dynamsoft-capture-vision.html)

---
