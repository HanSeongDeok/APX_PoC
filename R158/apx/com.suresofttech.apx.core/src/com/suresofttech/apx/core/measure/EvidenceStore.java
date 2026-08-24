package com.suresofttech.apx.core.measure;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * TC(측정 런)별 증거 폴더를 파일 DB처럼 다루는 저장소. SWT無(core).
 *
 * <p>규약:
 * <pre>
 * &lt;root&gt;/
 *   &lt;tcId&gt;/                 ← 측정 1회 = 1폴더
 *     meta.properties
 *     audio/ …
 *     vision/ …
 *     rear/ …
 * </pre>
 *
 * <p>후방 스냅샷 파일명의 {@code TC-001}은 격자 셀용이며, 여기서의 {@code tcId}(Aesop 측정 TC)와
 * 별개다. 클라가 {@code tcId}로 열고 PASS 시각 / 밴드를 읽는다.
 *
 * <pre>
 * EvidenceStore store = EvidenceStore.at(root);
 * File dir = store.prepare("AESOP-TC-01");
 * // … MeasureEvidence.saveTo + EvidenceBundle.writeMeta(dir, …)
 * EvidenceBundle b = store.open("AESOP-TC-01");
 * List&lt;double[]&gt; spans = store.getAudioPassSpans("AESOP-TC-01");
 * </pre>
 */
public final class EvidenceStore {

    private final File root;

    private EvidenceStore(File root) {
        this.root = root;
    }

    /** 증거 루트에 바인딩. root가 null이면 IllegalArgumentException. */
    public static EvidenceStore at(File root) {
        if (root == null) {
            throw new IllegalArgumentException("evidence root is null");
        }
        return new EvidenceStore(root);
    }

    public File getRoot() {
        return root;
    }

    /**
     * TC 폴더 경로({@code root/sanitize(tcId)}). 폴더를 만들지는 않는다.
     * tcId가 비면 null.
     */
    public File tcDir(String tcId) {
        String id = sanitize(tcId);
        if (id == null) {
            return null;
        }
        return new File(root, id);
    }

    /**
     * TC 폴더와 {@code audio/} / {@code vision/} / {@code rear/}를 만든다.
     * @return 준비된 TC 폴더. tcId 무효면 null
     */
    public File prepare(String tcId) {
        File dir = tcDir(tcId);
        if (dir == null) {
            return null;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        mk(new File(dir, EvidenceBundle.AUDIO_DIR));
        mk(new File(dir, EvidenceBundle.VISION_DIR));
        mk(new File(dir, EvidenceBundle.REAR_DIR));
        return dir;
    }

    /** {@code meta.properties}가 있는 TC id 목록(이름순). */
    public List<String> listTcIds() {
        File[] kids = root.listFiles();
        if (kids == null || kids.length == 0) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < kids.length; i++) {
            File f = kids[i];
            if (f.isDirectory() && new File(f, EvidenceBundle.META_NAME).isFile()) {
                out.add(f.getName());
            }
        }
        Collections.sort(out, new Comparator<String>() {
            public int compare(String a, String b) {
                return a.compareTo(b);
            }
        });
        return out;
    }

    /** TC 폴더를 {@link EvidenceBundle}로 연다. 없거나 폴더가 아니면 null. */
    public EvidenceBundle open(String tcId) {
        File dir = tcDir(tcId);
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        return EvidenceBundle.open(dir);
    }

    public boolean exists(String tcId) {
        File dir = tcDir(tcId);
        return dir != null && dir.isDirectory()
                && new File(dir, EvidenceBundle.META_NAME).isFile();
    }

    /** meta {@code audioPassMs}. 없거나 열 수 없으면 null. */
    public Long getAudioPassMs(String tcId) {
        EvidenceBundle b = open(tcId);
        return b == null ? null : b.getAudioPassMs();
    }

    /** meta {@code visionPassMs}. 없거나 열 수 없으면 null. */
    public Long getVisionPassMs(String tcId) {
        EvidenceBundle b = open(tcId);
        return b == null ? null : b.getVisionPassMs();
    }

    /**
     * 음향 PASS 초록 밴드 목록. 없으면 빈 목록.
     * @see EvidenceBundle#getAudioPassSpans()
     */
    public List<double[]> getAudioPassSpans(String tcId) {
        EvidenceBundle b = open(tcId);
        if (b == null) {
            return Collections.emptyList();
        }
        List<double[]> spans = b.getAudioPassSpans();
        return spans == null ? Collections.<double[]>emptyList() : spans;
    }

    /**
     * tcId에서 경로 이탈 / 예약 문자를 제거한다.
     * {@code / \ : * ? " < > |} 및 제어문자를 {@code _}로, 양끝 공백 / 점 제거.
     * 결과가 비면 null.
     */
    public static String sanitize(String tcId) {
        if (tcId == null) {
            return null;
        }
        String s = tcId.trim();
        if (s.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '*'
                    || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        while (sb.length() > 0 && (sb.charAt(0) == '.' || sb.charAt(0) == ' ')) {
            sb.deleteCharAt(0);
        }
        while (sb.length() > 0) {
            int last = sb.length() - 1;
            char c = sb.charAt(last);
            if (c == '.' || c == ' ') {
                sb.deleteCharAt(last);
            } else {
                break;
            }
        }
        // Windows 예약명 회피
        String out = sb.toString();
        if (out.isEmpty()) {
            return null;
        }
        String upper = out.toUpperCase();
        if (upper.equals("CON") || upper.equals("PRN") || upper.equals("AUX") || upper.equals("NUL")
                || upper.matches("COM[1-9]") || upper.matches("LPT[1-9]")) {
            out = "_" + out;
        }
        return out;
    }

    private static void mk(File dir) {
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
    }

    @Override
    public String toString() {
        return "EvidenceStore{root=" + root + ", tcs=" + listTcIds() + "}";
    }
}
