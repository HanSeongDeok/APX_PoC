package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

/**
 * OpenCV 공통 유틸 — 네이티브 로딩 + 한글경로 imread + BufferedImage↔Mat 변환.
 *
 * <p>네이티브(opencv_java490.dll)는 openpnp 번들에 포함, {@link #ensureLoaded()}가 임시폴더로
 * 풀어 로드(오프라인 OK). webcam-capture는 프레임을 {@link BufferedImage}로 주므로 처리 전
 * {@link #toMat(BufferedImage)}로 BGR Mat 변환, 표시용은 {@link #toBufferedImage(Mat)}.
 */
public final class Cv {

    private Cv() {
    }

    private static boolean loaded = false;

    /** OpenCV 네이티브 1회 로드(멱등). 어떤 OpenCV 호출보다 먼저 실행돼야 함. */
    public static synchronized void ensureLoaded() {
        if (!loaded) {
            nu.pattern.OpenCV.loadLocally();
            loaded = true;
        }
    }

    /** 한글/유니코드 경로 대응 imread (파이썬 np.fromfile+imdecode 대응). 실패 시 null. */
    public static Mat imreadKr(String path) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            if (bytes.length == 0) {
                return null;
            }
            MatOfByte buf = new MatOfByte(bytes);
            Mat img = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR);
            buf.release();
            return (img != null && !img.empty()) ? img : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** AWT BufferedImage → OpenCV BGR Mat(CV_8UC3). 렌더링(Graphics2D) 없이 픽셀만 접근. */
    public static Mat toMat(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        Mat m = new Mat(h, w, CvType.CV_8UC3);
        if (src.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            // 웹캠 기본 타입 — 래스터 바이트 그대로 복사(가장 빠름)
            byte[] px = ((DataBufferByte) src.getRaster().getDataBuffer()).getData();
            m.put(0, 0, px);
            return m;
        }
        // 일반 타입 — ColorModel 경유 ARGB 읽어 BGR로 패킹(Graphics2D 불필요)
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        byte[] bgr = new byte[w * h * 3];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            bgr[i * 3] = (byte) (p & 0xFF);              // B
            bgr[i * 3 + 1] = (byte) ((p >> 8) & 0xFF);   // G
            bgr[i * 3 + 2] = (byte) ((p >> 16) & 0xFF);  // R
        }
        m.put(0, 0, bgr);
        return m;
    }

    /** OpenCV Mat(BGR 3ch 또는 GRAY 1ch) → AWT BufferedImage. */
    public static BufferedImage toBufferedImage(Mat m) {
        int ch = m.channels();
        int type = ch > 1 ? BufferedImage.TYPE_3BYTE_BGR : BufferedImage.TYPE_BYTE_GRAY;
        int w = m.cols();
        int h = m.rows();
        byte[] data = new byte[w * h * ch];
        m.get(0, 0, data);
        BufferedImage img = new BufferedImage(w, h, type);
        byte[] target = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        System.arraycopy(data, 0, target, 0, data.length);
        return img;
    }
}
