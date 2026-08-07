package com.suresofttech.apx.core.measure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.suresofttech.apx.core.config.ApxSettings;

/**
 * 측정 시작 시점의 {@link ApxSettings} 고정 복사본.
 * 세션 중 설정 UI 변경은 이 스냅샷에 반영되지 않는다.
 */
public final class MeasureConfigSnapshot {

    public final String micName;
    public final String expectedWavPath;
    public final double audioFreqThr;
    public final double audioWaveThr;

    public final boolean useReferenceImage;
    public final String visionRefPath;
    public final double[] roiNorm;
    public final double simThr;

    public final int rearCols;
    public final int rearRows;
    public final boolean rearShowLegend;
    public final List<int[]> rearSelectedPoints;

    public MeasureConfigSnapshot(String micName, String expectedWavPath,
            double audioFreqThr, double audioWaveThr,
            boolean useReferenceImage, String visionRefPath, double[] roiNorm, double simThr,
            int rearCols, int rearRows, boolean rearShowLegend, List<int[]> rearSelectedPoints) {
        this.micName = micName;
        this.expectedWavPath = expectedWavPath;
        this.audioFreqThr = audioFreqThr;
        this.audioWaveThr = audioWaveThr;
        this.useReferenceImage = useReferenceImage;
        this.visionRefPath = visionRefPath;
        this.roiNorm = roiNorm == null ? null : roiNorm.clone();
        this.simThr = simThr;
        this.rearCols = rearCols;
        this.rearRows = rearRows;
        this.rearShowLegend = rearShowLegend;
        this.rearSelectedPoints = copyPoints(rearSelectedPoints);
    }

    public static MeasureConfigSnapshot from(ApxSettings s) {
        if (s == null) {
            s = ApxSettings.get();
        }
        return new MeasureConfigSnapshot(
                s.getMicName(),
                s.getExpectedWavPath(),
                s.getAudioFreqThr(),
                s.getAudioWaveThr(),
                s.isUseReferenceImage(),
                s.getVisionRefPath(),
                s.getRoiNorm(),
                s.getSimThr(),
                s.getRearCols(),
                s.getRearRows(),
                s.isRearShowLegend(),
                s.getRearSelectedPoints());
    }

    /** 스냅샷 roiNorm 기준 픽셀 ROI {y1,y2,x1,x2}. */
    public int[] toRoiPixels(int frameW, int frameH) {
        if (roiNorm == null || frameW <= 0 || frameH <= 0) {
            return null;
        }
        double[] n = roiNorm;
        int y1 = (int) Math.round(n[0] * frameH);
        int y2 = (int) Math.round(n[1] * frameH);
        int x1 = (int) Math.round(n[2] * frameW);
        int x2 = (int) Math.round(n[3] * frameW);
        y1 = clamp(y1, 0, frameH - 1);
        y2 = clamp(y2, y1 + 1, frameH);
        x1 = clamp(x1, 0, frameW - 1);
        x2 = clamp(x2, x1 + 1, frameW);
        return new int[] { y1, y2, x1, x2 };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static List<int[]> copyPoints(List<int[]> pts) {
        if (pts == null || pts.isEmpty()) {
            return Collections.emptyList();
        }
        List<int[]> out = new ArrayList<int[]>(pts.size());
        for (int i = 0; i < pts.size(); i++) {
            int[] p = pts.get(i);
            if (p != null && p.length >= 2) {
                out.add(new int[] { p[0], p[1] });
            }
        }
        return Collections.unmodifiableList(out);
    }
}
