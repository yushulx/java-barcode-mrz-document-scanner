# ZXing & Dynamsoft Java Barcode Reader: Command Line, GUI and Web

This repository contains three samples (command line, GUI, and web) demonstrating how to implement a Java barcode and QR code reader using [ZXing](https://github.com/zxing/zxing) and [Dynamsoft Barcode Reader](https://www.dynamsoft.com/barcode-reader/sdk-desktop-server/) 11.x.

## Install ZXing and Dynamsoft Barcode Reader in Maven Project

Configure the `pom.xml` file with the Dynamsoft Maven repository and the two dependencies:

```xml
<repositories>
    <repository>
        <id>dbr</id>
        <url>https://download2.dynamsoft.com/maven/dbr/jar</url>
    </repository>
</repositories>
<dependencies>
    <dependency>
        <groupId>com.dynamsoft</groupId>
        <artifactId>dbr</artifactId>
        <version>11.6.5000</version>
    </dependency>
    <dependency>
        <groupId>com.google.zxing</groupId>
        <artifactId>core</artifactId>
        <version>3.5.3</version>
    </dependency>
</dependencies>
```

## License Key

Get a 30-day free [trial license](https://www.dynamsoft.com/customer/license/trialLicense/?product=dcv&package=cross-platform) for Dynamsoft Barcode Reader. The samples read the key from the `DBR_LICENSE_KEY` environment variable and fall back to the placeholder `LICENSE-KEY`:

```java
String licenseKey = System.getenv().getOrDefault("DBR_LICENSE_KEY", "LICENSE-KEY");
LicenseError licenseError = LicenseManager.initLicense(licenseKey);
```

Or set the variable before running:

```bash
:: Windows
set DBR_LICENSE_KEY=your-trial-license-key

# Linux / macOS
export DBR_LICENSE_KEY=your-trial-license-key
```

## Decode API

Version 11.x replaces the legacy `BarcodeReader` class with `CaptureVisionRouter`. Decoding an image is a single call:

```java
CaptureVisionRouter cvRouter = new CaptureVisionRouter();
CapturedResult result = cvRouter.capture(filePath, EnumPresetTemplate.PT_READ_BARCODES);
DecodedBarcodesResult barcodeResult = result.getDecodedBarcodesResult();
BarcodeResultItem[] items = barcodeResult != null ? barcodeResult.getItems() : null;
```

The same `capture` method also accepts `byte[]` (for uploads / streams) and `ImageData` (for raw pixel buffers).

## Usage

- [Command Line](command-line/README.md)
- [GUI](gui/README.md)
- [Web](web/README.md)

## Blog

[Java Barcode & QR Code Apps: Command-Line, Swing GUI, and Spring Boot Web with Dynamsoft and ZXing](https://www.dynamsoft.com/codepool/java-barcode-command-line-gui-web.html)
