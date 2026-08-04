package com.suresofttech.apx.core.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * 경고음(파형) 스피커 재생 (파이썬 apx_app/engine/tone.py 의 TonePlayer 대응).
 * 순수 JDK javax.sound.sampled SourceDataLine, 비차단(별도 스레드).
 */
public final class TonePlayer {

    private SourceDataLine line;
    private Thread thread;
    private volatile boolean playing;

    /** javax.sound 는 항상 존재. */
    public boolean isAvailable() {
        return true;
    }

    public boolean isPlaying() {
        return playing;
    }

    /** 파형(double [-1,1]) 을 sr 로 재생. 비차단. 이전 재생은 중단. */
    public void play(double[] wave, int sr) {
        stop();
        try {
            AudioFormat fmt = new AudioFormat(sr, 16, 1, true, false);   // 16bit mono LE
            line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, fmt));
            line.open(fmt);
            line.start();
            final byte[] buf = new byte[wave.length * 2];
            for (int i = 0; i < wave.length; i++) {
                int v = (int) Math.max(-32768, Math.min(32767, wave[i] * 32767.0));
                buf[2 * i] = (byte) (v & 0xff);
                buf[2 * i + 1] = (byte) ((v >> 8) & 0xff);
            }
            playing = true;
            thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        line.write(buf, 0, buf.length);
                        line.drain();
                    } catch (Exception e) {
                        // 재생 중 정지 등 → 무시
                    } finally {
                        playing = false;
                    }
                }
            }, "apx-tone-player");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            playing = false;
        }
    }

    public void stop() {
        playing = false;
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Exception e) {
                // 무시
            }
            line = null;
        }
    }
}
