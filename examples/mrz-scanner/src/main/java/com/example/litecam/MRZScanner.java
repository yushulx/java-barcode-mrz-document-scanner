package com.example.litecam;

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
import java.awt.image.DataBufferByte;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dynamsoft.core.EnumErrorCode;
import com.dynamsoft.core.EnumImagePixelFormat;
import com.dynamsoft.cvr.CaptureVisionRouter;
import com.dynamsoft.cvr.CapturedResult;
import com.dynamsoft.dcp.EnumValidationStatus;
import com.dynamsoft.dcp.ParsedResult;
import com.dynamsoft.dcp.ParsedResultItem;
import com.dynamsoft.dlr.RecognizedTextLinesResult;
import com.dynamsoft.dlr.TextLineResultItem;
import com.dynamsoft.license.LicenseError;
import com.dynamsoft.license.LicenseException;
import com.dynamsoft.license.LicenseManager;
import com.dynamsoft.core.basic_structures.ImageData;
import com.dynamsoft.core.basic_structures.Quadrilateral;

/**
 * MRZ Scanner with camera and file modes.
 * Detects and parses Machine-Readable Zone data from passports and travel documents
 * using the Dynamsoft Capture Vision SDK.
 */
public class MRZScanner extends JPanel {
    private static final Logger logger = LoggerFactory.getLogger(MRZScanner.class);

    private static final String MRZ_TEMPLATE = "ReadPassportAndId";

    // -------------------------------------------------------------------------
    // Inner class: parsed MRZ result
    // -------------------------------------------------------------------------
    private static class MRZResult {
        final String docType;
        final String docId;
        final String surname;
        final String givenNames;
        final String nationality;
        final String issuingState;
        final String dateOfBirth;
        final String dateOfExpiry;
        final String sex;
        final boolean isPassport;
        final Quadrilateral location;

        MRZResult(ParsedResultItem item, Quadrilateral location) {
            docType     = item.getCodeType();
            isPassport  = "MRTD_TD3_PASSPORT".equals(docType);

            String idField = isPassport ? "passportNumber" : "documentNumber";
            docId        = validatedField(item, idField);
            surname      = validatedField(item, "primaryIdentifier");
            givenNames   = validatedField(item, "secondaryIdentifier");
            nationality  = validatedField(item, "nationality");
            issuingState = validatedField(item, "issuingState");
            dateOfBirth  = validatedField(item, "dateOfBirth");
            dateOfExpiry = validatedField(item, "dateOfExpiry");
            sex          = validatedField(item, "sex");
            this.location = location;
        }

        private static String validatedField(ParsedResultItem item, String fieldName) {
            String value = item.getFieldValue(fieldName);
            if (value == null) return null;
            if (item.getFieldValidationStatus(fieldName) == EnumValidationStatus.VS_FAILED) return null;
            return value;
        }

        /** Format fields for display in the results panel. */
        String toDisplayString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Document Type: ").append(docType != null ? docType : "Unknown").append("\n");
            appendField(sb, "Document ID:   ", docId);
            appendField(sb, "Surname:       ", surname);
            appendField(sb, "Given Names:   ", givenNames);
            appendField(sb, "Nationality:   ", nationality);
            appendField(sb, "Issuing State: ", issuingState);
            appendField(sb, "Date of Birth: ", dateOfBirth);
            appendField(sb, "Date of Expiry:", dateOfExpiry);
            appendField(sb, "Sex:           ", sex);
            return sb.toString();
        }

