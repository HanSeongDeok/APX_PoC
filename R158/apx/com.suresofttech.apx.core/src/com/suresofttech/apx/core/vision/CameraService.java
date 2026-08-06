package com.suresofttech.apx.core.vision;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import com.github.sarxos.webcam.Webcam;

/**
 * 웹캠 한 대를 열어 최신 프레임을 공유하는 서비스 (파이썬 main_window.py 의 단일 cap 대응).
 *
 * <p>순수 자바 webcam-capture(Sarxos) 사용. 프레임은 {@link BufferedImage}(AWT)로 반환하며
 * UI(SWT)는 표시할 때만 변환한다. 퍼스펙티브에 4개 View가 동시에 떠 있어도 카메라는 하나만
 * 열고, 각 View가 {@link #latest()}로 최신 프레임을 폴링한다.
 *
 * <p>싱글턴. {@link #open(int)}은 설정 View에서 카메라 선택 시 호출, {@link #close()}는 앱 종료 시.
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

    private static final CameraService INSTANCE = new CameraService();

    public static CameraService get() {
        return INSTANCE;
    }

    private CameraService() {
    }

    private Webcam current;
    private int currentIndex = -1;

    // 프레임 공유 캐시: 여러 뷰(기어·클러스터)가 짧은 시간에 latest()를 여러 번 불러도
    // getImage()(프레임 복사)는 CACHE_NS 안에 1회만 → 부하↓ + 두 뷰가 같은 프레임 → 동기 정확.
    private static final long CACHE_NS = 10_000_000L;   // 10ms
    private BufferedImage cachedFrame;
    private long cacheNanos;

    /**
     * 현재 연결된 웹캠 목록(가상캠 제외). 표시명은 드라이버 끝 인덱스({@code " … 0"})를 제거.
     * USB로 보이는 장치가 하나라도 있으면 USB만, 없으면 비가상 장치를 모두 반환.
     */
    public synchronized List<Cam> list() {
        List<Cam> all = new ArrayList<Cam>();
        List<Cam> usb = new ArrayList<Cam>();
        try {
            List<Webcam> ws = Webcam.getWebcams();
            for (int i = 0; i < ws.size(); i++) {
                String raw = ws.get(i).getName();
                if (isVirtualCamera(raw) || isBuiltinCamera(raw)) {
                    continue;   // 가상캠·노트북 내장캠 제외 → USB/외장만
                }
                Cam cam = new Cam(i, displayName(raw));
                all.add(cam);
                if (looksUsbCamera(raw)) {
                    usb.add(cam);
                }
            }
        } catch (Throwable t) {
            // 드라이버 미탑재/권한 등 → 빈 목록
        }
        return usb.isEmpty() ? all : usb;
    }

    /** 콤보 표시용 — 끝의 장치 인덱스 숫자 제거 ("Logitech … 0" → "Logitech …"). */
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

    /** 노트북 내장 캠 — USB 외장과 구분. */
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

    /** 지정 인덱스 웹캠 열기(기존 것 닫고). 성공 여부 반환. */
    public synchronized boolean open(int index) {
        close();
        try {
            List<Webcam> ws = Webcam.getWebcams();
            if (index < 0 || index >= ws.size()) {
                return false;
            }
            Webcam cam = ws.get(index);
            cam.setViewSize(pickSize(cam));
            cam.open(true);                 // async — 내부 스레드가 최신 프레임 유지
            current = cam;
            currentIndex = index;
            return cam.isOpen();
        } catch (Throwable t) {
            current = null;
            currentIndex = -1;
            return false;
        }
    }

    /**
     * 지원 해상도 중 640 폭에 가장 가까운 것(없으면 640x480).
     * <p>sarxos 기본 드라이버는 raw(YUY2) 포맷이라 고해상도(720p+)는 USB 대역폭상 15fps로 떨어진다.
     * 최대 프레임(C930e 30fps)을 확보하려면 640x480 급을 선택. 고해상도+30fps가 필요하면
     * OpenCV VideoCapture(MJPEG) 캡처로 전환해야 한다.
     */
    private Dimension pickSize(Webcam cam) {
        Dimension best = null;
        Dimension[] sizes = cam.getViewSizes();
        if (sizes != null) {
            for (Dimension d : sizes) {
                if (best == null || Math.abs(d.width - 640) < Math.abs(best.width - 640)) {
                    best = d;
                }
            }
        }
        return best != null ? best : new Dimension(640, 480);
    }

    /** 최신 프레임(BGR 아님, ARGB BufferedImage). 미오픈/미수신 시 null. CACHE_NS 안엔 캐시 공유. */
    public synchronized BufferedImage latest() {
        if (current == null || !current.isOpen()) {
            return null;
        }
        long now = System.nanoTime();
        if (cachedFrame != null && (now - cacheNanos) < CACHE_NS) {
            return cachedFrame;                 // 캐시 공유(여러 뷰가 같은 프레임 → getImage 부하↓·동기 정확)
        }
        try {
            cachedFrame = current.getImage();
            cacheNanos = now;
            return cachedFrame;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 드라이버가 보고하는 실측 FPS(미오픈 시 0). 1000/fps ≈ 새 프레임 간격(ms). */
    public synchronized double fps() {
        if (current != null && current.isOpen()) {
            try {
                return current.getFPS();
            } catch (Throwable t) {
                return 0;
            }
        }
        return 0;
    }

    public synchronized int currentIndex() {
        return currentIndex;
    }

    public synchronized boolean isOpen() {
        return current != null && current.isOpen();
    }

    public synchronized void close() {
        if (current != null) {
            try {
                current.close();
            } catch (Throwable t) {
                // 무시
            }
            current = null;
            currentIndex = -1;
            cachedFrame = null;
        }
    }
}
