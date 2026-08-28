package com.suresofttech.apx.core.measure;

/**
 * 측정 종료 시 동기 판정 - <b>기준점 T0 대비 각 채널이 몇 ms 뒤에 읽혔는지</b>.
 *
 * <p>T0 는 우선순위로 정해진다.
 * <ol>
 *   <li><b>R변속 CAN 수신</b> - {@code requireCan} (실차)</li>
 *   <li><b>기어봉 R 전환</b> - 도구가 자극을 직접 낸 시각({@code stimulusMs}, 시뮬레이터).
 *       이 순간 클러스터 화면 교체와 기대음 발사가 같이 나가므로,
 *       세 채널 각각의 {@code 검출 − T0} 가 곧 그 채널의 전체 지연이다
 *       (하드웨어 경로 + 프레임/블록 양자화 + 분석). <b>기어봉도 측정 대상</b>이다.</li>
 *   <li><b>기어봉 R 검출</b> - 자극 시각이 없을 때의 대타. 이때 기어봉 지연은 정의상 0이라
 *       기어봉 채널은 측정되지 않는다.</li>
 * </ol>
 *
 * <pre>
 * Sync = MAX(기어봉 지연, 클러스터 지연, 음향 지연)   ≤ 30ms
 * </pre>
 */
public final class MeasureSyncResult {

    /** 허용 오차 +30ms (기준점 이후). */
    public static final double SYNC_TOL_MS = 30.0;

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
    public final boolean syncOk;
    public final boolean overallPass;
    public final String summary;
    /** {@code 기어봉 R 전환} / {@code 기어봉 R 검출} / {@code R변속 CAN}. */
    public final String t0Name;

