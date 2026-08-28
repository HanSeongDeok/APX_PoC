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

    private SourceDataLine preparedLine;
    private SourceDataLine activeLine;
    private byte[] preparedBuffer;
    private int preparedSampleRate;
    private Thread thread;
    private volatile boolean playing;

    /** javax.sound 는 항상 존재. */
    public boolean isAvailable() {
        return true;
    }

    public boolean isPlaying() {
        return playing;
    }

    /** 오디오 라인과 PCM 버퍼를 미리 준비해 트리거 시 초기화 지연을 제거한다. */
    public synchronized boolean prepare(double[] wave, int sr) {
        stop();
        try {
            AudioFormat fmt = new AudioFormat(sr, 16, 1, true, false);   // 16bit mono LE
            preparedLine = (SourceDataLine) AudioSystem.getLine(
                    new DataLine.Info(SourceDataLine.class, fmt));
            preparedLine.open(fmt);
            preparedBuffer = new byte[wave.length * 2];
            for (int i = 0; i < wave.length; i++) {
                int v = (int) Math.max(-32768, Math.min(32767, wave[i] * 32767.0));
                preparedBuffer[2 * i] = (byte) (v & 0xff);
                preparedBuffer[2 * i + 1] = (byte) ((v >> 8) & 0xff);
            }
            preparedSampleRate = sr;
            return true;
        } catch (Exception e) {
            closePreparedLine();
            preparedBuffer = null;
            preparedSampleRate = 0;
            return false;
        }
    }

    /** 파형(double [-1,1]) 을 sr 로 재생. 비차단. 이전 재생은 중단. */
    public synchronized boolean play(double[] wave, int sr) {
        if (preparedLine == null || preparedBuffer == null || preparedSampleRate != sr) {
            if (!prepare(wave, sr)) {
                return false;
            }
        }
        SourceDataLine playLine = null;
        try {
            playLine = preparedLine;
            final byte[] playBuffer = preparedBuffer;
            preparedLine = null;
            activeLine = playLine;
            playLine.start();
            playing = true;
            final SourceDataLine workerLine = playLine;
            thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        workerLine.write(playBuffer, 0, playBuffer.length);
                        workerLine.drain();
                    } catch (Exception e) {
                        // 재생 중 정지 등은 무시
                    } finally {
                        synchronized (TonePlayer.this) {
                            if (activeLine == workerLine) {
                                activeLine = null;
                                playing = false;
                            }
                            close(workerLine);
                        }
                    }
                }
            }, "apx-tone-player");
            thread.setDaemon(true);
            thread.start();
            return true;
        } catch (Exception e) {
            playing = false;
            close(playLine);
            if (activeLine == playLine) {
                activeLine = null;
            }
            return false;
        }
    }

    public synchronized void stop() {
        playing = false;
        close(activeLine);
        activeLine = null;
        closePreparedLine();
    }

    private void closePreparedLine() {
        close(preparedLine);
        preparedLine = null;
    }

    private static void close(SourceDataLine target) {
        if (target == null) {
            return;
        }
        try {
            target.stop();
            target.flush();
            target.close();
        } catch (Exception e) {
            // 이미 닫힌 라인은 무시
        }
    }
}
