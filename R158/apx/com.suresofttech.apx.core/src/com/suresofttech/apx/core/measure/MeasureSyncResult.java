package com.suresofttech.apx.core.measure;

/**
 * 측정 종료 시 동기 판정 - <b>기준점 T0 대비 각 채널이 몇 ms 뒤에 읽혔는지</b>.
 *
 * <p>T0 는 우선순위로 정해진다.
 * <ol>
 *   <li><b>R변속 CAN 수신</b> - {@code requireCan} (실차)</li>
 *   <li><b>기어봉 R 검출</b> - 시뮬레이터. 기어봉을 0ms 기준 채널로 두고
 *       클러스터와 음향 PASS가 얼마나 뒤에 검출됐는지 비교한다.</li>
 * </ol>
 *
 * <pre>
 * Sync = MAX(클러스터 PASS − 기어봉 PASS, 음향 PASS − 기어봉 PASS)
 * PASS = Sync ≤ 사용자 설정 임계값 (기본 30ms)
 * </pre>
 */
public final class MeasureSyncResult {

    /** 사용자 지정이 없을 때의 동기 PASS 임계값(ms). */
    public static final double DEFAULT_SYNC_TOL_MS = 30.0;

    public final boolean audioPass;
    public final boolean visionPass;
    public final boolean canPass;
    public final Long audioPassMs;
    public final Long visionPassMs;
    public final Long clusterPassMs;
    public final Long gearPassMs;
    public final Long canPassMs;
    /** 자극 발사 시각(기어봉 R 전환). 시뮬레이터에서만 non-null. */
    public final Long stimulusMs;
    /** 기어봉 − 기준점 (ms), <b>보정 후</b>. 기준점이 기어봉 검출이면 0. 미산출 null. */
    public final Double gearDelayMs;
    /** 클러스터 − 기준점 (ms), <b>보정 후</b>. 미산출 null. */
    public final Double clusterDelayMs;
    /** 음향 − 기준점 (ms), <b>보정 후</b>. 미산출 null. */
    public final Double audioDelayMs;
    /** 보정 전 원 지연 (ms) - 증거 기록용. */
    public final Double gearRawDelayMs;
    public final Double clusterRawDelayMs;
    public final Double audioRawDelayMs;
    /** 적용한 캘리브 상수 (ms) - 증거 기록용. 0이면 보정 안 함. */
    public final double gearCalibMs;
    public final double clusterCalibMs;
    public final double audioCalibMs;
    /** 표시용: 지연 중 가장 큰 값. 미산출 null. */
    public final Double syncSpreadMs;
    /** 이 측정에 고정 적용된 동기 PASS 임계값(ms). */
    public final double syncToleranceMs;
    public final boolean syncOk;
    public final boolean overallPass;
    public final String summary;
    /** {@code 기어봉 R 검출} / {@code R변속 CAN}. */
    public final String t0Name;

    public MeasureSyncResult(boolean audioPass, boolean visionPass, boolean canPass,
            Long audioPassMs, Long visionPassMs, Long clusterPassMs, Long gearPassMs, Long canPassMs,
            Long stimulusMs, Double gearDelayMs, Double clusterDelayMs, Double audioDelayMs,
            Double gearRawDelayMs, Double clusterRawDelayMs, Double audioRawDelayMs,
            double gearCalibMs, double clusterCalibMs, double audioCalibMs,
            Double syncSpreadMs, double syncToleranceMs,
            boolean syncOk, boolean overallPass, String summary, String t0Name) {
        this.gearRawDelayMs = gearRawDelayMs;
        this.clusterRawDelayMs = clusterRawDelayMs;
        this.audioRawDelayMs = audioRawDelayMs;
        this.gearCalibMs = gearCalibMs;
        this.clusterCalibMs = clusterCalibMs;
        this.audioCalibMs = audioCalibMs;
        this.audioPass = audioPass;
        this.visionPass = visionPass;
        this.canPass = canPass;
        this.audioPassMs = audioPassMs;
        this.visionPassMs = visionPassMs;
        this.clusterPassMs = clusterPassMs;
        this.gearPassMs = gearPassMs;
        this.canPassMs = canPassMs;
        this.stimulusMs = stimulusMs;
        this.gearDelayMs = gearDelayMs;
        this.clusterDelayMs = clusterDelayMs;
        this.audioDelayMs = audioDelayMs;
        this.syncSpreadMs = syncSpreadMs;
        this.syncToleranceMs = syncToleranceMs;
        this.syncOk = syncOk;
        this.overallPass = overallPass;
        this.summary = summary;
        this.t0Name = t0Name;
    }

