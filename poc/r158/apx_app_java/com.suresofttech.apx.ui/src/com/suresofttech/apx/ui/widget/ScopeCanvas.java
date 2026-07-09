package com.suresofttech.apx.ui.widget;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.suresofttech.apx.core.dsp.Fft;
import com.suresofttech.apx.core.dsp.SignalMath;

/**
 * 실시간 파형·스펙트럼 스코프 (SWT Canvas + GC 직접 렌더).
 * 파이썬 apx_app/ui/scope.py 이식. 외부 차트 라이브러리 없이 무의존, Mars 호환.
 *   위: 파형(시간축)  /  아래: 스펙트럼(주파수축, FFT 크기, 목표주파수 표시)
 */
public class ScopeCanvas extends Canvas {

    private double[] wave;
    private double[] spec;      // 크기 스펙트럼 (bins 0..specN/2)
    private int specN;          // 스펙트럼 FFT 크기(주파수 매핑용)
    private int sr;
    private double targetFreq;
    private final double fmax;  // 스펙트럼 표시 상한(Hz)

    private final Color bg;
    private final Color panel;
    private final Color grid;
    private final Color waveColor;
    private final Color specLine;
    private final Color specFill;
    private final Color marker;
    private final Color text;

    public ScopeCanvas(Composite parent, double fmax) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        this.fmax = fmax;
        Display d = getDisplay();
        bg = new Color(d, 11, 11, 11);
        panel = new Color(d, 14, 14, 18);
        grid = new Color(d, 51, 51, 51);
        waveColor = new Color(d, 46, 230, 110);
        specLine = new Color(d, 74, 163, 255);
        specFill = new Color(d, 74, 163, 255);
        marker = new Color(d, 255, 82, 82);
        text = new Color(d, 136, 136, 136);
        addPaintListener(new PaintListener() {
            public void paintControl(PaintEvent e) {
                paintScope(e.gc);
            }
        });
        addListener(SWT.Dispose, new org.eclipse.swt.widgets.Listener() {
            public void handleEvent(org.eclipse.swt.widgets.Event ev) {
                bg.dispose(); panel.dispose(); grid.dispose(); waveColor.dispose();
                specLine.dispose(); specFill.dispose(); marker.dispose(); text.dispose();
            }
        });
    }

    /** 최근 버퍼로 갱신 (UI 스레드에서 호출). */
    public void setData(double[] w, int sr, double targetFreq) {
        this.wave = w;
        this.sr = sr;
        this.targetFreq = targetFreq;
        if (w != null && w.length >= 16) {
            double[] win = SignalMath.mul(w, SignalMath.hanning(w.length));
            this.spec = Fft.magnitude(win);
            this.specN = Fft.nextPow2(w.length);
        } else {
            this.spec = null;
        }
        if (!isDisposed()) {
            redraw();
        }
    }

    private void paintScope(GC gc) {
        Rectangle r = getClientArea();
        int w = r.width, h = r.height;
        gc.setBackground(bg);
        gc.fillRectangle(0, 0, w, h);
        int gap = 10;
        int ph = (h - gap) / 2;
        drawWave(gc, 0, w, ph);
        drawSpec(gc, ph + gap, w, h - (ph + gap));
    }

    private void drawWave(GC gc, int top, int w, int h) {
        gc.setBackground(panel);
        gc.fillRectangle(0, top, w, h);
        int mid = top + h / 2;
        gc.setForeground(grid);
        gc.drawLine(0, mid, w, mid);
        gc.setForeground(text);
        gc.drawText("파형 (시간축)", 6, top + 4, true);
        double[] wv = wave;
        if (wv == null || wv.length < 2 || w < 2) {
            return;
        }
        int n = Math.min(wv.length, w);
        double amp = 1e-9;
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            int idx = (int) ((long) i * (wv.length - 1) / (n - 1));
            ys[i] = wv[idx];
            double a = Math.abs(ys[i]);
            if (a > amp) {
                amp = a;
            }
        }
        double scale = (h / 2.0) * 0.9 / amp;
        int[] pts = new int[2 * n];
        for (int i = 0; i < n; i++) {
            pts[2 * i] = (int) (i * (w - 1.0) / (n - 1));
            pts[2 * i + 1] = (int) (mid - ys[i] * scale);
        }
        gc.setForeground(waveColor);
        gc.drawPolyline(pts);
    }

    private void drawSpec(GC gc, int top, int w, int h) {
        gc.setBackground(panel);
        gc.fillRectangle(0, top, w, h);
        int base = top + h - 14;
        gc.setForeground(grid);
        gc.drawLine(0, base, w, base);
        gc.setForeground(text);
        gc.drawText("스펙트럼 (0~" + (int) fmax + "Hz)", 6, top + 4, true);
        if (spec == null || specN <= 0 || w < 2) {
            return;
        }
        // fmax 이하 bins만
        int maxBin = spec.length - 1;
        double peak = 1e-9;
        for (int k = 0; k <= maxBin; k++) {
            double f = (double) k * sr / specN;
            if (f > fmax) {
                maxBin = k - 1;
                break;
            }
            if (spec[k] > peak) {
                peak = spec[k];
            }
        }
        if (maxBin < 1) {
            return;
        }
        double scale = (h - 24) / peak;
        int[] poly = new int[2 * (maxBin + 1) + 4];
        poly[0] = 0;
        poly[1] = base;
        for (int k = 0; k <= maxBin; k++) {
            double f = (double) k * sr / specN;
            poly[2 + 2 * k] = (int) (f / fmax * w);
            poly[2 + 2 * k + 1] = (int) (base - spec[k] * scale);
        }
        int last = 2 + 2 * maxBin;
        poly[last + 2] = poly[last];   // 마지막 x
        poly[last + 3] = base;
        gc.setBackground(specFill);
        gc.setAlpha(70);
        gc.fillPolygon(poly);
        gc.setAlpha(255);
        gc.setForeground(specLine);
        // 외곽선(바닥 두 점 제외)
        int[] line = new int[2 * (maxBin + 1)];
        System.arraycopy(poly, 2, line, 0, line.length);
        gc.drawPolyline(line);
        // 목표 주파수 표시선
        if (targetFreq > 0 && targetFreq <= fmax) {
            int tx = (int) (targetFreq / fmax * w);
            gc.setForeground(marker);
            gc.drawLine(tx, top, tx, base);
            gc.drawText((int) targetFreq + "Hz", tx + 4, top + 16, true);
        }
    }
}
