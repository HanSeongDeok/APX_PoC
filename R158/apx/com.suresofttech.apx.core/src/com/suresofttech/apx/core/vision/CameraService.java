package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.VideoWriter;
import org.opencv.videoio.Videoio;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamDiscoveryEvent;
import com.github.sarxos.webcam.WebcamDiscoveryListener;

/**
 * 웹캠 채널별 캡처 서비스 - 클러스터 / 기어봉을 서로 다른 장치로 연다.
 *
 * <p>장치 목록만 Sarxos(이름·연결/분리). 실제 캡처는 OpenCV {@link VideoCapture}
 * + DirectShow. 열기는 인덱스만, 1080p60 MJPEG는 첫 프레임 이후 grab 스레드에서.
 * UI 스레드에서 {@code VideoCapture} / {@code read()} 하면 네이티브가 행해도
 * 인터럽트가 안 되어 화면이 멈춘다.
 *
 * <p>프레임은 {@link BufferedImage}(BGR). {@link #of(VisionChannel)} 로 채널을 고른다.
 */
public final class CameraService {

    /** 콤보 표시용 카메라 식별자. */
    public static final class Cam {
        public final int index;
        public final String name;

        Cam(int index, String name) {
            this.index = index;
            this.name = name;
        }

        public String toString() {
            return name;
        }
    }

    /**
     * 장치 목록이 바뀌었을 때(USB 연결/분리). 드라이버 스레드에서 호출되므로
     * UI는 반드시 자기 디스플레이 스레드로 넘겨서 처리해야 한다.
     */
    public interface DeviceListener {
        /** @param currentLost 사용 중이던 카메라가 사라졌으면 true */
        void onDevicesChanged(boolean currentLost);
    }

    private static final int OPEN_TIMEOUT_MS = 1500;

    private static volatile List<Webcam> lastWebcams = java.util.Collections.emptyList();
    /** Sarxos 목록과 분리한 표시용 스냅샷. OpenCV가 캠을 연 뒤 getWebcams 가 비워도 남는다. */
    private static volatile List<Cam> cachedCams = java.util.Collections.emptyList();
    private static final Object LIST_LOCK = new Object();