    /** 자극 시각·캘리브 없이(구 호출부) - 기준점은 CAN 또는 기어봉 <b>검출</b>. */
    public static MeasureSyncResult evaluate(boolean audioPass, boolean clusterPass, boolean gearPass,
            Long audioPassMs, Long clusterPassMs, Long gearPassMs,
            Long canPassMs, boolean requireCan) {
        return evaluate(audioPass, clusterPass, gearPass, audioPassMs, clusterPassMs, gearPassMs,
                canPassMs, null, requireCan, 0, 0, 0, DEFAULT_SYNC_TOL_MS);
    }

    /**
     * @param stimulusMs 도구가 자극(기어봉 R 전환 + 클러스터 교체 + 기대음)을 낸 시각.
     *                   자극 이력으로 기록하며, 시뮬레이터 동기 T0는 기어봉 검출 시각이다.
     * @param requireCan true면 기준점 = R변속 CAN (실차).
     * @param gearCalib 채널별 물리지연 캘리브 상수(ms). 0이면 보정하지 않는다.
     *                  <b>자체판단의 frameGap 은 여기에 더하거나 빼지 않는다</b> -
     *                  프레임 대기는 이 상수에 이미 들어 있어 두 번 빼게 된다.
     */
    public static MeasureSyncResult evaluate(boolean audioPass, boolean clusterPass, boolean gearPass,
            Long audioPassMs, Long clusterPassMs, Long gearPassMs,
            Long canPassMs, Long stimulusMs, boolean requireCan,
            double gearCalib, double clusterCalib, double audioCalib) {
        return evaluate(audioPass, clusterPass, gearPass,
                audioPassMs, clusterPassMs, gearPassMs,
                canPassMs, stimulusMs, requireCan,
                gearCalib, clusterCalib, audioCalib, DEFAULT_SYNC_TOL_MS);
    }

