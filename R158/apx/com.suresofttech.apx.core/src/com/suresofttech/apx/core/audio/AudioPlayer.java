package com.suresofttech.apx.core.audio;

import java.io.File;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

/**
 * 저장된 WAV를 임의 시점부터 재생. SWT無(core).
 *
 * <p>결과 탭 스크럽용 - 슬라이더를 옮긴 지점부터 실제 녹음을 들려준다.
 * {@code full.wav}는 한 측정 분량이라 {@link Clip}으로 통째 올려도 부담이 없고,
 * {@code setMicrosecondPosition}으로 프레임 단위 시점 이동이 된다.
 *
 * <p>재생 위치는 {@link #getPositionMs()}로 폴링한다 - UI가 슬라이더를 따라 움직일 때 쓴다.
 */
public final class AudioPlayer {

    private Clip clip;
    private File source;
    /** 사용자가 "재생"을 눌러둔 상태인지(정지를 누르거나 끝까지 가면 false). */
    private volatile boolean playing;

    /**
     * WAV 로드(이미 같은 파일이면 재사용). 실패 시 false.
     */
    public synchronized boolean open(File wav) {
        if (wav == null || !wav.isFile()) {
            return false;
        }
        if (clip != null && clip.isOpen() && wav.equals(source)) {
            return true;
        }
        close();
        AudioInputStream in = null;
        try {
            in = AudioSystem.getAudioInputStream(wav);
            Clip c = AudioSystem.getClip();
            c.open(in);
            // LineListener(STOP)로 종료를 잡지 않는다 - 스크럽 중 play()가 내부적으로 stop()을
            // 부르면 STOP이 함께 튀어 "재생이 끝났다"고 오인한다. 종료 판정은 호출측이
            // isRunning()/getPositionMs()를 폴링해서 한다(어차피 슬라이더 갱신으로 매 틱 돈다).
            clip = c;
            source = wav;
            return true;
        } catch (Exception ex) {
            close();
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // 무시
                }
            }
        }
    }

    public synchronized boolean isOpen() {
        return clip != null && clip.isOpen();
    }

    /** 사용자가 재생을 눌러둔 상태(스크럽으로 시점을 옮겨도 유지). */
    public boolean isPlaying() {
        return playing;
    }

    /**
     * 클립이 <b>실제로</b> 소리를 내고 있는지 - 자연 종료 감지용.
     * {@link #isPlaying()}(사용자 의도)와 구분한다: wav가 타임라인보다 짧으면
     * 재생 의도는 유지된 채 이 값만 false가 된다.
     */
    public synchronized boolean isRunning() {
        return clip != null && clip.isRunning();
    }

    /** 전체 길이(ms). 미오픈 시 0. */
    public synchronized double durationMs() {
        return clip == null ? 0 : clip.getMicrosecondLength() / 1000.0;
    }

    /** 현재 재생 위치(ms). */
    public synchronized double getPositionMs() {
        return clip == null ? 0 : clip.getMicrosecondPosition() / 1000.0;
    }

    /** 재생하지 않고 위치만 이동 - 스크럽 중 커서 동기용. */
    public synchronized void seek(double ms) {
        if (clip == null) {
            return;
        }
        long us = (long) Math.max(0, Math.min(clip.getMicrosecondLength(), ms * 1000.0));
        clip.setMicrosecondPosition(us);
    }

    /** 지정 시점부터 재생. 이미 재생 중이면 그 시점으로 옮겨 이어 재생. */
    public synchronized void play(double fromMs) {
        if (clip == null) {
            return;
        }
        clip.stop();
        seek(fromMs);
        playing = true;
        clip.start();
    }

    public synchronized void pause() {
        if (clip != null) {
            clip.stop();
        }
        playing = false;
    }

    public synchronized void close() {
        if (clip != null) {
            try {
                clip.stop();
                clip.close();
            } catch (Exception ignored) {
                // 무시
            }
            clip = null;
        }
        source = null;
        playing = false;
    }
}
