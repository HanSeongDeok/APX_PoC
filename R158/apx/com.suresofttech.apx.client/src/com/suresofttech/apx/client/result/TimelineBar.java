package com.suresofttech.apx.client.result;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scale;

/**
 * 결과 탭 타임라인 — 슬라이더 + PASS 지점 마커 + 재생.
 *
 * <p>Scale 위에는 눈금을 그릴 수 없어, 아래 {@link #markerStrip}에 음향/비전 PASS
 * 구간·시각을 색 막대/틱으로 표시한다. 이동 시 현재 시각이 PASS 안인지 라벨로 안내한다.
 */
public class TimelineBar extends Composite {

    /** 슬라이더가 가리키는 시각이 바뀔 때. */
    public interface SeekListener {
        void onSeek(double tMs, boolean fromPlayback);
    }

    /** 재생/정지 요청. */
    public interface PlayListener {
        void onPlay(double fromMs);

        void onPause();
    }

    private static final int TICKS = 2000;

    private final Scale scale;
    private final Label timeLbl;
    private final Label markerLbl;
    private final Button playBtn;
    private final Button jumpAudioBtn;
    private final Button jumpVisionBtn;
    private final Canvas markerStrip;
    private final Color audioColor;
    private final Color visionColor;

    private SeekListener seekListener;
    private PlayListener playListener;

    private double durationMs;
    private double currentMs;
    private boolean playing;
    private boolean suppressEvents;

    private Long audioPassMs;
    private Long visionPassMs;
    private final List<double[]> audioPassSpans = new ArrayList<double[]>();
    private final List<double[]> visionPassSpans = new ArrayList<double[]>();

    public TimelineBar(Composite parent) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(5, false);
        gl.marginWidth = 4;
        gl.marginHeight = 2;
        setLayout(gl);

        audioColor = new Color(parent.getDisplay(), 46, 190, 90);
        visionColor = new Color(parent.getDisplay(), 0, 160, 255);

