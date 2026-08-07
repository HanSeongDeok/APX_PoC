package com.suresofttech.apx.core.measure;

/**
 * 측정 종료 시 동기 판정 결과.
 * POC realtime과 동일: 참여 채널 PASS 시각의 {@code max − min ≤ }{@link #SYNC_TOL_MS}.
 * CAN은 추후 타임스탬프만 추가하면 같은 규칙으로 확장.
 */
public final class MeasureSyncResult {

    /** POC SYNC_TOL_MS = 30. */
    public static final double SYNC_TOL_MS = 30.0;

    public final boolean audioPass;
    public final boolean visionPass;
    public final boolean canPass;       // 미연동 시 false + canPassMs=null
    public final Long audioPassMs;      // 측정 시작 기준 공통시계(ms)
    public final Long visionPassMs;
    public final Long canPassMs;
    /** 참여한 PASS 시각들의 max−min (ms). 2개 미만이면 null. */
    public final Double syncSpreadMs;
    public final boolean syncOk;
    /** 채널 전부 PASS ∧ syncOk. */
    public final boolean overallPass;
    public final String summary;

    public MeasureSyncResult(boolean audioPass, boolean visionPass, boolean canPass,
            Long audioPassMs, Long visionPassMs, Long canPassMs,
            Double syncSpreadMs, boolean syncOk, boolean overallPass, String summary) {
        this.audioPass = audioPass;
        this.visionPass = visionPass;
        this.canPass = canPass;
        this.audioPassMs = audioPassMs;
        this.visionPassMs = visionPassMs;
        this.canPassMs = canPassMs;
        this.syncSpreadMs = syncSpreadMs;
        this.syncOk = syncOk;
        this.overallPass = overallPass;
        this.summary = summary;
    }

    /**
     * 현재는 음향+비전 필수. CAN ms가 있으면 스프레드에 포함(채널 PASS로도 요구).
     * @param requireCan true면 CAN도 필수(미연동 단계에선 false)
     */
    public static MeasureSyncResult evaluate(boolean audioPass, boolean visionPass,
            Long audioPassMs, Long visionPassMs, Long canPassMs, boolean requireCan) {
        boolean canPresent = canPassMs != null;
        boolean canOk = !requireCan || (canPresent && canPassMs != null);

        java.util.ArrayList<Long> times = new java.util.ArrayList<Long>();
        if (audioPass && audioPassMs != null) {
            times.add(audioPassMs);
        }
        if (visionPass && visionPassMs != null) {
            times.add(visionPassMs);
        }
        if (canPresent) {
            times.add(canPassMs);
        }

        Double spread = null;
        boolean syncOk = true;
        if (times.size() >= 2) {
            long min = times.get(0).longValue();
            long max = min;
            for (int i = 1; i < times.size(); i++) {
                long v = times.get(i).longValue();
                if (v < min) {
                    min = v;
                }
                if (v > max) {
                    max = v;
                }
            }
            spread = Double.valueOf(max - min);
            syncOk = spread.doubleValue() <= SYNC_TOL_MS;
        }

        boolean channelsOk = audioPass && visionPass && canOk
                && (!requireCan || canPresent);
        // CAN 연동 전: 음향·비전만. CAN 시각이 있으면 스프레드에만 반영하되 필수는 requireCan.
        if (!requireCan && canPresent) {
            // optional can in spread already handled
        }
        boolean overall = channelsOk && syncOk;

        StringBuilder sb = new StringBuilder();
        if (!audioPass) {
            sb.append("음향 FAIL");
        }
        if (!visionPass) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("비전 FAIL");
        }
        if (requireCan && !canPresent) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append("CAN 없음");
        }
        if (channelsOk && !syncOk) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(String.format("동기 %.0fms > %.0fms",
                    spread == null ? 0 : spread.doubleValue(), SYNC_TOL_MS));
        }
        if (overall) {
            sb.append("PASS");
            if (spread != null) {
                sb.append(String.format(" (동기 %.0fms ≤ %.0fms)", spread.doubleValue(), SYNC_TOL_MS));
            }
        } else if (sb.length() == 0) {
            sb.append("FAIL");
        }

        return new MeasureSyncResult(audioPass, visionPass, canPresent,
                audioPassMs, visionPassMs, canPassMs, spread, syncOk, overall, sb.toString());
    }
}