    private static final ExecutorService OPEN_EXEC = Executors.newCachedThreadPool(
            new ThreadFactory() {
                private final AtomicInteger n = new AtomicInteger();

                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "apx-cam-open-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    private static final CameraService CLUSTER = new CameraService(VisionChannel.CLUSTER);
    private static final CameraService GEAR = new CameraService(VisionChannel.GEAR);
    private static final Object OPEN_LOCK = new Object();
    private static boolean discoveryHooked;

    /** 클러스터 채널. 예전 단일 카메라 호출과 같다. */
    public static CameraService get() {
        return CLUSTER;
    }

    public static CameraService of(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? GEAR : CLUSTER;
    }

    private final VisionChannel channel;

    private CameraService(VisionChannel channel) {
        this.channel = channel;
    }

    public VisionChannel channel() {
        return channel;
    }

    private VideoCapture cap;
    private int currentIndex = -1;
    /** 사용 중인 장치의 이름 - 인덱스는 재연결 시 바뀔 수 있어 이름으로 대조한다. */
    private String currentName;
    private double reportedFps;

    private volatile boolean grabbing;
    private Thread grabThread;
    /**
     * 한 프레임과 그 프레임이 {@code read()} 된 시각. 둘을 같이 읽어야 PASS @ 이 다른 프레임 시각을 쓰지 않는다.
     */
    public static final class Grabbed {
        public final BufferedImage image;
        public final long nanos;

        public Grabbed(BufferedImage image, long nanos) {
            this.image = image;
            this.nanos = nanos;
        }
    }

    private volatile Grabbed grabbed;
    private volatile BufferedImage cachedFrame;
    /** {@link #cachedFrame} 이 {@code read()} 로 들어온 시각({@code System.nanoTime}). */
    private volatile long cachedFrameNanos;
    /** grab 스레드가 잰 프레임 주기(ms). 판정기 UI 폴링/서명 스킵과 무관. */
    private volatile double grabGapMs;
    private long lastGrabNanos;
    private static final int GRAB_GAP_N = 15;
    private final double[] grabGaps = new double[GRAB_GAP_N];
    private int grabGapCount;
    private int grabGapHead;

    private final CopyOnWriteArrayList<DeviceListener> deviceListeners =
            new CopyOnWriteArrayList<DeviceListener>();

    /**
     * 현재 연결된 웹캠 목록. 표시명은 드라이버 끝 인덱스({@code " … 0"})를 제거.
     * USB 외장을 앞에 두고, 가상캠·내장캠은 뒤에 붙인다.
     *
     * <p>OpenCV가 이미 연 뒤에는 Sarxos 재탐색이 빈 목록이거나 행할 수 있다.
     * 그때는 직전 목록을 쓴다. 설정 창을 다시 열어도 콤보가 비지 않게.
     */
    public List<Cam> list() {
        return list(false);
    }

    /**
     * @param forceScan true면 Sarxos를 다시 훑는다(캠을 안 연 상태의 새로고침 / 핫플러그).
     *                  OpenCV가 이미 연 뒤에는 재탐색하지 않고 캐시만 돌려준다.
     */
    public List<Cam> list(boolean forceScan) {
        synchronized (LIST_LOCK) {
            if (isAnyCaptureOpen()) {
                return copyCachedOrOpen();
            }
            if (!forceScan && cachedCams != null && !cachedCams.isEmpty()) {
                return new ArrayList<Cam>(cachedCams);
            }
            List<Cam> scanned = toCams(scanWebcams());
            if (!scanned.isEmpty()) {
                cachedCams = java.util.Collections.unmodifiableList(scanned);
            }
            if (cachedCams != null && !cachedCams.isEmpty()) {
                return new ArrayList<Cam>(cachedCams);
            }
            return scanned;
        }
    }

    private static boolean isAnyCaptureOpen() {
        synchronized (OPEN_LOCK) {
            return CLUSTER.cap != null || GEAR.cap != null;
        }
    }

    private static List<Cam> copyCachedOrOpen() {
        if (cachedCams != null && !cachedCams.isEmpty()) {
            return new ArrayList<Cam>(cachedCams);
        }
        List<Cam> out = new ArrayList<Cam>();
        synchronized (OPEN_LOCK) {
            addOpenCam(out, CLUSTER);
            addOpenCam(out, GEAR);
        }
        return out;
    }

    private static void addOpenCam(List<Cam> out, CameraService s) {
        if (s.currentName != null && s.currentIndex >= 0) {
            out.add(new Cam(s.currentIndex, displayName(s.currentName)));
        }
    }

    private static List<Webcam> scanWebcams() {
        try {
            List<Webcam> fresh = Webcam.getWebcams();
            if (fresh != null && !fresh.isEmpty()) {
                // Sarxos가 반환한 내부 목록은 다음 디스커버리 때 제자리에서 비워질 수 있다.
                // 그대로 캐시하면 첫 조회 후 같은 객체가 빈 목록이 되어 설정 재진입 시 사라진다.
                List<Webcam> snapshot = java.util.Collections.unmodifiableList(
                        new ArrayList<Webcam>(fresh));
                lastWebcams = snapshot;
                return snapshot;
            }
        } catch (Throwable t) {
            // OpenCV가 DirectShow를 잡은 상태 등
        }
        return lastWebcams != null ? lastWebcams : java.util.Collections.<Webcam>emptyList();
    }

    private static List<Cam> toCams(List<Webcam> ws) {
        List<Cam> usb = new ArrayList<Cam>();
        List<Cam> rest = new ArrayList<Cam>();
        if (ws == null) {
            return usb;
        }
        for (int i = 0; i < ws.size(); i++) {
            String raw = ws.get(i).getName();
            Cam cam = new Cam(i, displayName(raw));
            if (looksUsbCamera(raw) && !isVirtualCamera(raw) && !isBuiltinCamera(raw)) {
                usb.add(cam);
            } else {
                rest.add(cam);
            }
        }
        List<Cam> out = new ArrayList<Cam>();
        out.addAll(usb);
        out.addAll(rest);
        return out;
    }

    /** 콤보 표시용 - 끝의 장치 인덱스 숫자 제거 ("Logitech … 0" → "Logitech …"). */
    static String displayName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceFirst("\\s+\\d+$", "").trim();
    }

    private static boolean isVirtualCamera(String name) {
        if (name == null) {
            return true;
        }
        String n = name.toLowerCase();
        return n.contains("virtual") || n.contains("obs ") || n.contains("obs-")
                || n.contains("manycam") || n.contains("snap camera")
                || n.contains("iriun") || n.contains("droidcam") || n.contains("ndi");
    }

    /** 노트북 내장 캠 - USB 외장과 구분. */
    private static boolean isBuiltinCamera(String name) {
        if (name == null) {
            return true;
        }
        String n = name.toLowerCase();
        return n.contains("integrated") || n.contains("internal")
                || n.contains("built-in") || n.contains("builtin")
                || n.contains("facetime");
    }

    private static boolean looksUsbCamera(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase();
        return n.contains("usb") || n.contains("uvc");
    }

    /**
     * USB 연결/분리 구독. 첫 구독 시 드라이버 디스커버리에 훅을 건다
     * (측정 장비는 현장에서 케이블이 자주 바뀌므로 목록을 수동 새로고침에만 맡기지 않는다).
     */
    public void addDeviceListener(DeviceListener l) {
        if (l == null) {
            return;
        }
        deviceListeners.add(l);
        OPEN_EXEC.execute(new Runnable() {
            public void run() {
                hookDiscovery();
            }
        });
    }

    public void removeDeviceListener(DeviceListener l) {
        deviceListeners.remove(l);
    }

    private static synchronized void hookDiscovery() {
        if (discoveryHooked) {
            return;
        }
        try {
            Webcam.addDiscoveryListener(new WebcamDiscoveryListener() {
                public void webcamFound(WebcamDiscoveryEvent event) {
                    CLUSTER.fireDevicesChanged();
                    GEAR.fireDevicesChanged();
                }

                public void webcamGone(WebcamDiscoveryEvent event) {
                    CLUSTER.fireDevicesChanged();
                    GEAR.fireDevicesChanged();
                }
            });
            discoveryHooked = true;
        } catch (Throwable t) {
            // 드라이버가 디스커버리를 지원하지 않으면 수동 새로고침으로만 동작
        }
    }

    private void fireDevicesChanged() {
        boolean lost = isCurrentGone();
        for (DeviceListener l : deviceListeners) {
            try {
                l.onDevicesChanged(lost);
            } catch (Exception ignored) {
                // 구독자 하나가 죽어도 나머지는 알린다
            }
        }
    }

    /** 사용 중이던 카메라가 목록에서 사라졌는지. 우리가 열어 둔 장치는 사라진 게 아니다. */
    public boolean isCurrentGone() {
        synchronized (OPEN_LOCK) {
            if (cap != null) {
                return false;
            }
            if (currentName == null) {
                return false;
            }
        }
        List<Webcam> ws = lastWebcams;
        if (ws == null || ws.isEmpty()) {
            return false;
        }
        String name = currentName;
        for (Webcam w : ws) {
            if (name.equals(w.getName())) {
                return false;
            }
        }
        return true;
    }

    /** 사용 중인 카메라 표시명. 없으면 null. */
    public synchronized String currentName() {
        return currentName == null ? null : displayName(currentName);
    }

    /**
     * 지정 인덱스 웹캠 열기(기존 것 닫고). 다른 채널이 이미 연 장치면 false.
     * 네이티브 행은 {@link #OPEN_TIMEOUT_MS} 후 포기한다(그 스레드는 남을 수 있음).
     * <b>UI 스레드에서 호출하지 말 것.</b>
     */
    public boolean open(int index) {
        try {
            Cv.ensureLoaded();
            List<Webcam> ws = lastWebcams;
            if (ws == null || index < 0 || index >= ws.size()) {
                synchronized (LIST_LOCK) {
                    ws = scanWebcams();
                }
            }
            if (ws == null || index < 0 || index >= ws.size()) {
                return false;
            }
            VideoCapture prev;
            synchronized (OPEN_LOCK) {
                CameraService other = this == CLUSTER ? GEAR : CLUSTER;
                if (other.currentIndex == index && other.isOpenUnlocked()) {
                    return false;
                }
                prev = detachUnlocked();
            }
            VideoCapture opened = openBackendTimed(index, prev);
            if (opened == null) {
                return false;
            }
            boolean clash = false;
            synchronized (OPEN_LOCK) {
                CameraService other = this == CLUSTER ? GEAR : CLUSTER;
                if (other.currentIndex == index && other.isOpenUnlocked()) {
                    clash = true;
                } else {
                    cap = opened;
                    currentIndex = index;
                    currentName = ws.get(index).getName();
                    startGrabUnlocked();
                    return true;
                }
            }
            if (clash) {
                releaseLater(opened);
            }
            return false;
        } catch (Throwable t) {
            VideoCapture leftover;
            synchronized (OPEN_LOCK) {
                leftover = detachUnlocked();
            }
            releaseLater(leftover);
            return false;
        }
    }

    /**
     * 이름으로 다시 연다 - 케이블을 뽑았다 꽂으면 인덱스가 바뀌므로 재연결은 이름 기준.
     * @return 찾아서 열었으면 true
     */
    public boolean reopenByName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        try {
            List<Webcam> ws = lastWebcams;
            if (ws == null || ws.isEmpty()) {
                synchronized (LIST_LOCK) {
                    ws = scanWebcams();
                }
            }
            for (int i = 0; i < ws.size(); i++) {
                String raw = ws.get(i).getName();
                if (name.equals(raw) || name.equals(displayName(raw))) {
                    return open(i);
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /** DirectShow 인덱스. 이전 캡처는 같은 스레드에서 닫고 연다(busy 방지). */
    private static VideoCapture openBackendTimed(final int index, final VideoCapture prev) {
        Future<VideoCapture> f = OPEN_EXEC.submit(new Callable<VideoCapture>() {
            public VideoCapture call() {
                releaseQuiet(prev);
                VideoCapture c = new VideoCapture(index, Videoio.CAP_DSHOW);
                if (!c.isOpened()) {
                    releaseQuiet(c);
                    return null;
                }
                return c;
            }
        });
        try {
            return f.get(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * USB2에서 1080p60은 MJPEG가 아니면 대역이 안 된다. YUY2 기본은 대개 30fps.
     * {@code set}은 DirectShow에서 수 초 걸릴 수 있다. <b>read() 도중에 호출하면
     * 스트림이 죽는 캠이 많다</b> — grab 루프 시작 전에만 부른다.
     */
    private static void apply1080p60(VideoCapture c) {
        c.set(Videoio.CAP_PROP_FOURCC, VideoWriter.fourcc('M', 'J', 'P', 'G'));
        c.set(Videoio.CAP_PROP_FRAME_WIDTH, 1920);
        c.set(Videoio.CAP_PROP_FRAME_HEIGHT, 1080);
        c.set(Videoio.CAP_PROP_FPS, 60);
        try {
            c.set(Videoio.CAP_PROP_BUFFERSIZE, 1);
        } catch (Throwable ignored) {
            // MSMF 등은 미지원
        }
    }

    private void startGrabUnlocked() {
        grabbing = true;
        final VideoCapture local = cap;
        grabThread = new Thread(new Runnable() {
            public void run() {
                Mat m = new Mat();
                try {
                    if (local != null && local.isOpened()) {
                        apply1080p60(local);
                    }
                    while (grabbing) {
                        if (local == null || !local.isOpened()) {
                            break;
                        }
                        try {
                            if (local.read(m) && !m.empty()) {
                                BufferedImage bi = Cv.toBufferedImage(m);
                                long grabAt = System.nanoTime();
                                grabbed = new Grabbed(bi, grabAt);
                                cachedFrame = bi;
                                cachedFrameNanos = grabAt;
                                noteGrabGap();
                            } else {
                                Thread.sleep(5);
                            }
                        } catch (InterruptedException e) {
                            return;
                        } catch (Throwable t) {
                            try {
                                Thread.sleep(20);
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                    }
                } finally {
                    try {
                        m.release();
                    } catch (Throwable ignored) {
                        // 무시
                    }
                }
            }
        }, "apx-cam-grab-" + channel.name());
        grabThread.setDaemon(true);
        grabThread.start();
    }

    private void noteGrabGap() {
        long now = System.nanoTime();
        if (lastGrabNanos > 0) {
            double g = (now - lastGrabNanos) / 1e6;
            if (g > 0.5 && g < 250.0) {
                grabGaps[(grabGapHead + grabGapCount) % GRAB_GAP_N] = g;
                if (grabGapCount < GRAB_GAP_N) {
                    grabGapCount++;
                } else {
                    grabGapHead = (grabGapHead + 1) % GRAB_GAP_N;
                }
                double[] tmp = new double[grabGapCount];
                for (int i = 0; i < grabGapCount; i++) {
                    tmp[i] = grabGaps[(grabGapHead + i) % GRAB_GAP_N];
                }
                java.util.Arrays.sort(tmp);
                grabGapMs = tmp[grabGapCount / 2];
            }
        }
        lastGrabNanos = now;
    }

    /** 최신 프레임(BGR BufferedImage). 미오픈/미수신 시 null. {@code read()} 하지 않는다. */
    public BufferedImage latest() {
        Grabbed g = grabbed;
        return g == null ? cachedFrame : g.image;
    }

    /** {@link #latest()} 프레임이 grab된 {@code System.nanoTime}. 없으면 0. */
    public long latestNanos() {
        Grabbed g = grabbed;
        return g == null ? cachedFrameNanos : g.nanos;
    }

    /** 프레임과 grab 시각을 같이 반환. 없으면 null. */
    public Grabbed latestGrabbed() {
        return grabbed;
    }

    /**
     * 캡처 스레드가 {@code read()} 성공 사이에 잰 주기(ms) 중앙값.
     * 판정기가 같은 픽셀 서명을 건너뛰어 표본이 없을 때 30fps로 위장하지 않기 위해 쓴다.
     * 미오픈/표본 없으면 0.
     */
    public double grabGapMs() {
        return grabGapMs;
    }

    /**
     * 드라이버가 보고하는 요청 FPS. 실제 도착 간격은 판정기의 프레임 서명 중앙값이 맞다.
     * 미오픈 시 0.
     */
    public double fps() {
        return reportedFps;
    }

    public int currentIndex() {
        synchronized (OPEN_LOCK) {
            return currentIndex;
        }
    }

    public boolean isOpen() {
        synchronized (OPEN_LOCK) {
            return cap != null;
        }
    }

    private boolean isOpenUnlocked() {
        return cap != null;
    }

    /** 다른 채널(클러스터↔기어봉)이 이미 연 장치면 true. */
    public boolean isHeldByOther(int index) {
        synchronized (OPEN_LOCK) {
            CameraService other = this == CLUSTER ? GEAR : CLUSTER;
            return other.currentIndex == index && other.isOpenUnlocked();
        }
    }

    public void close() {
        VideoCapture old;
        synchronized (OPEN_LOCK) {
            old = detachUnlocked();
        }
        releaseLater(old);
    }

    /** 필드만 비운다. {@code release}는 락 밖에서 — 네이티브가 행해도 UI/isOpen이 안 멈춘다. */
    private VideoCapture detachUnlocked() {
        grabbing = false;
        VideoCapture old = cap;
        cap = null;
        grabThread = null;
        currentIndex = -1;
        currentName = null;
        cachedFrame = null;
        cachedFrameNanos = 0;
        grabbed = null;
        reportedFps = 0;
        grabGapMs = 0;
        lastGrabNanos = 0;
        grabGapCount = 0;
        grabGapHead = 0;
        return old;
    }

    private static void releaseLater(final VideoCapture c) {
        if (c == null) {
            return;
        }
        OPEN_EXEC.execute(new Runnable() {
            public void run() {
                releaseQuiet(c);
            }
        });
    }

    private static void releaseQuiet(VideoCapture c) {
        if (c == null) {
            return;
        }
        try {
            c.release();
        } catch (Throwable t) {
            // 무시
        }
    }
}
