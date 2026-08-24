import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;

import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.client.monitor.AudioReadout;

/**
 * AudioReadout 조립 예시 - 일치도 / PASS 구간(clip.wav) / 자체 판단 판독값.
 *
 * <p>경로: {@code apx-settings-demo/examples/components/AudioReadoutExample.java}
 */
public final class AudioReadoutExample {

    private AudioReadoutExample() {
    }

    public static AudioReadout build(Composite parent) {
        AudioReadout readout = new AudioReadout(parent);
        readout.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return readout;
    }

    public static void onStarted(AudioReadout readout, String micName, int sampleRate,
            String expectedWavPath) {
        readout.onStarted(micName, sampleRate, expectedWavPath);
    }

    public static void onTick(AudioReadout readout, MatchResult m, double elapsedSec,
            int passSpanCount) {
        readout.update(m, elapsedSec, passSpanCount);
    }
}
