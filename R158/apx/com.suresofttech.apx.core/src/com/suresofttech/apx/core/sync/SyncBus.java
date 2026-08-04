package com.suresofttech.apx.core.sync;

import java.util.Arrays;

/**
 * 멀티모달 이벤트 동기화 버스 (공통 시계). SWT/RCP 무의존(core).
 *
 * <p>각 검출기(기어R·클러스터팝업·삐·CAN)가 이벤트를 최초로 검출한 순간
 * {@link #mark(Event)} 를 부르면, JVM 공통 단조시계(System.nanoTime) 기준 시각을 기록한다.
 * 그 뒤 {@link #offsetMs(Event, Event)} 로 이벤트 간 시간차(동기화)를 계산한다.
 *
 * <p>같은 JVM 안에서 도니 System.nanoTime 이 곧 공통 시계다(모달별 시계 통일 불필요).
 * mark()는 여러 스레드(오디오 콜백·UI 폴링)에서 불릴 수 있어 synchronized.
 *
 * <p>주의: 여기서 재는 것은 "검출 시각"이라 모달별 파이프라인 지연(D_cap/D_mic)이 섞여 있다.
 * 크로스모달 오프셋은 그 지연차를 보정해야 진짜 동기화다([[r158-project-spec]] 참조).
 */
public final class SyncBus {

    /** 동기화 대상 이벤트. */
    public enum Event {
        GEAR_R,          // 기어봉 R단
        CLUSTER_POPUP,   // 클러스터 경고 팝업
        BEEP,            // PDW 경고음
        CAN              // CAN 신호(예정)
    }

    private static final SyncBus INSTANCE = new SyncBus();

    public static SyncBus get() {
        return INSTANCE;
    }

    private SyncBus() {
        Arrays.fill(stamp, Double.NaN);
        Arrays.fill(latMs, Double.NaN);
    }

    private final double[] stamp = new double[Event.values().length];   // 초 단위, NaN=미검출
    private final double[] latMs = new double[Event.values().length];   // 판단 속도(도구 검출 지연, ms)
    private double audioEmit = Double.NaN;   // 음향 발사(재생 시작) 시각(초) — 캘리브용

    /** 공통 시계 현재 시각(초). */
    public static double now() {
        return System.nanoTime() * 1e-9;
    }

    /** 이벤트 검출 표시 — 현재 공통시계로. 최초 1회만 기록(재검출 무시). */
    public void mark(Event e) {
        mark(e, now(), Double.NaN);
    }

    /** 이벤트 검출 표시 — 지정 시각(초, 공통시계). 판단속도 없음. 최초 1회만 기록. */
    public void mark(Event e, double tSec) {
        mark(e, tSec, Double.NaN);
    }

    /**
     * 이벤트 검출 표시 + 판단 속도(도구 검출 지연, ms). 최초 1회만 기록.
     * @param tSec 검출 시각(초, 공통시계)
     * @param judgeMs 판단 속도(이벤트 발생→검출 지연, ms). 모르면 NaN.
     */
    public synchronized void mark(Event e, double tSec, double judgeMs) {
        if (Double.isNaN(stamp[e.ordinal()])) {
            stamp[e.ordinal()] = tSec;
            latMs[e.ordinal()] = judgeMs;
        }
    }

    /** 이벤트 검출 시각(초). 미검출이면 NaN. */
    public synchronized double stampOf(Event e) {
        return stamp[e.ordinal()];
    }

    /** 이벤트 판단 속도(도구 검출 지연, ms). 없으면 NaN. */
    public synchronized double judgeMsOf(Event e) {
        return latMs[e.ordinal()];
    }

    /** 음향 발사(재생 시작) 시각 기록 — 캘리브용. */
    public synchronized void setAudioEmit(double tSec) {
        audioEmit = tSec;
    }

    /** 음향 발사 시각(초). 미발사면 NaN. */
    public synchronized double audioEmit() {
        return audioEmit;
    }

    public synchronized boolean has(Event e) {
        return !Double.isNaN(stamp[e.ordinal()]);
    }

    /** to − from (ms). 둘 중 하나라도 미검출이면 NaN. 양수=to가 나중. */
    public synchronized double offsetMs(Event from, Event to) {
        double a = stamp[from.ordinal()];
        double b = stamp[to.ordinal()];
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return Double.NaN;
        }
        return (b - a) * 1000.0;
    }

    /** 단일 이벤트 시각·판단속도만 초기화 — 자동보정 반복 측정에서 재검출 허용용. */
    public synchronized void clearStamp(Event e) {
        stamp[e.ordinal()] = Double.NaN;
        latMs[e.ordinal()] = Double.NaN;
    }

    /** 새 측정 시작 — 모든 이벤트 시각·판단속도 초기화. */
    public synchronized void reset() {
        Arrays.fill(stamp, Double.NaN);
        Arrays.fill(latMs, Double.NaN);
        audioEmit = Double.NaN;
    }
}
