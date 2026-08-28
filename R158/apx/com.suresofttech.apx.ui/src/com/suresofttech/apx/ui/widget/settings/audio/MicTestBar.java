package com.suresofttech.apx.ui.widget.settings.audio;

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
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;

import com.suresofttech.apx.core.audio.AudioCapture;
import com.suresofttech.apx.core.audio.MicMeter;
import com.suresofttech.apx.core.config.ApxSettings;

/**
 * 입력 레벨 + 마이크 테스트 토글 - {@link MicMeter}. (장치 선택은 {@link MicSelectBar}.)
 * 장치는 {@link ApxSettings#getMicName()} → {@link AudioCapture#findInputDevice}.
 */
public class MicTestBar extends Composite {

    /** 클라이언트 주입 문구 - 기본값 유지, 필요한 것만 덮어쓴다. */
    public static final class Cfg {
        public String levelText = "입력 레벨";
        public String testText = "마이크 테스트 시작";
        public String testingText = "마이크 테스트 정지";
    }

    private final Display display;
    private final ApxSettings settings = ApxSettings.get();
    private final MicMeter meter = new MicMeter();
    private final Cfg cfg;
    private final ProgressBar levelBar;
    private final Button micTestBtn;
    private boolean polling;

    public MicTestBar(Composite parent) {
        this(parent, new Cfg());
    }

    public MicTestBar(Composite parent, Cfg cfg) {
        super(parent, SWT.NONE);
        this.cfg = (cfg != null) ? cfg : new Cfg();
        display = getDisplay();
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Composite levelRow = new Composite(this, SWT.NONE);
        GridLayout levelGl = new GridLayout(2, false);
        levelGl.marginWidth = 0;
        levelGl.marginHeight = 0;
        levelGl.horizontalSpacing = 8;
        levelRow.setLayout(levelGl);
        levelRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label lv = new Label(levelRow, SWT.NONE);
        lv.setText(this.cfg.levelText);
        levelBar = new ProgressBar(levelRow, SWT.HORIZONTAL | SWT.SMOOTH);
        levelBar.setMinimum(0);
        levelBar.setMaximum(100);
        levelBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        micTestBtn = new Button(this, SWT.TOGGLE);
        micTestBtn.setText(this.cfg.testText);
        micTestBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        micTestBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                onMicTest(micTestBtn.getSelection());
            }
        });

        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                polling = false;
                meter.stop();
            }
        });
        startLevelPoll();
    }

    private void onMicTest(boolean on) {
        if (on) {
            AudioCapture.Device dev = AudioCapture.findInputDevice(settings.getMicName());
            if (dev == null || !meter.start(dev.info)) {
                micTestBtn.setSelection(false);
                return;
            }
            settings.setMicName(dev.name);
            micTestBtn.setText(cfg.testingText);
        } else {
            meter.stop();
            micTestBtn.setText(this.cfg.testText);
        }
    }

    private void startLevelPoll() {
        polling = true;
        display.timerExec(60, new Runnable() {
            public void run() {
                if (!polling || levelBar == null || levelBar.isDisposed()) {
                    return;
                }
                int v = 0;
                if (micTestBtn.getSelection()) {
                    // RMS(0~1) → UI 0~100. 일반 말소리 RMS는 작아 감도 보정.
                    v = (int) Math.min(100, Math.round(meter.getLevel() * 800.0));
                }
                if (levelBar.getSelection() != v) {
                    levelBar.setSelection(v);
                }
                if (polling && !isDisposed()) {
                    display.timerExec(60, this);
                }
            }
        });
    }
}
