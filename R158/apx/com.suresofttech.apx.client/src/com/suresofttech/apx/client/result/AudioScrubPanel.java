package com.suresofttech.apx.client.result;

import java.io.File;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.audio.AudioPlayer;
import com.suresofttech.apx.core.audio.WavIo;
import com.suresofttech.apx.ui.widget.settings.audio.AudioScope;

/**
 * 결과 탭 음향 스크럽 패널 — 저장된 {@code full.wav} 하나로 파형과 소리를 모두 낸다.
 *
 * <p>파형 이미지를 따로 저장해두지 않고 <b>wav를 다시 그려서</b> 그 시점 구간을 보여준다
 * (샘플 배열 하나만 메모리에 올리면 어느 구간이든 즉시 렌더 — 구간별 PNG를 쌓는 것보다 가볍다).
 * 소리는 같은 파일을 {@link AudioPlayer}가 그 시점부터 재생한다.
 */
public class AudioScrubPanel extends Composite {

    private final AudioScope scope;
    private final Label infoLbl;
    private final AudioPlayer player = new AudioPlayer();

    private double[] samples;
    private int sampleRate;
    private double durationMs;
    /** 라이브 {@link AudioScope#MATCH_WIN_MS} 와 동일. */
    private double windowMs = AudioScope.MATCH_WIN_MS;

    public AudioScrubPanel(Composite parent) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        gl.verticalSpacing = 2;
        setLayout(gl);

        scope = new AudioScope(this, 5000.0);
        scope.setShowPitch(false);
        scope.setShowTrend(false);
        scope.setTickMs(AudioScope.DEFAULT_TICK_MS); // 설정·라이브와 동일 1s 눈금
        scope.setWaveTitle("녹음 파형 (스크럽)");
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 220;
        gd.minimumHeight = 140;
        scope.setLayoutData(gd);

        infoLbl = new Label(this, SWT.NONE);
        infoLbl.setText("—");
        infoLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                player.close();
            }
        });
    }

    /**
     * 증거 폴더의 {@code audio/full.wav}를 연다.
     * @return 열렸으면 true
     */
    public boolean open(File fullWav) {
        samples = null;
        sampleRate = 0;
        durationMs = 0;
        player.close();
        // 이전에 열어둔 TC의 PASS 밴드를 반드시 지운다 —
        // 남아 있으면 다른 측정의 초록 구간을 이 측정의 증거로 잘못 읽는다.
        scope.clearPass();
        if (fullWav == null || !fullWav.isFile()) {
            infoLbl.setText("녹음 파일 없음 (audio/full.wav)");
            scope.clear();
            return false;
        }
        try {
            WavIo.Wav w = WavIo.load(fullWav.getAbsolutePath());
            samples = w.samples;
            sampleRate = w.sampleRate;
            durationMs = sampleRate <= 0 ? 0 : samples.length * 1000.0 / sampleRate;
        } catch (Exception ex) {
            infoLbl.setText("녹음 파일을 읽지 못했습니다: " + ex.getMessage());
            return false;
        }
        player.open(fullWav);
        infoLbl.setText(String.format("%.2f s · %d Hz · wav 재렌더(저장본 없음)",
                Double.valueOf(durationMs / 1000.0), Integer.valueOf(sampleRate)));
        showAt(0);
        return true;
    }

    /**
     * PASS 초록 밴드 표시 — 저장된 clip 구간을 그대로 얹는다.
     * 구간이 없으면(=PASS 없던 측정) 밴드를 <b>지운다</b>. 이전 값이 남으면 안 된다.
     */
    public void setPassSpan(Double startMs, Double endMs) {
        if (startMs == null || endMs == null || endMs.doubleValue() <= startMs.doubleValue()) {
            scope.clearPass();
            return;
        }
        scope.setPassSpan(startMs.doubleValue(), endMs.doubleValue());
    }

    /**
     * 라이브 모니터와 동일 — PASS 초록 밴드 여러 구간.
     * null/빈 목록이면 밴드를 지운다.
     */
    public void setPassSpans(List<double[]> spans) {
        scope.clearPass();
        if (spans == null || spans.isEmpty()) {
            return;
        }
        for (int i = 0; i < spans.size(); i++) {
            double[] sp = spans.get(i);
            if (sp != null && sp.length >= 2 && sp[1] > sp[0]) {
                scope.addPassSpanQuiet(sp[0], sp[1]);
            }
        }
        if (!scope.isDisposed()) {
            scope.redraw();
        }
    }

    public double durationMs() {
        return durationMs;
    }

    public boolean hasAudio() {
        return samples != null && sampleRate > 0;
    }

    /** 커서 주변 창 폭(ms). 전체를 한 눈에 보려면 측정 길이를 넣으면 된다. */
    public void setWindowMs(double ms) {
        if (ms > 0) {
            this.windowMs = ms;
        }
    }

    /**
     * 그 시각의 파형 구간을 그리고 커서를 찍는다.
     * 창 계산은 라이브 {@code AudioScope} {@code updateWindow} 와 동일 —
     * 짧은 wav여도 축은 최소 {@code windowMs}(기본 0~10000ms)를 유지한다.
     */
    public void showAt(double tMs) {
        if (!hasAudio() || scope.isDisposed()) {
            return;
        }
        double end = Math.max(windowMs, tMs);
        double start = Math.max(0, tMs - windowMs);
        if (end <= start) {
            end = start + 1;
        }
        scope.showWindow(samples, sampleRate, start, end, tMs);
    }

    /** 그 시각부터 실제 녹음 재생. */
    public void play(double fromMs) {
        if (player.isOpen()) {
            player.play(fromMs);
        }
    }

    public void pause() {
        player.pause();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    /** 클립이 실제로 소리를 내는 중인지 — 자연 종료 감지용. */
    public boolean isRunning() {
        return player.isRunning();
    }

    /** 재생 위치(ms) — 타임라인이 폴링해 슬라이더를 따라 움직인다. */
    public double playbackPositionMs() {
        return player.getPositionMs();
    }

    public AudioScope getScope() {
        return scope;
    }
}
