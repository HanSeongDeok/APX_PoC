package com.suresofttech.apx.core.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * 음향 발사 — mono double[-1,1) 를 기본 출력(스피커)으로 재생. SWT/RCP 무의존(core).
 *
 * <p>auto-trigger·L2 캘리브용: {@link #play}는 <b>발사(재생 시작) 시각</b>(공통시계 초)을 반환한다.
 * 이후 마이크가 그 소리를 검출한 시각과 비교하면 파이프라인 지연(스피커 출력버퍼 + 음향경로 +
 * D_mic + 판단속도)을 잴 수 있다. 여러 번 재서 평균=상수지연(보정용), 편차=지터(하드웨어 스펙 근거).
 *
 * <p>재생은 데몬 스레드에서 비동기로 처리해 호출자(UI)를 막지 않는다.
 */
public final class AudioPlayer {

    private AudioPlayer() {
    }

    /**
     * mono double[-1,1) 를 스피커로 비동기 재생.
     * @return 발사 시각(초, System.nanoTime 기반 공통시계). 스피커 출력버퍼 지연은 이 시각 이후 추가.
     */
    public static double play(final double[] samples, final int sr) {
        double emit = System.nanoTime() * 1e-9;
        if (samples == null || samples.length == 0 || sr <= 0) {
            return emit;
        }
        final byte[] raw = to16bitLE(samples);
        Thread t = new Thread(new Runnable() {
            public void run() {
                SourceDataLine line = null;
                try {
                    AudioFormat fmt = new AudioFormat(sr, 16, 1, true, false);   // signed 16bit mono LE
                    line = AudioSystem.getSourceDataLine(fmt);
                    line.open(fmt);
                    line.start();
                    line.write(raw, 0, raw.length);
                    line.drain();
                } catch (Exception e) {
                    // 재생 실패(장치 없음 등) — 무시
                } finally {
                    if (line != null) {
                        try {
                            line.stop();
                            line.close();
                        } catch (Exception ignore) {
                            // 무시
                        }
                    }
                }
            }
        }, "apx-audio-play");
        t.setDaemon(true);
        t.start();
        return emit;
    }

    /** 순수 톤(사인파) 생성 후 재생. 캘리브용 간단 자극. @return 발사 시각(초). */
    public static double playTone(double freqHz, double durSec, int sr) {
        int n = Math.max(1, (int) (durSec * sr));
        double[] s = new double[n];
        double w = 2.0 * Math.PI * freqHz / sr;
        for (int i = 0; i < n; i++) {
            s[i] = 0.7 * Math.sin(w * i);
        }
        return play(s, sr);
    }

    private static byte[] to16bitLE(double[] s) {
        byte[] raw = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            double v = s[i];
            if (v > 0.999969) {
                v = 0.999969;
            } else if (v < -1.0) {
                v = -1.0;
            }
            int x = (int) Math.round(v * 32767.0);
            raw[2 * i] = (byte) (x & 0xff);
            raw[2 * i + 1] = (byte) ((x >> 8) & 0xff);
        }
        return raw;
    }
}
