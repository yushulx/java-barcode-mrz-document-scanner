# Document Scanner

A Java Swing desktop application that uses **LiteCam** for live camera access and the **Dynamsoft Capture Vision SDK (DDN)** for automatic document boundary detection and normalization (deskew/crop).

https://github.com/user-attachments/assets/bd91971e-07c7-4e02-b8bd-46f27097fc80


## Features

- **Live camera mode** — continuously detects the document boundary in real-time and draws the detected quad on the camera feed
- **File mode** — load any image (JPEG, PNG, BMP, TIFF) via file picker or drag-and-drop; the document boundary is detected immediately
- **One-click normalization** — click **Normalize Document** to deskew and crop the document to a clean rectangular output
- **Manual quad editing** — click **Edit Quad / Corners** to open a popup where you can drag the four corner handles to precisely define the document boundary
- **Save normalized image** — click **Save Normalized Image** to export the rectified document as PNG or JPEG
- **Visual overlay** — detected (cyan) or user-edited (orange) quad is drawn over the source image at all times


## Requirements

| Dependency | Version |
|---|---|
| Java | 17+ |
| Maven | 3.8+ |
| LiteCam SDK | `libs/litecam.jar` + native libs |
| Dynamsoft Capture Vision SDK | 3.4.1000 (downloaded from Maven) |

A valid [Dynamsoft license key](https://www.dynamsoft.com/customer/license/trialLicense/?product=dcv&package=cross-platform) is required at runtime. The project ships with a public trial key for evaluation.

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/yushulx/java-jni-barcode-qrcode-reader.git
cd java-jni-barcode-qrcode-reader/examples/document-scanner
```

### 2. Build

**Windows (PowerShell):**
```powershell
.\build.ps1
```

**Linux / macOS:**
```bash
./build.sh
```

Or directly with Maven:
```bash
mvn package -DskipTests
```

The fat JAR is created at `target/litecam-document-scanner-1.0.0.jar`.

### 4. Run

**Windows:**
```powershell
.\run.ps1
```

**Linux / macOS:**
```bash
./run.sh
```

Or directly:
```bash
java -jar target/litecam-document-scanner-1.0.0.jar
```

To start in **file-only mode** (no camera required):
```bash
java -jar target/litecam-document-scanner-1.0.0.jar --file
```

## Blog
[How to Build a Live Document Scanner Desktop App in Java with Dynamsoft Capture Vision SDK](https://www.dynamsoft.com/codepool/build-live-document-scanner-desktop-app-java-dynamsoft.html)
