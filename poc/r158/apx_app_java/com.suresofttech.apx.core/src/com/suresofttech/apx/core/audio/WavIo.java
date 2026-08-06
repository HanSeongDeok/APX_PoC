package com.suresofttech.apx.core.audio;

import java.io.ByteArrayInputStream;
import java.io.File;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * beep .wav 로드/저장 (파이썬 load_beep 대응). JDK 내장 javax.sound.sampled 사용.
 * PCM 16bit ↔ double mono [-1,1). 로드 결과는 {samples, sampleRate}.
 */
public final class WavIo {
    private WavIo() {}

    /** mono double[-1,1) → 16bit PCM WAV 저장(전체). */
    public static void save(String path, double[] samples, int sampleRate) throws Exception {
        saveRange(path, samples, sampleRate, 0.0, samples.length * 1000.0 / sampleRate);
    }

    /**
     * 특정 시간 구간 [startMs, endMs) 만 16bit PCM WAV 저장.
     * 범위는 [0, 전체길이]로 클램프된다. 구간추출 API의 파일 버전.
     */
    public static void saveRange(String path, double[] samples, int sampleRate,
                                 double startMs, double endMs) throws Exception {
        int from = clampIdx((int) Math.round(startMs / 1000.0 * sampleRate), samples.length);
        int to = clampIdx((int) Math.round(endMs / 1000.0 * sampleRate), samples.length);
        if (to < from) {
            to = from;
        }
        int n = to - from;
        byte[] raw = new byte[n * 2];                       // 16bit mono LE
        for (int i = 0; i < n; i++) {
            double s = samples[from + i];
            if (s > 0.999969) {
                s = 0.999969;
            } else if (s < -1.0) {
                s = -1.0;
            }
            int v = (int) Math.round(s * 32767.0);
            raw[2 * i] = (byte) (v & 0xff);
            raw[2 * i + 1] = (byte) ((v >> 8) & 0xff);
        }
        AudioFormat fmt = new AudioFormat(sampleRate, 16, 1, true, false);   // signed 16bit mono LE
        AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(raw), fmt, n);
        try {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, new File(path));
        } finally {
            ais.close();
        }
    }

    private static int clampIdx(int idx, int len) {
        return (idx < 0) ? 0 : (idx > len ? len : idx);
    }

    public static final class Wav {
        public final double[] samples;
        public final int sampleRate;

        public Wav(double[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    public static Wav load(String path) throws Exception {
        AudioInputStream in = AudioSystem.getAudioInputStream(new File(path));
        AudioFormat fmt = in.getFormat();
        int ch = fmt.getChannels();
        int bits = fmt.getSampleSizeInBits();
        boolean big = fmt.isBigEndian();
        byte[] raw = readAll(in);
        in.close();

        int bytesPerSample = bits / 8;
        int frames = raw.length / (bytesPerSample * ch);
        double[] out = new double[frames];
        double scale = (bits == 16) ? 32768.0 : (bits == 32 ? 2147483648.0 : 128.0);
        for (int f = 0; f < frames; f++) {
            int base = f * bytesPerSample * ch;                 // 채널 0(mono화)
            long v = 0;
            if (bits == 16) {
                int b0 = raw[base] & 0xff;
                int b1 = raw[base + 1];
                v = big ? (short) ((b0 << 8) | (raw[base + 1] & 0xff))
                        : (short) ((b1 << 8) | b0);
            } else if (bits == 8) {
                v = raw[base];
            } else {  // 32bit
                if (big) {
                    v = (raw[base] << 24) | ((raw[base + 1] & 0xff) << 16)
                            | ((raw[base + 2] & 0xff) << 8) | (raw[base + 3] & 0xff);
                } else {
                    v = (raw[base + 3] << 24) | ((raw[base + 2] & 0xff) << 16)
                            | ((raw[base + 1] & 0xff) << 8) | (raw[base] & 0xff);
                }
            }
            out[f] = v / scale;
        }
        return new Wav(out, (int) fmt.getSampleRate());
    }

    private static byte[] readAll(AudioInputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
