package com.suresofttech.apx.core.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.suresofttech.apx.core.sync.SyncBus.Event;

/**
 * 물리 지연(L2) 캘리브레이션 — 모달별 파이프라인 지연 상수와 지터를 표본에서 산출. SWT 무의존(core).
 *
 * <p>음향(D_mic)·비전(D_cap)의 하드웨어 경로 지연을 여러 번 재서 표본으로 쌓고,
 * <b>상수 = 중앙값(median)</b>, <b>지터 = 중앙값 절대편차(MAD)</b> 로 확정한다.
 * 상수는 발생시각 보정에 빼고(L2), 지터는 "하드웨어 스펙 근거"로 표시한다([[r158-project-spec]]).
 *
 * <p>중앙값을 쓰는 이유: 버퍼 지연 스파이크 등 튀는 표본에 강함(사용자 결정).
 */
public final class Calibration {

    /** 지연 모달리티 — 비전(카메라 경로) / 음향(마이크 경로). */
    public enum Modality {
        VISION,   // 카메라: 기어 R 표시·클러스터 팝업
        AUDIO     // 마이크: PDW 경고음
    }

    private final List<Double>[] samples;   // 모달별 HW 지연 표본(ms)

    @SuppressWarnings("unchecked")
    public Calibration() {
        samples = new List[Modality.values().length];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = new ArrayList<Double>();
        }
    }

    /** 이벤트 → 모달리티. BEEP=음향, 기어R·클러스터=비전, 그 외(CAN)=null. */
    public static Modality modalityOf(Event e) {
        if (e == Event.BEEP) {
            return Modality.AUDIO;
        }
        if (e == Event.GEAR_R || e == Event.CLUSTER_POPUP) {
            return Modality.VISION;
        }
        return null;
    }

    /** HW 지연 표본 추가(ms). NaN 무시. */
    public synchronized void addSample(Modality m, double hwMs) {
        if (m != null && !Double.isNaN(hwMs)) {
            samples[m.ordinal()].add(hwMs);
        }
    }

    public synchronized void clear(Modality m) {
        if (m != null) {
            samples[m.ordinal()].clear();
        }
    }

    public synchronized void clearAll() {
        for (List<Double> s : samples) {
            s.clear();
        }
    }

    public synchronized int count(Modality m) {
        return (m == null) ? 0 : samples[m.ordinal()].size();
    }

    /** 지연 상수(ms) = 표본 중앙값. 표본 없으면 NaN. L2 보정에 이 값을 뺀다. */
    public synchronized double constMs(Modality m) {
        return (m == null) ? Double.NaN : median(samples[m.ordinal()]);
    }

    /** 지터(ms) = 중앙값 절대편차(MAD). 표본 2개 미만이면 NaN. 하드웨어 스펙 근거. */
    public synchronized double jitterMs(Modality m) {
        if (m == null) {
            return Double.NaN;
        }
        List<Double> s = samples[m.ordinal()];
        double med = median(s);
        if (s.size() < 2 || Double.isNaN(med)) {
            return Double.NaN;
        }
        List<Double> dev = new ArrayList<Double>(s.size());
        for (double v : s) {
            dev.add(Math.abs(v - med));
        }
        return median(dev);
    }

    /** 중앙값. 빈 리스트면 NaN. 원본 불변(복사 후 정렬). */
    static double median(List<Double> s) {
        if (s == null || s.isEmpty()) {
            return Double.NaN;
        }
        List<Double> c = new ArrayList<Double>(s);
        Collections.sort(c);
        int n = c.size();
        return (n % 2 == 1) ? c.get(n / 2) : (c.get(n / 2 - 1) + c.get(n / 2)) / 2.0;
    }
}
