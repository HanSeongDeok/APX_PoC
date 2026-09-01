package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.Videoio;
import org.opencv.videoio.VideoWriter;

/**
 * 측정 시작~중단 구간의 비전 FULL 녹화. SWT無(core).
 *
 * <p>MJPG AVI로 쓴다 - 전 프레임이 키프레임이라 결과 탭 스크럽에서 임의 시점 역방향
 * 시크가 정확하다(H.264는 Windows 기본 배포에 인코더가 없어 열리지 않는다).
 *
 * <p>웹캠 실 fps는 흔들리므로 컨테이너 fps만 믿을 수 없다. 그래서 프레임마다
 * {@code frames.csv}에 {@code <프레임번호>,<측정시작기준 ms>}를 남기고, 재생 측
 * 재생 측은 이 인덱스로 시각→프레임을 찾는다(결과 뷰는 클라이언트 구현).
 *
 * <p>인코딩은 워커 스레드에서 한다 - UI 폴링 스레드가 JPEG 압축에 물리지 않게.
 * 큐가 차면 <b>가장 오래된 프레임을 버리고</b> 최신을 넣는다(측정 자체를 지연시키지 않는 쪽 우선).
 */
public final class VisionRecorder {

    /** 녹화 산출물 - 영상 + 시각 인덱스. */
    public static final class Recording {
        public final File video;
        public final File index;
        public final int frameCount;
        public final double lastMs;

        public Recording(File video, File index, int frameCount, double lastMs) {
            this.video = video;
            this.index = index;
            this.frameCount = frameCount;
            this.lastMs = lastMs;
        }
    }

    public static final String VIDEO_NAME = "full.avi";
    public static final String INDEX_NAME = "frames.csv";

    /** 컨테이너에 기록할 명목 fps - 실제 시각은 frames.csv가 정답. */
    private static final double NOMINAL_FPS = 30.0;
    /**
     * FHD 한 장은 약 6MB라 32장 × 두 채널이면 프레임 참조만 약 400MB가 된다.
     * Writer는 측정 전에 열어 두므로 짧은 최신 큐만 유지하고, 밀리면 오래된 프레임을 버린다.
     */
    private static final int QUEUE_CAP = 4;

    private static final class Job {
        final BufferedImage img;
        final double tMs;

        Job(BufferedImage img, double tMs) {
            this.img = img;
            this.tMs = tMs;
        }
    }

    private final BlockingQueue<Job> queue = new ArrayBlockingQueue<Job>(QUEUE_CAP);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private File dir;
    private File videoFile;
    private File indexFile;
    private VideoWriter writer;
    private PrintWriter index;
    private Thread worker;
    private volatile CountDownLatch readyLatch = new CountDownLatch(0);
    private volatile boolean ready;
    private int width;
    private int height;
    private volatile int frameCount;
    private volatile double lastMs;
    private volatile int dropped;
    /** 해상도가 달라 레터박스로 맞춰 넣은 프레임 수 - 측정 중 하드웨어 변경 흔적. */
    private volatile int resized;

    /** 해상도를 모를 때 - 첫 프레임이 올 때 writer를 연다(그 사이 프레임은 큐가 받친다). */
    public synchronized void start(File dir) {
        start(dir, 0, 0);
    }

    /**
     * 녹화 시작.
     *
     * <p>해상도를 미리 주면 <b>첫 프레임 전에</b> writer를 열어둔다 - VideoWriter 오픈은
     * 수백 ms가 걸려서, 첫 프레임에 열면 그동안 들어오는 측정 앞부분이 큐에서 밀려난다.
     *
     * @param dir 녹화 산출물 폴더(없으면 생성)
     * @param w 프레임 폭(모르면 0)
     * @param h 프레임 높이(모르면 0)
     */
    public synchronized void start(File dir, int w, int h) {
        if (running.get()) {
            return;
        }
        this.dir = dir;
        this.videoFile = null;
        this.indexFile = null;
        this.writer = null;
        this.index = null;
        this.width = 0;
        this.height = 0;
        this.frameCount = 0;
        this.lastMs = 0;
        this.dropped = 0;
        this.ready = false;
        this.readyLatch = new CountDownLatch(1);
        queue.clear();
        running.set(true);

        final int w0 = w;
        final int h0 = h;
        worker = new Thread(new Runnable() {
            public void run() {
                try {
                    if (w0 > 0 && h0 > 0) {
                        ready = ensureSinks(w0, h0);
                    }
                } finally {
                    readyLatch.countDown();
                }
                if (ready) {
                    pump();
                }
            }
        }, "apx-vision-recorder");
        worker.setDaemon(true);
        worker.start();
    }

    public boolean isRunning() {
        return running.get();
    }

    /** VideoWriter와 인덱스 파일이 실제로 열린 상태까지 기다린다. */
    public boolean awaitReady(long timeoutMs) throws InterruptedException {
        CountDownLatch latch = readyLatch;
        return latch.await(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS) && ready;
    }

    /** 큐가 가득 차 버려진 프레임 수 - 진단용. */
    public int getDroppedFrames() {
        return dropped;
    }

    /**
     * 녹화 해상도와 달라 레터박스로 맞춰 넣은 프레임 수.
     * 0보다 크면 측정 도중 카메라 / 해상도가 바뀌었다는 뜻이다.
     */
    public int getResizedFrames() {
        return resized;
    }

