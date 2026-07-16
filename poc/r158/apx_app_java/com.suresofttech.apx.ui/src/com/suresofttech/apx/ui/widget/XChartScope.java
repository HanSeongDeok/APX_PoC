package com.suresofttech.apx.ui.widget;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Frame;
import java.util.Arrays;
import java.util.function.Function;

import javax.swing.SwingUtilities;

import org.eclipse.swt.SWT;
import org.eclipse.swt.awt.SWT_AWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.knowm.xchart.XChartPanel;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.XYSeries.XYSeriesRenderStyle;
import org.knowm.xchart.style.markers.SeriesMarkers;

import com.suresofttech.apx.core.dsp.Fft;
import com.suresofttech.apx.core.dsp.SignalMath;

/**
 * 음향 스코프 — XChart + SWT_AWT. 레퍼런스-vs-라이브 오버레이 3패널:
 *  ① 파형 오버레이 (기대=점선, 라이브=실선. ~20ms, 라이브를 기대에 위상 정렬)
 *  ② 음정 추적 (X=경과시간, Y=주파수 Hz. 기대=수평 점선 @목표Hz, 라이브=검출 주 주파수 실선 — 선이 기대 높이에 붙으면 음정 일치)
 *  ③ 일치도 추이 (주파수·파형 일치도 + 각 임계선, X=시간) — 검출 시점 타임라인
 *
 * <p>기대는 점선 고스트로 깔고 라이브를 덧그림(가라오케식). 판정 결과는 막대/텍스트(AudioView)가 담당.
 * {@link #setWaveOnly(boolean)}로 파형 오버레이만 남기고 나머지 패널을 숨길 수 있다.
 */
public class XChartScope extends Composite {

    private static final double WAVE_MS = 40.0;          // 파형 오버레이 창(ms) — 모양 보이는 짧은 스케일
    private static final double MATCH_WIN_MS = 10000.0;  // 일치도/음정 추이 창(ms) = 10초(초 단위 스케일)
    private static final int TICK_HINT_PX = 80;          // X 눈금 최소 간격(px) — 3패널 밀도 통일(덜 촘촘)
    private static final int CAP_M = 1200;               // 추이 링버퍼 점 수(≈틱당 1점)
    private static final double ENV_COL_MS = 4.0;        // 파형 포락선 열 폭(ms) — 촘촘한 채움
    private static final int ENV_CAP = (int) (MATCH_WIN_MS / ENV_COL_MS) + 600;  // 포락선 링 열 수

