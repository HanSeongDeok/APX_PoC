package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;

/**
 * 화면 플래시(밝기 급상승) 검출기 — 비전 D_cap 캘리브용. SWT/RCP 무의존(core).
 *
 * <p>웹캠을 화면으로 향한 상태에서, 화면이 흰색으로 번쩍이는 순간을 카메라가 <b>얼마나 늦게</b>
 * 잡는지(=화면출력+D_cap) 재기 위해, 프레임 평균 밝기의 상승 엣지를 검출한다.
 * 사용 순서: {@link #arm()} → 플래시 전 프레임들을 {@link #observeBaseline}로 기준밝기 확보 →
 * 플래시 후 프레임들을 {@link #detect}에 넣어 기준+임계 이상으로 뛰는 첫 시각을 잡는다.
 *
 * <p>단일 스레드(캘리브 워커)에서만 쓰인다 — 동기화 없음.
 */
public final class FlashProbe {

    private final double deltaThresh;   // 상승으로 인정할 밝기 증가량(0..255)
    private double baseSum;
    private int baseCount;
    private double baseline = Double.NaN;
    private boolean armed;
    private double onsetT = Double.NaN;

    /** @param deltaThresh 기준 대비 밝기 상승 임계(예: 40). 클수록 확실한 플래시만 인정. */
    public FlashProbe(double deltaThresh) {
        this.deltaThresh = deltaThresh;
    }

    /** 새 측정 시작 — 기준·검출 상태 초기화. */
    public void arm() {
        baseSum = 0;
        baseCount = 0;
        baseline = Double.NaN;
        armed = true;
        onsetT = Double.NaN;
    }

    /** 플래시 전 프레임 누적 → 기준밝기(평균) 갱신. */
    public void observeBaseline(BufferedImage img) {
        if (img == null) {
            return;
        }
        baseSum += meanLuma(img);
        baseCount++;
        baseline = baseSum / baseCount;
    }

    /**
     * 플래시 후 프레임 검사 — 밝기가 기준+임계 이상이면 그 시각을 onset 으로 기록(최초 1회).
     * @return 이번에 onset을 잡았으면 true.
     */
    public boolean detect(BufferedImage img, double tSec) {
        if (!armed || img == null || Double.isNaN(baseline)) {
            return false;
        }
        if (meanLuma(img) >= baseline + deltaThresh) {
            onsetT = tSec;
            armed = false;
            return true;
        }
        return false;
    }

    /** 검출된 onset 시각(초). 미검출이면 NaN. */
    public double onsetT() {
        return onsetT;
    }

    public double baseline() {
        return baseline;
    }

    /** 프레임 평균 밝기(0..255) — 격자 샘플링(최대 ~40×40)으로 빠르게. */
    public static double meanLuma(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) {
            return 0;
        }
        int stepX = Math.max(1, w / 40);
        int stepY = Math.max(1, h / 40);
        long sum = 0;
        int cnt = 0;
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                sum += (r + g + b) / 3;
                cnt++;
            }
        }
        return cnt > 0 ? (double) sum / cnt : 0;
    }
}
