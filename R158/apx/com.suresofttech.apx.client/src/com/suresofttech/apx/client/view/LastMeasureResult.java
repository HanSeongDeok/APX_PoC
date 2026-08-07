package com.suresofttech.apx.client.view;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 가장 최근 측정(시작→중단) 결과 — DB 없이 메모리 1건만 보관(시뮬레이터용).
 * Kickoff가 중단 시 {@link #publish} 하고, {@link ResultView}가 구독해 표시한다.
 */
public final class LastMeasureResult {

    public interface Listener {
        void onResult(LastMeasureResult result);
    }

    private static final LastMeasureResult INSTANCE = new LastMeasureResult();

    public static LastMeasureResult get() {
        return INSTANCE;
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();

    private volatile boolean hasResult;
    private volatile long stoppedAtEpochMs;
    private volatile boolean overallPass;
    private volatile String summary = "";
    private volatile Long audioPassMs;
    private volatile Long visionPassMs;
    private volatile Double audioJudgeMs;
    private volatile Double visionJudgeMs;
    private volatile Double audioGapMs;
    private volatile Double visionGapMs;
    private volatile Double audioAnalysisMs;
    private volatile Double visionAnalysisMs;
    private volatile Double syncSpreadMs;
    private volatile boolean syncOk;
    private volatile byte[] audioPassPng;
    private volatile byte[] visionPassPng;
    private volatile byte[] rearPassPng;

    private LastMeasureResult() {
    }

    public void addListener(Listener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public boolean hasResult() {
        return hasResult;
    }

    public long getStoppedAtEpochMs() {
        return stoppedAtEpochMs;
    }

    public boolean isOverallPass() {
        return overallPass;
    }

    public String getSummary() {
        return summary;
    }

    public Long getAudioPassMs() {
        return audioPassMs;
    }

    public Long getVisionPassMs() {
        return visionPassMs;
    }

    public Double getAudioJudgeMs() {
        return audioJudgeMs;
    }

    public Double getVisionJudgeMs() {
        return visionJudgeMs;
    }

    public Double getAudioGapMs() {
        return audioGapMs;
    }

    public Double getVisionGapMs() {
        return visionGapMs;
    }

    public Double getAudioAnalysisMs() {
        return audioAnalysisMs;
    }

    public Double getVisionAnalysisMs() {
        return visionAnalysisMs;
    }

    public Double getSyncSpreadMs() {
        return syncSpreadMs;
    }

    public boolean isSyncOk() {
        return syncOk;
    }

    public byte[] getAudioPassPng() {
        return audioPassPng == null ? null : audioPassPng.clone();
    }

    public byte[] getVisionPassPng() {
        return visionPassPng == null ? null : visionPassPng.clone();
    }

    public byte[] getRearPassPng() {
        return rearPassPng == null ? null : rearPassPng.clone();
    }

    /**
     * 측정 중단 시 최신 결과로 교체.
     * 음향 PNG는 PASS 초록 밴드 종료 시점, 비전은 최초 PASS, 후방은 overallPass.
     */
    public synchronized void publish(boolean overallPass, String summary,
            Long audioPassMs, Long visionPassMs,
            Double audioJudgeMs, Double visionJudgeMs,
            Double audioGapMs, Double visionGapMs,
            Double audioAnalysisMs, Double visionAnalysisMs,
            Double syncSpreadMs, boolean syncOk,
            byte[] audioPassPng, byte[] visionPassPng, byte[] rearPassPng) {
        this.hasResult = true;
        this.stoppedAtEpochMs = System.currentTimeMillis();
        this.overallPass = overallPass;
        this.summary = summary == null ? "" : summary;
        this.audioPassMs = audioPassMs;
        this.visionPassMs = visionPassMs;
        this.audioJudgeMs = audioJudgeMs;
        this.visionJudgeMs = visionJudgeMs;
        this.audioGapMs = audioGapMs;
        this.visionGapMs = visionGapMs;
        this.audioAnalysisMs = audioAnalysisMs;
        this.visionAnalysisMs = visionAnalysisMs;
        this.syncSpreadMs = syncSpreadMs;
        this.syncOk = syncOk;
        this.audioPassPng = audioPassPng == null ? null : audioPassPng.clone();
        this.visionPassPng = visionPassPng == null ? null : visionPassPng.clone();
        this.rearPassPng = rearPassPng == null ? null : rearPassPng.clone();
        for (Listener l : listeners) {
            try {
                l.onResult(this);
            } catch (Exception ignored) {
            }
        }
    }
}
