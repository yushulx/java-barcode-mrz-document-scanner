package com.example.litecam;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dynamsoft.core.EnumErrorCode;
import com.dynamsoft.core.EnumImagePixelFormat;
import com.dynamsoft.cvr.CaptureVisionRouter;
import com.dynamsoft.cvr.CapturedResult;
import com.dynamsoft.cvr.EnumPresetTemplate;
import com.dynamsoft.dbr.BarcodeResultItem;
import com.dynamsoft.dbr.DecodedBarcodesResult;
import com.dynamsoft.license.LicenseError;
import com.dynamsoft.license.LicenseException;
import com.dynamsoft.license.LicenseManager;
import com.dynamsoft.core.basic_structures.ImageData;
import com.dynamsoft.core.basic_structures.Quadrilateral;

/**
 * Enhanced LiteCam Barcode Scanner with ZXing integration and visual overlays
 * Supports both camera and file modes with proper threading architecture
 */
public class BarcodeScanner extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(BarcodeScanner.class);

    // Inner class to hold detected barcode information
    private static class DetectedBarcode {
        final String text;
        final String format;
        final ResultPoint[] resultPoints;

        DetectedBarcode(String text, String format, ResultPoint[] resultPoints) {
            this.text = text;
            this.format = format;
            this.resultPoints = resultPoints;
        }
    }

    // Mode enum
    private enum Mode {
        CAMERA, FILE
    }

    // Scanner type enum
    private enum ScannerType {
        ZXING("ZXing"),
        DYNAMSOFT("Dynamsoft Barcode Reader");

        private final String displayName;

        ScannerType(String displayName) {
            this.displayName = displayName;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    // Camera components
    private LiteCam cam;
    private int cameraIndex;
    private BufferedImage img;
    private ByteBuffer buffer;

    // File mode components
    private BufferedImage fileImage;

    // Threading components
    private ExecutorService barcodeWorker;
    private AtomicBoolean isRunning;
    private Timer frameTimer;

    // Barcode detection components
    private CaptureVisionRouter cvRouter;
    private MultiFormatReader zxingReader;
    private GenericMultipleBarcodeReader multiReader;
    private ScannerType currentScannerType = ScannerType.ZXING;

    // Thread-safe barcode results (replacing queue-based approach)
    private volatile List<DetectedBarcode> latestBarcodeResults = new ArrayList<>();
    private final Object resultsLock = new Object();

    // Visual overlay settings
    private boolean showOverlay = true;
    private Color overlayColor = Color.GREEN;
    private int overlayThickness = 2;

    // UI components
    private JTextArea resultsArea;
    private JLabel statusLabel;
    private JButton clearResultsButton;
    private JButton loadFileButton;
    private JButton switchModeButton;
    private JLabel fpsLabel;
    private CameraPanel cameraPanel;
    private JComboBox<ScannerType> scannerDropdown;

    // Application state
    private Mode currentMode = Mode.CAMERA;
    private List<String> scannedResults = new ArrayList<>();
    private long frameCount = 0;
    private long lastFpsTime = System.currentTimeMillis();

    // Current overlay data
    private List<DetectedBarcode> currentOverlayBarcodes = new ArrayList<>();
    private final Object overlayLock = new Object();

    // Remember last directory for file chooser
    private File lastDirectory;

    public BarcodeScanner(int cameraIndex) {
        this.cameraIndex = cameraIndex;
        initializeBarcodeReaders();
        initializeThreading();
        initializeCamera(cameraIndex);
        initializeUI();
        startWorkerThread();
        startRenderingThread();
    }

    public BarcodeScanner() {
        // File mode constructor
        initializeBarcodeReaders();
        initializeThreading();
        initializeUI();
        switchToFileMode();
        startRenderingThread();
    }

    private void initializeBarcodeReaders() {
        // Initialize ZXing
        try {
            cvRouter = new CaptureVisionRouter();
            zxingReader = new MultiFormatReader();
            multiReader = new GenericMultipleBarcodeReader(zxingReader);
            logger.info("ZXing initialized successfully with multiple barcode support");
        } catch (Exception e) {
            logger.warn("Failed to initialize ZXing: {}", e.getMessage());
            zxingReader = null;
            multiReader = null;
        }
    }

    private void initializeThreading() {
        barcodeWorker = Executors.newSingleThreadExecutor();
        isRunning = new AtomicBoolean(true);
    }

    private void initializeCamera(int cameraIndex) {
        try {
            cam = new LiteCam();
            String[] devices = LiteCam.listDevices();
            logger.info("Available cameras:");
            for (int i = 0; i < devices.length; i++) {
                logger.info("  {}: {}", i, devices[i]);
            }

            if (cameraIndex >= devices.length) {
                throw new IllegalArgumentException("Camera index " + cameraIndex + " not available");
            }

            cam.openDevice(cameraIndex);
            int w = cam.getWidth();
            int h = cam.getHeight();
            img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            buffer = ByteBuffer.allocateDirect(w * h * 3);

            logger.info("Camera initialized: {}x{}", w, h);
            currentMode = Mode.CAMERA;
        } catch (Exception e) {
            logger.error("Failed to initialize camera", e);
            throw new RuntimeException("Camera initialization failed", e);
        }
    }

    private void initializeUI() {
        setLayout(new BorderLayout());

        // Create camera panel (left side)
        cameraPanel = new CameraPanel();
        cameraPanel.setPreferredSize(new java.awt.Dimension(1920, 1080));
        cameraPanel.setBorder(BorderFactory.createTitledBorder("Display"));
        add(cameraPanel, BorderLayout.CENTER);

        // Create control and results panel (right side)
        JPanel rightPanel = createRightPanel();
        add(rightPanel, BorderLayout.EAST);

        // Status bar
        statusLabel = new JLabel("Ready");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(statusLabel, BorderLayout.NORTH);

        // Enable overlay by default
        showOverlay = true;

        // Enable drag-and-drop for file mode
        setupDragAndDrop();
    }

    // Inner class for camera panel
    private class CameraPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            BufferedImage displayImage = getCurrentDisplayImage();
            if (displayImage != null) {
                Graphics2D g2d = (Graphics2D) g.create();

                // Scale image to fit panel
                int panelWidth = getWidth();
                int panelHeight = getHeight();
                int imgWidth = displayImage.getWidth();
                int imgHeight = displayImage.getHeight();

                double scaleX = (double) panelWidth / imgWidth;
                double scaleY = (double) panelHeight / imgHeight;
                double scale = Math.min(scaleX, scaleY);

                int scaledWidth = (int) (imgWidth * scale);
                int scaledHeight = (int) (imgHeight * scale);
                int x = (panelWidth - scaledWidth) / 2;
                int y = (panelHeight - scaledHeight) / 2;

                g2d.drawImage(displayImage, x, y, scaledWidth, scaledHeight, null);

                // Draw barcode overlays
                if (showOverlay) {
                    drawBarcodeOverlays(g2d, x, y, scale);
                }

                g2d.dispose();
            } else {
                // Draw placeholder
                g.setColor(Color.DARK_GRAY);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.WHITE);
                String text = currentMode == Mode.FILE ? "Load an image file" : "No camera feed";
                FontMetrics fm = g.getFontMetrics();
                int textWidth = fm.stringWidth(text);
                int textHeight = fm.getHeight();
                g.drawString(text, (getWidth() - textWidth) / 2, (getHeight() - textHeight) / 2);
            }
        }
    }

    private BufferedImage getCurrentDisplayImage() {
        return currentMode == Mode.CAMERA ? img : fileImage;
    }

    private JPanel createRightPanel() {
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setPreferredSize(new java.awt.Dimension(320, 0));

        // Control panel at top
        JPanel controlPanel = createControlPanel();
        rightPanel.add(controlPanel, BorderLayout.NORTH);

        // Results panel in center
        JPanel resultsPanel = createResultsPanel();
        rightPanel.add(resultsPanel, BorderLayout.CENTER);

        return rightPanel;
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Controls"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Scanner selection dropdown
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Scanner:"), gbc);

        gbc.gridx = 1;
        scannerDropdown = new JComboBox<>(ScannerType.values());
        scannerDropdown.setSelectedItem(currentScannerType);
        scannerDropdown.addActionListener(this::onScannerChanged);
        panel.add(scannerDropdown, gbc);

        // Mode switching
        gbc.gridx = 0;
        gbc.gridy++;
        gbc.gridwidth = 2;
        switchModeButton = new JButton("Switch to File Mode");
        switchModeButton.addActionListener(this::switchMode);
        panel.add(switchModeButton, gbc);

        // File loading (only visible in file mode)
        gbc.gridy++;
        loadFileButton = new JButton("Load Image File");
        loadFileButton.addActionListener(this::loadImageFile);
        loadFileButton.setVisible(false);
        panel.add(loadFileButton, gbc);

        // Clear results button
        gbc.gridy++;
        clearResultsButton = new JButton("Clear Results");
        clearResultsButton.addActionListener(e -> clearResults());
        panel.add(clearResultsButton, gbc);

        // FPS display (only for camera mode)
        gbc.gridy++;
        fpsLabel = new JLabel("FPS: 0");
        panel.add(fpsLabel, gbc);

        return panel;
    }

    private JPanel createResultsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Barcode Results"));
        panel.setPreferredSize(new java.awt.Dimension(0, 200));

        resultsArea = new JTextArea();
        resultsArea.setEditable(false);
        resultsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JScrollPane scrollPane = new JScrollPane(resultsArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private void switchMode(ActionEvent e) {
        if (currentMode == Mode.CAMERA) {
            switchToFileMode();
        } else {
            switchToCameraMode();
        }
    }

    private void onScannerChanged(ActionEvent e) {
        ScannerType newType = (ScannerType) scannerDropdown.getSelectedItem();
        if (newType != currentScannerType) {
            currentScannerType = newType;
            logger.info("Scanner changed to: {}", currentScannerType);
        }
    }

    private void switchToFileMode() {
        // Close camera when switching to file mode
        if (cam != null && cam.isOpen()) {
            cam.close();
            logger.info("Camera closed when switching to file mode");
        }

        currentMode = Mode.FILE;
        switchModeButton.setText("Switch to Camera Mode");
        loadFileButton.setVisible(true);
        fpsLabel.setVisible(false);
        cameraPanel.setBorder(BorderFactory.createTitledBorder("Image Display"));
        clearOverlay();
        repaintCamera();
        updateStatus();
    }

    private void switchToCameraMode() {
        try {
            // If camera is not open, try to reopen it
            if (cam == null || !cam.isOpen()) {
                if (cam != null) {
                    cam.close(); // Close any existing camera instance
                }
                cam = new LiteCam();
                cam.openDevice(cameraIndex);
                cam.setResolution(640, 480);
                logger.info("Camera reopened for camera mode: {}x{}", cam.getWidth(), cam.getHeight());
            }

            currentMode = Mode.CAMERA;
            switchModeButton.setText("Switch to File Mode");
            loadFileButton.setVisible(false);
            fpsLabel.setVisible(true);
            cameraPanel.setBorder(BorderFactory.createTitledBorder("Camera Feed"));
            clearOverlay();
            repaintCamera();
            updateStatus();

            // Restart worker thread for camera mode
            startWorkerThread();

        } catch (Exception e) {
            logger.error("Failed to switch to camera mode", e);
            JOptionPane.showMessageDialog(this, "Camera not available: " + e.getMessage(), "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadImageFile(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();

        // Remember the last directory
        if (lastDirectory != null && lastDirectory.exists()) {
            fileChooser.setCurrentDirectory(lastDirectory);
        }

        fileChooser.setFileFilter(new FileNameExtensionFilter("Image files", "jpg", "jpeg", "png", "bmp", "gif"));

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selectedFile = fileChooser.getSelectedFile();

            // Remember the directory for next time
            lastDirectory = selectedFile.getParentFile();

            try {
                fileImage = ImageIO.read(selectedFile);
                logger.info("Loaded image: {}x{}", fileImage.getWidth(), fileImage.getHeight());

                // Perform immediate scan on loaded image and display overlay
                List<DetectedBarcode> detections = detectBarcodes(fileImage);

                // Update both overlay and latest results for rendering thread
                synchronized (overlayLock) {
                    currentOverlayBarcodes.clear();
                    currentOverlayBarcodes.addAll(detections);
                }

                synchronized (resultsLock) {
                    latestBarcodeResults.clear();
                    latestBarcodeResults.addAll(detections);
                }

                updateResultsDisplay(detections);
                repaintCamera();

            } catch (Exception ex) {
                logger.error("Failed to load image file", ex);
                JOptionPane.showMessageDialog(this, "Failed to load image: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void startWorkerThread() {
        if (currentMode != Mode.CAMERA)
            return;

        barcodeWorker.submit(() -> {
            while (isRunning.get()) {
                try {
                    if (cam != null && cam.isOpen()) {
                        // Capture frame
                        if (cam.grabFrame(buffer)) {
                            // Update display image
                            byte[] data = ((java.awt.image.DataBufferByte) img.getRaster().getDataBuffer()).getData();
                            buffer.rewind();
                            int len = Math.min(data.length, buffer.remaining());
                            buffer.get(data, 0, len);
                            buffer.rewind();

                            // Perform barcode detection
                            List<DetectedBarcode> detections = detectBarcodes(img);

                            // Update latest results (thread-safe)
                            synchronized (resultsLock) {
                                latestBarcodeResults = new ArrayList<>(detections);
                            }

                            // Update results display if there are new detections
                            if (!detections.isEmpty()) {
                                updateResultsDisplay(detections);
                            }
                        }
                    }

                    Thread.sleep(33); // ~30 FPS
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in worker thread", e);
                }
            }
        });
    }

    private void startRenderingThread() {
        frameTimer = new Timer(33, e -> {
            // Get latest barcode results (thread-safe)
            List<DetectedBarcode> currentResults;
            synchronized (resultsLock) {
                currentResults = new ArrayList<>(latestBarcodeResults);
            }

            // Update overlay with current results (even if empty to clear overlay when no
            // barcodes)
            synchronized (overlayLock) {
                currentOverlayBarcodes.clear();
                currentOverlayBarcodes.addAll(currentResults);
            }

            // Update FPS and repaint
            if (currentMode == Mode.CAMERA) {
                updateFPS();
            }
            repaintCamera();
        });
        frameTimer.start();
    }

    private List<DetectedBarcode> detectBarcodes(BufferedImage image) {
        List<DetectedBarcode> detections = new ArrayList<>();

        if (image == null || (zxingReader == null && multiReader == null))
            return detections;

        switch (currentScannerType) {
            case ZXING:
                return detectWithZXing(image);
            case DYNAMSOFT:
                return detectWithDBR(image);
            default:
                return detections;
        }
    }

    private List<DetectedBarcode> detectWithDBR(BufferedImage image) {
        List<DetectedBarcode> detections = new ArrayList<>();

        if (image == null || cvRouter == null)
            return detections;

        try {
            // Convert BufferedImage to byte array for Dynamsoft
            int width = image.getWidth();
            int height = image.getHeight();

            // Convert to RGB format
            BufferedImage rgbImage = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g2d = rgbImage.createGraphics();
            g2d.drawImage(image, 0, 0, null);
            g2d.dispose();

            // Get byte array from image
            byte[] imageBytes = ((java.awt.image.DataBufferByte) rgbImage.getRaster().getDataBuffer()).getData();

            // Create ImageData object for Dynamsoft - use reflection to find correct
            // constructor
            ImageData imageData = new ImageData(imageBytes, width, height, width * 3, EnumImagePixelFormat.IPF_BGR_888,
                    0, null);

            // Capture barcodes using ImageData
            CapturedResult result = cvRouter.capture(imageData, EnumPresetTemplate.PT_READ_BARCODES);
            DecodedBarcodesResult barcodeResult = result != null ? result.getDecodedBarcodesResult() : null;
            BarcodeResultItem[] items = barcodeResult != null ? barcodeResult.getItems() : null;

            if (items != null) {
                for (int i = 0; i < items.length; i++) {
                    BarcodeResultItem item = items[i];

                    // Convert Dynamsoft location to ZXing ResultPoint format
                    Quadrilateral location = item.getLocation();
                    com.dynamsoft.core.basic_structures.Point[] dbrPoints = location.points;

                    // Create ZXing-compatible result points
                    ResultPoint[] resultPoints = new ResultPoint[dbrPoints.length];
                    for (int j = 0; j < dbrPoints.length; j++) {
                        float x = (float) dbrPoints[j].getX();
                        float y = (float) dbrPoints[j].getY();
                        resultPoints[j] = new ResultPoint(x, y);
                    }

                    // Create detection and add to list
                    DetectedBarcode detection = new DetectedBarcode(
                            item.getText(),
                            item.getFormatString(),
                            resultPoints);
                    detections.add(detection);
                }
            }

        } catch (Exception e) {
            logger.error("DBR detection failed: {}", e.getMessage());
        }

        return detections;
    }

    private List<DetectedBarcode> detectWithZXing(BufferedImage image) {
        List<DetectedBarcode> detections = new ArrayList<>();

        if (image == null || zxingReader == null || multiReader == null)
            return detections;

        try {
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            // Try to detect multiple barcodes first
            try {
                Result[] results = multiReader.decodeMultiple(bitmap);
                for (Result result : results) {
                    DetectedBarcode detection = new DetectedBarcode(
                            result.getText(),
                            result.getBarcodeFormat().toString(),
                            result.getResultPoints());
                    detections.add(detection);
                }
                if (detections.size() > 0) {
                    return detections; // Successfully found multiple barcodes
                }
            } catch (NotFoundException e) {
                // No multiple barcodes found, try single barcode detection
            }

            // Fallback to single barcode detection
            try {
                Result result = zxingReader.decode(bitmap);
                DetectedBarcode detection = new DetectedBarcode(
                        result.getText(),
                        result.getBarcodeFormat().toString(),
                        result.getResultPoints());
                detections.add(detection);
            } catch (NotFoundException e) {
                // No barcode found - this is normal
            }

        } catch (Exception e) {
            logger.debug("ZXing detection failed: {}", e.getMessage());
        }

        return detections;
    }

    private void updateResultsDisplay(List<DetectedBarcode> detections) {
        for (DetectedBarcode detection : detections) {
            String scannerType = currentScannerType == ScannerType.ZXING ? "ZXing" : "Dynamsoft";
            String barcodeInfo = String.format("[%s] %s (%s)",
                    scannerType, detection.text, detection.format);

            if (!scannedResults.contains(barcodeInfo)) {
                scannedResults.add(barcodeInfo);
                SwingUtilities.invokeLater(() -> {
                    resultsArea.append(String.format("[%s] %s%n",
                            java.time.LocalTime.now().toString(), barcodeInfo));
                    resultsArea.setCaretPosition(resultsArea.getDocument().getLength());
                    updateStatus();
                });
            }
        }
    }

    private void clearResults() {
        scannedResults.clear();
        clearOverlay();
        resultsArea.setText("");
        repaintCamera();
        updateStatus();
    }

    private void clearOverlay() {
        synchronized (overlayLock) {
            currentOverlayBarcodes.clear();
        }
        synchronized (resultsLock) {
            latestBarcodeResults.clear();
        }
    }

    private void updateFPS() {
        if (currentMode == Mode.CAMERA) {
            frameCount++;
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastFpsTime >= 1000) {
                double fps = frameCount * 1000.0 / (currentTime - lastFpsTime);
                SwingUtilities.invokeLater(() -> fpsLabel.setText(String.format("FPS: %.1f", fps)));
                frameCount = 0;
                lastFpsTime = currentTime;
            }
        }
    }

    private void updateStatus() {
        String status = String.format("Detected: %d | %s | Ready",
                scannedResults.size(),
                currentMode == Mode.CAMERA ? "Camera" : "File");
        statusLabel.setText(status);
    }

    private void repaintCamera() {
        if (cameraPanel != null) {
            cameraPanel.repaint();
        }
    }

    private void drawBarcodeOverlays(Graphics2D g2d, int offsetX, int offsetY, double scale) {
        synchronized (overlayLock) {
            for (DetectedBarcode detection : currentOverlayBarcodes) {
                drawBarcodeContour(g2d, detection, offsetX, offsetY, scale);
            }
        }
    }

    private void drawBarcodeContour(Graphics2D g2d, DetectedBarcode detection, int offsetX, int offsetY, double scale) {
        ResultPoint[] points = detection.resultPoints;
        if (points == null || points.length < 2)
            return;

        // Set up graphics for smooth drawing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setStroke(new BasicStroke(overlayThickness));
        g2d.setColor(overlayColor);

        // Convert result points to screen coordinates
        int[] xPoints = new int[points.length];
        int[] yPoints = new int[points.length];

        for (int i = 0; i < points.length; i++) {
            if (points[i] != null) {
                xPoints[i] = offsetX + (int) (points[i].getX() * scale);
                yPoints[i] = offsetY + (int) (points[i].getY() * scale);
            }
        }

        // Draw contour connecting the points
        if (points.length >= 4) {
            // Draw connected lines for 4+ points (QR codes, Data Matrix, etc.)
            for (int i = 0; i < points.length; i++) {
                int nextIndex = (i + 1) % points.length;
                g2d.drawLine(xPoints[i], yPoints[i], xPoints[nextIndex], yPoints[nextIndex]);
            }

            // Draw corner markers
            int cornerSize = 8;
            for (int i = 0; i < points.length; i++) {
                g2d.fillOval(xPoints[i] - cornerSize / 2, yPoints[i] - cornerSize / 2, cornerSize, cornerSize);
            }
        } else if (points.length >= 2) {
            // For linear barcodes, create a rectangle from endpoints and draw contour
            int minX = Math.min(xPoints[0], xPoints[1]);
            int maxX = Math.max(xPoints[0], xPoints[1]);
            int minY = Math.min(yPoints[0], yPoints[1]);
            int maxY = Math.max(yPoints[0], yPoints[1]);

            // Expand to create a visible rectangle
            int padding = 20;
            int[] rectX = { minX - padding, maxX + padding, maxX + padding, minX - padding };
            int[] rectY = { minY - padding, minY - padding, maxY + padding, maxY + padding };

            // Draw connected rectangle contour
            for (int i = 0; i < 4; i++) {
                int nextIndex = (i + 1) % 4;
                g2d.drawLine(rectX[i], rectY[i], rectX[nextIndex], rectY[nextIndex]);
            }

            // Draw corner markers
            int cornerSize = 6;
            for (int i = 0; i < 4; i++) {
                g2d.fillOval(rectX[i] - cornerSize / 2, rectY[i] - cornerSize / 2, cornerSize, cornerSize);
            }
        }

        // Draw text label
        String text = String.format("%s (%s)", detection.text, detection.format);
        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int textHeight = fm.getHeight();

        // Calculate label position (above the first point)
        int labelX = xPoints[0] - 5;
        int labelY = yPoints[0] - 10;

        // Draw background for text
        g2d.setColor(new Color(0, 0, 0, 180));
        g2d.fillRect(labelX - 5, labelY - textHeight, textWidth + 10, textHeight + 5);

        // Draw text
        g2d.setColor(Color.WHITE);
        g2d.drawString(text, labelX, labelY - 5);
    }

    @Override
    public java.awt.Dimension getPreferredSize() {
        return new java.awt.Dimension(1280, 720);
    }

    public void cleanup() {
        isRunning.set(false);
        if (frameTimer != null) {
            frameTimer.stop();
        }
        if (barcodeWorker != null) {
            barcodeWorker.shutdown();
        }
        if (cam != null) {
            cam.close();
        }
    }

    private void setupDragAndDrop() {
        // Enable drag and drop on the camera panel
        new DropTarget(cameraPanel, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (currentMode == Mode.FILE && dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                // No action needed
            }

            @Override
            public void dropActionChanged(DropTargetDragEvent dtde) {
                // No action needed
            }

            @Override
            public void dragExit(DropTargetEvent dte) {
                // No action needed
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                if (currentMode != Mode.FILE) {
                    dtde.rejectDrop();
                    return;
                }

                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable transferable = dtde.getTransferable();

                    if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);

                        if (!files.isEmpty()) {
                            File file = files.get(0); // Take the first file
                            if (isImageFile(file)) {
                                loadImageFromFile(file);
                                dtde.dropComplete(true);
                            } else {
                                JOptionPane.showMessageDialog(BarcodeScanner.this,
                                        "Please drop an image file (jpg, jpeg, png, bmp, gif, tiff)",
                                        "Invalid File Type", JOptionPane.WARNING_MESSAGE);
                                dtde.dropComplete(false);
                            }
                        } else {
                            dtde.dropComplete(false);
                        }
                    } else {
                        dtde.dropComplete(false);
                    }
                } catch (Exception e) {
                    logger.error("Error handling drag and drop", e);
                    dtde.dropComplete(false);
                }
            }
        });
    }

    private boolean isImageFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                name.endsWith(".png") || name.endsWith(".bmp") ||
                name.endsWith(".gif") || name.endsWith(".tiff");
    }

    private void loadImageFromFile(File file) {
        try {
            // Remember the directory for next time
            lastDirectory = file.getParentFile();

            fileImage = ImageIO.read(file);
            logger.info("Loaded image via drag-and-drop: {}x{}", fileImage.getWidth(), fileImage.getHeight());

            // Perform immediate scan on loaded image and display overlay
            List<DetectedBarcode> detections = detectBarcodes(fileImage);

            // Update both overlay and latest results for rendering thread
            synchronized (overlayLock) {
                currentOverlayBarcodes.clear();
                currentOverlayBarcodes.addAll(detections);
            }

            synchronized (resultsLock) {
                latestBarcodeResults.clear();
                latestBarcodeResults.addAll(detections);
            }

            updateResultsDisplay(detections);
            repaintCamera();

        } catch (Exception e) {
            logger.error("Failed to load dropped image file", e);
            JOptionPane.showMessageDialog(this, "Failed to load image: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void main(String[] args) {
        int errorCode = 0;
        String errorMsg = "";

        try {
            LicenseError licenseError = LicenseManager.initLicense(
                    "DLS2eyJoYW5kc2hha2VDb2RlIjoiMjAwMDAxLTE2NDk4Mjk3OTI2MzUiLCJvcmdhbml6YXRpb25JRCI6IjIwMDAwMSIsInNlc3Npb25QYXNzd29yZCI6IndTcGR6Vm05WDJrcEQ5YUoifQ==");
            if (licenseError.getErrorCode() != EnumErrorCode.EC_OK) {
                errorCode = licenseError.getErrorCode();
                errorMsg = licenseError.getErrorString();
            }
        } catch (LicenseException e) {
            errorCode = e.getErrorCode();
            errorMsg = e.getErrorString();
        }

        if (errorCode != EnumErrorCode.EC_OK) {
            System.out.println("License initialization failed: ErrorCode: " + errorCode + ", ErrorString: " + errorMsg);
        }

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("LiteCam Barcode Scanner");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            try {
                BarcodeScanner scanner;
                if (args.length > 0 && args[0].equals("--file")) {
                    scanner = new BarcodeScanner(); // File mode
                } else {
                    scanner = new BarcodeScanner(0); // Camera mode
                }

                frame.setContentPane(scanner);
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);

                // Add shutdown hook for cleanup
                Runtime.getRuntime().addShutdownHook(new Thread(scanner::cleanup));

            } catch (Exception e) {
                logger.error("Failed to start barcode scanner", e);
                JOptionPane.showMessageDialog(null,
                        "Failed to start barcode scanner: " + e.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
