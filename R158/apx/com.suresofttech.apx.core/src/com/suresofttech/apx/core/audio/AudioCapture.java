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

    /**
     * 캡처 가능한 <b>실제</b> 입력 장치 목록.
     * Primary/Mapper/Port 등 시스템 가상 엔트리는 제외. USB로 보이는 장치가 있으면 USB만,
     * 없으면 나머지 실장치. 표시명은 끝 인덱스 숫자를 제거한다.
     */
    public static List<Device> listInputDevices() {
        List<Device> all = new ArrayList<Device>();
        List<Device> usb = new ArrayList<Device>();
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (isSystemOrVirtualMic(mi.getName())) {
                continue;
            }
            Mixer mixer = AudioSystem.getMixer(mi);
            for (Line.Info li : mixer.getTargetLineInfo()) {
                if (li instanceof DataLine.Info) {
                    String display = stripTrailingIndex(fixName(mi.getName()));
                    Device d = new Device(display, mi);
                    all.add(d);
                    if (looksUsbMic(mi.getName()) || looksUsbMic(display)) {
                        usb.add(d);
                    }
                    break;
                }
            }
        }
        return usb.isEmpty() ? all : usb;
    }

    /**
     * 표시명으로 입력 장치를 찾는다. 없거나 name이 비면 목록의 첫 장치.
     * 장치가 없으면 null.
     */
    public static Device findInputDevice(String name) {
        List<Device> list = listInputDevices();
        if (list.isEmpty()) {
            return null;
        }
        if (name != null && !name.isEmpty()) {
            for (int i = 0; i < list.size(); i++) {
                if (name.equals(list.get(i).name)) {
                    return list.get(i);
                }
            }
        }
        return list.get(0);
    }

    /** "Primary Sound Capture Driver", Mapper, Port 라인 등 — 콤보에 넣지 않음. */
    private static boolean isSystemOrVirtualMic(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        String n = name.toLowerCase();
        return n.contains("primary sound") || n.contains("mapper")
                || n.startsWith("port ") || n.contains("java sound")
                || n.contains("virtual") || n.contains("stereo mix")
                || n.contains("what u hear") || n.contains("wave out mix");
    }

    private static boolean looksUsbMic(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase();
        return n.contains("usb");
    }

    /** 드라이버가 붙인 끝 인덱스 제거 ("Device 1" → "Device"). */
    static String stripTrailingIndex(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceFirst("\\s+\\d+$", "").trim();
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
