package com.suresofttech.apx.client.result;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.measure.EvidenceBundle;

/**
 * 증거 폴더 하나를 물려 <b>시간축으로 되짚는</b> 결과 재생기.
 *
 * <p>슬라이더 하나가 기준이고, 그 시각을 비전 프레임과 음향 파형이 함께 따라간다 —
 * 측정 당시가 아니라 <b>TC가 끝난 뒤 다시 열었을 때</b>도 폴더만 있으면 재현된다
 * (시각·판정은 {@code meta.properties}, 화면은 {@code full.avi}/{@code full.wav}에서 복원).
 *
 * <p>재생 중에는 음향 클럭이 마스터다 — 실제 재생 위치를 폴링해 슬라이더와 비전 프레임을
 * 끌고 간다(비전 프레임 속도로 소리를 맞추면 소리가 끊긴다).
 */
public class EvidenceScrubber extends Composite {

    /** 재생 중 위치 폴링 주기(ms) — 30fps 화면 갱신에 맞춘다. */
    private static final int TICK_MS = 33;

    private final Display display;
    private final Label headerLbl;
    private final TimelineBar timeline;
    private final VisionScrubPanel vision;
    private final AudioScrubPanel audio;

    private EvidenceBundle bundle;
    private boolean ticking;

    public EvidenceScrubber(Composite parent) {
        super(parent, SWT.NONE);
        display = parent.getDisplay();
        GridLayout gl = new GridLayout(2, true);
        gl.marginWidth = 4;
        gl.marginHeight = 2;
        setLayout(gl);

        headerLbl = new Label(this, SWT.WRAP);
        headerLbl.setText("증거 폴더를 열면 측정 전 구간을 되짚을 수 있습니다.");
        headerLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        timeline = new TimelineBar(this);
        timeline.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        vision = new VisionScrubPanel(this);
        vision.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        audio = new AudioScrubPanel(this);
        audio.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        timeline.setSeekListener(new TimelineBar.SeekListener() {
            public void onSeek(double tMs, boolean fromPlayback) {
                vision.showAt(tMs);
                audio.showAt(tMs);
                if (!fromPlayback && audio.isPlaying()) {
                    audio.play(tMs);   // 재생 중 스크럽 — 그 지점부터 이어 듣게
                }
            }
        });
        timeline.setPlayListener(new TimelineBar.PlayListener() {
            public void onPlay(double fromMs) {
                audio.play(fromMs);
                startTick();
            }

            public void onPause() {
                audio.pause();
                ticking = false;
            }
        });
        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                ticking = false;
            }
        });
    }

    /**
     * 증거 폴더를 연다 — {@code audio/full.wav}, {@code vision/full.avi}, {@code meta.properties}.
     * @return 스크럽할 자료가 하나라도 있으면 true
     */
    public boolean open(File evidenceRoot) {
        ticking = false;
        timeline.setPlaying(false);
        bundle = EvidenceBundle.open(evidenceRoot);
        if (bundle == null) {
            headerLbl.setText("증거 폴더가 아닙니다: " + evidenceRoot);
            timeline.setDuration(0);
            return false;
        }
        boolean hasVideo = vision.open(bundle.getVisionDir());
        boolean hasAudio = audio.open(bundle.getFullWav());
        // 저장 당시 PASS 초록 밴드를 그대로 복원 — clip.wav 구간과 같은 범위
        audio.setPassSpan(bundle.getAudioPassStartMs(), bundle.getAudioPassEndMs());

        double duration = Math.max(bundle.durationMs(),
                Math.max(vision.durationMs(), audio.durationMs()));
        timeline.setDuration(duration);
        timeline.setMarkers(bundle.getAudioPassMs(), bundle.getVisionPassMs());
        headerLbl.setText(buildHeader(hasVideo, hasAudio));

        if (duration > 0) {
            // 처음엔 PASS 시각으로 보내면 바로 판정 순간을 본다
            timeline.setCurrentMs(initialCursorMs(), false);
        }
        return hasVideo || hasAudio;
    }

    /** 현재 열려 있는 증거. 없으면 null. */
    public EvidenceBundle getBundle() {
        return bundle;
    }

    public TimelineBar getTimeline() {
        return timeline;
    }

    public VisionScrubPanel getVisionPanel() {
        return vision;
    }

    public AudioScrubPanel getAudioPanel() {
        return audio;
    }

    /** 처음 표시할 시각 — 비전 PASS > 음향 PASS > 0. */
    private double initialCursorMs() {
        Long v = bundle.getVisionPassMs();
        if (v != null) {
            return v.doubleValue();
        }
        Long a = bundle.getAudioPassMs();
        return a == null ? 0 : a.doubleValue();
    }

    private String buildHeader(boolean hasVideo, boolean hasAudio) {
        StringBuilder sb = new StringBuilder();
        sb.append(bundle.isOverallPass() ? "PASS" : "FAIL");
        String summary = bundle.getSummary();
        if (summary != null && !summary.isEmpty()) {
            sb.append(" — ").append(summary);
        }
        sb.append("   ·   ").append(bundle.getRoot().getName());
        if (!hasVideo || !hasAudio) {
            sb.append("   ·   없는 자료: ");
            if (!hasVideo) {
                sb.append("비전 녹화본 ");
            }
            if (!hasAudio) {
                sb.append("음향 녹음 ");
            }
        }
        return sb.toString();
    }

    /**
     * 재생 중 음향 위치를 따라 슬라이더·비전 프레임을 끈다.
     * 음향이 없으면 타이머로 시간을 흘려보낸다(무음 재생).
     */
    private void startTick() {
        if (ticking) {
            return;
        }
        ticking = true;
        final long[] lastNanos = { System.nanoTime() };
        display.timerExec(TICK_MS, new Runnable() {
            public void run() {
                if (!ticking || isDisposed()) {
                    return;
                }
                long now = System.nanoTime();
                double elapsed = (now - lastNanos[0]) / 1e6;
                lastNanos[0] = now;   // 분기와 무관하게 매 틱 갱신 — 전환 순간 시간이 튀지 않게
                double next;
                if (audio.hasAudio() && audio.isRunning()) {
                    next = audio.playbackPositionMs();   // 소리가 나는 동안은 음향이 마스터
                } else {
                    // wav가 타임라인보다 짧은 경우(비전 녹화가 더 김) — 벽시계로 이어 간다
                    next = timeline.getCurrentMs() + elapsed;
                }
                if (next >= timeline.getDurationMs()) {
                    timeline.setCurrentMs(timeline.getDurationMs(), true);
                    ticking = false;
                    audio.pause();
                    timeline.setPlaying(false);
                    return;
                }
                timeline.setCurrentMs(next, true);
                display.timerExec(TICK_MS, this);
            }
        });
    }
}
