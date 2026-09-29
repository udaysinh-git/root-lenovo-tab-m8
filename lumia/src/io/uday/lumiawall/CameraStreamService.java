package io.uday.lumiawall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Range;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Built-in replacement for IP Webcam: streams the front camera as MJPEG on 127.0.0.1:8080
 * (/video, /shot.jpg, /status.json). Loopback only, so it's reachable solely through `adb forward`
 * over the USB cable. The camera runs only while at least one client is connected.
 *
 * A camera-type foreground service started while the home activity is in front keeps camera access
 * after the activity goes to the background, with no overlay and no banner.
 */
public class CameraStreamService extends Service {
    static final int PORT = 8080;
    static final int W = 1280, H = 720, QUALITY = 60, MAX_FPS = 24;
    private static final long IDLE_CLOSE_MS = 5000;

    private HandlerThread camThread;
    private Handler cam;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private ServerSocket server;
    private volatile boolean running;

    private final AtomicInteger clients = new AtomicInteger();
    private final Object frameLock = new Object();
    private byte[] frame;                     // latest JPEG
    private long frameSeq;
    private long lastEncode;
    private byte[] nv21;
    private final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(96 * 1024);

    static void start(Context c) {
        c.startForegroundService(new Intent(c, CameraStreamService.class));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("cam", "Tablet camera", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "cam")
                .setContentTitle("Tablet camera ready")
                .setContentText("Streams to the laptop over USB when an app uses it")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);

