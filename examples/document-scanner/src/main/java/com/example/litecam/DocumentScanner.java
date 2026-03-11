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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import com.dynamsoft.core.EnumErrorCode;
import com.dynamsoft.core.EnumImagePixelFormat;
import com.dynamsoft.core.basic_structures.ImageData;
import com.dynamsoft.core.basic_structures.Point;
import com.dynamsoft.core.basic_structures.Quadrilateral;
import com.dynamsoft.cvr.CaptureVisionRouter;
import com.dynamsoft.cvr.CapturedResult;
import com.dynamsoft.ddn.DetectedQuadResultItem;
import com.dynamsoft.ddn.EnhancedImageResultItem;
import com.dynamsoft.ddn.ProcessedDocumentResult;
import com.dynamsoft.license.LicenseError;
import com.dynamsoft.license.LicenseException;
import com.dynamsoft.license.LicenseManager;

/**
 * Document Scanner with camera and file modes.
 * Detects document boundaries, normalizes (deskews/crops) the document,
 * supports manual quad editing, and saves the normalized image.
 */
public class DocumentScanner extends JPanel {

    private static final Logger logger = LoggerFactory.getLogger(DocumentScanner.class);

    private static final String DETECT_TEMPLATE    = "DetectDocumentBoundaries_Default";
    private static final String NORMALIZE_TEMPLATE = "NormalizeDocument_Default";

    // -------------------------------------------------------------------------
    // Enums
    // -------------------------------------------------------------------------
    private enum Mode { CAMERA, FILE }

    // -------------------------------------------------------------------------
    // Camera fields
    // -------------------------------------------------------------------------
    private LiteCam cam;
    private final int cameraIndex;
    private BufferedImage cameraFrame;   // latest raw frame (BGR)
    private ByteBuffer frameBuffer;

    // -------------------------------------------------------------------------
    // File mode
    // -------------------------------------------------------------------------
    private BufferedImage fileImage;

    // -------------------------------------------------------------------------
    // Threading
    // -------------------------------------------------------------------------
    private ExecutorService detectWorker;
    private AtomicBoolean isRunning;
    private AtomicBoolean detectPaused;  // paused while edit-quad dialog is open
    private Timer uiTimer;              // repaint + FPS timer

    // -------------------------------------------------------------------------
    // Dynamsoft CVR
    // -------------------------------------------------------------------------
    private CaptureVisionRouter cvRouter;

    // -------------------------------------------------------------------------
    // Detection / normalization results  (guarded by resultLock)
    // -------------------------------------------------------------------------
    private final Object resultLock = new Object();
    private volatile Quadrilateral     latestDetectedQuad  = null;
    private volatile BufferedImage     latestSourceImage   = null;  // snapshot used for normalize
    private volatile BufferedImage     normalizedImage     = null;
    private volatile Quadrilateral     customQuad          = null;  // user-edited quad
    private volatile boolean           overlayFrozen       = false; // suppress overlay after normalize

    // -------------------------------------------------------------------------
    // UI components
    // -------------------------------------------------------------------------
    private JLabel      statusLabel;
    private JButton     switchModeButton;
    private JButton     loadFileButton;
    private JButton     editQuadButton;
    private JButton     saveImageButton;
    private JLabel      fpsLabel;
    private CameraPanel cameraPanel;
    private NormalizedImagePanel normalizedPanel;

    // -------------------------------------------------------------------------
    // Misc state
    // -------------------------------------------------------------------------
    private Mode   currentMode = Mode.CAMERA;
    private long   frameCount  = 0;
    private long   lastFpsTime = System.currentTimeMillis();
    private File   lastDirectory;

    // =========================================================================
    // Constructors
    // =========================================================================

    /** Camera-mode constructor. Opens the specified camera device. */
    public DocumentScanner(int cameraIndex) {
        this.cameraIndex = cameraIndex;
        initRouter();
        initThreading();
        initCamera(cameraIndex);
        initUI();
        startDetectionWorker();
        startUITimer();
    }

    /** File-mode constructor (no camera opened). */
    public DocumentScanner() {
        this.cameraIndex = 0;
        initRouter();
        initThreading();
        initUI();
        switchToFileMode();
        startUITimer();
    }

    // =========================================================================
    // Initialization helpers
    // =========================================================================

    private void initRouter() {
        try {
            cvRouter = new CaptureVisionRouter();
            logger.info("CaptureVisionRouter initialized");
        } catch (Exception e) {
            logger.error("Failed to initialize CaptureVisionRouter", e);
            throw new RuntimeException("CVR init failed", e);
        }
    }