    public static MeasureSyncResult evaluate(boolean audioPass, boolean clusterPass, boolean gearPass,
            Long audioPassMs, Long clusterPassMs, Long gearPassMs,
            Long canPassMs, Long stimulusMs, boolean requireCan,
            double gearCalib, double clusterCalib, double audioCalib, double syncToleranceMs) {
        double tolerance = Math.max(1.0, syncToleranceMs);
        boolean visionPass = clusterPass && gearPass;
        Long visionPassMs = (clusterPassMs != null && gearPassMs != null)
                ? Long.valueOf(Math.max(clusterPassMs.longValue(), gearPassMs.longValue()))
                : null;
        boolean canPresent = canPassMs != null;
        boolean canOk = !requireCan || canPresent;

        // T0 우선순위: R변속 CAN(실차) > 기어봉 R 검출(시뮬).
        // stimulusMs는 자극 발사 이력이며, 상대 동기는 기어봉 PASS를 0ms 기준으로 계산한다.
        String t0Name;
        Long t0Ms;
        boolean gearMeasured;
        if (requireCan) {
            t0Name = "R변속 CAN";
            t0Ms = canPassMs;
            gearMeasured = true;
        } else {
            t0Name = "기어봉 R 검출";
            t0Ms = gearPassMs;
            gearMeasured = false;   // 기어봉이 기준점 - 자기 지연은 정의상 0
        }

        // 원 지연 = 검출 − T0. 여기엔 리그 물리지연과 그날의 프레임/블록 대기가 다 들어 있다.
        Double clusterRaw = delayFrom(clusterPassMs, t0Ms);
        Double audioRaw = delayFrom(audioPassMs, t0Ms);
        Double gearRaw = gearMeasured ? delayFrom(gearPassMs, t0Ms) : Double.valueOf(0.0);

        // 보정 = 원 지연 − 캘리브 상수. 상수에 프레임 대기가 이미 포함되어 있으므로
        // 자체판단의 frameGap 은 여기서 다시 빼지 않는다.
        double appliedGearCalib = gearMeasured ? gearCalib : 0;
        // 기어봉 PASS 기준에서는 이미 raw에 기어봉 물리지연이 빠져 있다.
        // 따라서 절대 캘리브 상수가 아니라 채널 간 상수 차이만 빼야 한다.
        double appliedClusterCalib = gearMeasured ? clusterCalib : clusterCalib - gearCalib;
        double appliedAudioCalib = gearMeasured ? audioCalib : audioCalib - gearCalib;
        Double clusterDelay = minus(clusterRaw, appliedClusterCalib);
        Double audioDelay = minus(audioRaw, appliedAudioCalib);
        Double gearDelay = gearMeasured ? minus(gearRaw, appliedGearCalib) : Double.valueOf(0.0);

        Double spread = null;
        if (clusterDelay != null && audioDelay != null) {
            spread = Double.valueOf(Math.max(
                    Math.abs(clusterDelay.doubleValue()), Math.abs(audioDelay.doubleValue())));
            if (gearMeasured && gearDelay != null) {
                spread = Double.valueOf(Math.max(
                        spread.doubleValue(), Math.abs(gearDelay.doubleValue())));
            }
        }

        boolean t0Ok = t0Ms != null;
        boolean syncOk = t0Ok && inWindow(clusterDelay, tolerance) && inWindow(audioDelay, tolerance)
                && (!gearMeasured || inWindow(gearDelay, tolerance));

        boolean channelsOk = audioPass && visionPass && canOk;
        boolean overall = channelsOk && syncOk;

        String summary = buildSummary(audioPass, clusterPass, gearPass, requireCan, canPresent,
                t0Ok, t0Name, clusterDelay, audioDelay, gearMeasured ? gearDelay : null,
                syncOk, overall, spread, tolerance);

        return new MeasureSyncResult(audioPass, visionPass, canPresent,
                audioPassMs, visionPassMs, clusterPassMs, gearPassMs, canPassMs,
                stimulusMs, gearDelay, clusterDelay, audioDelay,
                gearRaw, clusterRaw, audioRaw,
                appliedGearCalib, appliedClusterCalib, appliedAudioCalib,
                spread, tolerance, syncOk, overall, summary, t0Name);
    }

    private static Double minus(Double v, double calibMs) {
        if (v == null) {
            return null;
        }
        return Double.valueOf(v.doubleValue() - calibMs);
    }

    /** 캘리브 상수가 하나라도 걸려 있는가 - 표시에 "보정" 줄을 낼지 판단. */
    public boolean hasCalibration() {
        return gearCalibMs != 0 || clusterCalibMs != 0 || audioCalibMs != 0;
    }

    /**
     * 증거·보고서용 한 줄 - <b>원본 / 상수 / 보정</b>을 모두 남긴다.
     * 보정값만 남기면 나중에 검증할 수 없다.
     */
    public String formatCalibDetail() {
        if (clusterRawDelayMs == null || audioRawDelayMs == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("원본: 기어봉 %+.0f, 클러스터 %+.0f, 음향 %+.0f ms",
                gearRawDelayMs == null ? 0 : gearRawDelayMs.doubleValue(),
                clusterRawDelayMs.doubleValue(), audioRawDelayMs.doubleValue()));
        if (hasCalibration()) {
            sb.append(String.format("  /  적용상수: 기어봉 %+.0f, 클러스터 %+.0f, 음향 %+.0f ms",
                    gearCalibMs, clusterCalibMs, audioCalibMs));
        }
        return sb.toString();
    }

