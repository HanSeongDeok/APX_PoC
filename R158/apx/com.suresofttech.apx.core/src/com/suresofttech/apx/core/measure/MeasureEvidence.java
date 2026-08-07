package com.suresofttech.apx.core.measure;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;

import com.suresofttech.apx.core.audio.WavIo;

/**
 * 측정 증거 버퍼. WAV·스코프 PNG·PASS 시각 메타(메모리).
 * 디스크 규약(노션 음향 설계):
 * <ul>
 *   <li>{@code full.wav} — {@link #saveFull}</li>
 *   <li>{@code clip.wav} — PASS 초록 밴드 시작~끝 ({@link #setAudioPassSpan})</li>
 *   <li>{@code wave_center.png} — PASS 시점 파형(Evidence.wavePng 중심)</li>
 *   <li>{@code pass_times.txt} — 검출/자체판단 메타</li>
 * </ul>
 * 비전 프레임·후방 PNG는 각각 EvidenceCapture / RearGridCanvas 규약으로 클라가 저장.
 */
public final class MeasureEvidence {

    private double[] audioSamples = new double[0];
    private int audioSampleRate;
    private byte[] audioScopePng;
    private byte[] visionCanvasPng;
    private byte[] rearCanvasPng;
    private Long audioPassMs;
    private Long visionPassMs;
    private Long overallPassMs;
    /** 자체 판단(ms) = gap + analysis. 미확정 null. */
    private Double audioJudgeMs;
    private Double visionJudgeMs;
    /** PASS 초록 밴드 시작·끝(ms). clip.wav 구간. */
    private Double audioPassStartMs;
    private Double audioPassEndMs;

    public synchronized void setAudio(double[] samples, int sampleRate) {
        this.audioSamples = samples == null ? new double[0] : samples.clone();
        this.audioSampleRate = sampleRate;
    }

    public synchronized double[] getAudioSamples() {
        return Arrays.copyOf(audioSamples, audioSamples.length);
    }

    public synchronized int getAudioSampleRate() {
        return audioSampleRate;
    }

