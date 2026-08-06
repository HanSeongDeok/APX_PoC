package com.suresofttech.apx.ui.widget.settings.audio;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
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
 * 음향 스코프 — <b>ChartDirector 렌더(→PNG→SWT Image)</b>. (구 XChart+SWT_AWT 구성을 라이브러리만 교체.)
 * 화면 구성·축(경과시간 ms)·범례·점선·채움은 이전 그대로 유지. 3패널:
 *  ① 파형 크기 포락선 (X=경과시간ms, Y=±1, 라이브 채움)
 *  ② 음정 추적 (X=경과시간ms, Y=주파수 Hz, 기대=수평 점선 @목표Hz, 라이브=검출 주 주파수 실선)
 *  ③ 일치도 추이 (주파수·파형 일치도 + 각 임계 점선, X=경과시간ms)
 *
 * <p><b>기본은 파형만</b> 표시. 옵션으로 ② 주파수(음정){@link #setShowPitch}·③ 일치도 추이{@link #setShowTrend}
 * 를 각각 켜면, 상단: ① 파형(좌) | ② 음정(우), 하단 전폭: ③ 추이 로 확장된다.
 * 판정 결과 텍스트/막대는 AudioView가 담당. ChartDirector multiline 방식(범례 박스 + dashLineColor 점선).
 */
public class AudioScope extends Canvas {

    private static final double MATCH_WIN_MS = 10000.0;   // 흐르는 창 = 10초
    private static final double ENV_COL_MS = 4.0;         // 파형 포락선 열 폭(ms)
    private static final int CAP_M = 1200;                // 음정·추이 링 점 수
    private static final int ENV_CAP = (int) (MATCH_WIN_MS / ENV_COL_MS) + 600;

    private static final int C_EXP = 0x828282;    // 기대/보조(회색)
    private static final int C_LIVE = 0x1e6edc;   // 라이브(파랑)
    private static final int C_WAVE = 0xe67814;   // 파형 일치도(주황)
    private static final int C_THR = 0xc83c3c;    // 임계(빨강)
    private static final int C_FILL = 0x000000;   // 파형 포락선 채움(검정)
    private static final int BG = 0xffffff;
    private static final String FONT = "Malgun Gothic";
    private static final int LEGEND_W = 92;       // 범례 영역 폭(px)
    private static final int DEFAULT_TICK_APPROX = 10;   // 기본 X축 목표 눈금 수(10s창 → 1000ms 간격)

    // 플롯영역 여백(px) — baseChart 의 setPlotArea 와 drawPassBand 오버레이가 반드시 공유해야
    // PASS 밴드가 스크롤되는 파형 데이터와 정확히 정렬된다(불일치 시 x좌표가 커질수록 밀림).
    private static final int PLOT_L = 52;                    // 좌: Y축 라벨
    private static final int PLOT_T = 24;                    // 상: 제목
    private static final int PLOT_V_CHROME = 54;             // 상+하 여백 합(plotH = h - 54)
    private static final int PLOT_R_LEGEND = LEGEND_W + 8;   // 우: 범례 있을 때
    private static final int PLOT_R_PLAIN = 12;              // 우: 범례 없을 때(파형 패널)

    private final double fmax;
    private boolean showPitch = false;   // 주파수(음정) 패널 — 기본 off(파형만)
    private boolean showTrend = false;   // 일치도 추이 패널 — 기본 off(파형만)

    // ── 표출 스타일(클라이언트 주입) — 기본값 유지, setter로 재정의 ──
    private int tickApprox = DEFAULT_TICK_APPROX;              // X축 목표 눈금 수
    private double tickMs = 0;                                 // >0 이면 눈금 간격(ms) 직접 지정(tickApprox 무시)
    private int passAlpha = 80;                               // PASS 밴드 투명도(0~255)
    private String waveTitle = "파형 그래프";   // 파형 패널 제목

    // 파형 크기 포락선(시간축 ms) — 열당 max/min
    private final double[] eT = new double[ENV_CAP];
    private final double[] eHi = new double[ENV_CAP];
    private final double[] eLo = new double[ENV_CAP];
    private int eHead, eCount;
    private double eLast = -1;

    // 음정 추적(시간축 ms) — 라이브 지배 주파수(Hz)
    private final double[] pT = new double[CAP_M];
    private final double[] pHz = new double[CAP_M];
    private int pHead, pCount;
    private double pLast = -1;

    // 일치도 추이(시간축 ms) — 주파수·파형
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

    // PASS 판정 구간(ms) 목록 — 파형 패널에 초록 밴드로 표시. isPass 인 동안 실시간으로 자란다.
    private final List<double[]> passSpans = new ArrayList<double[]>();   // 각 원소 = {startMs, endMs}
    private int passOpen = -1;   // 현재 열린(자라는) 밴드 index. -1=없음
    private Color passColor;   // 초록(반투명 밴드용)

    private Image composite;

    public AudioScope(Composite parent, double fmax) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        this.fmax = fmax;
        this.passColor = new Color(parent.getDisplay(), 46, 190, 90);   // PASS 밴드(초록)
        addPaintListener(new PaintListener() {
            public void paintControl(PaintEvent e) {
                if (composite == null || composite.isDisposed()) {
                    rebuild();
                }
                if (composite != null && !composite.isDisposed()) {
                    e.gc.drawImage(composite, 0, 0);
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

    // ── 표출 스타일 주입(items 3·4) ──────────────────────────────

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
     * <b>실시간 PASS 밴딩</b> — 매 틱 현재 시각(ms)과 합격 여부를 넘긴다.
     * 합격 중이면 열린 밴드를 현재 시각까지 <b>실시간으로 늘리고</b>, 불합격이 되면 그 밴드를 닫는다.
     * (리빌드는 하지 않음 — 호출측이 {@code setMatchTrend} 로 커밋. isPass 는 {@code MatchResult.isPass}.)
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
    }

    /** PASS 판정 구간(ms)을 파형 그래프에 <b>초록 밴드</b>로 <b>누적</b> 추가(닫힌 구간). */
    public void addPassSpan(double startMs, double endMs) {
        passSpans.add(new double[] { Math.min(startMs, endMs), Math.max(startMs, endMs) });
        rebuildAndRedraw();
    }

    /** PASS 밴드 1개만 표시(기존 것 지우고 설정) — 단일 판정용 편의. */
    public void setPassSpan(double startMs, double endMs) {
        passSpans.clear();
        addPassSpan(startMs, endMs);
    }

    /** 모든 PASS 밴드 제거. */
    public void clearPass() {
        passSpans.clear();
        passOpen = -1;
        rebuildAndRedraw();
    }

    /** 기대 beep 등록 — 목표 주파수만 음정 패널 수평 점선으로. (파형은 라이브 포락선만 표시) */
    public void setExpected(double[] tmpl, int sr) {
        if (tmpl == null || tmpl.length < 2) {
            return;
        }
        this.targetHz = dominantHz(tmpl, sr);
        rebuildAndRedraw();
    }

    /** 매 틱: 라이브 파형 포락선 + 음정(지배 주파수)을 시간축(ms)에 누적. (렌더는 setMatchTrend에서 커밋) */
    public void setData(double[] w, int sr, double targetFreq, double elapsedSec) {
        if (w == null || w.length == 0) {
            return;
        }
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

        // ── 음정 추적: 라이브 지배 주파수 1점 push ──
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

    /** 매 틱: 주파수·파형 일치도 추이 누적 + 화면 커밋(리빌드·리드로우). */
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

    /** 그래프 초기화(측정 리셋) — 링 비움. 기대(목표 주파수)는 유지. */
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
        passSpans.clear();
        passOpen = -1;
        rebuildAndRedraw();
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

    private void rebuildAndRedraw() {
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
            // 상단: 파형(좌) | 주파수(우)
            int halfW = w / 2;
            drawPng(gc, wavePng(halfW, topH), 0, 0);
            drawPassBand(gc, 0, 0, halfW, topH);
            drawPng(gc, pitchPng(w - halfW, topH), halfW, 0);
        } else {
            // 파형 전폭
            drawPng(gc, wavePng(w, topH), 0, 0);
            drawPassBand(gc, 0, 0, w, topH);
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

    private void drawPng(GC gc, byte[] png, int x, int y) {
        if (png == null) {
            return;
        }
        Image img = new Image(getDisplay(), new ByteArrayInputStream(png));
        gc.drawImage(img, x, y);
        img.dispose();
    }

    /**
     * PASS 구간을 파형 패널 플롯영역에 초록 반투명 밴드로 덧그림.
     * (px,py)=패널 원점, (pw,ph)=패널 크기. 플롯영역은 baseChart 의 setPlotArea(52,24,·,h-54) 와 일치.
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

    /** 차트 공통 골격 — 플롯영역·제목·ms X축. showLegend=false 면 범례 자리까지 플롯에 사용. */
    private XYChart baseChart(int w, int h, String title, int approxTicks) {
        return baseChart(w, h, title, approxTicks, true);
    }

    private XYChart baseChart(int w, int h, String title, int approxTicks, boolean showLegend) {
        XYChart c = new XYChart(w, h, BG);
        int right = showLegend ? PLOT_R_LEGEND : PLOT_R_PLAIN;
        int plotW = Math.max(1, w - PLOT_L - right);
        c.setPlotArea(PLOT_L, PLOT_T, plotW, Math.max(1, h - PLOT_V_CHROME), BG, -1, 0xdddddd, 0xf0f0f0, -1);
        c.addTitle(title, FONT, 9);
        if (showLegend) {
            c.addLegend(w - LEGEND_W, 26, true, FONT, 8);
        }
        // 눈금을 정수 그리드에 정렬(라이브 이동 시 소수점 라벨 방지) + 정수 ms 포맷
        double tick = (tickMs > 0) ? tickMs : msTick(winMax - winMin, approxTicks);
        axLo = Math.floor(winMin / tick) * tick;
        axHi = Math.ceil(winMax / tick) * tick;
        if (axHi - axLo < tick) {
            axHi = axLo + tick;
        }
        c.xAxis().setLinearScale(axLo, axHi, tick);
        c.xAxis().setLabelFormat("{value|0}ms");
        return c;
    }

    /** ① 파형 크기 포락선 — 채움. Y축은 진폭 ±1 → ±100%. 범례 없음. */
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
            double[] tX = Arrays.copyOf(tx, m);
            AreaLayer ah = c.addAreaLayer(Arrays.copyOf(hi, m), C_FILL);   // 0..+peak
            ah.setXData(tX);
            AreaLayer al = c.addAreaLayer(Arrays.copyOf(lo, m), C_FILL);   // 0..-peak
            al.setXData(tX);
        }
        return c.makeChart2(Chart.PNG);
    }

    /** ② 음정 추적 — 기대(수평 점선) vs 라이브(실선). */
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

    /** ③ 일치도 추이 — 주파수·파형 실선 + 각 임계 점선. */
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

    /** ms X축 눈금 간격 — 구간/목표틱수에 맞는 nice-step. */
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
