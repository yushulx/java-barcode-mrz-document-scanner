# Cross-Platform Java Barcode SDK for Windows, Linux and macOS
This repository provides a simple example demonstrating how to build a cross-platform Java JAR package that encapsulates JNI shared libraries for **Windows**, **Linux**, and **macOS**, binding to the [Dynamsoft C++ Barcode Reader SDK](https://www.dynamsoft.com/barcode-reader/overview/). For using production-ready Java barcode detection API, please download the [official Java Barcode SDK](https://www.dynamsoft.com/barcode-reader/downloads/) directly.

```xml
<repositories>
    <repository>
        <id>dbr </id>
        <url>
        https://download2.dynamsoft.com/maven/dbr/jar
        </url>
    </repository>
</repositories>
<dependencies>
    <dependency>
        <groupId>com.dynamsoft</groupId>
        <artifactId>dbr</artifactId>
        <version>9.6.40.1</version>
    </dependency>
</dependencies>
```

## License Key for Dynamsoft Barcode Reader SDK
Obtain a [30-day free trial license](https://www.dynamsoft.com/customer/license/trialLicense/?product=dcv&package=cross-platform).

## JNI Header Generation

Navigate to your Java source directory and generate the JNI header file:

```bash
cd src/main/java
javah -o ../../../jni/NativeBarcodeReader.h com.dynamsoft.barcode.NativeBarcodeReader
```

## Build the JNI Shared Library with CMake

### Windows
```
mkdir build
cd build
cmake -DCMAKE_GENERATOR_PLATFORM=x64 ..
cmake --build . --config Release --target install
```

### Linux & macOS

```
mkdir build
cd build
cmake .. 
cmake --build . --config Release --target install
```

## Build the JAR Package Using Maven

```
mvn package
```

## Test the JAR Package for Barcode Reading
1. Set the license key in `Test.java`:

    ```java
    barcodeReader.setLicense("LICENSE-KEY");
    ```

2. Run the test:
    ```bash
    java -cp target/barcode-1.0.0.jar Test.java <image file>
    ```