    /** WAV 파일 바이트 (임시 파일 경유). 샘플 없으면 null. */
    public synchronized byte[] getAudioWavBytes() throws Exception {
        if (audioSamples == null || audioSamples.length == 0 || audioSampleRate <= 0) {
            return null;
        }
        File tmp = File.createTempFile("apx-ev-", ".wav");
        try {
            WavIo.save(tmp.getAbsolutePath(), audioSamples, audioSampleRate);
            return readAll(tmp);
        } finally {
            if (!tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
    }

    /** 음향 스코프 PNG — 최초 PASS 밴드 종료 스냅샷만 보관. → wave_center.png */
    public synchronized void putAudioPng(byte[] png) {
        if (this.audioScopePng == null && png != null) {
            this.audioScopePng = png.clone();
        }
    }

    /** 비전 캔버스 PNG — ResultView 메모리 표시용(최초만). 디스크는 EvidenceCapture 규약. */
    public synchronized void putVisionPng(byte[] png) {
        if (this.visionCanvasPng == null && png != null) {
            this.visionCanvasPng = png.clone();
        }
    }

    /**
     * 후방 캔버스 PNG — ResultView 메모리 표시용(최초만).
     * 디스크 저장은 후방 규약 파일명 API를 쓴다({@code saveVerdictSnapshot}).
     */
    public synchronized void putRearPng(byte[] png) {
        if (this.rearCanvasPng == null && png != null) {
            this.rearCanvasPng = png.clone();
        }
    }

    public synchronized byte[] getAudioScopeSnapshot() {
        return audioScopePng == null ? null : audioScopePng.clone();
    }

    public synchronized byte[] getVisionCanvasSnapshot() {
        return visionCanvasPng == null ? null : visionCanvasPng.clone();
    }

    public synchronized byte[] getRearCanvasSnapshot() {
        return rearCanvasPng == null ? null : rearCanvasPng.clone();
    }

    public synchronized void setAudioPassMs(long ms) {
        if (this.audioPassMs == null) {
            this.audioPassMs = Long.valueOf(ms);
        }
    }

    public synchronized void setVisionPassMs(long ms) {
        if (this.visionPassMs == null) {
            this.visionPassMs = Long.valueOf(ms);
        }
    }

    public synchronized void setOverallPassMs(long ms) {
        if (this.overallPassMs == null) {
            this.overallPassMs = Long.valueOf(ms);
        }
    }

    /** 음향 자체 판단(ms) = blockGap + analysis. 최초 1회만. */
    public synchronized void setAudioJudgeMs(double ms) {
        if (this.audioJudgeMs == null && !Double.isNaN(ms)) {
            this.audioJudgeMs = Double.valueOf(ms);
        }
    }

    /** 비전 자체 판단(ms) = frameGap + analysis. 최초 1회만. */
    public synchronized void setVisionJudgeMs(double ms) {
        if (this.visionJudgeMs == null && !Double.isNaN(ms)) {
            this.visionJudgeMs = Double.valueOf(ms);
        }
    }

    public synchronized Long getAudioPassMs() {
        return audioPassMs;
    }

    public synchronized Long getVisionPassMs() {
        return visionPassMs;
    }

    public synchronized Long getOverallPassMs() {
        return overallPassMs;
    }

    public synchronized Double getAudioJudgeMs() {
        return audioJudgeMs;
    }

    public synchronized Double getVisionJudgeMs() {
        return visionJudgeMs;
    }

    /** MeasureReport.saveFull 대응 — 측정 전체 WAV. */
    public synchronized File saveFull(File path) throws Exception {
        if (path == null || audioSamples == null || audioSamples.length == 0 || audioSampleRate <= 0) {
            return null;
        }
        File parent = path.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        WavIo.save(path.getAbsolutePath(), audioSamples, audioSampleRate);
        return path;
    }

    /**
     * PASS 초록 밴드 구간(ms). {@code clip.wav} = 이 시작~끝.
     * AudioScope 밴드와 동일해야 한다.
     */
    public synchronized void setAudioPassSpan(double startMs, double endMs) {
        double a = Math.min(startMs, endMs);
        double b = Math.max(startMs, endMs);
        this.audioPassStartMs = Double.valueOf(a);
        this.audioPassEndMs = Double.valueOf(b);
    }

    public synchronized Double getAudioPassStartMs() {
        return audioPassStartMs;
    }

    public synchronized Double getAudioPassEndMs() {
        return audioPassEndMs;
    }

    /** 구간 WAV 저장 [startMs, endMs). */
    public synchronized File saveRange(File path, double startMs, double endMs) throws Exception {
        if (path == null || audioSamples == null || audioSamples.length == 0 || audioSampleRate <= 0) {
            return null;
        }
        File parent = path.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        WavIo.saveRange(path.getAbsolutePath(), audioSamples, audioSampleRate, startMs, endMs);
        return path;
    }

    /**
     * 클라 {@code setSnapshotDir} 폴더에 음향 규약 파일 저장.
     * {@code full.wav}, {@code wave_center.png},
     * (PASS 밴드 있으면) {@code clip.wav} = 초록 시작~끝, {@code pass_times.txt}.
     */
    public synchronized void saveTo(File dir) throws Exception {
        if (dir == null) {
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("증거 폴더 생성 실패: " + dir);
        }
        saveFull(new File(dir, "full.wav"));
        writeBytes(new File(dir, "wave_center.png"), audioScopePng);
        if (audioPassStartMs != null && audioPassEndMs != null
                && audioPassEndMs.doubleValue() > audioPassStartMs.doubleValue()) {
            saveRange(new File(dir, "clip.wav"),
                    audioPassStartMs.doubleValue(), audioPassEndMs.doubleValue());
        }
        writeTimes(new File(dir, "pass_times.txt"));
    }

    /** PASS 시각 메타 — 물리지연 포함 검출시각 + 자체 판단(gap+분석). */
    private void writeTimes(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# PASS times (no L2 calibration)\n");
        sb.append("# detectMs = wall/common-clock from measure start (includes D_mic/D_cap)\n");
        sb.append("# judgeMs  = tool overhead = gap + analysis\n");
        sb.append("audio.detectMs=").append(audioPassMs == null ? "" : audioPassMs).append('\n');
        sb.append("audio.judgeMs=").append(audioJudgeMs == null ? "" : audioJudgeMs).append('\n');
        sb.append("audio.passSpanStartMs=")
                .append(audioPassStartMs == null ? "" : audioPassStartMs).append('\n');
        sb.append("audio.passSpanEndMs=")
                .append(audioPassEndMs == null ? "" : audioPassEndMs).append('\n');
        sb.append("vision.detectMs=").append(visionPassMs == null ? "" : visionPassMs).append('\n');
        sb.append("vision.judgeMs=").append(visionJudgeMs == null ? "" : visionJudgeMs).append('\n');
        sb.append("overall.detectMs=").append(overallPassMs == null ? "" : overallPassMs).append('\n');
        writeBytes(f, sb.toString().getBytes("UTF-8"));
    }

    private static void writeBytes(File f, byte[] data) throws Exception {
        if (data == null || data.length == 0) {
            return;
        }
        java.io.FileOutputStream out = new java.io.FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }
}
