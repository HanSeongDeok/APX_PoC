package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;


/**
 * 비전 FULL 녹화본 재생/스크럽 리더. SWT無(core).
 *
 * <p>{@link VisionRecorder}가 남긴 {@code frames.csv}(프레임번호,측정시작기준ms)로
 * <b>시각 → 프레임번호</b>를 찾고, MJPG AVI라 그 프레임으로 바로 시크한다.
 * 인덱스가 없으면 컨테이너 fps로 근사한다(정확도는 떨어진다).
 *
 * <p>스레드 안전하지 않다 — UI 스레드에서만 쓴다. 다 쓰면 {@link #close()}.
 */
public final class VisionPlayer {

    private VideoCapture cap;
    private long[] frameMs;        // idx → 측정시작기준 ms (인덱스 파일 기준)
    private int frameCount;
    private double fps;
    private int lastFrame = -1;
    private BufferedImage lastImage;

    private VisionPlayer() {
    }

    /**
     * 녹화 폴더를 연다 — {@code full.avi} + (있으면) {@code frames.csv}.
     * @return 열지 못하면 null
     */
    public static VisionPlayer open(File visionDir) {
        if (visionDir == null) {
            return null;
        }
        return openFile(new File(visionDir, VisionRecorder.VIDEO_NAME),
                new File(visionDir, VisionRecorder.INDEX_NAME));
    }

    /** 영상·인덱스 파일을 직접 지정해 연다. 인덱스는 null 허용. */
    public static VisionPlayer openFile(File video, File index) {
        if (video == null || !video.isFile()) {
            return null;
        }
        try {
            Cv.ensureLoaded();
        } catch (Throwable t) {
            return null;
        }
        VideoCapture c = new VideoCapture(video.getAbsolutePath());
        if (!c.isOpened()) {
            c.release();
            return null;
        }
        VisionPlayer p = new VisionPlayer();
        p.cap = c;
        p.frameCount = (int) Math.max(0, c.get(Videoio.CAP_PROP_FRAME_COUNT));
        p.fps = c.get(Videoio.CAP_PROP_FPS);
        if (p.fps <= 0) {
            p.fps = 30.0;
        }
        p.frameMs = readIndex(index, p.frameCount);
        return p;
    }

    public boolean hasIndex() {
        return frameMs != null && frameMs.length > 0;
    }

    public int getFrameCount() {
        return frameCount;
    }

    /** 녹화 길이(ms) — 인덱스가 있으면 마지막 프레임 시각, 없으면 fps 환산. */
    public double durationMs() {
        if (hasIndex()) {
            return frameMs[frameMs.length - 1];
        }
        return frameCount <= 0 ? 0 : frameCount * 1000.0 / fps;
    }

    /** 첫 프레임 시각(ms) — 보통 0 근처. */
    public double startMs() {
        return hasIndex() ? frameMs[0] : 0;
    }

    /**
     * 시각(ms)에 해당하는 프레임 번호 — 그 시각 <b>이하</b>의 가장 늦은 프레임.
     * (스크럽에서 "아직 안 온 미래 프레임"을 보여주지 않도록)
     */
    public int frameAt(double tMs) {
        if (!hasIndex()) {
            int idx = (int) Math.floor(tMs / 1000.0 * fps);
            return clamp(idx, 0, Math.max(0, frameCount - 1));
        }
        long t = Math.round(tMs);
        int lo = 0;
        int hi = frameMs.length - 1;
        if (t <= frameMs[0]) {
            return 0;
        }
        if (t >= frameMs[hi]) {
            return hi;
        }
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (frameMs[mid] <= t) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** 프레임 번호의 실제 시각(ms). 인덱스가 없으면 fps 환산. */
    public double timeOf(int frame) {
        if (hasIndex() && frame >= 0 && frame < frameMs.length) {
            return frameMs[frame];
        }
        return frame * 1000.0 / fps;
    }

    /**
     * 시각(ms) 프레임 이미지. 같은 프레임을 다시 요청하면 디코딩 없이 캐시를 준다
     * (슬라이더가 잘게 움직여도 프레임이 안 바뀌면 시크하지 않는다).
     * @return 실패 시 null
     */
    public BufferedImage frameImageAt(double tMs) {
        return frameImage(frameAt(tMs));
    }

    /** 프레임 번호로 이미지 조회. */
    public BufferedImage frameImage(int frame) {
        if (cap == null || frame < 0) {
            return null;
        }
        if (frame == lastFrame && lastImage != null) {
            return lastImage;
        }
        Mat m = new Mat();
        try {
            // 순차 재생이면 시크 없이 read — MJPG라도 seek 비용이 있어서
            if (frame != lastFrame + 1) {
                cap.set(Videoio.CAP_PROP_POS_FRAMES, frame);
            }
            if (!cap.read(m) || m.empty()) {
                return lastImage;
            }
            BufferedImage bi = Cv.toBufferedImage(m);
            if (bi != null) {
                lastFrame = frame;
                lastImage = bi;
            }
            return bi;
        } catch (Throwable t) {
            return lastImage;
        } finally {
            m.release();
        }
    }

    public void close() {
        if (cap != null) {
            try {
                cap.release();
            } catch (Throwable ignored) {
                // 무시
            }
            cap = null;
        }
        lastImage = null;
        lastFrame = -1;
    }

    /** {@code frame,ms} CSV → idx별 ms 배열. 없거나 깨졌으면 null. */
    private static long[] readIndex(File index, int frameCount) {
        if (index == null || !index.isFile()) {
            return null;
        }
        List<Long> times = new ArrayList<Long>(Math.max(16, frameCount));
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(index), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                // frame,ms[,srcW,srcH] — 뒤 열은 원본 해상도(측정 중 변경 추적용)로,
                // 여기서는 앞 두 열만 쓴다. 열이 늘어도 인덱스가 깨지지 않아야 한다.
                String[] parts = line.split(",");
                if (parts.length < 2) {
                    continue;
                }
                try {
                    int idx = Integer.parseInt(parts[0].trim());
                    long ms = Long.parseLong(parts[1].trim());
                    while (times.size() < idx) {
                        times.add(Long.valueOf(times.isEmpty()
                                ? 0L : times.get(times.size() - 1).longValue()));
                    }
                    if (times.size() == idx) {
                        times.add(Long.valueOf(ms));
                    } else {
                        times.set(idx, Long.valueOf(ms));
                    }
                } catch (NumberFormatException ignored) {
                    // 헤더(frame,ms) 등은 건너뜀
                }
            }
        } catch (Exception ex) {
            return null;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Exception ignored) {
                    // 무시
                }
            }
        }
        if (times.isEmpty()) {
            return null;
        }
        long[] out = new long[times.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = times.get(i).longValue();
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
