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

    /** 연결된 웹캠 목록. 드라이버 없음/예외 시 빈 목록. */
    public synchronized List<Cam> list() {
        List<Cam> out = new ArrayList<Cam>();
        try {
            List<Webcam> ws = Webcam.getWebcams();
            for (int i = 0; i < ws.size(); i++) {
                out.add(new Cam(i, ws.get(i).getName()));
            }
        } catch (Throwable t) {
            // 드라이버 미탑재/권한 등 → 빈 목록
        }
        return out;
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

    /** 지원 해상도 중 1280 폭에 가장 가까운 것(없으면 640x480). */
    private Dimension pickSize(Webcam cam) {
        Dimension best = null;
        Dimension[] sizes = cam.getViewSizes();
        if (sizes != null) {
            for (Dimension d : sizes) {
                if (best == null || Math.abs(d.width - 1280) < Math.abs(best.width - 1280)) {
                    best = d;
                }
            }
        }
        return best != null ? best : new Dimension(640, 480);
    }

    /** 최신 프레임(BGR 아님, ARGB BufferedImage). 미오픈/미수신 시 null. */
    public synchronized BufferedImage latest() {
        if (current != null && current.isOpen()) {
            try {
                return current.getImage();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
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
        }
    }
}