    public MeasureSyncResult(boolean audioPass, boolean visionPass, boolean canPass,
            Long audioPassMs, Long visionPassMs, Long clusterPassMs, Long gearPassMs, Long canPassMs,
            Long stimulusMs, Double gearDelayMs, Double clusterDelayMs, Double audioDelayMs,
            Double gearRawDelayMs, Double clusterRawDelayMs, Double audioRawDelayMs,
            double gearCalibMs, double clusterCalibMs, double audioCalibMs,
            Double syncSpreadMs, boolean syncOk, boolean overallPass, String summary, String t0Name) {
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
                canPassMs, null, requireCan, 0, 0, 0);
    }

    /**
     * @param stimulusMs 도구가 자극(기어봉 R 전환 + 클러스터 교체 + 기대음)을 낸 시각.
     *                   non-null 이면 이것이 T0 가 되고 <b>기어봉도 측정 대상</b>이 된다.
     * @param requireCan true면 기준점 = R변속 CAN (실차). {@code stimulusMs} 보다 우선한다.
     * @param gearCalib 채널별 물리지연 캘리브 상수(ms). 0이면 보정하지 않는다.
     *                  <b>자체판단의 frameGap 은 여기에 더하거나 빼지 않는다</b> -
     *                  프레임 대기는 이 상수에 이미 들어 있어 두 번 빼게 된다.
     */
    public static MeasureSyncResult evaluate(boolean audioPass, boolean clusterPass, boolean gearPass,
            Long audioPassMs, Long clusterPassMs, Long gearPassMs,
            Long canPassMs, Long stimulusMs, boolean requireCan,
            double gearCalib, double clusterCalib, double audioCalib) {
        boolean visionPass = clusterPass && gearPass;
        Long visionPassMs = (clusterPassMs != null && gearPassMs != null)
                ? Long.valueOf(Math.max(clusterPassMs.longValue(), gearPassMs.longValue()))
                : null;
        boolean canPresent = canPassMs != null;
        boolean canOk = !requireCan || canPresent;

        // T0 우선순위: R변속 CAN(실차) > 자극 발사(시뮬) > 기어봉 검출(대타)
        String t0Name;
        Long t0Ms;
        boolean gearMeasured;
        if (requireCan) {
            t0Name = "R변속 CAN";
            t0Ms = canPassMs;
            gearMeasured = true;
        } else if (stimulusMs != null) {
            t0Name = "기어봉 R 전환";
            t0Ms = stimulusMs;
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
        Double clusterDelay = minus(clusterRaw, clusterCalib);
        Double audioDelay = minus(audioRaw, audioCalib);
        Double gearDelay = gearMeasured ? minus(gearRaw, gearCalib) : Double.valueOf(0.0);

        Double spread = null;
        if (clusterDelay != null && audioDelay != null) {
            spread = Double.valueOf(Math.max(clusterDelay.doubleValue(), audioDelay.doubleValue()));
            if (gearMeasured && gearDelay != null) {
                spread = Double.valueOf(Math.max(spread.doubleValue(), gearDelay.doubleValue()));
            }
        }

        boolean t0Ok = t0Ms != null;
        boolean syncOk = t0Ok && inWindow(clusterDelay) && inWindow(audioDelay)
                && (!gearMeasured || inWindow(gearDelay));

        boolean channelsOk = audioPass && visionPass && canOk;
        boolean overall = channelsOk && syncOk;

        String summary = buildSummary(audioPass, clusterPass, gearPass, requireCan, canPresent,
                t0Ok, t0Name, clusterDelay, audioDelay, gearMeasured ? gearDelay : null,
                syncOk, overall, spread);

        return new MeasureSyncResult(audioPass, visionPass, canPresent,
                audioPassMs, visionPassMs, clusterPassMs, gearPassMs, canPassMs,
                stimulusMs, gearDelay, clusterDelay, audioDelay,
                gearRaw, clusterRaw, audioRaw,
                gearMeasured ? gearCalib : 0, clusterCalib, audioCalib,
                spread, syncOk, overall, summary, t0Name);
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
            sb.append(String.format("  /  캘리브: -%.0f, -%.0f, -%.0f ms",
                    gearCalibMs, clusterCalibMs, audioCalibMs));
        }
        return sb.toString();
    }

    public String formatLabel(boolean preview) {
        String prefix = preview ? "동기(미리보기): " : "동기: ";
        if (clusterDelayMs == null || audioDelayMs == null) {
            return prefix + "- (" + t0Name + " 기준 ≤" + ((int) SYNC_TOL_MS) + "ms)";
        }
        StringBuilder sb = new StringBuilder(prefix);
        // 기어봉이 기준점이면 자기 지연은 0이라 표시하지 않는다.
        if (stimulusMs != null || canPassMs != null) {
            if (gearDelayMs != null) {
                sb.append(String.format("기어봉 %+.0fms, ", gearDelayMs.doubleValue()));
            }
        }
        sb.append(String.format("클러스터 %+.0fms, 음향 %+.0fms %s (%s 기준 ≤%.0fms%s)",
                clusterDelayMs.doubleValue(),
                audioDelayMs.doubleValue(),
                syncOk ? "OK" : "FAIL",
                t0Name,
                SYNC_TOL_MS,
                hasCalibration() ? ", 캘리브 보정" : ""));
        return sb.toString();
    }

    private static Double delayFrom(Long tMs, Long t0Ms) {
        if (tMs == null || t0Ms == null) {
            return null;
        }
        return Double.valueOf(tMs.longValue() - t0Ms.longValue());
    }

    /** 기준점 이후 0 ~ +30ms. */
    private static boolean inWindow(Double delayMs) {
        if (delayMs == null) {
            return false;
        }
        double d = delayMs.doubleValue();
        return d >= 0.0 && d <= SYNC_TOL_MS;
    }

    private static String buildSummary(boolean audioPass, boolean clusterPass, boolean gearPass,
            boolean requireCan, boolean canPresent, boolean t0Ok, String t0Name,
            Double clusterDelay, Double audioDelay, Double gearDelay,
            boolean syncOk, boolean overall, Double spread) {
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
            sb.append(formatFailReason(t0Name, clusterDelay, audioDelay, requireCan ? gearDelay : null));
        }
        if (overall) {
            sb.append("PASS");
            if (spread != null) {
                sb.append(String.format(" (동기 최대 %.0fms ≤ %.0fms, %s 기준)",
                        spread.doubleValue(), SYNC_TOL_MS, t0Name));
            }
        } else if (sb.length() == 0) {
            sb.append("FAIL");
        }
        return sb.toString();
    }

    private static String formatFailReason(String t0Name, Double clusterDelay, Double audioDelay,
            Double gearDelay) {
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
        sb.append(" (").append(t0Name).append(" 기준 0~").append((int) SYNC_TOL_MS).append("ms 아님)");
        return sb.toString();
    }

    private static void appendComma(StringBuilder sb) {
        if (sb.length() > 0) {
            sb.append(", ");
        }
    }
}
