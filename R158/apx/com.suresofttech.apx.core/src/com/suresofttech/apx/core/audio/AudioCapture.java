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

    /**
     * 캡처가 더 이상 진행될 수 없을 때 - 마이크 분리, 드라이버 오류, 입력 정지.
     * 측정 중 하드웨어가 빠지면 조용히 무음이 녹음되므로 반드시 위로 알린다.
     */
    public interface ErrorListener {
        void onCaptureError(String reason);
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

    /** 입력이 이만큼(ms) 한 블록도 안 들어오면 장치가 빠진 것으로 본다. */
    private static final long STALL_TIMEOUT_MS = 2000;

    private TargetDataLine line;
    private Thread thread;
    private volatile boolean running;
    private volatile ErrorListener errorListener;
    private volatile String lastError;
    /** 마지막으로 블록을 받은 시각(nanoTime). 끊김 감시용. */
    private volatile long lastBlockNanos;

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

    /** "Primary Sound Capture Driver", Mapper, Port 라인 등 - 콤보에 넣지 않음. */
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
        lastError = null;
        lastBlockNanos = System.nanoTime();
        // 설계값(JAVA_STACK): D_blk = 2048/sr ≈ 46ms @44.1kHz.
        // 자체판단 passMs = blockGap(=n/sr) + analysis.
        final int blockSamples = 2048;
        final byte[] raw = new byte[blockSamples * 2];
        thread = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    int read;
                    try {
                        read = line.read(raw, 0, raw.length);
                    } catch (Exception ex) {
                        // 측정 중 마이크 분리 - 드라이버가 예외를 던지는 경로
                        fail("마이크 입력 오류: " + ex.getMessage());
                        return;
                    }
                    if (read < 0) {
                        fail("마이크 입력이 종료되었습니다 (장치 분리)");
                        return;
                    }
                    if (read == 0) {
                        // 0을 계속 돌려주는 장치가 있다 - 바쁜 대기 대신 끊김으로 판정
                        if (elapsedSinceBlockMs() > STALL_TIMEOUT_MS) {
                            fail("마이크 입력이 " + STALL_TIMEOUT_MS + "ms 이상 들어오지 않습니다");
                            return;
                        }
                        sleep(5);
                        continue;
                    }
                    lastBlockNanos = System.nanoTime();
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

    /** 캡처 중단 사유 통지 대상 - {@link #start} 전에 걸어둔다. */
    public void setErrorListener(ErrorListener l) {
        this.errorListener = l;
    }

    /** 마지막 캡처 오류 사유. 정상이면 null. */
    public String getLastError() {
        return lastError;
    }

    /** 마지막 블록 이후 경과(ms). 캡처 중이 아니면 0. */
    public long elapsedSinceBlockMs() {
        return running ? (System.nanoTime() - lastBlockNanos) / 1000000L : 0;
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        running = false;
        closeLine();
    }

    /** 캡처 스레드에서 치명적 상황 - 라인을 닫고 위로 알린다. */
    private void fail(String reason) {
        if (!running) {
            return;
        }
        running = false;
        lastError = reason;
        closeLine();
        ErrorListener l = errorListener;
        if (l != null) {
            try {
                l.onCaptureError(reason);
            } catch (Exception ignored) {
                // 통지 실패로 캡처 정리를 막지 않는다
            }
        }
    }

    private synchronized void closeLine() {
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
