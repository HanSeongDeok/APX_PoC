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
    /** 증거가 저장된 TC 폴더 — 결과 탭이 전 구간 스크럽을 물릴 대상. */
    private volatile java.io.File evidenceDir;
    /** 측정 TC id({@code EvidenceStore} 키). */
    private volatile String measureTcId;
    /** 저장된 후방 셀 스냅샷 id — 결과 탭 조회 API 테스트의 입력. */
    private volatile java.util.List<String> rearTcIds = java.util.Collections.emptyList();

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
        this.evidenceDir = null;   // 저장이 끝나면 publishEvidence로 채운다
        this.measureTcId = null;
        fire();
    }

    /**
     * 증거 저장이 끝난 뒤 TC 폴더·측정 TC id·후방 셀 id를 알린다.
     * 결과 탭이 전 구간 스크럽·스냅샷 조회에 쓴다. {@link #publish} 직후 호출.
     */
    public synchronized void publishEvidence(java.io.File dir, String measureTcId,
            java.util.List<String> rearTcIds) {
        this.evidenceDir = dir;
        this.measureTcId = measureTcId;
        this.rearTcIds = (rearTcIds == null)
                ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(rearTcIds));
        fire();
    }

    /** @deprecated {@link #publishEvidence(java.io.File, String, java.util.List)} 사용 */
    public synchronized void publishEvidence(java.io.File dir, java.util.List<String> rearTcIds) {
        publishEvidence(dir, dir == null ? null : dir.getName(), rearTcIds);
    }

    /** 직전 측정의 TC 증거 폴더. 저장 전이거나 실패면 null. */
    public java.io.File getEvidenceDir() {
        return evidenceDir;
    }

    /** 직전 측정 TC id({@link com.suresofttech.apx.core.measure.EvidenceStore} 키). */
    public String getMeasureTcId() {
        return measureTcId;
    }

    /** 직전 측정에서 저장된 후방 셀 스냅샷 id 목록(없으면 빈 목록). */
    public java.util.List<String> getRearTcIds() {
        return rearTcIds;
    }

    private void fire() {
        for (Listener l : listeners) {
            try {
                l.onResult(this);
            } catch (Exception ignored) {
            }
        }
    }
}