        playBtn = new Button(this, SWT.PUSH);
        playBtn.setText("▶ 재생");
        playBtn.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        playBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                togglePlay();
            }
        });

        jumpAudioBtn = new Button(this, SWT.PUSH);
        jumpAudioBtn.setText("음향 PASS");
        jumpAudioBtn.setToolTipText("음향 PASS 시각으로 이동");
        jumpAudioBtn.setEnabled(false);
        jumpAudioBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (audioPassMs != null) {
                    setCurrentMs(audioPassMs.doubleValue(), false);
                }
            }
        });

        jumpVisionBtn = new Button(this, SWT.PUSH);
        jumpVisionBtn.setText("비전 PASS");
        jumpVisionBtn.setToolTipText("비전 PASS 시각으로 이동");
        jumpVisionBtn.setEnabled(false);
        jumpVisionBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (visionPassMs != null) {
                    setCurrentMs(visionPassMs.doubleValue(), false);
                }
            }
        });

        scale = new Scale(this, SWT.HORIZONTAL);
        scale.setMinimum(0);
        scale.setMaximum(TICKS);
        scale.setIncrement(1);
        scale.setPageIncrement(TICKS / 20);
        scale.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        scale.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                if (suppressEvents) {
                    return;
                }
                currentMs = tickToMs(scale.getSelection());
                updateTimeLabel();
                updateMarkerLabel();
                markerStrip.redraw();
                fireSeek(false);
            }
        });

        timeLbl = new Label(this, SWT.NONE);
        timeLbl.setText("0.00 / 0.00 s");
        GridData tg = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        tg.widthHint = 130;
        timeLbl.setLayoutData(tg);

        markerStrip = new Canvas(this, SWT.DOUBLE_BUFFERED);
        GridData sg = new GridData(SWT.FILL, SWT.CENTER, true, false, 5, 1);
        sg.heightHint = 14;
        markerStrip.setLayoutData(sg);
        markerStrip.addPaintListener(new PaintListener() {
            public void paintControl(PaintEvent e) {
                paintMarkers(e.gc);
            }
        });

        markerLbl = new Label(this, SWT.WRAP);
        markerLbl.setText("");
        markerLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 5, 1));

        setEnabledAll(false);
    }

    public void setSeekListener(SeekListener l) {
        this.seekListener = l;
    }

    public void setPlayListener(PlayListener l) {
        this.playListener = l;
    }

    /**
     * 타임라인 길이 설정 — 0 이하면 비활성(스크럽할 게 없음).
     * 커서는 0으로 되돌린다.
     */
    public void setDuration(double durationMs) {
        this.durationMs = Math.max(0, durationMs);
        this.currentMs = 0;
        setEnabledAll(this.durationMs > 0);
        suppressEvents = true;
        scale.setSelection(0);
        suppressEvents = false;
        updateTimeLabel();
        updateMarkerLabel();
        markerStrip.redraw();
    }

    public double getDurationMs() {
        return durationMs;
    }

    public double getCurrentMs() {
        return currentMs;
    }

    /**
     * PASS 시각·구간 마커.
     * @param audioPassMs 음향 PASS latch 시각
     * @param visionPassMs 비전 PASS latch 시각
     * @param audioSpans 음향 초록 밴드 구간들
     * @param visionSpans 비전 hit=true 연속 구간들
     */
    public void setMarkers(Long audioPassMs, Long visionPassMs,
            List<double[]> audioSpans, List<double[]> visionSpans) {
        this.audioPassMs = audioPassMs;
        this.visionPassMs = visionPassMs;
        audioPassSpans.clear();
        visionPassSpans.clear();
        if (audioSpans != null) {
            audioPassSpans.addAll(audioSpans);
        }
        if (visionSpans != null) {
            visionPassSpans.addAll(visionSpans);
        }
        jumpAudioBtn.setEnabled(audioPassMs != null && durationMs > 0);
        jumpVisionBtn.setEnabled(visionPassMs != null && durationMs > 0);
        updateMarkerLabel();
        markerStrip.redraw();
        layout(true);
    }

    /** 하위호환 — 구간 없이 시각만. */
    public void setMarkers(Long audioPassMs, Long visionPassMs) {
        setMarkers(audioPassMs, visionPassMs, null, null);
    }

    /** 프로그램에서 시각 이동 — 재생 위치 반영 등. 리스너로 다시 알린다. */
    public void setCurrentMs(double tMs, boolean fromPlayback) {
        if (durationMs <= 0) {
            return;
        }
        currentMs = clamp(tMs, 0, durationMs);
        suppressEvents = true;
        scale.setSelection(msToTick(currentMs));
        suppressEvents = false;
        updateTimeLabel();
        updateMarkerLabel();
        markerStrip.redraw();
        fireSeek(fromPlayback);
    }

    /** 재생 중 표시 상태만 갱신(요청은 PlayListener가 처리). */
    public void setPlaying(boolean on) {
        playing = on;
        playBtn.setText(on ? "■ 정지" : "▶ 재생");
    }

    public boolean isPlaying() {
        return playing;
    }

    private void paintMarkers(GC gc) {
        Rectangle r = markerStrip.getClientArea();
        gc.setBackground(markerStrip.getDisplay().getSystemColor(SWT.COLOR_WIDGET_LIGHT_SHADOW));
        gc.fillRectangle(r);
        if (durationMs <= 0) {
            return;
        }
        // 음향 PASS 구간(초록 반투명 막대)
        gc.setBackground(audioColor);
        for (int i = 0; i < audioPassSpans.size(); i++) {
            fillSpan(gc, r, audioPassSpans.get(i));
        }
        // 비전 PASS 구간(파란 막대, 아래쪽 절반)
        gc.setBackground(visionColor);
        for (int i = 0; i < visionPassSpans.size(); i++) {
            fillSpanHalf(gc, r, visionPassSpans.get(i), true);
        }
        // latch 시각 틱
        gc.setForeground(audioColor);
        drawTick(gc, r, audioPassMs);
        gc.setForeground(visionColor);
        drawTick(gc, r, visionPassMs);
        // 현재 커서
        int cx = msToX(r, currentMs);
        gc.setForeground(markerStrip.getDisplay().getSystemColor(SWT.COLOR_BLACK));
        gc.setLineWidth(2);
        gc.drawLine(cx, 0, cx, r.height);
    }

    private void fillSpan(GC gc, Rectangle r, double[] sp) {
        if (sp == null || sp.length < 2) {
            return;
        }
        int x0 = msToX(r, sp[0]);
        int x1 = msToX(r, sp[1]);
        int w = Math.max(2, x1 - x0);
        gc.setAlpha(140);
        gc.fillRectangle(x0, 2, w, r.height - 4);
        gc.setAlpha(255);
    }

    private void fillSpanHalf(GC gc, Rectangle r, double[] sp, boolean bottom) {
        if (sp == null || sp.length < 2) {
            return;
        }
        int x0 = msToX(r, sp[0]);
        int x1 = msToX(r, sp[1]);
        int w = Math.max(2, x1 - x0);
        int y = bottom ? r.height / 2 : 1;
        int h = Math.max(2, r.height / 2 - 1);
        gc.setAlpha(160);
        gc.fillRectangle(x0, y, w, h);
        gc.setAlpha(255);
    }

    private void drawTick(GC gc, Rectangle r, Long ms) {
        if (ms == null) {
            return;
        }
        int x = msToX(r, ms.doubleValue());
        gc.setLineWidth(2);
        gc.drawLine(x, 0, x, r.height);
    }

    private int msToX(Rectangle r, double ms) {
        if (durationMs <= 0) {
            return r.x;
        }
        double t = clamp(ms, 0, durationMs) / durationMs;
        return r.x + (int) Math.round(t * Math.max(1, r.width - 1));
    }

    private void togglePlay() {
        if (durationMs <= 0 || playListener == null) {
            return;
        }
        if (playing) {
            playListener.onPause();
            setPlaying(false);
        } else {
            double from = currentMs >= durationMs - 1 ? 0 : currentMs;
            playListener.onPlay(from);
            setPlaying(true);
        }
    }

    private void fireSeek(boolean fromPlayback) {
        if (seekListener != null) {
            seekListener.onSeek(currentMs, fromPlayback);
        }
    }

    private void updateTimeLabel() {
        timeLbl.setText(String.format("%.2f / %.2f s",
                Double.valueOf(currentMs / 1000.0), Double.valueOf(durationMs / 1000.0)));
    }

    private void updateMarkerLabel() {
        StringBuilder sb = new StringBuilder();
        if (audioPassMs != null) {
            sb.append("음향 PASS ").append(audioPassMs).append(" ms");
        }
        if (visionPassMs != null) {
            if (sb.length() > 0) {
                sb.append("   ·   ");
            }
            sb.append("비전 PASS ").append(visionPassMs).append(" ms");
        }
        if (sb.length() == 0) {
            sb.append("PASS 시각 없음");
        }
        // 현재 커서 상태
        boolean inAudio = inAnySpan(currentMs, audioPassSpans)
                || (audioPassMs != null && Math.abs(currentMs - audioPassMs.longValue()) < 1);
        boolean inVision = inAnySpan(currentMs, visionPassSpans)
                || (visionPassMs != null && Math.abs(currentMs - visionPassMs.longValue()) < 1);
        if (inAudio || inVision) {
            sb.append("   |   지금: ");
            if (inAudio) {
                sb.append("음향 PASS");
            }
            if (inVision) {
                if (inAudio) {
                    sb.append("+");
                }
                sb.append("비전 PASS");
            }
        }
        markerLbl.setText(sb.toString());
    }

    private static boolean inAnySpan(double t, List<double[]> spans) {
        for (int i = 0; i < spans.size(); i++) {
            double[] sp = spans.get(i);
            if (sp != null && sp.length >= 2 && t >= sp[0] && t <= sp[1]) {
                return true;
            }
        }
        return false;
    }

    private void setEnabledAll(boolean on) {
        scale.setEnabled(on);
        playBtn.setEnabled(on);
        jumpAudioBtn.setEnabled(on && audioPassMs != null);
        jumpVisionBtn.setEnabled(on && visionPassMs != null);
    }

    private double tickToMs(int tick) {
        return durationMs <= 0 ? 0 : tick / (double) TICKS * durationMs;
    }

    private int msToTick(double ms) {
        if (durationMs <= 0) {
            return 0;
        }
        int t = (int) Math.round(ms / durationMs * TICKS);
        return t < 0 ? 0 : (t > TICKS ? TICKS : t);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public void dispose() {
        if (audioColor != null && !audioColor.isDisposed()) {
            audioColor.dispose();
        }
        if (visionColor != null && !visionColor.isDisposed()) {
            visionColor.dispose();
        }
        super.dispose();
    }
}
