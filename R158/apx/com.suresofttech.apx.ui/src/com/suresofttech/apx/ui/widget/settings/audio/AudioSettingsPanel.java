package com.suresofttech.apx.ui.widget.settings.audio;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;

import com.suresofttech.apx.ui.widget.settings.StatusLabel;
import com.suresofttech.apx.ui.widget.settings.StatusSink;

/**
 * 음향 설정 조립 패널 — {@link MicSelectBar}·기대 wav/측정/재생·임계 조합.
 * <pre>new AudioSettingsPanel(parent);</pre>
 */
public class AudioSettingsPanel extends Composite {

    private final MicSelectBar micBar;

    public AudioSettingsPanel(Composite parent) {
        super(parent, SWT.NONE);
        setLayout(new GridLayout(1, false));
        setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group mic = new Group(this, SWT.NONE);
        mic.setText("마이크");
        mic.setLayout(new GridLayout(1, false));
        mic.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        micBar = new MicSelectBar(mic);
        micBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        final StatusLabel audioStatus = new StatusLabel(mic, "마이크·기대음을 설정하세요.");
        StatusSink audioSink = new StatusSink() {
            public void setMessage(String msg) {
                audioStatus.setMessage(msg);
            }
        };
        micBar.setStatusSink(audioSink);

        Group expected = new Group(this, SWT.NONE);
        expected.setText("기대 음향");
        expected.setLayout(new GridLayout(1, false));
        expected.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        ExpectedWavBar wavBar = new ExpectedWavBar(expected);
        wavBar.setStatusSink(audioSink);

        ExpectedAudioMeasurePane measurePane = new ExpectedAudioMeasurePane(expected);
        measurePane.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        measurePane.setStatusSink(audioSink);

        ExpectedTonePlayBar playBar = new ExpectedTonePlayBar(measurePane.getActionRow());
        playBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        playBar.setStatusSink(audioSink);

        new AudioThresholdBar(expected);

        new MicExclusive().bind(micBar, measurePane);

        micBar.refreshMics();
    }

    public MicSelectBar getMicSelect() {
        return micBar;
    }
}