    public String formatLabel(boolean preview) {
        String prefix = preview ? "동기(미리보기): " : "동기: ";
        if (clusterDelayMs == null || audioDelayMs == null) {
            return prefix + "- (" + t0Name + " 기준 ±" + ((int) syncToleranceMs) + "ms)";
        }
        StringBuilder sb = new StringBuilder(prefix);
        // 테스트 자극 이력이 있으면 기준 채널인 기어봉 0ms도 함께 표시한다.
        if (stimulusMs != null || canPassMs != null) {
            if (gearDelayMs != null) {
                sb.append(String.format("기어봉 %+.0fms, ", gearDelayMs.doubleValue()));
            }
        }
        sb.append(String.format("클러스터 %+.0fms, 음향 %+.0fms %s (%s 기준 ±%.0fms%s)",
                clusterDelayMs.doubleValue(),
                audioDelayMs.doubleValue(),
                syncOk ? "OK" : "FAIL",
                t0Name,
                syncToleranceMs,
                hasCalibration() ? ", 캘리브 보정" : ""));
        return sb.toString();
    }

    private static Double delayFrom(Long tMs, Long t0Ms) {
        if (tMs == null || t0Ms == null) {
            return null;
        }
        return Double.valueOf(tMs.longValue() - t0Ms.longValue());
    }

    /** 기준점 전후 절대 편차가 사용자 지정 임계값 이내인지. */
    private static boolean inWindow(Double delayMs, double toleranceMs) {
        if (delayMs == null) {
            return false;
        }
        return Math.abs(delayMs.doubleValue()) <= toleranceMs;
    }

    private static String buildSummary(boolean audioPass, boolean clusterPass, boolean gearPass,
            boolean requireCan, boolean canPresent, boolean t0Ok, String t0Name,
            Double clusterDelay, Double audioDelay, Double gearDelay,
            boolean syncOk, boolean overall, Double spread, double toleranceMs) {
        StringBuilder sb = new StringBuilder();
        if (!audioPass) {
            sb.append("음향 FAIL");
        }
        if (!clusterPass) {
            appendComma(sb);
            sb.append("클러스터 FAIL");
        }
        if (!gearPass) {
            appendComma(sb);
            sb.append("기어봉 FAIL");
        }
        if (requireCan && !canPresent) {
            appendComma(sb);
            sb.append("CAN 없음");
        }
        if (!t0Ok && sb.length() == 0) {
            sb.append(t0Name).append(" 기준점 없음");
        }
        if (audioPass && clusterPass && gearPass && (!requireCan || canPresent) && !syncOk) {
            appendComma(sb);
            sb.append(formatFailReason(t0Name, clusterDelay, audioDelay, gearDelay, toleranceMs));
        }
        if (overall) {
            sb.append("PASS");
            if (spread != null) {
                sb.append(String.format(" (동기 최대 %.0fms ≤ %.0fms, %s 기준)",
                        spread.doubleValue(), toleranceMs, t0Name));
            }
        } else if (sb.length() == 0) {
            sb.append("FAIL");
        }
        return sb.toString();
    }

    private static String formatFailReason(String t0Name, Double clusterDelay, Double audioDelay,
            Double gearDelay, double toleranceMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("동기 ");
        if (clusterDelay != null) {
            sb.append(String.format("클러스터 %+.0fms", clusterDelay.doubleValue()));
        }
        if (audioDelay != null) {
            if (clusterDelay != null) {
                sb.append(", ");
            }
            sb.append(String.format("음향 %+.0fms", audioDelay.doubleValue()));
        }
        if (gearDelay != null) {
            sb.append(String.format(", 기어봉 %+.0fms", gearDelay.doubleValue()));
        }
        sb.append(" (").append(t0Name).append(" 기준 ±")
                .append((int) toleranceMs).append("ms 초과)");
        return sb.toString();
    }

    private static void appendComma(StringBuilder sb) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
    }
}
