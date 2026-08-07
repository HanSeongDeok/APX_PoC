package com.suresofttech.apx.core.measure;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import com.suresofttech.apx.core.audio.WavIo;
import com.suresofttech.apx.core.vision.VisionEvidenceStore;
import com.suresofttech.apx.core.vision.VisionRecorder;

/**
 * 증거 폴더 하나를 통째로 읽어 결과 탭에 물리는 리더. SWT無(core).
 *
 * <p>폴더 구조는 저장 규약 그대로다 — {@code audio/}, {@code vision/}, {@code rear/}.
 * TC가 끝나고 앱을 껐다 켠 뒤에도 결과를 복원해야 하므로, 메모리에만 있던
 * PASS 시각·판정은 {@link #writeMeta}가 {@code meta.properties}로 남긴다.
 *
 * <p>스크럽 타임라인의 시간축은 <b>측정 시작 = 0 ms</b>로, 저장된 PASS 시각과 같은 기준이다.
 */
public final class EvidenceBundle {

    public static final String META_NAME = "meta.properties";
    public static final String AUDIO_DIR = "audio";
    public static final String VISION_DIR = "vision";
    public static final String REAR_DIR = "rear";

    public static final String FULL_WAV = "full.wav";
    public static final String CLIP_WAV = "clip.wav";
    public static final String WAVE_PASS_PNG = "wave_pass.png";
    public static final String WAVE_FULL_PNG = "wave_full.png";

    private final File root;
    private final File audioDir;
    private final File visionDir;
    private final File rearDir;
    private final Properties meta = new Properties();

    private EvidenceBundle(File root) {
        this.root = root;
        this.audioDir = new File(root, AUDIO_DIR);
        this.visionDir = new File(root, VISION_DIR);
        this.rearDir = new File(root, REAR_DIR);
    }

    /** 증거 루트 폴더를 연다. 폴더가 아니면 null. */
    public static EvidenceBundle open(File root) {
        if (root == null || !root.isDirectory()) {
            return null;
        }
        EvidenceBundle b = new EvidenceBundle(root);
        b.loadMeta();
        return b;
    }

    public File getRoot() {
        return root;
    }

    public File getAudioDir() {
        return audioDir;
    }

    public File getVisionDir() {
        return visionDir;
    }

    public File getRearDir() {
        return rearDir;
    }

    // ── 음향 ────────────────────────────────────────────────────

    public File getFullWav() {
        return existing(new File(audioDir, FULL_WAV));
    }

    public File getClipWav() {
        return existing(new File(audioDir, CLIP_WAV));
    }

    public File getWavePassPng() {
        return existing(new File(audioDir, WAVE_PASS_PNG));
    }

    public File getWaveFullPng() {
        return existing(new File(audioDir, WAVE_FULL_PNG));
    }

    /**
     * 저장된 {@code full.wav}에서 임의 구간 [startMs,endMs) 바이트.
     * 세션이 끝난 뒤에도 되는 경로 — 메모리 버퍼가 아니라 파일에서 읽는다.
     */
    public byte[] audioRangeBytes(double startMs, double endMs) throws Exception {
        File wav = getFullWav();
        return wav == null ? null : WavIo.rangeBytesOf(wav.getAbsolutePath(), startMs, endMs);
    }

    /** 저장된 {@code full.wav}의 임의 구간을 파일로 떼어낸다. */
    public File audioRangeTo(File out, double startMs, double endMs) throws Exception {
        File wav = getFullWav();
        return wav == null ? null : WavIo.saveRangeOf(wav.getAbsolutePath(),
                out.getAbsolutePath(), startMs, endMs);
    }

    public double audioDurationMs() {
        File wav = getFullWav();
        return wav == null ? 0 : WavIo.durationMs(wav.getAbsolutePath());
    }

    // ── 비전 ────────────────────────────────────────────────────

    /** PASS 시점 3장 — 기본 조회(없는 장은 null 자리 유지). */
    public List<File> getVisionFrames() {
        return VisionEvidenceStore.getAll(visionDir);
    }

    public boolean hasVisionFrames() {
        return VisionEvidenceStore.isComplete(visionDir);
    }

    /** FULL 녹화본({@code full.avi}). 없으면 null. */
    public File getVisionVideo() {
        return existing(new File(visionDir, VisionRecorder.VIDEO_NAME));
    }

    /** 프레임↔시각 인덱스({@code frames.csv}). 없으면 null. */
    public File getVisionIndex() {
        return existing(new File(visionDir, VisionRecorder.INDEX_NAME));
    }

    // ── 후방 ────────────────────────────────────────────────────

    /** 개별 판정 스냅샷 목록(파일명순). combined_ 는 제외. */
    public List<File> getRearSnapshots() {
        File[] all = rearDir.listFiles();
        List<File> out = new ArrayList<File>();
        if (all == null) {
            return out;
        }
        for (File f : all) {
            String n = f.getName();
            if (f.isFile() && n.endsWith(".png") && !n.startsWith("combined")) {
                out.add(f);
            }
        }
        sortByName(out);
        return out;
    }