    /** 녹화 중인 프레임 크기 - 아직 writer가 안 열렸으면 0. */
    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * 프레임 투입. 카메라 서비스가 프레임마다 새 BufferedImage를 만들므로 참조만 큐에 넣고,
     * Mat 변환과 인코딩은 모두 워커가 한다.
     * @param tMs 측정 시작 기준 경과(ms) - PASS 시각과 같은 시간축이어야 스크럽이 맞는다
     */
    public void feed(BufferedImage bi, double tMs) {
        if (!running.get() || bi == null) {
            return;
        }
        Job job = new Job(bi, tMs);
        if (!queue.offer(job)) {
            Job old = queue.poll();     // 최신 우선 - 오래된 프레임을 버린다
            if (old != null) {
                dropped++;
            }
            if (!queue.offer(job)) {
                dropped++;
            }
        }
    }

    /**
     * 녹화 종료 - 큐를 비우고 파일을 닫는다.
     * @return 산출물(프레임이 하나도 없으면 null)
     */
    public synchronized Recording stop() {
        if (!running.getAndSet(false)) {
            return null;
        }
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(3000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }
        drainRemaining();
        closeSinks();
        if (frameCount <= 0 || videoFile == null) {
            return null;
        }
        return new Recording(videoFile, indexFile, frameCount, lastMs);
    }

    // ── 워커 ────────────────────────────────────────────────────

    private void pump() {
        while (running.get()) {
            Job job;
            try {
                job = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (job != null) {
                writeOne(job);
            }
        }
    }

    private void drainRemaining() {
        Job job;
        while ((job = queue.poll()) != null) {
            writeOne(job);
        }
    }

    private void writeOne(Job job) {
        Mat source = null;
        Mat fitted = null;
        try {
            Cv.ensureLoaded();
            source = Cv.toMat(job.img);
            if (source == null || source.empty()) {
                return;
            }
            int srcW = source.cols();
            int srcH = source.rows();
            if (!ensureSinks(srcW, srcH)) {
                return;
            }
            Mat out = source;
            if (srcW != width || srcH != height) {
                // 측정 도중 웹캠 교체 / 해상도 변경 - AVI 컨테이너는 프레임 크기 변경을 못 받는다.
                // 버리면 그 뒤 구간이 통째로 사라지므로, 비율을 유지한 채 레터박스로 맞춰 넣고
                // 원본 해상도는 frames.csv에 남겨 증거에서 변경 시점을 알 수 있게 한다.
                fitted = letterbox(source, width, height);
                if (fitted == null) {
                    return;
                }
                out = fitted;
                resized++;
            }
            writer.write(out);
            index.println(frameCount + "," + Math.round(job.tMs) + "," + srcW + "," + srcH);
            frameCount++;
            lastMs = job.tMs;
        } catch (Throwable t) {
            // 프레임 하나 실패로 측정을 죽이지 않는다
        } finally {
            if (fitted != null) {
                fitted.release();
            }
            if (source != null) {
                source.release();
            }
        }
    }

    /** 비율 유지 축소 + 검은 여백 - 늘려서 왜곡시키지 않는다(증거 화면이므로). */
    private static Mat letterbox(Mat src, int dstW, int dstH) {
        Mat resizedMat = null;
        Mat roi = null;
        try {
            double s = Math.min(dstW / (double) src.cols(), dstH / (double) src.rows());
            int w = Math.max(1, (int) Math.round(src.cols() * s));
            int h = Math.max(1, (int) Math.round(src.rows() * s));
            Mat canvas = new Mat(dstH, dstW, src.type(), Scalar.all(0));
            resizedMat = new Mat();
            Imgproc.resize(src, resizedMat, new Size(w, h));
            roi = canvas.submat(new Rect((dstW - w) / 2, (dstH - h) / 2, w, h));
            resizedMat.copyTo(roi);
            return canvas;
        } catch (Throwable t) {
            return null;
        } finally {
            if (roi != null) {
                roi.release();
            }
            if (resizedMat != null) {
                resizedMat.release();
            }
        }
    }

    /** 첫 프레임 해상도로 writer/index를 연다. 실패하면 이후 프레임은 무시. */
    private boolean ensureSinks(int w, int h) {
        if (writer != null) {
            return writer.isOpened();
        }
        if (dir == null || w <= 0 || h <= 0) {
            return false;
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            width = w;
            height = h;
            videoFile = new File(dir, VIDEO_NAME);
            indexFile = new File(dir, INDEX_NAME);
            int fourcc = VideoWriter.fourcc('M', 'J', 'P', 'G');
            writer = new VideoWriter();
            // CAP_ANY는 사용 가능한 인코더를 찾다가 AVI 경로를 CV_IMAGES에 넘긴다.
            // CV_IMAGES는 "%04d.jpg" 같은 이미지 시퀀스 전용이라
            // !filename_pattern.empty() 오류를 출력한다. OpenCV 내장 MJPEG AVI
            // 백엔드를 지정해 코덱 설치 여부와 백엔드 탐색 순서에 영향받지 않게 한다.
            writer.open(videoFile.getAbsolutePath(), Videoio.CAP_OPENCV_MJPEG, fourcc,
                    NOMINAL_FPS, new Size(w, h), true);
            if (!writer.isOpened()) {
                writer.release();
                writer = null;
                return false;
            }
            index = new PrintWriter(indexFile, "UTF-8");
            // srcW/srcH = 그 프레임의 원본 해상도. 녹화 해상도와 다르면 도중에 하드웨어가 바뀐 것.
            index.println("frame,ms,srcW,srcH");
            return true;
        } catch (Throwable t) {
            closeSinks();
            return false;
        }
    }

    private void closeSinks() {
        if (writer != null) {
            try {
                writer.release();
            } catch (Throwable ignored) {
                // 무시
            }
            writer = null;
        }
        if (index != null) {
            index.flush();
            index.close();
            index = null;
        }
    }
}
