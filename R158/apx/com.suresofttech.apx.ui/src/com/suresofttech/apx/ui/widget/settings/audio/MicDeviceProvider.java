package com.suresofttech.apx.ui.widget.settings.audio;

import com.suresofttech.apx.core.audio.AudioCapture;

/** 측정 Pane이 사용할 마이크 장치 공급자. */
public interface MicDeviceProvider {
    AudioCapture.Device selectedDevice();
}
