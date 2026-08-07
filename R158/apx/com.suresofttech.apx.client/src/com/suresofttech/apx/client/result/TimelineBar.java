package com.suresofttech.apx.client.result;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Scale;

/**
 * 결과 탭 타임라인 — 측정 시작(0ms)부터 끝까지 이동하는 슬라이더 + 재생 컨트롤.
 *
 * <p>시각을 아는 유일한 주체이고, 실제로 무엇을 보여줄지는 모른다.
 * {@link SeekListener}로 시각(ms)만 흘려보내면 비전/음향 패널이 각자 그 시점을 그린다.
 *
 * <p>{@link Scale}은 정수 눈금이라 0..{@link #TICKS} 로 정규화해서 쓴다
 * (ms를 그대로 넣으면 긴 측정에서 눈금이 뭉개진다).
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

    private SeekListener seekListener;
    private PlayListener playListener;

    private double durationMs;
    private double currentMs;
    private boolean playing;
    /** 재생 중 위치 반영으로 인한 setSelection이 다시 seek을 쏘지 않도록. */
    private boolean suppressEvents;

    public TimelineBar(Composite parent) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 4;
        gl.marginHeight = 2;
        setLayout(gl);

        playBtn = new Button(this, SWT.PUSH);
        playBtn.setText("▶ 재생");
        playBtn.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        playBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                togglePlay();
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
                fireSeek(false);
            }
        });

        timeLbl = new Label(this, SWT.NONE);
        timeLbl.setText("0.00 / 0.00 s");
        GridData tg = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        tg.widthHint = 130;
        timeLbl.setLayoutData(tg);

        markerLbl = new Label(this, SWT.NONE);
        markerLbl.setText("");
        markerLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));

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
    }

    public double getDurationMs() {
        return durationMs;
    }

    public double getCurrentMs() {
        return currentMs;
    }

    /**
     * PASS 시각 등 참고 표기 — 슬라이더 아래 한 줄.
     * (Scale 위젯에는 눈금 마커를 그릴 수 없어 텍스트로 안내한다)
     */
    public void setMarkers(Long audioPassMs, Long visionPassMs) {
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
        markerLbl.setText(sb.length() == 0 ? "PASS 시각 없음" : sb.toString());
        layout(true);
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

    private void togglePlay() {
        if (durationMs <= 0 || playListener == null) {
            return;
        }
        if (playing) {
            playListener.onPause();
            setPlaying(false);
        } else {
            // 끝에서 누르면 처음부터
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

    private void setEnabledAll(boolean on) {
        scale.setEnabled(on);
        playBtn.setEnabled(on);
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
}
