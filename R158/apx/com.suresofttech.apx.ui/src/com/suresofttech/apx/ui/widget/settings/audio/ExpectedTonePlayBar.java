package com.suresofttech.apx.ui.widget.settings.audio;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.suresofttech.apx.core.audio.TonePlayer;
import com.suresofttech.apx.core.audio.WavIo;
import com.suresofttech.apx.core.config.ApxSettings;

/**
 * 기대음 재생/정지 토글 — {@link TonePlayer}.
 */
public class ExpectedTonePlayBar extends Composite {

    private final Display display;
    private final ApxSettings settings = ApxSettings.get();
    private final TonePlayer tonePlayer = new TonePlayer();
    private final Button playBtn;
    private boolean syncPolling;
    private double[] samples;
    private int sampleRate;
    private String loadedPath;

    public ExpectedTonePlayBar(Composite parent) {
        super(parent, SWT.NONE);
        display = getDisplay();
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        playBtn = new Button(this, SWT.TOGGLE);
        playBtn.setText("기대음 재생");
        playBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        playBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                onPlay(playBtn.getSelection());
            }
        });

        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                syncPolling = false;
                tonePlayer.stop();
            }
        });
        startSyncPoll();
    }


    private void onPlay(boolean on) {
        if (on) {
            if (!ensureLoaded(true)) {
                playBtn.setSelection(false);
                msg("기대 경고음 .wav를 먼저 등록하세요.");
                return;
            }
            tonePlayer.play(samples, sampleRate);
            playBtn.setText("기대음 정지");
            msg("기대음 재생 중…");
        } else {
            tonePlayer.stop();
            playBtn.setText("기대음 재생");
            msg("기대음 재생 정지");
        }
    }

    private boolean ensureLoaded(boolean announceError) {
        String p = settings.getExpectedWavPath();
        if (p == null || p.isEmpty() || !new File(p).isFile()) {
            samples = null;
            sampleRate = 0;
            loadedPath = null;
            return false;
        }
        if (p.equals(loadedPath) && samples != null) {
            return true;
        }
        try {
            WavIo.Wav wav = WavIo.load(p);
            samples = wav.samples;
            sampleRate = wav.sampleRate;
            loadedPath = p;
            return true;
        } catch (Exception ex) {
            samples = null;
            sampleRate = 0;
            loadedPath = null;
            if (announceError) {
                msg("기대음 로드 실패: " + ex.getMessage());
            }
            return false;
        }
    }

    private void startSyncPoll() {
        syncPolling = true;
        display.timerExec(60, new Runnable() {
            public void run() {
                if (!syncPolling || playBtn == null || playBtn.isDisposed()) {
                    return;
                }
                if (playBtn.getSelection() && !tonePlayer.isPlaying()) {
                    playBtn.setSelection(false);
                    playBtn.setText("기대음 재생");
                }
                if (syncPolling && !isDisposed()) {
                    display.timerExec(60, this);
                }
            }
        });
    }

    private void msg(String m) {
        // 상태 표시 제거(미니멀)
    }
}
