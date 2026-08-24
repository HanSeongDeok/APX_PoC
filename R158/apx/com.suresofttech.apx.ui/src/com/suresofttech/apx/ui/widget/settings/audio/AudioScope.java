package com.suresofttech.apx.ui.widget.settings.audio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.core.dsp.Fft;
import com.suresofttech.apx.core.dsp.SignalMath;

import ChartDirector.AreaLayer;
import ChartDirector.Chart;
import ChartDirector.LineLayer;
import ChartDirector.XYChart;

/**
 * 음향 스코프 - <b>ChartDirector 렌더(→PNG→SWT Image)</b>. (구 XChart+SWT_AWT 구성을 라이브러리만 교체.)
 * 화면 구성 / 축(경과시간 ms) / 범례 / 점선 / 채움은 이전 그대로 유지. 3패널:
 *  ① 파형 크기 포락선 (X=경과시간ms, Y=±1, 라이브 채움)
 *  ② 음정 추적 (X=경과시간ms, Y=주파수 Hz, 기대=수평 점선 @목표Hz, 라이브=검출 주 주파수 실선)
 *  ③ 일치도 추이 (주파수 / 파형 일치도 + 각 임계 점선, X=경과시간ms)
 *
 * <p><b>기본은 파형만</b> 표시. 옵션으로 ② 주파수(음정){@link #setShowPitch} / ③ 일치도 추이{@link #setShowTrend}
 * 를 각각 켜면, 상단: ① 파형(좌) | ② 음정(우), 하단 전폭: ③ 추이 로 확장된다.
 * 판정 결과 텍스트/막대는 AudioView가 담당. ChartDirector multiline 방식(범례 박스 + dashLineColor 점선).
 */
public class AudioScope extends Canvas {

    /** 라이브 / 스크럽 공통 흐르는 창 폭(ms). 설정/모니터와 결과 wav 재렌더가 동일. */
    public static final double MATCH_WIN_MS = 10000.0;
    private static final double ENV_COL_MS = 4.0;         // 파형 포락선 열 폭(ms)
    private static final int CAP_M = 1200;                // 음정 / 추이 링 점 수
    private static final int ENV_CAP = (int) (MATCH_WIN_MS / ENV_COL_MS) + 600;

    private static final int C_EXP = 0x828282;    // 기대/보조(회색)
    private static final int C_LIVE = 0x1e6edc;   // 라이브(파랑)
    private static final int C_WAVE = 0xe67814;   // 파형 일치도(주황)
    private static final int C_THR = 0xc83c3c;    // 임계(빨강)
    private static final int C_FILL = 0x000000;   // 파형 포락선 채움(검정)
    private static final int BG = 0xffffff;
    private static final String FONT = "Malgun Gothic";
    private static final int LEGEND_W = 92;       // 범례 영역 폭(px)
    private static final int DEFAULT_TICK_APPROX = 10;
    /** 설정 / 모니터 / 결과 스크럽 공통 기본 X축 눈금 간격(ms). */
    public static final double DEFAULT_TICK_MS = 1000.0;

    // 플롯영역 여백(px) - baseChart 의 setPlotArea 와 drawPassBand 오버레이가 반드시 공유해야
    // PASS 밴드가 스크롤되는 파형 데이터와 정확히 정렬된다(불일치 시 x좌표가 커질수록 밀림).
    private static final int PLOT_L = 52;                    // 좌: Y축 라벨
    private static final int PLOT_T = 24;                    // 상: 제목
    private static final int PLOT_V_CHROME = 54;             // 상+하 여백 합(plotH = h - 54)
    private static final int PLOT_R_LEGEND = LEGEND_W + 8;   // 우: 범례 있을 때
    private static final int PLOT_R_PLAIN = 12;              // 우: 범례 없을 때(파형 패널)

    private final double fmax;
    private boolean showPitch = false;   // 주파수(음정) 패널 - 기본 off(파형만)
    private boolean showTrend = false;   // 일치도 추이 패널 - 기본 off(파형만)

    // ── 표출 스타일(클라이언트 주입) - 기본값 유지, setter로 재정의 ──
    private int tickApprox = DEFAULT_TICK_APPROX;              // X축 목표 눈금 수
    private double tickMs = DEFAULT_TICK_MS;                   // >0 이면 눈금 간격(ms) 직접 지정(tickApprox 무시)
    private int passAlpha = 80;                               // PASS 밴드 투명도(0~255)
    private String waveTitle = "파형 그래프";   // 파형 패널 제목

    // 파형 크기 포락선(시간축 ms) - 열당 max/min
    private final double[] eT = new double[ENV_CAP];
    private final double[] eHi = new double[ENV_CAP];
    private final double[] eLo = new double[ENV_CAP];
    private int eHead, eCount;
    private double eLast = -1;

    // 음정 추적(시간축 ms) - 라이브 지배 주파수(Hz)
    private final double[] pT = new double[CAP_M];
    private final double[] pHz = new double[CAP_M];
    private int pHead, pCount;
    private double pLast = -1;

    // 일치도 추이(시간축 ms) - 주파수 / 파형
    private final double[] mT = new double[CAP_M];
    private final double[] mF = new double[CAP_M];
    private final double[] mW = new double[CAP_M];
    private int mHead, mCount;
    private double mLast = -1;
    private double freqThr = 0.5, waveThr = 0.5;
    private double targetHz = 0;   // 기대 주파수(음정 패널 수평 점선)

