package x.vladgba.gps.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Size;
import android.util.SizeF;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import java.util.Collections;

/**
 * Live back-camera preview for the AR sky (Camera2, no libraries). The preview is centre-cropped
 * to fill the view, and {@link #getFocalPx()} reports the resulting focal length in view pixels,
 * so the gnomonic sky overlay can be drawn at exactly the camera's scale.
 *
 * <p>The camera is only open while the view is active, attached, on a visible window and its
 * surface exists; every other state closes it so no other app is blocked.
 */
public final class ArCameraView extends TextureView implements TextureView.SurfaceTextureListener {

    public interface Listener {
        /** Focal length (view pixels) changed: new camera, new preview size or new layout. */
        void onFocalChanged(double focalPx);

        /** The camera could not be opened or failed while running. */
        void onCameraError(String message);
    }

    /** Used when the camera does not report its optics (horizontal FOV across the long side). */
    private static final double FALLBACK_LONG_SIDE_FOV_DEG = 66;
    private static final int MAX_PREVIEW_LONG_SIDE = 1920;

    private final CameraManager cameraManager;
    private final DisplayManager displayManager;
    private final String cameraId;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) { }

        @Override
        public void onDisplayRemoved(int displayId) { }

        @Override
        public void onDisplayChanged(int displayId) {
            Display display = getDisplay();
            if (display != null && display.getDisplayId() == displayId) configureTransform();
        }
    };
    private Listener listener;
    private boolean active;

    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface surface;
    private boolean opening;
    private int cameraGeneration;
    private int openingGeneration;

    private Size previewSize;
    private int sensorOrientation = 90;
    /** Focal length in preview-buffer pixels. */
    private double previewFocalPx = Double.NaN;
    private double focalPx = Double.NaN;

    public ArCameraView(Context context) {
        this(context, null);
    }

    public ArCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        displayManager = context.getSystemService(DisplayManager.class);
        cameraId = findBackCamera(cameraManager);
        setSurfaceTextureListener(this);
        if (cameraId != null) readOptics();
    }

    private static String findBackCamera(CameraManager manager) {
        if (manager == null) return null;
        try {
            for (String id : manager.getCameraIdList()) {
                Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
            }
        } catch (CameraAccessException | RuntimeException ignored) {
            // No usable camera service.
        }
        return null;
    }

    // ---- Public API ---------------------------------------------------------------------------

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isAvailable() {
        return cameraId != null && previewSize != null;
    }

    public boolean hasPermission() {
        return getContext().checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    public void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        updateCamera();
    }

    /** Focal length of the displayed preview in view pixels, NaN until known. */
    public double getFocalPx() {
        return focalPx;
    }

    // ---- Optics -------------------------------------------------------------------------------

    /** Picks the preview size and derives its focal length in buffer pixels. */
    private void readOptics() {
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraId);
            Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orientation != null) sensorOrientation = orientation;
            Rect activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) return;
            double aspect = activeArray != null && activeArray.height() > 0
                    ? (double) activeArray.width() / activeArray.height() : 4.0 / 3.0;
            previewSize = choosePreviewSize(map.getOutputSizes(SurfaceTexture.class), aspect);
            if (previewSize == null) return;

            float[] focalLengths = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            SizeF physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            Size pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
            if (focalLengths != null && focalLengths.length > 0 && physical != null && physical.getWidth() > 0
                    && pixelArray != null && activeArray != null && activeArray.width() > 0) {
                // mm -> sensor pixels -> preview pixels. The stream is the largest centred crop
                // of the active array with the preview's aspect ratio.
                double arrayFocal = focalLengths[0] * pixelArray.getWidth() / physical.getWidth();
                double previewAspect = (double) previewSize.getWidth() / previewSize.getHeight();
                double scale = previewAspect >= aspect
                        ? (double) previewSize.getWidth() / activeArray.width()
                        : (double) previewSize.getHeight() / activeArray.height();
                previewFocalPx = arrayFocal * scale;
            } else {
                previewFocalPx = previewSize.getWidth() / 2.0
                        / Math.tan(Math.toRadians(FALLBACK_LONG_SIDE_FOV_DEG) / 2);
            }
        } catch (CameraAccessException | RuntimeException e) {
            previewSize = null;
        }
    }

    /** Largest size up to 1080p with the sensor's aspect ratio, else the closest aspect. */
    private static Size choosePreviewSize(Size[] sizes, double aspect) {
        if (sizes == null) return null;
        Size best = null;
        double bestScore = Double.MAX_VALUE;
        for (Size s : sizes) {
            int longSide = Math.max(s.getWidth(), s.getHeight());
            if (longSide > MAX_PREVIEW_LONG_SIDE) continue;
            double a = (double) s.getWidth() / s.getHeight();
            // Aspect mismatch dominates; among equal aspects prefer the larger size.
            double score = Math.abs(a - aspect) * 1e7 - (double) s.getWidth() * s.getHeight();
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best != null ? best : (sizes.length > 0 ? sizes[0] : null);
    }

    /**
     * Centre-crop: the SurfaceTexture is already upright for the display's natural orientation;
     * scale it to the content size that covers the view, then undo the display rotation.
     */
    private void configureTransform() {
        int viewW = getWidth();
        int viewH = getHeight();
        if (previewSize == null || viewW == 0 || viewH == 0) return;
        int rotationDeg = displayRotationDegrees();
        boolean bufferSideways = sensorOrientation % 180 != 0;
        float naturalW = bufferSideways ? previewSize.getHeight() : previewSize.getWidth();
        float naturalH = bufferSideways ? previewSize.getWidth() : previewSize.getHeight();
        boolean displaySideways = rotationDeg % 180 != 0;
        float shownW = displaySideways ? naturalH : naturalW;
        float shownH = displaySideways ? naturalW : naturalH;
        float s = Math.max(viewW / shownW, viewH / shownH);
        float cx = viewW / 2f;
        float cy = viewH / 2f;
        Matrix matrix = new Matrix();
        matrix.setScale(naturalW * s / viewW, naturalH * s / viewH, cx, cy);
        matrix.postRotate(-rotationDeg, cx, cy);
        setTransform(matrix);

        double newFocal = previewFocalPx * s;
        if (Double.isNaN(focalPx) || Math.abs(newFocal - focalPx) > 0.5) {
            focalPx = newFocal;
            if (listener != null) listener.onFocalChanged(focalPx);
        }
    }

    private int displayRotationDegrees() {
        Display display = getDisplay();
        int rotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        switch (rotation) {
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }

    // ---- Lifecycle ----------------------------------------------------------------------------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (displayManager != null) displayManager.registerDisplayListener(displayListener, getHandler());
        updateCamera();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        configureTransform();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateCamera();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateCamera();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (displayManager != null) displayManager.unregisterDisplayListener(displayListener);
        closeCamera();
        super.onDetachedFromWindow();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
        updateCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        configureTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        closeCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
    }

    private void updateCamera() {
        if (shouldRun()) openCamera();
        else closeCamera();
    }

    private boolean shouldRun() {
        return active && cameraId != null && previewSize != null && isAttachedToWindow()
                && getWindowVisibility() == VISIBLE && isShown() && getSurfaceTexture() != null;
    }

    // ---- Camera2 ------------------------------------------------------------------------------

    @SuppressLint("MissingPermission") // checked by hasPermission() just below
    private void openCamera() {
        if (device != null || opening) return;
        if (!hasPermission()) {
            if (listener != null) listener.onCameraError("Camera permission not granted");
            return;
        }
        opening = true;
        final int generation = ++cameraGeneration;
        openingGeneration = generation;
        try {
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (openingGeneration == generation) opening = false;
                    if (generation != cameraGeneration) {
                        camera.close();
                        updateCamera();
                        return;
                    }
                    device = camera;
                    if (!shouldRun()) {
                        closeCamera();
                        return;
                    }
                    startPreview();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    if (openingGeneration == generation) opening = false;
                    camera.close();
                    if (generation != cameraGeneration) {
                        updateCamera();
                        return;
                    }
                    failCamera("Camera disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    if (openingGeneration == generation) opening = false;
                    camera.close();
                    if (generation != cameraGeneration) {
                        updateCamera();
                        return;
                    }
                    failCamera("Camera error " + error);
                }
            }, getHandler());
        } catch (CameraAccessException | RuntimeException e) {
            opening = false;
            failCamera(e.getMessage() != null ? e.getMessage() : "Camera unavailable");
        }
    }

    @SuppressWarnings("deprecation") // createCaptureSession(List, ...) is the only form on API 24-27
    private void startPreview() {
        SurfaceTexture texture = getSurfaceTexture();
        if (device == null || texture == null) return;
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        configureTransform();
        if (surface != null) surface.release();
        surface = new Surface(texture);
        final CameraDevice camera = device;
        try {
            camera.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (device != camera || !shouldRun()) {
                        s.close();
                        return;
                    }
                    session = s;
                    try {
                        s.setRepeatingRequest(buildRequest(camera), null, getHandler());
                    } catch (CameraAccessException | RuntimeException e) {
                        failCamera("Preview failed");
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    s.close();
                    if (device == camera) failCamera("Preview configuration failed");
                }
            }, getHandler());
        } catch (CameraAccessException | RuntimeException e) {
            failCamera("Preview failed");
        }
    }

    /** Preview with focus at infinity and no digital stabilisation (it crops and shifts the image). */
    private CaptureRequest buildRequest(CameraDevice camera) throws CameraAccessException {
        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(surface);
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraId);
            Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            int[] focusModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (contains(focusModes, CaptureRequest.CONTROL_AF_MODE_OFF)) {
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                if (minFocus != null && minFocus > 0) b.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
            }
            if (contains(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            }
            if (Build.VERSION.SDK_INT >= 31 && contains(c.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES),
                    CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)) {
                b.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE);
            }
        } catch (CameraAccessException | RuntimeException ignored) {
            // Keep the preview template's defaults when optional characteristics are unavailable.
        }
        return b.build();
    }

    private static boolean contains(int[] values, int wanted) {
        if (values != null) for (int value : values) if (value == wanted) return true;
        return false;
    }

    private void failCamera(String message) {
        closeCamera();
        if (listener != null) listener.onCameraError(message);
    }

    private void closeCamera() {
        // Invalidate callbacks already queued by openCamera; wait for their completion before reopening.
        cameraGeneration++;
        if (session != null) {
            try {
                session.close();
            } catch (IllegalStateException ignored) {
                // Already closed with its device.
            }
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }
}
