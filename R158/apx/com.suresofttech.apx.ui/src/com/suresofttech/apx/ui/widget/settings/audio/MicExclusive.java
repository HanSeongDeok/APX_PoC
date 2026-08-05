package com.suresofttech.apx.ui.widget.settings.audio;

/**
 * 마이크 테스트 ↔ 파형 측정 장치 배타 — 설정값이 아닌 런타임 충돌만 조정.
 * {@link com.suresofttech.apx.ui.widget.SettingsPanel} 또는 이솝 조립 코드에서 {@link #bind} 한 번 호출.
 */
public final class MicExclusive {

    private MicSelectBar mic;
    private ExpectedAudioMeasurePane measure;

    public void bind(MicSelectBar micBar, ExpectedAudioMeasurePane measurePane) {
        this.mic = micBar;
        this.measure = measurePane;
        if (mic != null) {
            mic.setBeforeTestStart(new Runnable() {
                public void run() {
                    if (measure != null) {
                        measure.pauseForExclusive();
                    }
                }
            });
        }
        if (measure != null) {
            measure.setBeforeMeasureStart(new Runnable() {
                public void run() {
                    if (mic != null) {
                        mic.stopTest();
                    }
                }
            });
            measure.setMicDeviceProvider(mic);
        }
    }
}