        camThread = new HandlerThread("camera");
        camThread.start();
        cam = new Handler(camThread.getLooper());
        running = true;
        new Thread(this::serve, "http").start();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }

    @Override public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        cam.post(this::closeCamera);
        camThread.quitSafely();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ camera

    private final Runnable idleCheck = new Runnable() {
        @Override public void run() {
            if (clients.get() == 0) closeCamera();
        }
    };

    /** Called on the camera thread whenever a client connects. */
    private void ensureCamera() {
        cam.removeCallbacks(idleCheck);
        if (device != null) return;
        try {
            CameraManager cm = getSystemService(CameraManager.class);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) { id = c; break; }
            }
            if (id == null) id = cm.getCameraIdList()[0];
            reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(this::onImage, cam);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { device = d; startSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); device = null; }
                @Override public void onError(CameraDevice d, int e) { d.close(); device = null; }
            }, cam);
        } catch (SecurityException | android.hardware.camera2.CameraAccessException e) {
            device = null;
        }
    }

    private void startSession() {
        try {
            device.createCaptureSession(Collections.singletonList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                                b.addTarget(reader.getSurface());
                                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(15, 30));
                                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                                s.setRepeatingRequest(b.build(), null, cam);
                            } catch (Exception e) { closeCamera(); }
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) { closeCamera(); }
                    }, cam);
        } catch (Exception e) {
            closeCamera();
        }
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (device != null) device.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        session = null; device = null; reader = null;
    }

    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            long now = System.currentTimeMillis();
            if (clients.get() == 0 || now - lastEncode < 1000 / MAX_FPS) return;   // throttle
            lastEncode = now;
            byte[] data = toNv21(img);
            jpegOut.reset();
            new YuvImage(data, ImageFormat.NV21, img.getWidth(), img.getHeight(), null)
                    .compressToJpeg(new Rect(0, 0, img.getWidth(), img.getHeight()), QUALITY, jpegOut);
            synchronized (frameLock) {
                frame = jpegOut.toByteArray();
                frameSeq++;
                frameLock.notifyAll();
            }
        } finally {
            img.close();
        }
    }

    /** YUV_420_888 (any strides) -> NV21 (Y plane, then interleaved V/U). */
    private byte[] toNv21(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        if (nv21 == null || nv21.length != w * h * 3 / 2) nv21 = new byte[w * h * 3 / 2];
        Image.Plane[] p = img.getPlanes();
        ByteBuffer y = p[0].getBuffer();
        int yRow = p[0].getRowStride();
        int pos = 0;
        for (int row = 0; row < h; row++) {
            y.position(row * yRow);
            y.get(nv21, pos, w);
            pos += w;
        }
        ByteBuffer u = p[1].getBuffer(), v = p[2].getBuffer();
        int uvRow = p[1].getRowStride(), uvPix = p[1].getPixelStride();
        for (int row = 0; row < h / 2; row++) {
            int base = row * uvRow;
            for (int col = 0; col < w / 2; col++) {
                int i = base + col * uvPix;
                nv21[pos++] = v.get(i);
                nv21[pos++] = u.get(i);
            }
        }
        return nv21;
    }

    // ------------------------------------------------------------------ http (loopback only)

    private void serve() {
        try {
            server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
            while (running) {
                final Socket s = server.accept();
                new Thread(() -> handle(s), "client").start();
            }
        } catch (Exception e) {
            // socket closed on destroy, or port busy (IP Webcam still running): retry shortly
            if (running) cam.postDelayed(() -> new Thread(this::serve, "http").start(), 3000);
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setTcpNoDelay(true);
            String path = readRequestPath(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            if (path.startsWith("/video") || path.startsWith("/videofeed")) {
                streamMjpeg(out);
            } else if (path.startsWith("/shot.jpg")) {
                byte[] f = waitForFrame(-1, 4000);
                if (f == null) { write(out, "HTTP/1.0 503 Service Unavailable\r\n\r\n"); return; }
                write(out, "HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length + "\r\n\r\n");
                out.write(f);
            } else if (path.startsWith("/status.json")) {
                String j = "{\"camera\":\"" + (device != null ? "open" : "idle") + "\",\"clients\":" + clients.get()
                        + ",\"size\":\"" + W + "x" + H + "\",\"quality\":" + QUALITY + "}";
                write(out, "HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + j.length() + "\r\n\r\n" + j);
            } else {
                write(out, "HTTP/1.0 404 Not Found\r\n\r\n");
            }
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private void streamMjpeg(OutputStream out) throws Exception {
        write(out, "HTTP/1.0 200 OK\r\nCache-Control: no-cache\r\nConnection: close\r\n"
                + "Content-Type: multipart/x-mixed-replace;boundary=frame\r\n\r\n");
        long seq = -1;
        try {
            while (running) {
                byte[] f = waitForFrame(seq, 3000);
                if (f == null) continue;
                synchronized (frameLock) { seq = frameSeq; }
                write(out, "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length + "\r\n\r\n");
                out.write(f);
                write(out, "\r\n");
                out.flush();
            }
        } finally {
            if (clients.get() == 0) cam.postDelayed(idleCheck, IDLE_CLOSE_MS);
        }
    }

    /** Registers the caller as a viewer (starting the camera if needed) and waits for a frame newer than {@code afterSeq}. */
    private byte[] waitForFrame(long afterSeq, long timeoutMs) throws InterruptedException {
        clients.incrementAndGet();
        cam.post(this::ensureCamera);
        try {
            long until = System.currentTimeMillis() + timeoutMs;
            synchronized (frameLock) {
                while (frame == null || frameSeq == afterSeq) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) return null;
                    frameLock.wait(left);
                }
                return frame;
            }
        } finally {
            if (clients.decrementAndGet() == 0) cam.postDelayed(idleCheck, IDLE_CLOSE_MS);
        }
    }

    private static String readRequestPath(InputStream in) throws Exception {
        StringBuilder line = new StringBuilder();
        int c, prev = 0;
        boolean first = true;
        String path = "/";
        // read headers until blank line
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                if (first) {
                    String[] parts = line.toString().trim().split(" ");
                    if (parts.length > 1) path = parts[1];
                    first = false;
                }
                if (line.length() == 0 || (line.length() == 1 && prev == '\r')) break;
                line.setLength(0);
            } else if (c != '\r') {
                line.append((char) c);
            }
            prev = c;
        }
        return path;
    }

    private static void write(OutputStream out, String s) throws Exception {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }
}
