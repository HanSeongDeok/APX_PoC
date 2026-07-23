package com.suresofttech.apx.ui.widget;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
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
 * <p>상단: ① 파형(좌) | ② 음정(우), 하단 전폭: ③ 추이. {@link #setWaveOnly}로 ②를 숨긴다(파형 전폭).
 * 판정 결과 텍스트/막대는 AudioView가 담당. ChartDirector multiline 방식(범례 박스 + dashLineColor 점선).
 */
public class XChartScope extends Canvas {

    private static final double MATCH_WIN_MS = 10000.0;   // 흐르는 창 = 10초
    private static final double ENV_COL_MS = 4.0;         // 파형 포락선 열 폭(ms)
    private static final int CAP_M = 1200;                // 음정·추이 링 점 수
    private static final int ENV_CAP = (int) (MATCH_WIN_MS / ENV_COL_MS) + 600;

    private static final int C_EXP = 0x828282;    // 기대/보조(회색)
    private static final int C_LIVE = 0x1e6edc;   // 라이브(파랑)
    private static final int C_WAVE = 0xe67814;   // 파형 일치도(주황)
    private static final int C_THR = 0xc83c3c;    // 임계(빨강)
    private static final int C_FILL = 0x601e6edc; // 파형 포락선 채움(반투명 파랑)
    private static final int BG = 0xffffff;
    private static final String FONT = "Malgun Gothic";
    private static final int LEGEND_W = 92;       // 범례 영역 폭(px)

    private final double fmax;
    private boolean waveOnly;

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

    private Image composite;

    public XChartScope(Composite parent, double fmax) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        this.fmax = fmax;
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
        });
        addListener(SWT.Resize, e -> rebuildAndRedraw());
    }

    /** 음정 추적 패널만 숨김(토글). 파형·추이는 유지·갱신. */
    public void setWaveOnly(boolean b) {
        this.waveOnly = b;
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
        int topH = h / 2;
        int botH = h - topH;

        Image comp = new Image(getDisplay(), w, h);
        GC gc = new GC(comp);
        gc.setBackground(getDisplay().getSystemColor(SWT.COLOR_WHITE));
        gc.fillRectangle(0, 0, w, h);
        if (waveOnly) {
            drawPng(gc, wavePng(w, topH), 0, 0);
        } else {
            int halfW = w / 2;
            drawPng(gc, wavePng(halfW, topH), 0, 0);
            drawPng(gc, pitchPng(w - halfW, topH), halfW, 0);
        }
        drawPng(gc, trendPng(w, botH), 0, topH);
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

    /** 차트 공통 골격 — 플롯영역(오른쪽 범례 공간)·제목·범례·ms X축. */
    private XYChart baseChart(int w, int h, String title, int approxTicks) {
        XYChart c = new XYChart(w, h, BG);
        int plotW = Math.max(1, w - 52 - LEGEND_W - 8);
        c.setPlotArea(52, 24, plotW, Math.max(1, h - 54), BG, -1, 0xdddddd, 0xf0f0f0, -1);
        c.addTitle(title, FONT, 9);
        c.addLegend(w - LEGEND_W, 26, true, FONT, 8);
        c.xAxis().setLinearScale(winMin, winMax, msTick(winMax - winMin, approxTicks));
        c.xAxis().setLabelFormat("{value}ms");
        return c;
    }

    /** ① 파형 크기 포락선 — 라이브 채움(반투명 파랑). */
    private byte[] wavePng(int w, int h) {
        XYChart c = baseChart(w, h, "파형 크기 포락선 (X=경과시간)", 5);
        c.yAxis().setLinearScale(-1.0, 1.0, 0.5);
        int n = eCount;
        double[] tx = new double[n];
        double[] hi = new double[n];
        double[] lo = new double[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            int p = (eHead + i) % ENV_CAP;
            double t = eT[p];
            if (t < winMin || t > winMax) {   // 창 밖 제외(오버플로 방지)
                continue;
            }
            tx[m] = t;
            hi[m] = eHi[p];
            lo[m] = eLo[p];
            m++;
        }
        if (m >= 2) {
            double[] tX = Arrays.copyOf(tx, m);
            AreaLayer ah = c.addAreaLayer(Arrays.copyOf(hi, m), C_FILL, "라이브");   // 0..+peak
            ah.setXData(tX);
            AreaLayer al = c.addAreaLayer(Arrays.copyOf(lo, m), C_FILL);            // 0..-peak (범례 중복 방지: 무명)
            al.setXData(tX);
        }
        return c.makeChart2(Chart.PNG);
    }

    /** ② 음정 추적 — 기대(수평 점선) vs 라이브(실선). */
    private byte[] pitchPng(int w, int h) {
        XYChart c = baseChart(w, h, "음정 추적 (X=경과시간 / Y=주파수 Hz / 기대 점선 / 라이브 실선)", 5);
        c.yAxis().setLinearScale(0, fmax, 1000);
        double[] edge = { winMin, winMax };
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
            if (t < winMin || t > winMax) {
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
        XYChart c = baseChart(w, h, "일치도 추이 - 주파수 and 파형 (경과시간축, 임계선)", 10);
        c.yAxis().setLinearScale(0, 1.0, 0.2);
        int nn = mCount;
        double[] tx = new double[nn];
        double[] tf = new double[nn];
        double[] tw = new double[nn];
        int m = 0;
        for (int i = 0; i < nn; i++) {
            int p = (mHead + i) % CAP_M;
            double t = mT[p];
            if (t < winMin || t > winMax) {
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
        double[] edge = { winMin, winMax };
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