    /** 통합 스냅샷({@code combined_….png}). 없으면 null. */
    public File getRearCombined() {
        File[] all = rearDir.listFiles();
        if (all == null) {
            return null;
        }
        for (File f : all) {
            if (f.isFile() && f.getName().startsWith("combined") && f.getName().endsWith(".png")) {
                return f;
            }
        }
        return null;
    }

    // ── 메타(재오픈용) ──────────────────────────────────────────

    public boolean isOverallPass() {
        return Boolean.parseBoolean(meta.getProperty("overallPass", "false"));
    }

    public String getSummary() {
        return meta.getProperty("summary", "");
    }

    public Long getAudioPassMs() {
        return getLong("audioPassMs");
    }

    public Long getVisionPassMs() {
        return getLong("visionPassMs");
    }

    public Double getSyncSpreadMs() {
        return getDouble("syncSpreadMs");
    }

    /** PASS 초록 밴드 시작(ms) — {@code clip.wav} 구간. 없으면 null. */
    public Double getAudioPassStartMs() {
        return getDouble("audioPassStartMs");
    }

    public Double getAudioPassEndMs() {
        return getDouble("audioPassEndMs");
    }

    public boolean isSyncOk() {
        return Boolean.parseBoolean(meta.getProperty("syncOk", "false"));
    }

    /** 측정 시각(epoch ms). 없으면 0. */
    public long getStoppedAtEpochMs() {
        Long v = getLong("stoppedAtEpochMs");
        return v == null ? 0 : v.longValue();
    }

    /** meta에 기록된 측정 길이(ms). 없으면 0. */
    public double getRecordedDurationMs() {
        Double v = getDouble("durationMs");
        return v == null ? 0 : v.doubleValue();
    }

    /**
     * 타임라인 전체 길이(ms) — 음향 wav 길이와 meta 기록 중 큰 쪽.
     * 둘 다 없으면 PASS 시각 뒤로 1초 여유를 준다.
     */
    public double durationMs() {
        double d = Math.max(audioDurationMs(), getRecordedDurationMs());
        if (d > 0) {
            return d;
        }
        long max = 0;
        Long a = getAudioPassMs();
        Long v = getVisionPassMs();
        if (a != null) {
            max = Math.max(max, a.longValue());
        }
        if (v != null) {
            max = Math.max(max, v.longValue());
        }
        return max > 0 ? max + 1000 : 0;
    }

    private void loadMeta() {
        File f = new File(root, META_NAME);
        if (!f.isFile()) {
            return;
        }
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                meta.load(new InputStreamReader(in, "UTF-8"));
            } finally {
                in.close();
            }
        } catch (Exception ignored) {
            // 메타 없이도 파일 조회는 된다
        }
    }

    /**
     * 결과 재오픈에 필요한 최소 메타를 증거 루트에 남긴다.
     * 측정 중단 시 클라가 호출한다.
     */
    public static void writeMeta(File root, boolean overallPass, String summary,
            Long audioPassMs, Long visionPassMs,
            Double syncSpreadMs, boolean syncOk, double durationMs,
            Double audioPassStartMs, Double audioPassEndMs) throws Exception {
        if (root == null) {
            return;
        }
        if (!root.exists() && !root.mkdirs()) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("overallPass", Boolean.toString(overallPass));
        p.setProperty("summary", summary == null ? "" : summary);
        putIfPresent(p, "audioPassMs", audioPassMs);
        putIfPresent(p, "visionPassMs", visionPassMs);
        putIfPresent(p, "audioPassStartMs", audioPassStartMs);
        putIfPresent(p, "audioPassEndMs", audioPassEndMs);
        if (syncSpreadMs != null) {
            p.setProperty("syncSpreadMs", String.valueOf(syncSpreadMs.doubleValue()));
        }
        p.setProperty("syncOk", Boolean.toString(syncOk));
        p.setProperty("durationMs", String.valueOf(durationMs));
        p.setProperty("stoppedAtEpochMs", String.valueOf(System.currentTimeMillis()));

        FileOutputStream out = new FileOutputStream(new File(root, META_NAME));
        try {
            p.store(new OutputStreamWriter(out, "UTF-8"),
                    "APX 측정 증거 메타 — 시간축 기준: 측정 시작 = 0 ms");
        } finally {
            out.close();
        }
    }

    private static void putIfPresent(Properties p, String key, Long v) {
        if (v != null) {
            p.setProperty(key, String.valueOf(v.longValue()));
        }
    }

    private static void putIfPresent(Properties p, String key, Double v) {
        if (v != null) {
            p.setProperty(key, String.valueOf(v.doubleValue()));
        }
    }

    private Long getLong(String key) {
        String s = meta.getProperty(key);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Double getDouble(String key) {
        String s = meta.getProperty(key);
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static File existing(File f) {
        return f != null && f.isFile() ? f : null;
    }

    private static void sortByName(List<File> files) {
        File[] arr = files.toArray(new File[files.size()]);
        Arrays.sort(arr, new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        files.clear();
        files.addAll(Arrays.asList(arr));
    }
}
