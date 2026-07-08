package com.suresofttech.apx.core.audio;

import java.io.File;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * 기대 beep .wav 로드 (파이썬 load_beep 대응). JDK 내장 javax.sound.sampled 사용.
 * PCM 16bit → float32 mono [-1,1). 결과는 {samples, sampleRate}.
 */
public final class WavIo {
    private WavIo() {}

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
