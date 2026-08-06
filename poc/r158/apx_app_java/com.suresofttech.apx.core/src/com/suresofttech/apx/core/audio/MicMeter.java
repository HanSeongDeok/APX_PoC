package com.suresofttech.apx.core.audio;

import javax.sound.sampled.Mixer;

/**
 * 마이크 입력 레벨(RMS 0~1) 실시간 측정 (파이썬 apx_app/devices.py MicMeter 대응).
 * AudioCapture 를 감싸 블록마다 RMS 를 계산, UI 타이머가 getLevel() 로 읽는다.
 */
public final class MicMeter {

    private final AudioCapture capture = new AudioCapture();
    private volatile double level;

    /** 측정 시작. 성공 여부 반환. device=null 이면 기본 입력. */
    public boolean start(Mixer.Info device) {
        try {
            capture.start(device, 16000, new AudioCapture.BlockListener() {
                public void onBlock(double[] block, double now) {
                    double s = 0;
                    for (int i = 0; i < block.length; i++) {
                        s += block[i] * block[i];
                    }
                    level = Math.sqrt(s / Math.max(1, block.length));
                }
            });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 최근 RMS 레벨 [0~1]. */
    public double getLevel() {
        return level;
    }

    public void stop() {
        capture.stop();
        level = 0.0;
    }
}
