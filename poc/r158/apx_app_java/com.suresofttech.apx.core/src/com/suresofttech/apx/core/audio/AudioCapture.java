package com.suresofttech.apx.core.audio;

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * 마이크 입력 캡처 (파이썬 sounddevice InputStream 대응). 순수 JDK javax.sound.sampled.
 * 블록 단위로 double[-1,1) 를 콜백으로 전달. UI(SWT) 무의존이라 core 번들에 위치.
 */
public final class AudioCapture {

    /** 블록 콜백. now=시각(초, System.nanoTime 기반). */
    public interface BlockListener {
        void onBlock(double[] block, double now);
    }

    /** 입력 장치 식별자(콤보 표시용). */
    public static final class Device {
        public final String name;
        public final Mixer.Info info;

        Device(String name, Mixer.Info info) {
            this.name = name;
            this.info = info;
        }

        public String toString() {
            return name;
        }
    }

    private TargetDataLine line;
    private Thread thread;
    private volatile boolean running;

    /** 캡처(입력) 가능한 장치 목록. */
    public static List<Device> listInputDevices() {
        List<Device> out = new ArrayList<Device>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer mixer = AudioSystem.getMixer(mi);
            for (Line.Info li : mixer.getTargetLineInfo()) {
                if (li instanceof DataLine.Info) {
                    out.add(new Device(fixName(mi.getName()), mi));
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 윈도우 오디오 장치명은 한글부분('마이크')이 cp949→다른 charset 오해석으로 깨지고,
     * MME는 31자에서 잘려 닫는 ')'까지 사라진다. charset 복구는 불안정하므로(부분 깨짐 '마이?'),
     * 한글부분(정보 없는 일반명사)은 버리고 성한 ASCII 제품명만 뽑아 깔끔히 재구성한다.
     * 절단으로 빠진 ')'는 이때 함께 보완된다.
     */
    static String fixName(String s) {
        if (s == null) {
            return "";
        }
        if (isAscii(s)) {
            return s;                 // 순수 ASCII → 그대로
        }
        String tail = asciiTail(s);
        return tail.isEmpty() ? "마이크" : "마이크 (" + tail + ")";
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 0x80) {
                return false;
            }
        }
        return true;
    }

    /** 깨진 앞부분을 버리고 성한 ASCII 구간(우선 괄호 안 제품명)을 뽑아낸다. */
    private static String asciiTail(String s) {
        int lp = s.indexOf('(');
        if (lp >= 0) {
            int rp = s.indexOf(')', lp);
            String inside = (rp > lp ? s.substring(lp + 1, rp) : s.substring(lp + 1)).trim();
            if (!inside.isEmpty() && isAscii(inside)) {
                return inside;
            }
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c < 0x80 ? c : ' ');
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    /** 캡처 시작. device=null 이면 시스템 기본 입력. */
    public void start(Mixer.Info device, int sampleRate, final BlockListener listener)
            throws LineUnavailableException {
        AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);  // 16bit mono LE
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
        if (device != null) {
            line = (TargetDataLine) AudioSystem.getMixer(device).getLine(info);
        } else {
            line = (TargetDataLine) AudioSystem.getLine(info);
        }
        line.open(fmt);
        line.start();
        running = true;
        final int blockSamples = 256;   // 5.8ms @44.1kHz — 블록 양자화 지연 축소(검출 ~30ms 목표)
        final byte[] raw = new byte[blockSamples * 2];
        thread = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    int read = line.read(raw, 0, raw.length);
                    if (read <= 0) {
                        continue;
                    }
                    double[] block = new double[read / 2];
                    for (int i = 0; i < block.length; i++) {
                        int lo = raw[2 * i] & 0xff;
                        int hi = raw[2 * i + 1];
                        block[i] = (short) ((hi << 8) | lo) / 32768.0;
                    }
                    listener.onBlock(block, System.nanoTime() / 1e9);
                }
            }
        }, "apx-audio-capture");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        if (line != null) {
            try {
                line.stop();
                line.close();
            } catch (Exception e) {
                // 무시
            }
            line = null;
        }
    }
}
