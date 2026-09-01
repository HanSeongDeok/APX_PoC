package com.suresofttech.apx.core.measure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.vision.VisionChannel;

/**
 * 측정 시작 시점의 {@link ApxSettings} 고정 복사본.
 * 세션 중 설정 UI 변경은 이 스냅샷에 반영되지 않는다.
 */
public final class MeasureConfigSnapshot {

    public final String micName;
    public final String expectedWavPath;
    public final double audioFreqThr;
    public final double audioWaveThr;
    public final double syncToleranceMs;

    public final boolean useReferenceImage;
    public final String visionRefPath;
    public final double[] roiNorm;
    public final double simThr;
    public final String visionJudge;
    public final boolean autoPlayExpectedOnVisionPass;

    public final boolean gearUseReferenceImage;
    public final String gearVisionRefPath;
    public final double[] gearRoiNorm;
    public final double gearSimThr;
    public final String gearVisionJudge;

    public final int rearCols;
    public final int rearRows;
    public final boolean rearShowLegend;
    public final List<int[]> rearSelectedPoints;

    public MeasureConfigSnapshot(String micName, String expectedWavPath,
            double audioFreqThr, double audioWaveThr, double syncToleranceMs,
            boolean useReferenceImage, String visionRefPath, double[] roiNorm, double simThr,
        String visionJudge, boolean autoPlayExpectedOnVisionPass,
        boolean gearUseReferenceImage, String gearVisionRefPath, double[] gearRoiNorm,
        double gearSimThr, String gearVisionJudge,
            int rearCols, int rearRows, boolean rearShowLegend, List<int[]> rearSelectedPoints) {
        this.micName = micName;
        this.expectedWavPath = expectedWavPath;
        this.audioFreqThr = audioFreqThr;
        this.audioWaveThr = audioWaveThr;
        this.syncToleranceMs = syncToleranceMs;
        this.useReferenceImage = useReferenceImage;
        this.visionRefPath = visionRefPath;
        this.roiNorm = roiNorm == null ? null : roiNorm.clone();
        this.simThr = simThr;
        this.visionJudge = visionJudge;
        this.autoPlayExpectedOnVisionPass = autoPlayExpectedOnVisionPass;
        this.gearUseReferenceImage = gearUseReferenceImage;
        this.gearVisionRefPath = gearVisionRefPath;
        this.gearRoiNorm = gearRoiNorm == null ? null : gearRoiNorm.clone();
        this.gearSimThr = gearSimThr;
        this.gearVisionJudge = gearVisionJudge;
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
                s.getSyncToleranceMs(),
            s.isUseReferenceImage(VisionChannel.CLUSTER),
            s.getVisionRefPath(VisionChannel.CLUSTER),
            s.getRoiNorm(VisionChannel.CLUSTER),
            s.getSimThr(VisionChannel.CLUSTER),
            s.getVisionJudge(VisionChannel.CLUSTER),
            s.isAutoPlayExpectedOnVisionPass(),
            s.isUseReferenceImage(VisionChannel.GEAR),
            s.getVisionRefPath(VisionChannel.GEAR),
            s.getRoiNorm(VisionChannel.GEAR),
            s.getSimThr(VisionChannel.GEAR),
            s.getVisionJudge(VisionChannel.GEAR),
                s.getRearCols(),
                s.getRearRows(),
                s.isRearShowLegend(),
                s.getRearSelectedPoints());
    }

    public boolean useReferenceImage(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearUseReferenceImage : useReferenceImage;
    }

    public String visionRefPath(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearVisionRefPath : visionRefPath;
    }

    public double[] roiNorm(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearRoiNorm : roiNorm;
    }

    public double simThr(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearSimThr : simThr;
    }

    public String visionJudge(VisionChannel ch) {
        return ch == VisionChannel.GEAR ? gearVisionJudge : visionJudge;
    }

    /** 스냅샷 roiNorm 기준 픽셀 ROI {y1,y2,x1,x2}. 클러스터. */
    public int[] toRoiPixels(int frameW, int frameH) {
        return toRoiPixels(VisionChannel.CLUSTER, frameW, frameH);
    }

    public int[] toRoiPixels(VisionChannel ch, int frameW, int frameH) {
        double[] n = roiNorm(ch);
        if (n == null || frameW <= 0 || frameH <= 0) {
            return null;
        }
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