        private void appendField(StringBuilder sb, String label, String value) {
            if (value != null && !value.isBlank()) {
                sb.append(label).append(" ").append(value).append("\n");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------
    private enum Mode {
        CAMERA, FILE
    }

    // -------------------------------------------------------------------------
    // Camera fields
    // -------------------------------------------------------------------------
    private LiteCam cam;
    private final int cameraIndex;
    private BufferedImage img;
    private ByteBuffer buffer;

    // -------------------------------------------------------------------------
    // File mode fields
    // -------------------------------------------------------------------------
    private BufferedImage fileImage;

    // -------------------------------------------------------------------------
    // Threading fields
    // -------------------------------------------------------------------------
    private ExecutorService mrzWorker;
    private AtomicBoolean isRunning;
    private Timer frameTimer;

    // -------------------------------------------------------------------------
    // MRZ detection
    // -------------------------------------------------------------------------
    private CaptureVisionRouter cvRouter;

    // Thread-safe result storage
    private volatile MRZResult latestMRZResult = null;
    private final Object resultsLock = new Object();

    // -------------------------------------------------------------------------
    // UI fields
    // -------------------------------------------------------------------------
    private JTextArea resultsArea;
    private JLabel statusLabel;
    private JButton clearResultsButton;
    private JButton loadFileButton;
    private JButton switchModeButton;
    private JLabel fpsLabel;
    private CameraPanel cameraPanel;

    // -------------------------------------------------------------------------
    // App state
    // -------------------------------------------------------------------------
    private Mode currentMode = Mode.CAMERA;

    private volatile MRZResult currentOverlayResult = null;
    private final Object overlayLock = new Object();

    private long frameCount = 0;
    private long lastFpsTime = System.currentTimeMillis();
    private File lastDirectory;

    // =========================================================================
    // Constructors
    // =========================================================================

    /** Camera-mode constructor. Opens the specified camera device. */
    public MRZScanner(int cameraIndex) {
        this.cameraIndex = cameraIndex;
        initializeMRZReader();
        initializeThreading();
        initializeCamera(cameraIndex);
        initializeUI();
        startWorkerThread();
        startRenderingThread();
    }

    /** File-mode constructor. No camera is opened. */
    public MRZScanner() {
        this.cameraIndex = 0;
        initializeMRZReader();
        initializeThreading();
        initializeUI();
        switchToFileMode();
        startRenderingThread();
    }

    // =========================================================================
    // Initialization
    // =========================================================================

    private void initializeMRZReader() {
        try {
            cvRouter = new CaptureVisionRouter();
            logger.info("CaptureVisionRouter initialized successfully");
        } catch (Exception e) {
            logger.error("Failed to initialize CaptureVisionRouter", e);
            throw new RuntimeException("MRZ reader initialization failed", e);
        }
    }

    private void initializeThreading() {
        mrzWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mrz-worker");
            t.setDaemon(true);
            return t;
        });
        isRunning = new AtomicBoolean(true);
    }

    private void initializeCamera(int cameraIndex) {
        try {
            cam = new LiteCam();
            String[] devices = LiteCam.listDevices();
            logger.info("Available cameras: {}", devices.length);
            for (int i = 0; i < devices.length; i++) {
                logger.info("  {}: {}", i, devices[i]);
            }

            if (cameraIndex >= devices.length) {
                throw new IllegalArgumentException("Camera index " + cameraIndex + " not available");
            }

            cam.openDevice(cameraIndex);
            int w = cam.getWidth();
            int h = cam.getHeight();
            img    = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
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

        cameraPanel = new CameraPanel();
        cameraPanel.setPreferredSize(new Dimension(1920, 1080));
        cameraPanel.setBorder(BorderFactory.createTitledBorder("Camera Feed"));
        add(cameraPanel, BorderLayout.CENTER);

        JPanel rightPanel = createRightPanel();
        add(rightPanel, BorderLayout.EAST);

        statusLabel = new JLabel("Ready — scanning for MRZ...");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        add(statusLabel, BorderLayout.NORTH);

        setupDragAndDrop();
    }

    // =========================================================================
    // Camera panel (video display + overlay)
    // =========================================================================

    private class CameraPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            BufferedImage displayImage = getCurrentDisplayImage();
            if (displayImage == null) {
                drawPlaceholder(g);
                return;
            }

            Graphics2D g2d = (Graphics2D) g.create();

            int panelW  = getWidth();
            int panelH  = getHeight();
            int imgW    = displayImage.getWidth();
            int imgH    = displayImage.getHeight();

            double scaleX = (double) panelW / imgW;
            double scaleY = (double) panelH / imgH;
            double scale  = Math.min(scaleX, scaleY);

            int scaledW = (int) (imgW * scale);
            int scaledH = (int) (imgH * scale);
            int offX    = (panelW - scaledW) / 2;
            int offY    = (panelH - scaledH) / 2;

            g2d.drawImage(displayImage, offX, offY, scaledW, scaledH, null);
            drawMRZOverlay(g2d, offX, offY, scale);

            g2d.dispose();
        }

