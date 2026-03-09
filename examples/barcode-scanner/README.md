# LiteCam: Java Camera SDK + Barcode Scanner

This project provides two main components: a lightweight **Java Camera SDK** for cross-platform video capture and a complete **Java Barcode Scanner** application demonstrating real-time barcode detection.

https://github.com/user-attachments/assets/53326c6b-1893-41ee-9f6a-dfe409afdd2d


## Prerequisites
- **Java JDK 8+** and **Maven 3.6+**
- **Camera device** (for scanner application)
- Platform dependencies: Windows (Visual Studio), Linux (`libx11-dev libv4l-dev`), macOS (Xcode)
- [30-day free trial license](https://www.dynamsoft.com/customer/license/trialLicense/?product=dcv&package=cross-platform) for Dynamsoft Barcode Reader

## Components

### 1. LiteCam Java Camera SDK
Lightweight cross-platform camera capture library with JNI bridge.

```bash
# 1. Build Camera SDK
.\build-jar.ps1          # Windows
./build-jar.sh           # Linux/macOS

# 2. Run Tests
.\run-litecam.ps1 # Windows
./run-litecam.sh  # Linux/macOS
```


**Features:**
- Cross-platform video capture (Windows/Linux/macOS)
- Direct RGB frame access via ByteBuffer
- Multiple resolution support
- Minimal overhead JNI implementation

### 2. Java Barcode Scanner Application  
Complete barcode scanning application built with the Camera SDK, Dynamsoft Barcode Reader and ZXing.

```bash
# 1. Build Barcode Scanner
.\build.ps1       # Windows
./build.sh           # Linux/macOS

# 2. Run the app
.\run.ps1 # Windows
./run.sh  # Linux/macOS
```
![Java barcode scanner](https://www.dynamsoft.com/codepool/img/2025/08/java-barcode-scanner-reader.png)

**Features:**
- Real-time camera scanning with visual overlays
- Dual engines: ZXing (open-source) + Dynamsoft Barcode Reader (enterprise)
- File processing mode with drag-and-drop
- 25+ barcode formats (1D/2D/Postal)


## Quick Start

```java
// List cameras
String[] devices = LiteCam.listDevices();

// Open camera
LiteCam cam = new LiteCam();
cam.openDevice(0);
cam.setResolution(640, 480);

// Capture frames
ByteBuffer buffer = ByteBuffer.allocateDirect(640 * 480 * 3);
if (cam.grabFrame(buffer)) {
    // Process RGB data
    byte[] frameData = new byte[buffer.remaining()];
    buffer.get(frameData);
}

cam.close();
```

## Blog
[Building a Java Barcode Scanner with Camera SDK, ZXing, and Dynamsoft Barcode Reader](https://www.dynamsoft.com/codepool/java-barcode-scanner-reader.html)