    // 공통 흐르는 창(ms)
    private double winMin = 0;
    private double winMax = MATCH_WIN_MS;
    private double axLo = 0;    // 눈금 그리드에 정렬된 X축 표시 범위(정수 라벨)
    private double axHi = MATCH_WIN_MS;

    // PASS 판정 구간(ms) 목록 - 파형 패널에 초록 밴드로 표시. isPass 인 동안 실시간으로 자란다.
    private final List<double[]> passSpans = new ArrayList<double[]>();   // 각 원소 = {startMs, endMs}
    private int passOpen = -1;   // 현재 열린(자라는) 밴드 index. -1=없음
    private Color passColor;   // 초록(반투명 밴드용)

    // 결과 스크럽(정적 구간 렌더) - 라이브 스트리밍 대신 wav 구간을 한 번에 그린다.
    private double cursorMs = -1;   // <0 이면 커서 없음
    private Color cursorColor;
    /**
     * true면 X축을 winMin~winMax 그대로 쓴다(스크럽).
     * false면 눈금에 맞춰 축을 늘린다(라이브) - 늘린 구간엔 포락선이 없어 양끝이 비어 보인다.
     */
    private boolean exactXAxis;

    private Image composite;

    public AudioScope(Composite parent, double fmax) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        this.fmax = fmax;
        this.passColor = new Color(parent.getDisplay(), 46, 190, 90);   // PASS 밴드(초록)
        this.cursorColor = new Color(parent.getDisplay(), 220, 40, 40); // 스크럽 커서(빨강)
        addPaintListener(new PaintListener() {
            public void paintControl(PaintEvent e) {
                if (composite == null || composite.isDisposed()) {
                    rebuild();
                }
                if (composite != null && !composite.isDisposed()) {
                    e.gc.drawImage(composite, 0, 0);
                    // PASS/커서는 파형 리빌드와 분리 - updatePass 직후 즉시 반영
                    Rectangle ca = getClientArea();
                    paintOverlays(e.gc, Math.max(120, ca.width), Math.max(160, ca.height));
                } else {
                    e.gc.setBackground(getDisplay().getSystemColor(SWT.COLOR_WHITE));
                    e.gc.fillRectangle(getClientArea());
                }
            }
        });
        addDisposeListener(e -> {
            if (composite != null && !composite.isDisposed()) {
                composite.dispose();
            }
            if (passColor != null && !passColor.isDisposed()) {
                passColor.dispose();
            }
            if (cursorColor != null && !cursorColor.isDisposed()) {
                cursorColor.dispose();
            }
        });
        addListener(SWT.Resize, e -> rebuildAndRedraw());
    }

    /** 주파수(음정) 패널 표시 옵션. 기본 false(파형만). */
    public void setShowPitch(boolean b) {
        this.showPitch = b;
        rebuildAndRedraw();
    }

    /** 일치도 추이 패널 표시 옵션. 기본 false(파형만). */
    public void setShowTrend(boolean b) {
        this.showTrend = b;
        rebuildAndRedraw();
    }

    /** (하위호환) waveOnly=true → 음정 숨김. */
    public void setWaveOnly(boolean b) {
        setShowPitch(!b);
    }

    // ── 표출 스타일 주입(items 3 / 4) ──────────────────────────────

    /** X축 목표 눈금 수(틱 개수). tickMs 미지정(0) 시 사용. */
    public void setTickApprox(int n) {
        if (n >= 2) {
            this.tickApprox = n;
            rebuildAndRedraw();
        }
    }

    /** X축 눈금 간격(ms) 직접 지정. 0 이하이면 tickApprox 기반 자동 산정. */
    public void setTickMs(double ms) {
        this.tickMs = ms > 0 ? ms : 0;
        rebuildAndRedraw();
    }

    /** PASS 밴드 색(0xRRGGBB). */
    public void setPassColor(int rgb) {
        Color old = passColor;
        passColor = new Color(getDisplay(), (rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }
        rebuildAndRedraw();
    }

    /** PASS 밴드 투명도(0=완전투명 ~ 255=불투명). */
    public void setPassAlpha(int a) {
        this.passAlpha = Math.max(0, Math.min(255, a));
        rebuildAndRedraw();
    }

    /** 파형 패널 제목. */
    public void setWaveTitle(String t) {
        this.waveTitle = (t != null) ? t : "";
        rebuildAndRedraw();
    }

    /**
     * <b>실시간 PASS 밴딩</b> - 매 틱 현재 시각(ms)과 합격 여부를 넘긴다.
     * 합격 중이면 열린 밴드를 현재 시각까지 <b>실시간으로 늘리고</b>, 불합격이 되면 그 밴드를 닫는다.
     * 파형 ChartDirector 리빌드 없이 오버레이만 즉시 paint - 짧은 PASS도 초록으로 보인다.
     *
     * <p>축은 마지막 파형 리빌드와 공유한다. 오버레이만 축을 앞서 바꾸면 PNG와 밴드가 어긋난다.
     */
    public void updatePass(double nowMs, boolean isPass) {
        if (isPass) {
            if (passOpen < 0 || passOpen >= passSpans.size()) {
                passSpans.add(new double[] { nowMs, nowMs });   // 새 밴드 시작
                passOpen = passSpans.size() - 1;
            } else {
                passSpans.get(passOpen)[1] = nowMs;             // 열린 밴드 실시간 연장
            }
        } else {
            passOpen = -1;                                      // 불합격 → 현재 밴드 닫음
        }
        if (!isDisposed()) {
            redraw();
            update(); // 파형 ChartDirector 리빌드 대기 없이 즉시 페인트
        }
    }

    /** PASS 판정 구간(ms)을 파형 그래프에 <b>초록 밴드</b>로 <b>누적</b> 추가(닫힌 구간). */
    public void addPassSpan(double startMs, double endMs) {
        addPassSpanQuiet(startMs, endMs);
        if (!isDisposed()) {
            redraw();
            update();
        }
    }

    /** 밴드만 목록에 추가(리빌드/리드로우 없음). 스크럽에서 여러 구간 일괄 복원용. */
    public void addPassSpanQuiet(double startMs, double endMs) {
        passSpans.add(new double[] { Math.min(startMs, endMs), Math.max(startMs, endMs) });
    }

    /** PASS 밴드 1개만 표시(기존 것 지우고 설정) - 단일 판정용 편의. */
    public void setPassSpan(double startMs, double endMs) {
        passSpans.clear();
        passOpen = -1;
        addPassSpan(startMs, endMs);
    }

    /** 모든 PASS 밴드 제거. */
    public void clearPass() {
        passSpans.clear();
        passOpen = -1;
        if (!isDisposed()) {
            redraw();
            update();
        }
    }

    /**
     * 초록 PASS 밴드 구간 목록(복사). 각 원소 {@code {startMs, endMs}}.
     * 증거 clip.wav = 밴드 시작~끝.
     */
    public List<double[]> getPassSpans() {
        List<double[]> out = new ArrayList<double[]>();
        for (int i = 0; i < passSpans.size(); i++) {
            double[] sp = passSpans.get(i);
            out.add(new double[] { sp[0], sp[1] });
        }
        return out;
    }

    /** 기대 beep 등록 - 목표 주파수만 음정 패널 수평 점선으로. (파형은 라이브 포락선만 표시) */
    public void setExpected(double[] tmpl, int sr) {
        if (tmpl == null || tmpl.length < 2) {
            return;
        }
        this.targetHz = dominantHz(tmpl, sr);
        rebuildAndRedraw();
    }

    /** 매 틱: 라이브 파형 포락선(+옵션 음정)을 시간축(ms)에 누적.
     * 추이 패널 on이면 렌더는 {@link #setMatchTrend}에서 커밋.
     * 파형만 모드({@code showTrend=false})면 여기서 바로 커밋. */
    public void setData(double[] w, int sr, double targetFreq, double elapsedSec) {
        if (w == null || w.length == 0) {
            return;
        }
        this.exactXAxis = false;
        if (targetFreq > 0) {
            this.targetHz = targetFreq;
        }
        double elapsedMs = elapsedSec * 1000.0;
        updateWindow(elapsedMs);

        // ── 파형 포락선: 새 구간을 ENV_COL_MS 열로 잘게 쪼개 각 열 max/min push ──
        if (elapsedMs < eLast - 1e-6) {   // 되감김(리셋)
            eHead = 0;
            eCount = 0;
            eLast = -1;
        }
        double newMs = (eLast < 0) ? ENV_COL_MS : Math.min(elapsedMs - eLast, 500.0);
        int ncols = Math.max(1, (int) Math.round(newMs / ENV_COL_MS));
        int colLen = Math.max(1, (int) (ENV_COL_MS / 1000.0 * sr));
        int take = Math.min(w.length, ncols * colLen);
        int base = w.length - take;
        for (int cIdx = 0; cIdx < ncols; cIdx++) {
            int s0 = base + cIdx * colLen;
            int s1 = Math.min(w.length, s0 + colLen);
            double hi = 0, lo = 0;
            for (int i = s0; i < s1; i++) {
                if (w[i] > hi) {
                    hi = w[i];
                }
                if (w[i] < lo) {
                    lo = w[i];
                }
            }
            double t = elapsedMs - (ncols - 1 - cIdx) * ENV_COL_MS;
            int tail = (eHead + eCount) % ENV_CAP;
            eT[tail] = t;
            eHi[tail] = hi;
            eLo[tail] = lo;
            if (eCount < ENV_CAP) {
                eCount++;
            } else {
                eHead = (eHead + 1) % ENV_CAP;
            }
        }
        eLast = elapsedMs;

        // ── 음정 추적: 라이브 지배 주파수 1점 push (패널 on일 때만) ──
        if (showPitch) {
            if (elapsedMs < pLast - 1e-6) {
                pHead = 0;
                pCount = 0;
            }
            int ptail = (pHead + pCount) % CAP_M;
            pT[ptail] = elapsedMs;
            pHz[ptail] = dominantHz(w, sr);
            if (pCount < CAP_M) {
                pCount++;
            } else {
                pHead = (pHead + 1) % CAP_M;
            }
            pLast = elapsedMs;
        }

        // 추이 없으면 여기서 커밋 (있으면 setMatchTrend가 커밋)
        if (!showTrend) {
            rebuildAndRedraw();
        }
    }

    /** 매 틱: 주파수 / 파형 일치도 추이 누적 + 화면 커밋(리빌드 / 리드로우). */
    public void setMatchTrend(double freqSim, double waveSim, double fThr, double wThr, double elapsedSec) {
        this.freqThr = fThr;
        this.waveThr = wThr;
        double elapsedMs = elapsedSec * 1000.0;
        updateWindow(elapsedMs);
        if (elapsedMs < mLast - 1e-6) {   // 되감김(리셋)
            mHead = 0;
            mCount = 0;
        }
        int tail = (mHead + mCount) % CAP_M;
        mT[tail] = elapsedMs;
        mF[tail] = clamp01(freqSim);
        mW[tail] = clamp01(waveSim);
        if (mCount < CAP_M) {
            mCount++;
        } else {
            mHead = (mHead + 1) % CAP_M;
        }
        mLast = elapsedMs;
        rebuildAndRedraw();
    }

    /**
     * <b>결과 스크럽용 정적 렌더</b> - 저장된 wav의 한 구간을 통째로 그린다.
     * 라이브 스트리밍({@link #setData})과 달리 링에 누적하지 않고 매번 새로 채우므로,
     * 슬라이더를 아무 방향으로 움직여도 그 시점 파형이 바로 나온다.
     *
     * <p>DB 없이 wav만 다시 읽어 그리는 경로다 - 파형 이미지를 따로 저장하지 않는다.
     *
     * @param samples wav 전체 샘플([-1,1))
     * @param sr 샘플레이트
     * @param startMs 창 시작(측정 시작 기준 ms)
     * @param endMs 창 끝
     * @param cursorMs 커서 위치(창 밖이면 안 그림). 음수면 커서 없음
     */
    public void showWindow(double[] samples, int sr, double startMs, double endMs, double cursorMs) {
        if (samples == null || sr <= 0 || endMs <= startMs) {
            return;
        }
        // ── X축을 눈금 그리드에 맞춘 뒤, 그 <b>전 구간</b>에 데이터를 채운다 ──
        // 예전에는 [startMs, endMs] 만 채우고 축은 floor/ceil 로 넓혔다. 그래서 축 왼쪽
        // (axLo~startMs)과 오른쪽에 데이터가 없는 빈 띠가 생겨 "파형이 살짝 오른쪽에서
        // 시작"하는 것처럼 보였다. 정렬된 축 범위로 채우면 왼쪽 끝까지 그려진다.
        double tick = (tickMs > 0) ? tickMs : msTick(endMs - startMs, tickApprox);
        double lo0 = Math.floor(startMs / tick) * tick;
        double hi0 = Math.ceil(endMs / tick) * tick;
        if (hi0 - lo0 < tick) {
            hi0 = lo0 + tick;
        }
        double span = hi0 - lo0;

        // 창이 그대로면 커서만 옮긴다 - 포락선 재계산과 리빌드를 아예 건너뛴다.
        // 스크럽 드래그에서 표시 구간이 바뀌지 않는 동안(예: 0~10s 구간 안에서 이동)
        // 매 이벤트마다 수십만 샘플을 다시 스캔하고 차트를 다시 그리던 낭비를 없앤다.
        // 커서는 paintOverlays 가 그리므로 redraw 만으로 충분하다.
        if (eCount > 0
                && Math.abs(winMin - lo0) < 1e-6
                && Math.abs(winMax - hi0) < 1e-6) {
            this.cursorMs = cursorMs;
            if (!isDisposed()) {
                redraw();
            }
            return;
        }

        eHead = 0;
        eCount = 0;
        eLast = -1;
        pHead = 0;
        pCount = 0;
        mHead = 0;
        mCount = 0;

        // 열 개수는 플롯 <b>픽셀 폭</b>에 맞춘다. 예전엔 ENV_CAP(약 3100) 기준이라
        // 화면 폭의 4~5배를 계산 / 전달했다 - 눈에 보이지 않는 낭비였고 스크럽이 느렸다.
        int plotW = Math.max(1, Math.max(120, getClientArea().width) - PLOT_L - PLOT_R_PLAIN);
        int cols = Math.max(2, Math.min(ENV_CAP - 8, plotW));
        double colMs = span / cols;
        for (int c = 0; c < cols && eCount < ENV_CAP; c++) {
            double t0 = lo0 + c * colMs;
            int s0 = (int) Math.round(t0 / 1000.0 * sr);
            int s1 = (int) Math.round((t0 + colMs) / 1000.0 * sr);
            s0 = Math.max(0, Math.min(samples.length, s0));
            s1 = Math.max(s0, Math.min(samples.length, s1));
            double hi = 0;
            double lo = 0;
            for (int i = s0; i < s1; i++) {
                if (samples[i] > hi) {
                    hi = samples[i];
                }
                if (samples[i] < lo) {
                    lo = samples[i];
                }
            }
            int tail = (eHead + eCount) % ENV_CAP;
            eT[tail] = t0 + colMs * 0.5;
            eHi[tail] = hi;
            eLo[tail] = lo;
            eCount++;
        }
        // 데이터를 채운 범위와 축 범위를 일치시킨다(위에서 그리드에 맞춘 lo0~hi0).
        // exactXAxis=false 라도 이미 정렬된 값이라 baseChart 의 floor/ceil 이 그대로 통과한다.
        winMin = lo0;
        winMax = hi0;
        this.cursorMs = cursorMs;
        // 라이브 setData 와 동일 - tickMs 그리드(floor/ceil). exact면 짧은 wav에서 0~4.5s처럼 줄어듦
        this.exactXAxis = false;
        rebuildAndRedraw();
    }

    /** 스크럽 커서만 이동(파형 창은 그대로). 음수면 커서 제거. */
    public void setCursorMs(double ms) {
        this.cursorMs = ms;
        if (!isDisposed()) {
            redraw();   // 커서는 paint 오버레이 - 전체 차트 리빌드 불필요
        }
    }

    public double getCursorMs() {
        return cursorMs;
    }

    /** 그래프 초기화(측정 리셋) - 링 비움. 기대(목표 주파수)는 유지. */
    public void clear() {
        eHead = 0;
        eCount = 0;
        eLast = -1;
        pHead = 0;
        pCount = 0;
        pLast = -1;
        mHead = 0;
        mCount = 0;
        mLast = -1;
        winMin = 0;
        winMax = MATCH_WIN_MS;
        exactXAxis = false;
        passSpans.clear();
        passOpen = -1;
        rebuildAndRedraw();
    }

    /**
     * 현재 스코프 PNG (증거 / Result용).
     * ChartDirector 오프스크린 {@code composite}를 직접 인코딩한다.
     * (SWT {@code copyArea}는 redraw 전이면 빈 축만 찍히는 문제 있음)
     */
    public byte[] capturePng() {
        rebuild();
        if (composite == null || composite.isDisposed()) {
            return null;
        }
        // composite는 파형만 - PASS/커서는 임시 스냅에만 구워 이중 오버레이를 피한다
        Rectangle b = composite.getBounds();
        Image snap = new Image(getDisplay(), b.width, b.height);
        GC bake = new GC(snap);
        try {
            bake.drawImage(composite, 0, 0);
            paintOverlays(bake, b.width, b.height);
        } finally {
            bake.dispose();
        }
        try {
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[] { snap.getImageData() };
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            loader.save(bos, SWT.IMAGE_PNG);
            return bos.toByteArray();
        } catch (Exception ex) {
            return null;
        } finally {
            snap.dispose();
        }
    }

    // ── 내부 ────────────────────────────────────────────────────────────────────

    private void updateWindow(double elapsedMs) {
        winMax = Math.max(MATCH_WIN_MS, elapsedMs);
        winMin = Math.max(0, elapsedMs - MATCH_WIN_MS);
    }

    private double dominantHz(double[] w, int sr) {
        double[] mag = Fft.magnitude(SignalMath.mul(w, SignalMath.hanning(w.length)));
        int nfft = Fft.nextPow2(w.length);
        int kmax = Math.min(mag.length, (int) (fmax * nfft / sr));
        int argmax = 1;
        double peak = -1;
        for (int k = 1; k < kmax; k++) {   // DC 제외
            if (mag[k] > peak) {
                peak = mag[k];
                argmax = k;
            }
        }
        return (double) argmax * sr / nfft;
    }

    /** 리빌드 최소 간격(ms). ChartDirector 렌더+PNG→Image 는 무거워 매 호출 처리하면 UI가 막힌다. */
    private static final int REBUILD_MIN_MS = 40;
    private boolean rebuildPending;
    private long lastRebuildAt;

    /**
     * 재렌더 요청 - <b>과도한 호출을 합친다</b>.
     *
     * <p>이 메서드는 세 곳에서 몰려 들어온다: 리사이즈 드래그(초당 수십 번),
     * 오디오 블록 도착(약 46ms마다), 스크럽 슬라이더 드래그. 예전에는 그때마다
     * 곧바로 리빌드해서 UI 스레드가 포화됐고, 그 결과 리사이즈 중 실시간 파형이
     * 멈춰 보이고 결과 탭 스크럽이 느렸다.
     *
     * <p>{@link #REBUILD_MIN_MS} 안에 다시 요청이 오면 타이머 하나로 미뤄 합친다.
     * 마지막 요청도 반드시 반영되므로 화면이 낡은 상태로 남지 않는다.
     */
    private void rebuildAndRedraw() {
        if (isDisposed() || rebuildPending) {
            return;                       // 이미 예약돼 있으면 그 한 번이 최신 상태를 그린다
        }
        long now = System.currentTimeMillis();
        long due = lastRebuildAt + REBUILD_MIN_MS;
        if (now >= due) {
            rebuildNow();
            return;
        }
        rebuildPending = true;
        getDisplay().timerExec((int) (due - now), new Runnable() {
            public void run() {
                rebuildPending = false;
                if (!isDisposed()) {
                    rebuildNow();
                }
            }
        });
    }

    private void rebuildNow() {
        lastRebuildAt = System.currentTimeMillis();
        rebuild();
        if (!isDisposed()) {
            redraw();
        }
    }

    private void rebuild() {
        if (isDisposed()) {
            return;
        }
        Rectangle ca = getClientArea();
        int w = Math.max(120, ca.width);
        int h = Math.max(160, ca.height);
        int topH = showTrend ? h / 2 : h;   // 추이 표시 시 상단 절반, 아니면 전체 높이
        int botH = showTrend ? h - topH : 0;

        Image comp = new Image(getDisplay(), w, h);
        GC gc = new GC(comp);
        gc.setBackground(getDisplay().getSystemColor(SWT.COLOR_WHITE));
        gc.fillRectangle(0, 0, w, h);
        if (showPitch) {
            // 상단: 파형(좌) | 주파수(우) - PASS/커서는 paintOverlays
            int halfW = w / 2;
            drawPng(gc, wavePng(halfW, topH), 0, 0);
            drawPng(gc, pitchPng(w - halfW, topH), halfW, 0);
        } else {
            drawPng(gc, wavePng(w, topH), 0, 0);
        }
        if (showTrend) {
            drawPng(gc, trendPng(w, botH), 0, topH);
        }
        gc.dispose();

        if (composite != null && !composite.isDisposed()) {
            composite.dispose();
        }
        composite = comp;
    }

    /** PASS 밴드 / 스크럽 커서를 파형 패널 위에 덧그림(리빌드와 분리). */
    private void paintOverlays(GC gc, int w, int h) {
        int topH = showTrend ? h / 2 : h;
        if (showPitch) {
            int halfW = w / 2;
            drawPassBand(gc, 0, 0, halfW, topH);
            drawCursor(gc, 0, 0, halfW, topH);
        } else {
            drawPassBand(gc, 0, 0, w, topH);
            drawCursor(gc, 0, 0, w, topH);
        }
    }

    private void drawPng(GC gc, byte[] png, int x, int y) {
        if (png == null) {
            return;
        }
        Image img = new Image(getDisplay(), new ByteArrayInputStream(png));
        gc.drawImage(img, x, y);
        img.dispose();
    }

    /**
     * 스크럽 커서(세로선)를 파형 패널에 덧그림. 플롯 기하는 {@link #drawPassBand}와 동일.
     * 창 밖이거나 커서가 없으면 아무것도 안 그린다.
     */
    private void drawCursor(GC gc, int px, int py, int pw, int ph) {
        if (cursorMs < 0 || cursorColor == null) {
            return;
        }
        double span = axHi - axLo;
        if (span <= 0 || cursorMs < axLo || cursorMs > axHi) {
            return;
        }
        int plotL = px + PLOT_L;
        int plotT = py + PLOT_T;
        int plotW = Math.max(1, pw - PLOT_L - PLOT_R_PLAIN);
        int plotH = Math.max(1, ph - PLOT_V_CHROME);
        int x = plotL + (int) ((cursorMs - axLo) / span * plotW);
        gc.setForeground(cursorColor);
        gc.setLineWidth(2);
        gc.drawLine(x, plotT, x, plotT + plotH);
    }

    /**
     * PASS 구간을 파형 패널 플롯영역에 초록 반투명 밴드로 덧그림.
     * (px,py)=패널 원점, (pw,ph)=패널 크기. 플롯영역은 baseChart 의 setPlotArea(52,24, / ,h-54) 와 일치.
     */
    private void drawPassBand(GC gc, int px, int py, int pw, int ph) {
        if (passSpans.isEmpty() || passColor == null) {
            return;
        }
        double span = axHi - axLo;
        if (span <= 0) {
            return;
        }
        // 파형 패널은 범례 없이(showLegend=false) 그려지므로 우측 여백은 PLOT_R_PLAIN 사용.
        int plotL = px + PLOT_L;
        int plotT = py + PLOT_T;
        int plotW = Math.max(1, pw - PLOT_L - PLOT_R_PLAIN);
        int plotH = Math.max(1, ph - PLOT_V_CHROME);
        int prevAlpha = gc.getAlpha();
        gc.setAlpha(passAlpha);
        gc.setBackground(passColor);
        for (double[] sp : passSpans) {
            double s = Math.max(sp[0], axLo);
            double e = Math.min(sp[1], axHi);
            if (e <= s) {
                continue;   // 창 밖
            }
            int x0 = plotL + (int) ((s - axLo) / span * plotW);
            int x1 = plotL + (int) ((e - axLo) / span * plotW);
            gc.fillRectangle(x0, plotT, Math.max(2, x1 - x0), plotH);
        }
        gc.setAlpha(prevAlpha);
    }

    /** 차트 공통 골격 - 플롯영역 / 제목 / ms X축. showLegend=false 면 범례 자리까지 플롯에 사용. */
    private XYChart baseChart(int w, int h, String title, int approxTicks) {
        return baseChart(w, h, title, approxTicks, true);
    }

    private XYChart baseChart(int w, int h, String title, int approxTicks, boolean showLegend) {
        XYChart c = new XYChart(w, h, BG);
        c.setAntiAlias(true);   // 계단현상 완화 - 파형 가장자리가 부드럽게
        int right = showLegend ? PLOT_R_LEGEND : PLOT_R_PLAIN;
        int plotW = Math.max(1, w - PLOT_L - right);
        c.setPlotArea(PLOT_L, PLOT_T, plotW, Math.max(1, h - PLOT_V_CHROME), BG, -1, 0xdddddd, 0xf0f0f0, -1);
        c.addTitle(title, FONT, 9);
        if (showLegend) {
            c.addLegend(w - LEGEND_W, 26, true, FONT, 8);
        }
        // 눈금 간격 + 정수 ms 포맷. 스크럽은 창 범위를 그대로, 라이브는 그리드에 맞춤.
        // axLo/axHi 는 paint 오버레이(PASS/커서)와 공유한다.
        double tick = (tickMs > 0) ? tickMs : msTick(winMax - winMin, approxTicks);
        if (exactXAxis) {
            axLo = winMin;
            axHi = winMax;
            if (axHi - axLo < 1) {
                axHi = axLo + 1;
            }
        } else {
            axLo = Math.floor(winMin / tick) * tick;
            axHi = Math.ceil(winMax / tick) * tick;
            if (axHi - axLo < tick) {
                axHi = axLo + tick;
            }
        }
        c.xAxis().setLinearScale(axLo, axHi, tick);
        c.xAxis().setLabelFormat("{value|0}ms");
        return c;
    }

    /** ① 파형 크기 포락선 - 채움. Y축은 진폭 ±1 → ±100%. 범례 없음. */
    /**
     * <b>임의 구간 파형 PNG</b> - 라이브 스코프 상태와 무관하게 샘플에서 직접 렌더한다.
     * 클라가 {@code full.wav} 샘플의 {@code [startMs, endMs)} 구간만 잘라
     * 보고서에 넣을 때 사용. 스타일(검정 채움 / ±100% / ms축)은 라이브 파형과 동일.
     *
     * @param samples    전체 샘플 [-1,1]
     * @param sampleRate 샘플레이트(Hz)
     * @param title      차트 제목(null이면 기본)
     * @return PNG 바이트, 구간이 비었거나 샘플이 없으면 null
     */
    public static byte[] renderRangePng(double[] samples, int sampleRate,
            double startMs, double endMs, int width, int height, String title) {
        if (samples == null || samples.length == 0 || sampleRate <= 0) {
            return null;
        }
        double a = Math.min(startMs, endMs);
        double b = Math.max(startMs, endMs);
        if (b - a < 1e-6) {
            return null;
        }
        int w = Math.max(160, width);
        int h = Math.max(90, height);

        XYChart c = new XYChart(w, h, BG);
        c.setAntiAlias(true);
        int plotW = Math.max(1, w - PLOT_L - PLOT_R_PLAIN);
        c.setPlotArea(PLOT_L, PLOT_T, plotW, Math.max(1, h - PLOT_V_CHROME),
                BG, -1, 0xdddddd, 0xf0f0f0, -1);
        c.addTitle(title == null ? "파형 구간" : title, FONT, 9);
        double tick = msTick(b - a, DEFAULT_TICK_APPROX);
        double lo = Math.floor(a / tick) * tick;
        double hi = Math.ceil(b / tick) * tick;
        if (hi - lo < tick) {
            hi = lo + tick;
        }
        c.xAxis().setLinearScale(lo, hi, tick);
        c.xAxis().setLabelFormat("{value|0}ms");
        c.yAxis().setLinearScale(-100, 100, 50);
        c.yAxis().setLabelFormat("{value}%");

        int i0 = (int) Math.max(0, Math.floor(a / 1000.0 * sampleRate));
        int i1 = (int) Math.min(samples.length, Math.ceil(b / 1000.0 * sampleRate));
        if (i1 - i0 < 2) {
            return null;
        }
        // 열(column)당 max/min 포락선 - 열 수는 플롯 폭을 넘지 않게
        int cols = Math.max(2, Math.min(plotW, (int) Math.round((b - a) / ENV_COL_MS)));
        double[] tx = new double[cols];
        double[] hiV = new double[cols];
        double[] loV = new double[cols];
        long span = i1 - i0;
        for (int k = 0; k < cols; k++) {
            int s0 = i0 + (int) (span * k / cols);
            int s1 = i0 + (int) (span * (k + 1) / cols);
            if (s1 <= s0) {
                s1 = Math.min(i1, s0 + 1);
            }
            double mx = 0;
            double mn = 0;
            for (int s = s0; s < s1; s++) {
                double v = samples[s];
                if (v > mx) {
                    mx = v;
                }
                if (v < mn) {
                    mn = v;
                }
            }
            tx[k] = a + (b - a) * (k + 0.5) / cols;
            hiV[k] = mx * 100.0;
            loV[k] = mn * 100.0;
        }
        addWaveArea(c, tx, hiV);
        addWaveArea(c, tx, loV);
        return c.makeChart2(Chart.PNG);
    }

    /**
     * 파형 채움 레이어 - 테두리를 <b>채움과 같은 색 1px</b>로 둔다.
     * <ul>
     *   <li>기본 테두리(다른 색 / 굵기)를 그대로 두면 열마다 윤곽선이 겹쳐 굵은 덩어리로 보인다.</li>
     *   <li>반대로 테두리를 아예 없애면(Transparent) 1픽셀 폭의 짧은 스파이크가
     *       안티에일리어싱에 묻혀 <b>잘려 보인다</b>. 같은 색 1px이면 최소 1픽셀은 찍힌다.</li>
     * </ul>
     */
    private static void addWaveArea(XYChart c, double[] tX, double[] data) {
        AreaLayer a = c.addAreaLayer(data, C_FILL);
        a.setXData(tX);
        a.setBorderColor(C_FILL);
        a.setLineWidth(1);
    }

    private byte[] wavePng(int w, int h) {
        XYChart c = baseChart(w, h, waveTitle, tickApprox, false);
        c.yAxis().setLinearScale(-100, 100, 50);   // 100% / 50% / 0% / -50% / -100%
        c.yAxis().setLabelFormat("{value}%");
        int n = eCount;
        double[] tx = new double[n];
        double[] hi = new double[n];
        double[] lo = new double[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            int p = (eHead + i) % ENV_CAP;
            double t = eT[p];
            if (t < axLo || t > axHi) {   // 축 범위 밖 제외(오버플로 방지)
                continue;
            }
            tx[m] = t;
            hi[m] = eHi[p] * 100.0;   // [-1,1] → [-100,100]%
            lo[m] = eLo[p] * 100.0;
            m++;
        }
        if (m >= 2) {
            // 한 픽셀 = 한 열(max/min) - 녹음 파형과 같은 밀도.
            // 버킷 경계를 <b>시간(axLo 기준)</b>으로 고정한다. 인덱스로 나누면 프레임마다
            // 경계가 밀려 같은 소리가 픽셀을 오가며 "지글지글" 떨린다.
            int plotW = Math.max(1, w - PLOT_L - PLOT_R_PLAIN);
            double span = axHi - axLo;
            double[] tX;
            double[] hiV;
            double[] loV;
            if (span > 0 && m > plotW) {
                double[] bHi = new double[plotW];
                double[] bLo = new double[plotW];
                boolean[] has = new boolean[plotW];
                for (int i = 0; i < m; i++) {
                    int px = (int) ((tx[i] - axLo) / span * plotW);
                    if (px < 0) {
                        px = 0;
                    } else if (px >= plotW) {
                        px = plotW - 1;
                    }
                    if (!has[px]) {
                        has[px] = true;
                        bHi[px] = hi[i];
                        bLo[px] = lo[i];
                    } else {
                        if (hi[i] > bHi[px]) {
                            bHi[px] = hi[i];
                        }
                        if (lo[i] < bLo[px]) {
                            bLo[px] = lo[i];
                        }
                    }
                }
                int k = 0;
                for (int px = 0; px < plotW; px++) {
                    if (has[px]) {
                        k++;
                    }
                }
                tX = new double[k];
                hiV = new double[k];
                loV = new double[k];
                int j = 0;
                for (int px = 0; px < plotW; px++) {
                    if (has[px]) {
                        tX[j] = axLo + (px + 0.5) * span / plotW;
                        hiV[j] = bHi[px];
                        loV[j] = bLo[px];
                        j++;
                    }
                }
            } else {
                tX = Arrays.copyOf(tx, m);
                hiV = Arrays.copyOf(hi, m);
                loV = Arrays.copyOf(lo, m);
            }
            addWaveArea(c, tX, hiV);   // 0..+peak
            addWaveArea(c, tX, loV);   // 0..-peak
        }
        return c.makeChart2(Chart.PNG);
    }

    /** ② 음정 추적 - 기대(수평 점선) vs 라이브(실선). */
    private byte[] pitchPng(int w, int h) {
        XYChart c = baseChart(w, h, "음정 추적 (X=경과시간 / Y=주파수 Hz / 기대 점선 / 라이브 실선)", tickApprox);
        c.yAxis().setLinearScale(0, fmax, 1000);
        double[] edge = { axLo, axHi };
        int dashExp = c.dashLineColor(C_EXP, Chart.DashLine);
        LineLayer le = c.addLineLayer(new double[] { targetHz, targetHz }, dashExp, "기대");
        le.setXData(edge);
        int n = pCount;
        double[] tx = new double[n];
        double[] hz = new double[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            int p = (pHead + i) % CAP_M;
            double t = pT[p];
            if (t < axLo || t > axHi) {   // 축 범위 밖 제외(오버플로 방지)
                continue;
            }
            tx[m] = t;
            hz[m] = pHz[p];
            m++;
        }
        if (m >= 2) {
            LineLayer ll = c.addLineLayer(Arrays.copyOf(hz, m), C_LIVE, "라이브");
            ll.setXData(Arrays.copyOf(tx, m));
            ll.setLineWidth(2);
        }
        return c.makeChart2(Chart.PNG);
    }

    /** ③ 일치도 추이 - 주파수 / 파형 실선 + 각 임계 점선. */
    private byte[] trendPng(int w, int h) {
        XYChart c = baseChart(w, h, "일치도 추이 - 주파수 and 파형 (경과시간축, 임계선)", tickApprox);
        c.yAxis().setLinearScale(0, 1.0, 0.2);
        int nn = mCount;
        double[] tx = new double[nn];
        double[] tf = new double[nn];
        double[] tw = new double[nn];
        int m = 0;
        for (int i = 0; i < nn; i++) {
            int p = (mHead + i) % CAP_M;
            double t = mT[p];
            if (t < axLo || t > axHi) {   // 축 범위 밖 제외(오버플로 방지)
                continue;
            }
            tx[m] = t;
            tf[m] = mF[p];
            tw[m] = mW[p];
            m++;
        }
        if (m >= 2) {
            double[] tX = Arrays.copyOf(tx, m);
            LineLayer lf = c.addLineLayer(Arrays.copyOf(tf, m), C_LIVE, "주파수");
            lf.setXData(tX);
            lf.setLineWidth(2);
            LineLayer lw = c.addLineLayer(Arrays.copyOf(tw, m), C_WAVE, "파형");
            lw.setXData(tX);
            lw.setLineWidth(2);
        }
        double[] edge = { axLo, axHi };
        int dashF = c.dashLineColor(C_THR, Chart.DashLine);
        LineLayer fr = c.addLineLayer(new double[] { freqThr, freqThr }, dashF, "주파수 임계");
        fr.setXData(edge);
        int dashW = c.dashLineColor(C_EXP, Chart.DashLine);
        LineLayer wr = c.addLineLayer(new double[] { waveThr, waveThr }, dashW, "파형 임계");
        wr.setXData(edge);
        return c.makeChart2(Chart.PNG);
    }

    /** ms X축 눈금 간격 - 구간/목표틱수에 맞는 nice-step. */
    private static double msTick(double range, int approx) {
        double raw = range / Math.max(1, approx);
        double[] nice = { 100, 200, 500, 1000, 2000, 5000, 10000 };
        for (double s : nice) {
            if (s >= raw) {
                return s;
            }
        }
        return 10000;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