    private void initThreading() {
        detectWorker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "detect-worker");
            t.setDaemon(true);
            return t;
        });
        isRunning    = new AtomicBoolean(true);
        detectPaused = new AtomicBoolean(false);
    }

    private void initCamera(int idx) {
        try {
            cam = new LiteCam();
            String[] devices = LiteCam.listDevices();
            logger.info("Available cameras: {}", devices.length);
            if (idx >= devices.length) {
                throw new IllegalArgumentException("Camera index " + idx + " out of range");
            }
            cam.openDevice(idx);
            int w = cam.getWidth(), h = cam.getHeight();
            cameraFrame  = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            frameBuffer  = ByteBuffer.allocateDirect(w * h * 3);
            logger.info("Camera opened: {}x{}", w, h);
            currentMode = Mode.CAMERA;
        } catch (Exception e) {
            logger.error("Camera init failed", e);
            throw new RuntimeException("Camera init failed", e);
        }
    }

    private void initUI() {
        setLayout(new BorderLayout(0, 0));

        // ---- Top status bar ----
        statusLabel = new JLabel("Ready — point at a document or load an image.");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        statusLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        add(statusLabel, BorderLayout.NORTH);

        // ---- Center: camera panel ----
        cameraPanel = new CameraPanel();
        cameraPanel.setPreferredSize(new Dimension(860, 580));
        cameraPanel.setBorder(BorderFactory.createTitledBorder("Camera / Source Image"));
        add(cameraPanel, BorderLayout.CENTER);

        // ---- Right: controls + normalized image ----
        add(createRightPanel(), BorderLayout.EAST);

        setupDragAndDrop();
    }

    // =========================================================================
    // Right panel (controls + normalized image)
    // =========================================================================

    private JPanel createRightPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setPreferredSize(new Dimension(360, 0));

        panel.add(createControlPanel(), BorderLayout.NORTH);

        normalizedPanel = new NormalizedImagePanel();
        normalizedPanel.setBorder(BorderFactory.createTitledBorder("Normalized Document"));
        panel.add(normalizedPanel, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createControlPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Controls"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets    = new Insets(4, 6, 4, 6);
        gbc.fill      = GridBagConstraints.HORIZONTAL;
        gbc.gridwidth = 1;
        gbc.weightx   = 1.0;

        int row = 0;

        // Mode switch
        gbc.gridy = row++;
        switchModeButton = new JButton("Switch to File Mode");
        switchModeButton.addActionListener(this::switchMode);
        panel.add(switchModeButton, gbc);

        // Load file (hidden in camera mode)
        gbc.gridy = row++;
        loadFileButton = new JButton("Load Image File");
        loadFileButton.addActionListener(this::onLoadFile);
        loadFileButton.setVisible(false);
        panel.add(loadFileButton, gbc);

        // Divider
        gbc.gridy = row++;
        panel.add(new JSeparator(), gbc);

        // Combined edit-quad + normalize (prominent)
        gbc.gridy = row++;
        editQuadButton = new JButton("Edit Quad/Corners & Normalize Document");
        editQuadButton.setBackground(new Color(59, 130, 200));
        editQuadButton.setForeground(Color.WHITE);
        editQuadButton.setOpaque(true);
        editQuadButton.setFont(editQuadButton.getFont().deriveFont(Font.BOLD));
        editQuadButton.addActionListener(this::onEditQuad);
        panel.add(editQuadButton, gbc);

        // Save image button
        gbc.gridy = row++;
        saveImageButton = new JButton("Save Normalized Image");
        saveImageButton.addActionListener(this::onSaveImage);
        saveImageButton.setEnabled(false);
        panel.add(saveImageButton, gbc);

        // Divider
        gbc.gridy = row++;
        panel.add(new JSeparator(), gbc);

        // FPS label
        gbc.gridy = row++;
        fpsLabel = new JLabel("FPS: 0");
        fpsLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(fpsLabel, gbc);

        return panel;
    }

    // =========================================================================
    // Camera panel (source image + quad overlay)
    // =========================================================================

    private class CameraPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage display = getDisplayImage();
            if (display == null) {
                paintPlaceholder(g);
                return;
            }
            Graphics2D g2d = (Graphics2D) g.create();
            int[] layout = computeLayout(g2d, display);
            g2d.drawImage(display, layout[0], layout[1], layout[2], layout[3], null);
            drawQuadOverlay(g2d, layout[0], layout[1], (double) layout[2] / display.getWidth());
            g2d.dispose();
        }

        private int[] computeLayout(Graphics2D g2d, BufferedImage img) {
            int pw = getWidth(), ph = getHeight();
            int iw = img.getWidth(), ih = img.getHeight();
            double scale = Math.min((double) pw / iw, (double) ph / ih);
            int sw = (int)(iw * scale), sh = (int)(ih * scale);
            int ox = (pw - sw) / 2,    oy = (ph - sh) / 2;
            return new int[]{ox, oy, sw, sh};
        }

        private void paintPlaceholder(Graphics g) {
            g.setColor(new Color(40, 40, 40));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.GRAY);
            String msg = currentMode == Mode.FILE
                    ? "Drop an image here or use 'Load Image File'"
                    : "No camera feed";
            FontMetrics fm = g.getFontMetrics();
            g.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
        }
    }

    // =========================================================================
    // Normalized image panel
    // =========================================================================

    private class NormalizedImagePanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage ni = normalizedImage;
            if (ni == null) {
                g.setColor(new Color(25, 25, 25));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.DARK_GRAY);
                String msg = "Normalized document appears here";
                FontMetrics fm = g.getFontMetrics();
                g.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
                return;
            }
            Graphics2D g2d = (Graphics2D) g.create();
            int pw = getWidth(), ph = getHeight();
            int iw = ni.getWidth(), ih = ni.getHeight();
            double scale = Math.min((double) pw / iw, (double) ph / ih);
            int sw = (int)(iw * scale), sh = (int)(ih * scale);
            int ox = (pw - sw) / 2, oy = (ph - sh) / 2;
            g2d.drawImage(ni, ox, oy, sw, sh, null);
            g2d.dispose();
        }
    }

    private BufferedImage getDisplayImage() {
        return currentMode == Mode.CAMERA ? cameraFrame : fileImage;
    }

    // =========================================================================
    // Quad overlay drawing
    // =========================================================================

    private void drawQuadOverlay(Graphics2D g2d, int ox, int oy, double scale) {
        if (overlayFrozen) return;
        Quadrilateral quad;
        boolean isCustom;
        synchronized (resultLock) {
            isCustom = customQuad != null;
            quad = isCustom ? customQuad : latestDetectedQuad;
        }
        if (quad == null || quad.points == null || quad.points.length < 4) return;

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int[] xs = new int[4], ys = new int[4];
        for (int i = 0; i < 4; i++) {
            xs[i] = ox + (int)(quad.points[i].getX() * scale);
            ys[i] = oy + (int)(quad.points[i].getY() * scale);
        }

        // Semi-transparent fill
        Color fillColor  = isCustom ? new Color(255, 165, 0, 35) : new Color(0, 200, 255, 35);
        Color edgeColor  = isCustom ? new Color(255, 165, 0, 220) : new Color(0, 200, 255, 220);
        Color dotColor   = isCustom ? Color.ORANGE : Color.CYAN;

        g2d.setColor(fillColor);
        g2d.fillPolygon(xs, ys, 4);

        g2d.setStroke(new BasicStroke(2.5f));
        g2d.setColor(edgeColor);
        for (int i = 0; i < 4; i++) {
            g2d.drawLine(xs[i], ys[i], xs[(i + 1) % 4], ys[(i + 1) % 4]);
        }

        // Corner dots
        g2d.setColor(dotColor);
        for (int i = 0; i < 4; i++) {
            g2d.fillOval(xs[i] - 5, ys[i] - 5, 10, 10);
        }

        // Label badge
        String label  = isCustom ? "CUSTOM QUAD" : "DETECTED QUAD";
        Font   font   = new Font(Font.SANS_SERIF, Font.BOLD, 13);
        g2d.setFont(font);
        FontMetrics fm = g2d.getFontMetrics();
        int lw = fm.stringWidth(label), lh = fm.getHeight();
        g2d.setColor(new Color(0, 0, 0, 160));
        g2d.fillRoundRect(ox + 8, oy + 8, lw + 14, lh + 4, 8, 8);
        g2d.setColor(dotColor);
        g2d.drawString(label, ox + 15, oy + lh + 4);
    }

    // =========================================================================
    // Mode switching
    // =========================================================================

    private void switchMode(ActionEvent e) {
        if (currentMode == Mode.CAMERA) switchToFileMode();
        else switchToCameraMode();
    }

    private void switchToFileMode() {
        if (cam != null && cam.isOpen()) cam.close();
        currentMode = Mode.FILE;
        switchModeButton.setText("Switch to Camera Mode");
        loadFileButton.setVisible(true);
        fpsLabel.setVisible(false);
        cameraPanel.setBorder(BorderFactory.createTitledBorder("Source Image"));
        clearResults();
        // Re-run boundary detection on any previously loaded image
        if (fileImage != null) {
            final BufferedImage img = fileImage;
            detectWorker.submit(() -> {
                DetectedQuadResultItem[] quads = detectDocumentBoundary(img);
                synchronized (resultLock) {
                    latestSourceImage  = img;
                    latestDetectedQuad = (quads != null && quads.length > 0)
                            ? quads[0].getLocation() : null;
                    customQuad         = null;
                }
                final DetectedQuadResultItem[] fq = quads;
                SwingUtilities.invokeLater(() -> {
                    updateStatusAfterDetect(fq);
                    repaintAll();
                });
            });
        }
    }

    private void switchToCameraMode() {
        try {
            if (cam == null || !cam.isOpen()) {
                cam = new LiteCam();
                cam.openDevice(cameraIndex);
                int w = cam.getWidth(), h = cam.getHeight();
                cameraFrame = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                frameBuffer = ByteBuffer.allocateDirect(w * h * 3);
            }
            currentMode = Mode.CAMERA;
            switchModeButton.setText("Switch to File Mode");
            loadFileButton.setVisible(false);
            fpsLabel.setVisible(true);
            cameraPanel.setBorder(BorderFactory.createTitledBorder("Camera Feed"));
            clearResults();
            startDetectionWorker();
        } catch (Exception ex) {
            logger.error("Cannot switch to camera mode", ex);
            JOptionPane.showMessageDialog(this, "Camera not available: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // File loading
    // =========================================================================

    private void onLoadFile(ActionEvent e) {
        JFileChooser chooser = new JFileChooser();
        if (lastDirectory != null) chooser.setCurrentDirectory(lastDirectory);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "Image files", "jpg", "jpeg", "png", "bmp", "tif", "tiff"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadFromFile(chooser.getSelectedFile());
        }
    }

    private void loadFromFile(File file) {
        try {
            lastDirectory = file.getParentFile();
            BufferedImage loaded = ImageIO.read(file);
            if (loaded == null) throw new Exception("Unrecognized image format.");
            fileImage = loaded;
            logger.info("Loaded image: {} ({}x{})", file.getName(), loaded.getWidth(), loaded.getHeight());

            // Detect quad immediately
            DetectedQuadResultItem[] quads = detectDocumentBoundary(fileImage);
            synchronized (resultLock) {
                latestSourceImage   = fileImage;
                latestDetectedQuad  = (quads != null && quads.length > 0) ? quads[0].getLocation() : null;
                customQuad          = null;
            }
            updateStatusAfterDetect(quads);
            repaintAll();
        } catch (Exception ex) {
            logger.error("File load error", ex);
            JOptionPane.showMessageDialog(this, "Failed to load: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // Detection worker (camera mode)
    // =========================================================================

    private void startDetectionWorker() {
        if (currentMode != Mode.CAMERA) return;

        detectWorker.submit(() -> {
            while (isRunning.get() && currentMode == Mode.CAMERA) {
                try {
                    if (!detectPaused.get() && cam != null && cam.isOpen() && cam.grabFrame(frameBuffer)) {
                        byte[] dst = ((DataBufferByte) cameraFrame.getRaster().getDataBuffer()).getData();
                        frameBuffer.rewind();
                        frameBuffer.get(dst, 0, Math.min(dst.length, frameBuffer.remaining()));
                        frameBuffer.rewind();

                        BufferedImage snapshot = deepCopy(cameraFrame);
                        DetectedQuadResultItem[] quads = detectDocumentBoundary(snapshot);

                        synchronized (resultLock) {
                            latestSourceImage  = snapshot;
                            latestDetectedQuad = (quads != null && quads.length > 0)
                                    ? quads[0].getLocation() : null;
                        }
                        if (quads != null && quads.length > 0) {
                            final DetectedQuadResultItem[] finalQuads = quads;
                            SwingUtilities.invokeLater(() -> updateStatusAfterDetect(finalQuads));
                        }
                    }
                    Thread.sleep(33); // ~30 FPS cap
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception ex) {
                    logger.debug("Detection worker error: {}", ex.getMessage());
                }
            }
        });
    }

    private void startUITimer() {
        uiTimer = new Timer(33, e -> {
            if (currentMode == Mode.CAMERA) updateFPS();
            repaintAll();
        });
        uiTimer.start();
    }

    // =========================================================================
    // Document boundary detection
    // =========================================================================

    private DetectedQuadResultItem[] detectDocumentBoundary(BufferedImage source) {
        if (source == null || cvRouter == null) return null;
        try {
            ImageData imageData = toImageData(source);
            CapturedResult result = cvRouter.capture(imageData, DETECT_TEMPLATE);
            if (result == null) return null;
            int err = result.getErrorCode();
            if (err != EnumErrorCode.EC_OK && err != EnumErrorCode.EC_UNSUPPORTED_JSON_KEY_WARNING) {
                logger.debug("DetectDocumentBoundary error {}: {}", err, result.getErrorString());
                return null;
            }
            ProcessedDocumentResult docResult = result.getProcessedDocumentResult();
            if (docResult == null) return null;
            return docResult.getDetectedQuadResultItems();
        } catch (Exception e) {
            logger.debug("detectDocumentBoundary error: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Save normalized image
    // =========================================================================

    private void onSaveImage(ActionEvent e) {
        BufferedImage toSave = normalizedImage;
        if (toSave == null) {
            JOptionPane.showMessageDialog(this, "No normalized image to save.",
                    "Nothing to Save", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        if (lastDirectory != null) chooser.setCurrentDirectory(lastDirectory);
        chooser.setSelectedFile(new File("normalized_document.png"));
        chooser.setFileFilter(new FileNameExtensionFilter("PNG Image (*.png)", "png"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("JPEG Image (*.jpg)", "jpg", "jpeg"));

        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        String name = file.getName().toLowerCase();
        String fmt  = (name.endsWith(".jpg") || name.endsWith(".jpeg")) ? "jpg" : "png";
        if (!name.endsWith(".png") && !name.endsWith(".jpg") && !name.endsWith(".jpeg")) {
            file = new File(file.getAbsolutePath() + ".png");
        }

        try {
            ImageIO.write(toSave, fmt, file);
            lastDirectory = file.getParentFile();
            JOptionPane.showMessageDialog(this,
                    "Saved: " + file.getAbsolutePath(),
                    "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            logger.error("Save failed", ex);
            JOptionPane.showMessageDialog(this, "Failed to save: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // Edit quad popup
    // =========================================================================

    private void onEditQuad(ActionEvent e) {
        BufferedImage source;
        Quadrilateral quad;
        synchronized (resultLock) {
            source = latestSourceImage;
            quad   = customQuad != null ? customQuad : latestDetectedQuad;
        }

        if (source == null) {
            JOptionPane.showMessageDialog(this,
                    "No image available. Load an image or point camera at a document.",
                    "No Image", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Pause the detection worker so the shared cvRouter is idle while the
        // dialog is open and during normalization.
        if (currentMode == Mode.CAMERA) detectPaused.set(true);

        // Snapshot so the camera worker cannot mutate the image while the dialog is open
        final BufferedImage snapshot = deepCopy(source);
        QuadEditDialog dialog = new QuadEditDialog(
                SwingUtilities.getWindowAncestor(this), snapshot, quad);
        dialog.setVisible(true);

        Quadrilateral edited = dialog.getResult();
        if (edited != null) {
            synchronized (resultLock) {
                customQuad = edited;
            }
            statusLabel.setText("Normalizing document…");
            editQuadButton.setEnabled(false);
            final Quadrilateral finalQuad = edited;
            SwingWorker<BufferedImage, Void> sw = new SwingWorker<>() {
                @Override
                protected BufferedImage doInBackground() {
                    return perspectiveWarp(snapshot, finalQuad);
                }

                @Override
                protected void done() {
                    editQuadButton.setEnabled(true);
                    try {
                        BufferedImage result = get();
                        if (result != null) {
                            normalizedImage = result;
                            if (currentMode == Mode.FILE) overlayFrozen = true;
                            saveImageButton.setEnabled(true);
                            statusLabel.setText("Document normalized. Click 'Save Normalized Image' to save.");
                        } else {
                            statusLabel.setText("Normalization failed — check that the quad covers the document.");
                        }
                    } catch (Exception ex) {
                        logger.error("Normalization error", ex);
                        statusLabel.setText("Normalization error: " + ex.getMessage());
                    }
                    // Resume live detection now that CVR is free.
                    if (currentMode == Mode.CAMERA) {
                        synchronized (resultLock) { customQuad = null; }
                        detectPaused.set(false);
                    }
                    repaintAll();
                }
            };
            sw.execute();
        } else {
            // Dialog was cancelled — resume detection immediately.
            if (currentMode == Mode.CAMERA) detectPaused.set(false);
        }
    }

    // =========================================================================
    // Quad-edit dialog  (inner class)
    // =========================================================================

    /**
     * Modal dialog showing the source image with draggable corner handles.
     * The user can reposition each corner and confirm the result.
     * Corner order: top-left (0), top-right (1), bottom-right (2), bottom-left (3).
     */
    private static class QuadEditDialog extends JDialog {

        private final BufferedImage source;
        private final float[][] corners = new float[4][2]; // image coords
        private final Quadrilateral original;
        private Quadrilateral editResult = null;

        // Rendering state (updated in paintComponent)
        private int   panelOffX, panelOffY;
        private double panelScale;
        private int dragIdx = -1;

        private final JPanel canvas;

        QuadEditDialog(Window owner, BufferedImage source, Quadrilateral existing) {
            super(owner, "Edit Document Quad — drag corner handles", ModalityType.APPLICATION_MODAL);
            this.source   = source;
            this.original = existing;

            // Initialize corners
            if (existing != null && existing.points != null && existing.points.length >= 4) {
                for (int i = 0; i < 4; i++) {
                    corners[i][0] = (float) existing.points[i].getX();
                    corners[i][1] = (float) existing.points[i].getY();
                }
            } else {
                int w = source.getWidth(), h = source.getHeight();
                corners[0] = new float[]{ w * 0.1f, h * 0.1f };
                corners[1] = new float[]{ w * 0.9f, h * 0.1f };
                corners[2] = new float[]{ w * 0.9f, h * 0.9f };
                corners[3] = new float[]{ w * 0.1f, h * 0.9f };
            }

            // Canvas
            canvas = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    renderCanvas((Graphics2D) g);
                }
                @Override
                public Dimension getPreferredSize() { return new Dimension(820, 620); }
            };
            canvas.setBackground(new Color(40, 40, 40));
            canvas.addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e)  { beginDrag(e); }
                @Override public void mouseReleased(MouseEvent e) { dragIdx = -1; }
            });
            canvas.addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseDragged(MouseEvent e) { doDrag(e); }
            });

            // Buttons
            JButton confirmBtn = new JButton("Normalize Document");
            confirmBtn.addActionListener(e -> { editResult = buildQuadrilateral(); dispose(); });

            JButton cancelBtn = new JButton("Cancel");
            cancelBtn.addActionListener(e -> dispose());

            JButton resetBtn = new JButton("Reset to Auto-Detected");
            resetBtn.addActionListener(e -> {
                if (original != null && original.points != null) {
                    for (int i = 0; i < 4; i++) {
                        corners[i][0] = (float) original.points[i].getX();
                        corners[i][1] = (float) original.points[i].getY();
                    }
                }
                canvas.repaint();
            });

            JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
            btnRow.add(resetBtn);
            btnRow.add(cancelBtn);
            btnRow.add(confirmBtn);

            JLabel hint = new JLabel(
                    "<html>Drag the <b>orange</b> corner handles to adjust the document boundary. " +
                    "Corner order: <b>TL → TR → BR → BL</b>.</html>");
            hint.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));

            setLayout(new BorderLayout());
            add(hint,  BorderLayout.NORTH);
            add(new JScrollPane(canvas), BorderLayout.CENTER);
            add(btnRow, BorderLayout.SOUTH);

            pack();
            setLocationRelativeTo(owner);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        }

        private void renderCanvas(Graphics2D g2d) {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int pw = canvas.getWidth(), ph = canvas.getHeight();
            int iw = source.getWidth(),  ih = source.getHeight();
            panelScale = Math.min((double) pw / iw, (double) ph / ih);
            int sw = (int)(iw * panelScale), sh = (int)(ih * panelScale);
            panelOffX = (pw - sw) / 2;
            panelOffY = (ph - sh) / 2;

            g2d.drawImage(source, panelOffX, panelOffY, sw, sh, null);

            // Screen coords of the 4 corners
            int[] xs = new int[4], ys = new int[4];
            for (int i = 0; i < 4; i++) {
                xs[i] = panelOffX + (int)(corners[i][0] * panelScale);
                ys[i] = panelOffY + (int)(corners[i][1] * panelScale);
            }

            // Shaded fill
            g2d.setColor(new Color(255, 165, 0, 50));
            g2d.fillPolygon(xs, ys, 4);

            // Edges
            g2d.setStroke(new BasicStroke(2.2f));
            g2d.setColor(new Color(255, 165, 0, 220));
            for (int i = 0; i < 4; i++) {
                g2d.drawLine(xs[i], ys[i], xs[(i + 1) % 4], ys[(i + 1) % 4]);
            }

            // Corner handles + labels
            String[] labels = { "TL", "TR", "BR", "BL" };
            for (int i = 0; i < 4; i++) {
                g2d.setColor(dragIdx == i ? Color.RED : Color.ORANGE);
                g2d.fillOval(xs[i] - 9, ys[i] - 9, 18, 18);
                g2d.setColor(Color.WHITE);
                g2d.setStroke(new BasicStroke(1.5f));
                g2d.drawOval(xs[i] - 9, ys[i] - 9, 18, 18);
                g2d.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
                g2d.setColor(Color.BLACK);
                FontMetrics fm = g2d.getFontMetrics();
                g2d.drawString(labels[i],
                        xs[i] - fm.stringWidth(labels[i]) / 2,
                        ys[i] + fm.getAscent() / 2);
            }
        }

        private void beginDrag(MouseEvent e) {
            int[] xs = new int[4], ys = new int[4];
            for (int i = 0; i < 4; i++) {
                xs[i] = panelOffX + (int)(corners[i][0] * panelScale);
                ys[i] = panelOffY + (int)(corners[i][1] * panelScale);
            }
            for (int i = 0; i < 4; i++) {
                int dx = e.getX() - xs[i], dy = e.getY() - ys[i];
                if (dx * dx + dy * dy <= 18 * 18) { dragIdx = i; return; }
            }
        }

        private void doDrag(MouseEvent e) {
            if (dragIdx < 0) return;
            float imgX = (e.getX() - panelOffX) / (float) panelScale;
            float imgY = (e.getY() - panelOffY) / (float) panelScale;
            imgX = Math.max(0, Math.min(source.getWidth()  - 1, imgX));
            imgY = Math.max(0, Math.min(source.getHeight() - 1, imgY));
            corners[dragIdx][0] = imgX;
            corners[dragIdx][1] = imgY;
            canvas.repaint();
        }

        private Quadrilateral buildQuadrilateral() {
            Point p0 = new Point((int) corners[0][0], (int) corners[0][1]);
            Point p1 = new Point((int) corners[1][0], (int) corners[1][1]);
            Point p2 = new Point((int) corners[2][0], (int) corners[2][1]);
            Point p3 = new Point((int) corners[3][0], (int) corners[3][1]);
            return new Quadrilateral(p0, p1, p2, p3);
        }

        /** Returns the confirmed quad, or {@code null} if the dialog was cancelled. */
        Quadrilateral getResult() { return editResult; }
    }

    // =========================================================================
    // Perspective warp (for custom quad normalization)
    // =========================================================================

    /**
     * Performs a perspective (projective) warp to extract the document region
     * defined by the 4 corners of {@code quad} from {@code src}.
     * Corner order expected: top-left, top-right, bottom-right, bottom-left.
     */
    private static BufferedImage perspectiveWarp(BufferedImage src, Quadrilateral quad) {
        if (src == null || quad == null || quad.points == null || quad.points.length < 4) return null;

        Point[] pts = quad.points;

        // Output dimensions from the longest opposite edges
        double topW  = distance(pts[0], pts[1]);
        double botW  = distance(pts[3], pts[2]);
        double leftH = distance(pts[0], pts[3]);
        double riteH = distance(pts[1], pts[2]);
        int outW = Math.max(4, (int) Math.max(topW, botW));
        int outH = Math.max(4, (int) Math.max(leftH, riteH));

        // Source (quad) → destination (axis-aligned rectangle)
        float[] srcPts = {
            (float) pts[0].getX(), (float) pts[0].getY(),
            (float) pts[1].getX(), (float) pts[1].getY(),
            (float) pts[2].getX(), (float) pts[2].getY(),
            (float) pts[3].getX(), (float) pts[3].getY()
        };
        float[] dstPts = {
            0,        0,
            outW - 1, 0,
            outW - 1, outH - 1,
            0,        outH - 1
        };

        double[] H    = computeHomography(srcPts, dstPts);       // src → dst
        double[] Hinv = invertHomography(H);                      // dst → src (for inverse mapping)

        BufferedImage out = new BufferedImage(outW, outH, BufferedImage.TYPE_3BYTE_BGR);
        int srcW = src.getWidth(), srcH = src.getHeight();

        for (int oy = 0; oy < outH; oy++) {
            for (int ox = 0; ox < outW; ox++) {
                // Map output pixel → source pixel via inverse homography
                double denom = Hinv[6] * ox + Hinv[7] * oy + Hinv[8];
                double sx    = (Hinv[0] * ox + Hinv[1] * oy + Hinv[2]) / denom;
                double sy    = (Hinv[3] * ox + Hinv[4] * oy + Hinv[5]) / denom;

                int x0 = (int) sx, y0 = (int) sy;
                int x1 = x0 + 1,   y1 = y0 + 1;

                if (x0 < 0 || y0 < 0 || x1 >= srcW || y1 >= srcH) continue; // black border

                double dx = sx - x0, dy = sy - y0;
                int c00 = src.getRGB(x0, y0), c10 = src.getRGB(x1, y0);
                int c01 = src.getRGB(x0, y1), c11 = src.getRGB(x1, y1);

                int r = bilinear(c00 >> 16, c10 >> 16, c01 >> 16, c11 >> 16, dx, dy);
                int g = bilinear(c00 >> 8,  c10 >> 8,  c01 >> 8,  c11 >> 8,  dx, dy);
                int b = bilinear(c00,        c10,        c01,        c11,        dx, dy);

                out.setRGB(ox, oy, 0xFF000000 | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF));
            }
        }
        return out;
    }

    private static int bilinear(int c00, int c10, int c01, int c11, double dx, double dy) {
        double v = (c00 & 0xFF) * (1 - dx) * (1 - dy)
                 + (c10 & 0xFF) *       dx  * (1 - dy)
                 + (c01 & 0xFF) * (1 - dx)  *       dy
                 + (c11 & 0xFF) *       dx  *       dy;
        return (int) Math.min(255, Math.max(0, v));
    }

    private static double distance(Point a, Point b) {
        double dx = a.getX() - b.getX(), dy = a.getY() - b.getY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Compute 3×3 homography (row-major, last element = 1) from 4 src→dst point pairs. */
    private static double[] computeHomography(float[] src, float[] dst) {
        double[][] A = new double[8][8];
        double[]   b = new double[8];
        for (int i = 0; i < 4; i++) {
            float sx = src[i * 2], sy = src[i * 2 + 1];
            float dx = dst[i * 2], dy = dst[i * 2 + 1];
            A[i*2][0] = sx; A[i*2][1] = sy; A[i*2][2] = 1;
            A[i*2][3] = 0;  A[i*2][4] = 0;  A[i*2][5] = 0;
            A[i*2][6] = -dx*sx; A[i*2][7] = -dx*sy; b[i*2] = dx;
            A[i*2+1][0] = 0; A[i*2+1][1] = 0; A[i*2+1][2] = 0;
            A[i*2+1][3] = sx; A[i*2+1][4] = sy; A[i*2+1][5] = 1;
            A[i*2+1][6] = -dy*sx; A[i*2+1][7] = -dy*sy; b[i*2+1] = dy;
        }
        double[] h = gaussElim(A, b);
        return new double[]{ h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0 };
    }

    private static double[] gaussElim(double[][] A, double[] b) {
        int n = 8;
        for (int col = 0; col < n; col++) {
            // Partial pivot
            int maxRow = col;
            for (int r = col + 1; r < n; r++) if (Math.abs(A[r][col]) > Math.abs(A[maxRow][col])) maxRow = r;
            double[] tmp = A[col]; A[col] = A[maxRow]; A[maxRow] = tmp;
            double t = b[col]; b[col] = b[maxRow]; b[maxRow] = t;
            // Eliminate below
            for (int r = col + 1; r < n; r++) {
                double f = A[r][col] / A[col][col];
                b[r] -= f * b[col];
                for (int c = col; c < n; c++) A[r][c] -= f * A[col][c];
            }
        }
        double[] x = new double[n];
        for (int i = n-1; i >= 0; i--) {
            x[i] = b[i];
            for (int j = i+1; j < n; j++) x[i] -= A[i][j] * x[j];
            x[i] /= A[i][i];
        }
        return x;
    }

    private static double[] invertHomography(double[] H) {
        double a = H[0], b = H[1], c = H[2];
        double d = H[3], e = H[4], f = H[5];
        double g = H[6], h = H[7], k = H[8];
        double det = a*(e*k - f*h) - b*(d*k - f*g) + c*(d*h - e*g);
        if (Math.abs(det) < 1e-12) return H.clone();
        double id = 1.0 / det;
        return new double[]{
            (e*k-f*h)*id, (c*h-b*k)*id, (b*f-c*e)*id,
            (f*g-d*k)*id, (a*k-c*g)*id, (c*d-a*f)*id,
            (d*h-e*g)*id, (b*g-a*h)*id, (a*e-b*d)*id
        };
    }

    // =========================================================================
    // Image conversion helpers
    // =========================================================================

    /** Convert BufferedImage → Dynamsoft ImageData (BGR). */
    private static ImageData toImageData(BufferedImage src) {
        BufferedImage bgr = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = bgr.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        byte[] bytes = ((DataBufferByte) bgr.getRaster().getDataBuffer()).getData();
        return new ImageData(bytes, src.getWidth(), src.getHeight(), src.getWidth() * 3,
                EnumImagePixelFormat.IPF_BGR_888, 0, null);
    }

    /** Convert Dynamsoft ImageData → BufferedImage, handling common pixel formats. */
    private static BufferedImage toBufferedImage(ImageData data) {
        if (data == null) return null;
        int w = data.getWidth(), h = data.getHeight(), stride = data.getStride();
        byte[] bytes = data.getBytes();
        int fmt = data.getImagePixelFormat();

        if (fmt == EnumImagePixelFormat.IPF_BGR_888) {
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
            byte[] dst = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
            for (int row = 0; row < h; row++) {
                System.arraycopy(bytes, row * stride, dst, row * w * 3, w * 3);
            }
            return out;
        }
        if (fmt == EnumImagePixelFormat.IPF_RGB_888) {
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    int i = row * stride + col * 3;
                    int r = bytes[i] & 0xFF, g2 = bytes[i+1] & 0xFF, b2 = bytes[i+2] & 0xFF;
                    out.setRGB(col, row, (r << 16) | (g2 << 8) | b2);
                }
            }
            return out;
        }
        if (fmt == EnumImagePixelFormat.IPF_GRAYSCALED) {
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            byte[] dst = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
            for (int row = 0; row < h; row++) {
                System.arraycopy(bytes, row * stride, dst, row * w, w);
            }
            return out;
        }
        // Fallback: treat as BGR
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        byte[] dst = ((DataBufferByte) out.getRaster().getDataBuffer()).getData();
        System.arraycopy(bytes, 0, dst, 0, Math.min(bytes.length, dst.length));
        return out;
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    // =========================================================================
    // Drag-and-drop
    // =========================================================================

    private void setupDragAndDrop() {
        new DropTarget(cameraPanel, new DropTargetListener() {
            @Override
            public void dragEnter(DropTargetDragEvent d) {
                if (currentMode == Mode.FILE && d.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
                    d.acceptDrag(DnDConstants.ACTION_COPY);
                else
                    d.rejectDrag();
            }
            @Override public void dragOver(DropTargetDragEvent d) {}
            @Override public void dropActionChanged(DropTargetDragEvent d) {}
            @Override public void dragExit(DropTargetEvent d) {}
            @Override
            public void drop(DropTargetDropEvent d) {
                if (currentMode != Mode.FILE) { d.rejectDrop(); return; }
                try {
                    d.acceptDrop(DnDConstants.ACTION_COPY);
                    Transferable t = d.getTransferable();
                    if (t.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @SuppressWarnings("unchecked")
                        java.util.List<File> files =
                                (java.util.List<File>) t.getTransferData(DataFlavor.javaFileListFlavor);
                        if (!files.isEmpty() && isImageFile(files.get(0))) {
                            loadFromFile(files.get(0));
                            d.dropComplete(true);
                            return;
                        }
                    }
                    JOptionPane.showMessageDialog(DocumentScanner.this,
                            "Please drop an image file (jpg, png, bmp, tif).",
                            "Invalid File", JOptionPane.WARNING_MESSAGE);
                    d.dropComplete(false);
                } catch (Exception ex) {
                    logger.error("Drag-and-drop error", ex);
                    d.dropComplete(false);
                }
            }
        });
    }

    private static boolean isImageFile(File f) {
        String n = f.getName().toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".bmp") || n.endsWith(".tif") || n.endsWith(".tiff");
    }

    // =========================================================================
    // Misc UI helpers
    // =========================================================================

    private void updateStatusAfterDetect(DetectedQuadResultItem[] quads) {
        if (quads != null && quads.length > 0) {
            statusLabel.setText(String.format(
                    "Document detected (confidence %d%%). Click 'Edit Quad/Corners & Normalize Document'.",
                    quads[0].getConfidenceAsDocumentBoundary()));
        } else {
            statusLabel.setText("No document boundary detected — adjust camera angle or lighting.");
        }
    }

    private void clearResults() {
        overlayFrozen = false;
        synchronized (resultLock) {
            latestDetectedQuad = null;
            latestSourceImage  = null;
            normalizedImage    = null;
            customQuad         = null;
        }
        SwingUtilities.invokeLater(() -> {
            saveImageButton.setEnabled(false);
            statusLabel.setText("Ready — point at a document or load an image.");
            repaintAll();
        });
    }

    private void updateFPS() {
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            final double fps = frameCount * 1000.0 / (now - lastFpsTime);
            SwingUtilities.invokeLater(() -> fpsLabel.setText(String.format("FPS: %.1f", fps)));
            frameCount  = 0;
            lastFpsTime = now;
        }
    }

    private void repaintAll() {
        if (cameraPanel    != null) cameraPanel.repaint();
        if (normalizedPanel != null) normalizedPanel.repaint();
    }

    // =========================================================================
    // Preferred size / cleanup
    // =========================================================================

    @Override
    public Dimension getPreferredSize() { return new Dimension(1280, 720); }

    public void cleanup() {
        isRunning.set(false);
        if (uiTimer      != null) uiTimer.stop();
        if (detectWorker != null) detectWorker.shutdownNow();
        if (cam          != null) cam.close();
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    public static void main(String[] args) {
        int    errorCode = 0;
        String errorMsg  = "";

        // Initialize Dynamsoft license.
        // Request a free trial at https://www.dynamsoft.com/customer/license/trialLicense/?product=dcv&package=cross-platform
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
            System.err.println("License warning — ErrorCode: " + errorCode + ", ErrorString: " + errorMsg);
        }

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Document Scanner");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            try {
                boolean fileMode = args.length > 0 && args[0].equalsIgnoreCase("--file");
                DocumentScanner scanner = fileMode ? new DocumentScanner() : new DocumentScanner(0);
                frame.setContentPane(scanner);
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
                Runtime.getRuntime().addShutdownHook(new Thread(scanner::cleanup));
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null,
                        "Failed to start Document Scanner: " + e.getMessage(),
                        "Startup Error", JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
}