    private static final Color C_EXP = new Color(130, 130, 130);   // 기대(고스트)
    private static final Color C_LIVE = new Color(30, 110, 220);   // 라이브
    private static final Color C_WAVE = new Color(230, 120, 20);   // 파형 일치도
    private static final Color C_THR = new Color(200, 60, 60);     // 임계
    private static final BasicStroke DASH =
            new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] { 5f, 5f }, 0f);
    private static final BasicStroke DASH_THIN =
            new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] { 3f, 4f }, 0f);
    private static final BasicStroke SOLID = new BasicStroke(1.6f);

    private final double fmax;
    private final XYChart waveChart;
    private final XYChart pitchChart;
    private final XYChart matchChart;
    private final XChartPanel<XYChart> waveP;
    private final XChartPanel<XYChart> pitchP;
    private final XChartPanel<XYChart> matchP;
    private final Composite waveHolder;
    private final Composite pitchHolder;
    private final Composite matchHolder;

    private double[] expWaveRef;   // 기대 파형 첫 20ms 원본(위상정렬 기준)
    private double[] expWaveY;     // 기대 파형 정규화 표시값(이동 창에 매 틱 재배치)

    // 일치도 추이(시간축, 틱당 1점) — 주파수·파형
    private final double[] mRingT = new double[CAP_M];
    private final double[] mRingV = new double[CAP_M];   // freqSim
    private final double[] mRingW = new double[CAP_M];   // waveSim
    private int mHead;
    private int mCount;
    private double mLastT = -1;

    // 음정 추적(시간축, 틱당 1점) — 라이브 검출 주 주파수(Hz)
    private final double[] pRingT = new double[CAP_M];
    private final double[] pRingHz = new double[CAP_M];
    private int pHead;
    private int pCount;
    private double pLastT = -1;

    // 파형 크기 포락선(시간축, 열당 max/min) — 촘촘한 채움, 음정 추적과 동일 축
    private final double[] eRingT = new double[ENV_CAP];
    private final double[] eRingHi = new double[ENV_CAP];
    private final double[] eRingLo = new double[ENV_CAP];
    private int eHead;
    private int eCount;
    private double eLastT = -1;

    public XChartScope(Composite parent, double fmax) {
        super(parent, SWT.NONE);
        this.fmax = fmax;
        setLayout(new GridLayout(2, true));   // 위: 파형 | 음정, 아래: 추이(전폭)

        waveChart = build("파형 크기 포락선 (X=경과시간)", -1.0, 1.0);
        msAxis(waveChart);
        addArea(waveChart, "라이브", C_LIVE);                          // 위 경계(0→+peak)
        addArea(waveChart, "라이브 하한", C_LIVE).setShowInLegend(false);   // 아래 경계(0→-peak) — 범례엔 '라이브' 하나만
        waveHolder = newHolder(1);
        waveP = embedInto(waveHolder, waveChart);

        pitchChart = build("음정 추적 (X=경과시간 / Y=주파수 Hz / 기대 점선 / 라이브 실선)", 0.0, fmax);
        msAxis(pitchChart);
        addLine(pitchChart, "기대", C_EXP, DASH);
        addLine(pitchChart, "라이브", C_LIVE, SOLID);
        pitchHolder = newHolder(1);
        pitchP = embedInto(pitchHolder, pitchChart);

        matchChart = build("일치도 추이 - 주파수 and 파형 (경과시간축, 임계선)", 0.0, 1.0);
        msAxis(matchChart);
        addLine(matchChart, "주파수", C_LIVE, SOLID);
        addLine(matchChart, "파형", C_WAVE, SOLID);
        addLine(matchChart, "주파수 임계", C_THR, DASH_THIN);
        addLine(matchChart, "파형 임계", C_EXP, DASH_THIN);
        matchHolder = newHolder(2);
        matchP = embedInto(matchHolder, matchChart);
    }

    private XYChart build(String title, double ymin, double ymax) {
        XYChart c = new XYChartBuilder().title(title).build();
        c.getStyler().setLegendVisible(true);
        c.getStyler().setYAxisMin(Double.valueOf(ymin));
        c.getStyler().setYAxisMax(Double.valueOf(ymax));
        return c;
    }

    private static void addLine(XYChart c, String name, Color color, BasicStroke stroke) {
        XYSeries s = c.addSeries(name, new double[] { 0, 1 }, new double[] { 0, 0 });
        s.setXYSeriesRenderStyle(XYSeriesRenderStyle.Line);
        s.setMarker(SeriesMarkers.NONE);
        s.setLineColor(color);
        s.setLineStyle(stroke);
    }

    /** 0 기준선까지 채우는 Area 시리즈(파형 포락선 — 꽉 찬 물결 모양). */
    private static XYSeries addArea(XYChart c, String name, Color color) {
        XYSeries s = c.addSeries(name, new double[] { 0, 1 }, new double[] { 0, 0 });
        s.setXYSeriesRenderStyle(XYSeriesRenderStyle.Area);
        s.setMarker(SeriesMarkers.NONE);
        s.setLineColor(color);
        s.setLineStyle(new BasicStroke(0.8f));
        s.setFillColor(new Color(30, 110, 220, 90));   // 반투명 파랑 채움
        return s;
    }

    /** 경과시간 축: 측정 시작 기준 ms 라벨(0ms … 3450ms …). X값 단위 ms. */
    private static void msAxis(XYChart c) {
        c.getStyler().setXAxisTickMarkSpacingHint(TICK_HINT_PX);   // 3패널 눈금 밀도 통일(2ms→덜 촘촘)
        c.getStyler().setxAxisTickLabelsFormattingFunction(new Function<Double, String>() {
            public String apply(Double v) {
                return String.format("%.0fms", v);
            }
        });
    }

    private Composite newHolder(int hspan) {
        Composite holder = new Composite(this, SWT.EMBEDDED | SWT.NO_BACKGROUND);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.horizontalSpan = hspan;
        holder.setLayoutData(gd);
        return holder;
    }

    private XChartPanel<XYChart> embedInto(Composite holder, XYChart chart) {
        Frame frame = SWT_AWT.new_Frame(holder);
        XChartPanel<XYChart> panel = new XChartPanel<XYChart>(chart);
        frame.add(panel);
        return panel;
    }

    /** 음정 추적 패널만 숨김(토글). 파형 오버레이와 일치도 추이는 계속 표시·갱신된다. */
    public void setWaveOnly(boolean waveOnly) {
        if (isDisposed()) {
            return;
        }
        excludeHolder(pitchHolder, waveOnly);       // 음정 추적만 숨김 (일치도 추이는 유지)
        ((GridData) waveHolder.getLayoutData()).horizontalSpan = waveOnly ? 2 : 1;
        layout(true, true);
    }

    private static void excludeHolder(Composite holder, boolean hide) {
        ((GridData) holder.getLayoutData()).exclude = hide;
        holder.setVisible(!hide);
    }

    /** 기대 beep 등록(한 번): 파형 첫 20ms 저장(이동 창에 매 틱 표시). */
    public void setExpected(double[] tmpl, int sr) {
        if (isDisposed() || tmpl == null || tmpl.length < 2) {
            return;
        }
        int wlen = Math.min(tmpl.length, Math.max(2, (int) (WAVE_MS / 1000.0 * sr)));
        expWaveRef = Arrays.copyOfRange(tmpl, 0, wlen);   // 정렬 기준(원본 스케일 무관)
        double pk = 1e-9;
        for (double v : expWaveRef) {
            pk = Math.max(pk, Math.abs(v));
        }
        double[] y = new double[wlen];
        for (int i = 0; i < wlen; i++) {
            y[i] = expWaveRef[i] / pk;
        }
        expWaveY = y;                                     // 정규화 표시값(파형은 setData 이동 창에 그림)
    }

    /** 매 틱: 라이브 파형(위상정렬 후 겹침) + 음정 추적(라이브 주 주파수). */
    public void setData(double[] w, int sr, double targetFreq, double elapsedSec) {
        if (isDisposed() || w == null || w.length == 0) {
            return;
        }
        double elapsedMs = elapsedSec * 1000.0;

        // ── 파형 크기 포락선: 새로 들어온 구간을 ENV_COL_MS 열로 잘게 쪼개 각 열 max/min push(촘촘) ──
        if (elapsedMs < eLastT - 1e-6) {   // 리셋(되감김)
            eHead = 0;
            eCount = 0;
            eLastT = -1;
        }
        double newMs = (eLastT < 0) ? ENV_COL_MS : Math.min(elapsedMs - eLastT, 500.0);
        int ncols = Math.max(1, (int) Math.round(newMs / ENV_COL_MS));
        int colLen = Math.max(1, (int) (ENV_COL_MS / 1000.0 * sr));
        int take = Math.min(w.length, ncols * colLen);
        int base = w.length - take;
        for (int c = 0; c < ncols; c++) {
            int s0 = base + c * colLen;
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
            double t = elapsedMs - (ncols - 1 - c) * ENV_COL_MS;
            int tail = (eHead + eCount) % ENV_CAP;
            eRingT[tail] = t;
            eRingHi[tail] = hi;
            eRingLo[tail] = lo;
            if (eCount < ENV_CAP) {
                eCount++;
            } else {
                eHead = (eHead + 1) % ENV_CAP;
            }
        }
        eLastT = elapsedMs;

        final double[] ex = new double[eCount];
        final double[] ehi = new double[eCount];
        final double[] elo = new double[eCount];
        for (int i = 0; i < eCount; i++) {
            int p = (eHead + i) % ENV_CAP;
            ex[i] = eRingT[p];
            ehi[i] = eRingHi[p];
            elo[i] = eRingLo[p];
        }

        // ── 음정 추적: 라이브 버퍼의 주 주파수(peak) 1점을 시간축 링버퍼에 push ──
        double peakHz = dominantHz(w, sr);
        if (elapsedMs < pLastT - 1e-6) {   // 리셋(되감김)
            pHead = 0;
            pCount = 0;
        }
        int pTail = (pHead + pCount) % CAP_M;
        pRingT[pTail] = elapsedMs;
        pRingHz[pTail] = peakHz;
        if (pCount < CAP_M) {
            pCount++;
        } else {
            pHead = (pHead + 1) % CAP_M;
        }
        pLastT = elapsedMs;

        final double[] px = new double[pCount];
        final double[] py = new double[pCount];
        for (int i = 0; i < pCount; i++) {
            int p = (pHead + i) % CAP_M;
            px[i] = pRingT[p];
            py[i] = pRingHz[p];
        }

        // 파형·음정 공통 시간축(음정 추적과 동일한 sliding 창)
        final double winMin = Math.max(0, elapsedMs - MATCH_WIN_MS);
        final double winMax = Math.max(MATCH_WIN_MS, elapsedMs);
        final double tgt = targetFreq;   // 기대 주파수 수평선 높이

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (ex.length >= 2) {
                    waveChart.updateXYSeries("라이브", ex, ehi, null);
                    waveChart.updateXYSeries("라이브 하한", ex, elo, null);
                }
                waveChart.getStyler().setXAxisMin(winMin);
                waveChart.getStyler().setXAxisMax(winMax);
                waveP.repaint();

                if (px.length >= 2) {
                    pitchChart.updateXYSeries("라이브", px, py, null);
                }
                pitchChart.updateXYSeries("기대", new double[] { winMin, winMax },
                        new double[] { tgt, tgt }, null);   // 기대 주파수 수평 점선
                pitchChart.getStyler().setXAxisMin(winMin);
                pitchChart.getStyler().setXAxisMax(winMax);
                pitchP.repaint();
            }
        });
    }

    /** 매 틱: 주파수·파형 일치도를 시간축(ms, 10초 흐르는 창)에 누적 + 각 임계선. */
    public void setMatchTrend(double freqSim, double waveSim, double freqThr, double waveThr,
                              double elapsedSec) {
        if (isDisposed()) {
            return;
        }
        double elapsedMs = elapsedSec * 1000.0;
        if (elapsedMs < mLastT - 1e-6) {   // 리셋(되감김)
            mHead = 0;
            mCount = 0;
        }
        int tail = (mHead + mCount) % CAP_M;
        mRingT[tail] = elapsedMs;
        mRingV[tail] = Math.max(0.0, Math.min(1.0, freqSim));
        mRingW[tail] = Math.max(0.0, Math.min(1.0, waveSim));
        if (mCount < CAP_M) {
            mCount++;
        } else {
            mHead = (mHead + 1) % CAP_M;
        }
        mLastT = elapsedMs;

        final double[] mx = new double[mCount];
        final double[] mf = new double[mCount];
        final double[] mw = new double[mCount];
        for (int i = 0; i < mCount; i++) {
            int p = (mHead + i) % CAP_M;
            mx[i] = mRingT[p];
            mf[i] = mRingV[p];
            mw[i] = mRingW[p];
        }
        final double xmin = Math.max(0, elapsedMs - MATCH_WIN_MS);
        final double xmax = Math.max(MATCH_WIN_MS, elapsedMs);
        final double fThr = freqThr;
        final double wThr = waveThr;
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                if (mx.length >= 2) {
                    matchChart.updateXYSeries("주파수", mx, mf, null);
                    matchChart.updateXYSeries("파형", mx, mw, null);
                }
                matchChart.updateXYSeries("주파수 임계", new double[] { xmin, xmax },
                        new double[] { fThr, fThr }, null);
                matchChart.updateXYSeries("파형 임계", new double[] { xmin, xmax },
                        new double[] { wThr, wThr }, null);
                matchChart.getStyler().setXAxisMin(xmin);
                matchChart.getStyler().setXAxisMax(xmax);
                matchP.repaint();
            }
        });
    }

    /** 라이브 그래프(파형·음정·일치도) 초기화 — 링버퍼 비우고 시리즈 공백화. 기대(로드된 삐)는 유지. */
    public void clear() {
        if (isDisposed()) {
            return;
        }
        eHead = 0;
        eCount = 0;
        eLastT = -1;
        pHead = 0;
        pCount = 0;
        pLastT = -1;
        mHead = 0;
        mCount = 0;
        mLastT = -1;
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                double[] x0 = { 0, 1 };
                double[] y0 = { 0, 0 };
                waveChart.updateXYSeries("라이브", x0, y0, null);
                waveChart.updateXYSeries("라이브 하한", x0, y0, null);
                pitchChart.updateXYSeries("라이브", x0, y0, null);
                pitchChart.updateXYSeries("기대", x0, y0, null);
                matchChart.updateXYSeries("주파수", x0, y0, null);
                matchChart.updateXYSeries("파형", x0, y0, null);
                matchChart.updateXYSeries("주파수 임계", x0, y0, null);
                matchChart.updateXYSeries("파형 임계", x0, y0, null);
                waveP.repaint();
                pitchP.repaint();
                matchP.repaint();
            }
        });
    }

    /** 라이브 버퍼의 주 주파수(Hz) — Hanning 후 FFT 크기 최대 bin(DC 제외, 0..fmax). */
    private double dominantHz(double[] w, int sr) {
        double[] mag = Fft.magnitude(SignalMath.mul(w, SignalMath.hanning(w.length)));
        int nfft = Fft.nextPow2(w.length);
        int kmax = Math.min(mag.length, (int) (fmax * nfft / sr));
        int argmax = 1;
        double peak = -1;
        for (int k = 1; k < kmax; k++) {   // k=0(DC) 제외
            if (mag[k] > peak) {
                peak = mag[k];
                argmax = k;
            }
        }
        return (double) argmax * sr / nfft;
    }
}
