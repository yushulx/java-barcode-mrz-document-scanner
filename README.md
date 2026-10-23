# Java Barcode, MRZ and Document Scanner Samples

A collection of Java samples for **barcode scanning**, **document detection & normalization**, and **MRZ recognition**, built on the official Dynamsoft Java SDK pulled straight from Maven — no wrapper layer to compile, no native libraries to copy around.

```xml
<dependency>
    <groupId>com.dynamsoft</groupId>
    <artifactId>dbr</artifactId>   <!-- Barcode Reader -->
    <version>11.6.4000</version>
</dependency>
<dependency>
    <groupId>com.dynamsoft</groupId>
    <artifactId>dcv</artifactId>   <!-- Capture Vision (document / MRZ) -->
    <version>3.6.4000</version>
</dependency>
```

## Samples

| Sample | What it does | SDK | JDK |
|---|---|---|---|
| [Barcode Scanner](examples/barcode-scanner) | Real-time camera scanning with visual overlays plus drag-and-drop file mode; dual engines (Dynamsoft Barcode Reader + ZXing) | `dbr` 11.6.4000, ZXing 3.5.3 | 11+ |
| [Document Scanner](examples/document-scanner) | Live document boundary detection and one-click deskew/crop with a Swing UI | `dcv` 3.6.4000 | 17+ |
| [MRZ Scanner](examples/mrz-scanner) | Passport and ID MRZ detection with parsed fields (document number, name, nationality, dates) | `dcv` 3.6.4000 | 17+ |
| [ZXing vs Dynamsoft](examples/zxing-dynamsoft) | Three side-by-side samples: command line, Swing GUI, Spring Boot web API | `dbr` 11.6.4000, ZXing 3.5.3 | 8+ / 17+ |

The desktop samples capture video through the bundled **LiteCam** library (`libs/litecam.jar`, a thin camera capture helper for Windows/Linux/macOS); all barcode, document and MRZ processing is done by the Dynamsoft Java SDK.

## Maven Repository

Both SDKs are hosted on Dynamsoft's Maven repositories:

```xml
<repositories>
    <repository>
        <id>dbr</id>
        <url>https://download2.dynamsoft.com/maven/dbr/jar</url>
    </repository>
    <repository>
        <id>dcv</id>
        <url>https://download2.dynamsoft.com/maven/jar</url>
    </repository>
</repositories>
```

## License Key

A valid license key is required at runtime. Get a [30-day free trial license](https://www.dynamsoft.com/customer/license/trialLicense/?product=dcv&package=cross-platform) and initialize it once at startup:

```java
LicenseError error = LicenseManager.initLicense("LICENSE-KEY");
if (error.getErrorCode() != EnumErrorCode.EC_OK) {
    System.out.println(error.getErrorString());
}
```

- The three desktop samples (`barcode-scanner`, `document-scanner`, `mrz-scanner`) ship with a public trial key that requires an internet connection. Replace it in `main()` for offline or production use.
- The `zxing-dynamsoft` samples read the key from the `DBR_LICENSE_KEY` environment variable, falling back to `LICENSE-KEY`:

```bash
# Windows
set DBR_LICENSE_KEY=your-license-key
# Linux / macOS
export DBR_LICENSE_KEY=your-license-key
```

## Barcode Scanner

Live camera barcode reader (25+ 1D/2D/postal symbologies) with an overlay of decoded results, plus a file mode with drag-and-drop.

```bash
cd examples/barcode-scanner/maven-example
./build.sh      # Windows: .\build.ps1
./run.sh        # Windows: .\run.ps1
```

This folder also holds the LiteCam camera library source. Its prebuilt JAR already ships with the document and MRZ samples; rebuild it only if you change the native code:

```bash
cd examples/barcode-scanner
./build-jar.sh  # Windows: .\build-jar.ps1
```

## Document Scanner

Continuously detects the document quad in a camera feed, supports manual corner editing, and normalizes the result with a single click.

```bash
cd examples/document-scanner
./build.sh              # Windows: .\build.ps1
./run.sh                # camera mode
./run.sh --file         # file-only mode, no camera required
```

The fat JAR is written to `target/litecam-document-scanner-1.0.0.jar`.

## MRZ Scanner

Detects and parses MRZ from ICAO TD1/TD2/TD3 travel documents, from a camera feed or an image file, at roughly 30 FPS.

```bash
cd examples/mrz-scanner
./build.sh              # Windows: .\build.ps1
./run.sh                # camera mode
./run.sh --file         # file-only mode
```

Supported code types: `MRTD_TD3_PASSPORT`, `MRTD_TD1_ID`, `MRTD_TD2_ID`.

## ZXing vs Dynamsoft

Three implementations of the same task, so you can compare the open-source and commercial engines directly.

```bash
# Command line
cd examples/zxing-dynamsoft/command-line
mvn clean package
java -jar target/test-1.0-SNAPSHOT-jar-with-dependencies.jar ../../images/AllSupportedBarcodeTypes.png

# Swing GUI
cd examples/zxing-dynamsoft/gui
mvn clean package
java -jar target/test-1.0-SNAPSHOT-jar-with-dependencies.jar

# Spring Boot web API
cd examples/zxing-dynamsoft/web
mvn clean package
java -jar target/web-1.0-SNAPSHOT.jar
# then open http://localhost:8080/swagger-ui/index.html and try /api/dynamsoft or /api/zxing
```

## Repository Layout

```
examples/
├── barcode-scanner/    # Camera barcode scanner (DBR + ZXing) and the LiteCam camera library
├── document-scanner/   # Document boundary detection and normalization (Capture Vision)
├── mrz-scanner/        # Passport / ID MRZ recognition (Capture Vision)
└── zxing-dynamsoft/    # ZXing vs Dynamsoft: command line, Swing GUI, Spring Boot web
images/                 # Test images used by the command-line samples
```

## Resources

- [Building a Java Barcode Scanner with Camera SDK, ZXing, and Dynamsoft Barcode Reader](https://www.dynamsoft.com/codepool/java-barcode-scanner-reader.html)
- [How to Build a Live Document Scanner Desktop App in Java with Dynamsoft Capture Vision SDK](https://www.dynamsoft.com/codepool/build-live-document-scanner-desktop-app-java-dynamsoft.html)
- [How to Build a Java MRZ Scanner Desktop App with Dynamsoft Capture Vision](https://www.dynamsoft.com/codepool/build-java-mrz-scanner-desktop-app-dynamsoft-capture-vision.html)
- [Dynamsoft Barcode Reader — Java](https://www.dynamsoft.com/barcode-reader/docs/server/programming/java/)
- [Dynamsoft Capture Vision — Java](https://www.dynamsoft.com/capture-vision/docs/server/programming/java/)

## License

[MIT](LICENSE)