        private void drawPlaceholder(Graphics g) {
            g.setColor(Color.DARK_GRAY);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.WHITE);
            String text = currentMode == Mode.FILE
                    ? "Drop an image here or use 'Load Image File'"
                    : "No camera feed";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text,
                    (getWidth()  - fm.stringWidth(text)) / 2,
                    (getHeight() - fm.getHeight())        / 2);
        }
    }

    private BufferedImage getCurrentDisplayImage() {
        return currentMode == Mode.CAMERA ? img : fileImage;
    }

    // =========================================================================
    // Right panel (controls + results)
    // =========================================================================

    private JPanel createRightPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(320, 0));
        panel.add(createControlPanel(),  BorderLayout.NORTH);
        panel.add(createResultsPanel(),  BorderLayout.CENTER);
        return panel;
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Controls"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets    = new Insets(5, 5, 5, 5);
        gbc.anchor    = GridBagConstraints.WEST;
        gbc.fill      = GridBagConstraints.HORIZONTAL;
        gbc.gridwidth = 2;

        gbc.gridy = 0;
        switchModeButton = new JButton("Switch to File Mode");
        switchModeButton.addActionListener(this::switchMode);
        panel.add(switchModeButton, gbc);

        gbc.gridy++;
        loadFileButton = new JButton("Load Image File");
        loadFileButton.addActionListener(this::loadImageFile);
        loadFileButton.setVisible(false);
        panel.add(loadFileButton, gbc);

        gbc.gridy++;
        clearResultsButton = new JButton("Clear Results");
        clearResultsButton.addActionListener(e -> clearResults());
        panel.add(clearResultsButton, gbc);

        gbc.gridy++;
        fpsLabel = new JLabel("FPS: 0");
        panel.add(fpsLabel, gbc);

        return panel;
    }

    private JPanel createResultsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("MRZ Results"));

        resultsArea = new JTextArea();
        resultsArea.setEditable(false);
        resultsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        resultsArea.setBackground(new Color(30, 30, 30));
        resultsArea.setForeground(Color.GREEN);
        resultsArea.setMargin(new Insets(8, 8, 8, 8));

        JScrollPane scrollPane = new JScrollPane(resultsArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    // =========================================================================
    // Mode switching
    // =========================================================================

    private void switchMode(ActionEvent e) {
        if (currentMode == Mode.CAMERA) {
            switchToFileMode();
        } else {
            switchToCameraMode();
        }
    }

    private void switchToFileMode() {
        if (cam != null && cam.isOpen()) {
            cam.close();
            logger.info("Camera closed");
        }
        currentMode = Mode.FILE;
        switchModeButton.setText("Switch to Camera Mode");
        loadFileButton.setVisible(true);
        fpsLabel.setVisible(false);
        cameraPanel.setBorder(BorderFactory.createTitledBorder("Image Display"));
        clearResults();
    }

    private void switchToCameraMode() {
        try {
            if (cam == null || !cam.isOpen()) {
                if (cam != null) cam.close();
                cam = new LiteCam();
                cam.openDevice(cameraIndex);
                cam.setResolution(640, 480);
                int w = cam.getWidth();
                int h = cam.getHeight();
                img    = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                buffer = ByteBuffer.allocateDirect(w * h * 3);
                logger.info("Camera reopened: {}x{}", w, h);
            }
            currentMode = Mode.CAMERA;
            switchModeButton.setText("Switch to File Mode");
            loadFileButton.setVisible(false);
            fpsLabel.setVisible(true);
            cameraPanel.setBorder(BorderFactory.createTitledBorder("Camera Feed"));
            clearResults();
            startWorkerThread();

        } catch (Exception e) {
            logger.error("Failed to switch to camera mode", e);
            JOptionPane.showMessageDialog(this,
                    "Camera not available: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // File loading
    // =========================================================================

    private void loadImageFile(ActionEvent e) {
        JFileChooser chooser = new JFileChooser();
        if (lastDirectory != null && lastDirectory.exists()) {
            chooser.setCurrentDirectory(lastDirectory);
        }
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Image files", "jpg", "jpeg", "png", "bmp", "tif", "tiff"));

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadImageFromFile(chooser.getSelectedFile());
        }
    }

    private void loadImageFromFile(File file) {
        try {
            lastDirectory = file.getParentFile();
            fileImage = ImageIO.read(file);
            if (fileImage == null) {
                throw new Exception("Could not decode image; unsupported format.");
            }
            logger.info("Loaded image: {} ({}x{})",
                    file.getName(), fileImage.getWidth(), fileImage.getHeight());

            MRZResult result = detectMRZ(fileImage);
            updateAfterDetection(result);

        } catch (Exception ex) {
            logger.error("Failed to load image file", ex);
            JOptionPane.showMessageDialog(this,
                    "Failed to load image: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // Worker / rendering threads
    // =========================================================================

    private void startWorkerThread() {
        if (currentMode != Mode.CAMERA) return;

        mrzWorker.submit(() -> {
            while (isRunning.get() && currentMode == Mode.CAMERA) {
                try {
                    if (cam != null && cam.isOpen()) {
                        if (cam.grabFrame(buffer)) {
                            byte[] data = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
                            buffer.rewind();
                            int len = Math.min(data.length, buffer.remaining());
                            buffer.get(data, 0, len);
                            buffer.rewind();

                            MRZResult result = detectMRZ(img);
                            synchronized (resultsLock) {
                                latestMRZResult = result;
                            }
                            if (result != null) {
                                SwingUtilities.invokeLater(() -> updateMRZDisplay(result));
                            }
                        }
                    }
                    Thread.sleep(33); // ~30 FPS
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in MRZ worker thread", e);
                }
            }
        });
    }

    private void startRenderingThread() {
        frameTimer = new Timer(33, e -> {
            MRZResult current;
            synchronized (resultsLock) {
                current = latestMRZResult;
            }
            synchronized (overlayLock) {
                currentOverlayResult = current;
            }
            if (currentMode == Mode.CAMERA) {
                updateFPS();
            }
            repaintCamera();
        });
        frameTimer.start();
    }

    // =========================================================================
    // MRZ detection
    // =========================================================================

    /**
     * Runs MRZ detection on the given image using Dynamsoft Capture Vision.
     *
     * @param image source image (must be non-null)
     * @return parsed MRZResult, or {@code null} if no MRZ was found
     */
    private MRZResult detectMRZ(BufferedImage image) {
        if (image == null || cvRouter == null) return null;

        try {
            int width  = image.getWidth();
            int height = image.getHeight();

            // Convert to BGR byte array for Dynamsoft
            BufferedImage bgrImage = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g2d = bgrImage.createGraphics();
            g2d.drawImage(image, 0, 0, null);
            g2d.dispose();

            byte[] bytes = ((DataBufferByte) bgrImage.getRaster().getDataBuffer()).getData();
            ImageData imageData = new ImageData(bytes, width, height, width * 3,
                    EnumImagePixelFormat.IPF_BGR_888, 0, null);

            CapturedResult captured = cvRouter.capture(imageData, MRZ_TEMPLATE);
            if (captured == null) return null;

            if (captured.getErrorCode() != EnumErrorCode.EC_OK
                    && captured.getErrorCode() != EnumErrorCode.EC_UNSUPPORTED_JSON_KEY_WARNING) {
                logger.debug("MRZ capture error {}: {}", captured.getErrorCode(), captured.getErrorString());
                return null;
            }

            RecognizedTextLinesResult recognizedTextLinesResult = captured.getRecognizedTextLinesResult();
            ParsedResult parsedResult = captured.getParsedResult();
            if (parsedResult == null) return null;

            TextLineResultItem[] textLines = recognizedTextLinesResult != null
                    ? recognizedTextLinesResult.getItems() : null;
            ParsedResultItem[] items = parsedResult.getItems();
            if (items == null || items.length == 0) return null;

            Quadrilateral location = textLines != null && textLines.length > 0 ? textLines[0].getLocation() : null;
            return new MRZResult(items[0], location);

        } catch (Exception e) {
            logger.debug("MRZ detection failed: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // UI updates
    // =========================================================================

    private void updateAfterDetection(MRZResult result) {
        synchronized (resultsLock) {
            latestMRZResult = result;
        }
        synchronized (overlayLock) {
            currentOverlayResult = result;
        }
        updateMRZDisplay(result);
        repaintCamera();
    }

    private void updateMRZDisplay(MRZResult result) {
        SwingUtilities.invokeLater(() -> {
            if (result == null) {
                statusLabel.setText("Scanning for MRZ...");
                return;
            }
            resultsArea.setText(result.toDisplayString());
            resultsArea.setCaretPosition(0);
            statusLabel.setText("MRZ detected — " + (result.isPassport ? "Passport" : "ID Document"));
        });
    }

    private void clearResults() {
        synchronized (resultsLock)  { latestMRZResult       = null; }
        synchronized (overlayLock)  { currentOverlayResult  = null; }
        SwingUtilities.invokeLater(() -> {
            resultsArea.setText("");
            statusLabel.setText("Ready — scanning for MRZ...");
            repaintCamera();
        });
    }

    private void updateFPS() {
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            double fps = frameCount * 1000.0 / (now - lastFpsTime);
            SwingUtilities.invokeLater(() -> fpsLabel.setText(String.format("FPS: %.1f", fps)));
            frameCount    = 0;
            lastFpsTime   = now;
        }
    }

    private void repaintCamera() {
        if (cameraPanel != null) {
            cameraPanel.repaint();
        }
    }

    // =========================================================================
    // MRZ overlay drawing
    // =========================================================================

    private void drawMRZOverlay(Graphics2D g2d, int offX, int offY, double scale) {
        MRZResult result;
        synchronized (overlayLock) {
            result = currentOverlayResult;
        }
        if (result == null) return;

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Draw location polygon if the quadrilateral has meaningful coordinates
        if (hasValidLocation(result.location)) {
            drawLocationPolygon(g2d, result.location, offX, offY, scale);
        }

        // Draw banner at top of image area
        drawDetectedBanner(g2d, result, offX, offY, scale);
    }

    private boolean hasValidLocation(Quadrilateral quad) {
        if (quad == null || quad.points == null || quad.points.length < 4) return false;
        for (com.dynamsoft.core.basic_structures.Point p : quad.points) {
            if (p == null) return false;
            if (p.getX() != 0 || p.getY() != 0) return true; // at least one non-origin point
        }
        return false;
    }

    private void drawLocationPolygon(Graphics2D g2d, Quadrilateral quad,
                                     int offX, int offY, double scale) {
        com.dynamsoft.core.basic_structures.Point[] pts = quad.points;
        int n  = pts.length;
        int[] xs = new int[n];
        int[] ys = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = offX + (int) (pts[i].getX() * scale);
            ys[i] = offY + (int) (pts[i].getY() * scale);
        }

        g2d.setStroke(new BasicStroke(2.5f));
        g2d.setColor(new Color(0, 230, 0, 220));
        for (int i = 0; i < n; i++) {
            g2d.drawLine(xs[i], ys[i], xs[(i + 1) % n], ys[(i + 1) % n]);
        }

        // Corner dots
        g2d.setColor(Color.GREEN);
        for (int i = 0; i < n; i++) {
            g2d.fillOval(xs[i] - 4, ys[i] - 4, 8, 8);
        }
    }

    private void drawDetectedBanner(Graphics2D g2d, MRZResult result,
                                    int offX, int offY, double scale) {
        String label = result.isPassport ? "PASSPORT DETECTED" : "ID DOCUMENT DETECTED";
        if (result.docId != null) label += "  |  " + result.docId;

        g2d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        FontMetrics fm  = g2d.getFontMetrics();
        int tw          = fm.stringWidth(label);
        int th          = fm.getHeight();
        int bannerX     = offX + 10;
        int bannerY     = offY + 10;

        // Background
        g2d.setColor(new Color(0, 0, 0, 180));
        g2d.fillRoundRect(bannerX - 6, bannerY - th + 2, tw + 12, th + 4, 8, 8);

        // Text
        g2d.setColor(Color.GREEN);
        g2d.drawString(label, bannerX, bannerY);
    }

    // =========================================================================
    // Drag-and-drop
    // =========================================================================

    private void setupDragAndDrop() {
        new DropTarget(cameraPanel, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                if (currentMode == Mode.FILE
                        && dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    dtde.acceptDrag(DnDConstants.ACTION_COPY);
                } else {
                    dtde.rejectDrag();
                }
            }

            @Override public void dragOver(DropTargetDragEvent dtde)        {}
            @Override public void dropActionChanged(DropTargetDragEvent dtde) {}
            @Override public void dragExit(DropTargetEvent dte)              {}

            @Override
            public void drop(DropTargetDropEvent dtde) {
                if (currentMode != Mode.FILE) {
                    dtde.rejectDrop();
                    return;
                }
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = dtde.getTransferable();
                    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        java.util.List<File> files =
                                (java.util.List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                        if (!files.isEmpty() && isImageFile(files.get(0))) {
                            loadImageFromFile(files.get(0));
                            dtde.dropComplete(true);
                            return;
                        }
                    }
                    JOptionPane.showMessageDialog(MRZScanner.this,
                            "Please drop an image file (jpg, png, bmp, tif)",
                            "Invalid File", JOptionPane.WARNING_MESSAGE);
                    dtde.dropComplete(false);
                } catch (Exception e) {
                    logger.error("Drag-and-drop error", e);
                    dtde.dropComplete(false);
                }
            }
        });
    }

    private static boolean isImageFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png") || name.endsWith(".bmp")
                || name.endsWith(".tif") || name.endsWith(".tiff");
    }

    // =========================================================================
    // Preferred size / cleanup
    // =========================================================================

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(1280, 720);
    }

    public void cleanup() {
        isRunning.set(false);
        if (frameTimer  != null) frameTimer.stop();
        if (mrzWorker   != null) mrzWorker.shutdownNow();
        if (cam         != null) cam.close();
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        int    errorCode = 0;
        String errorMsg  = "";

        // Initialize Dynamsoft license.
        // Request a free trial at https://www.dynamsoft.com/customer/license/trialLicense/?product=dcv&package=cross-platform
        // The key below is a public trial key that requires network connectivity.
        try {
            LicenseError licenseError = LicenseManager.initLicense(
                    "DLS2eyJoYW5kc2hha2VDb2RlIjoiMjAwMDAxLTE2NDk4Mjk3OTI2MzUiLCJvcmdhbml6YXRpb25JRCI6IjIwMDAwMSIsInNlc3Npb25QYXNzd29yZCI6IndTcGR6Vm05WDJrcEQ5YUoifQ==");
            if (licenseError.getErrorCode() != EnumErrorCode.EC_OK
                    && licenseError.getErrorCode() != EnumErrorCode.EC_LICENSE_WARNING) {
                errorCode = licenseError.getErrorCode();
                errorMsg  = licenseError.getErrorString();
            }
        } catch (LicenseException e) {
            errorCode = e.getErrorCode();
            errorMsg  = e.getErrorString();
        }

        if (errorCode != EnumErrorCode.EC_OK) {
            System.err.println("License initialization failed — ErrorCode: "
                    + errorCode + ", ErrorString: " + errorMsg);
        }

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("MRZ Scanner");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            try {
                MRZScanner scanner;
                boolean fileMode = args.length > 0 && args[0].equalsIgnoreCase("--file");
                if (fileMode) {
                    scanner = new MRZScanner();
                } else {
                    scanner = new MRZScanner(0);
                }

                frame.setContentPane(scanner);
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);

                Runtime.getRuntime().addShutdownHook(new Thread(scanner::cleanup));

            } catch (Exception e) {
                logger.error("Failed to start MRZ scanner", e);
                JOptionPane.showMessageDialog(null,
                        "Failed to start MRZ scanner: " + e.getMessage(),
                        "Startup Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
