package com.suresofttech.apx.core.vision;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 비전 증거({@code vision/}) 파일명 규약 + 조회 API. SWT無(core).
 *
 * <p>PASS 판정 시점 기준 3장이 한 세트다 - {@code -1f} / 판정 / {@code +1f}.
 * 클라(이솝) 조회의 <b>기본은 3장 전부</b>({@link #getAll} / {@link #readAll});
 * 낱장이 필요할 때만 {@link #get(File, Frame)}을 쓴다.
 */
public final class VisionEvidenceStore {

    /** PASS 판정 기준 상대 프레임. */
    public enum Frame {
        PRE("evidence_pre_-1f.png"),
        DECIDE("evidence_decide.png"),
        POST("evidence_post_+1f.png");

        private final String fileName;

        Frame(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }

    /** 세트 순서 - pre → decide → post 고정. */
    public static final Frame[] SET = { Frame.PRE, Frame.DECIDE, Frame.POST };

    private VisionEvidenceStore() {
    }

    /** 규약 파일명. */
    public static String fileName(Frame f) {
        return f == null ? null : f.fileName();
    }

    /** 낱장 조회 - 없으면 null. */
    public static File get(File visionDir, Frame f) {
        if (visionDir == null || f == null) {
            return null;
        }
        File file = new File(visionDir, f.fileName());
        return file.isFile() ? file : null;
    }

    /**
     * <b>기본 조회</b> - pre/decide/post 3장 전부. 순서는 {@link #SET} 고정이고,
     * 없는 장은 {@code null}로 자리를 지켜 항상 크기 3을 반환한다
     * (인덱스로 어느 프레임이 빠졌는지 바로 알 수 있게).
     */
    public static List<File> getAll(File visionDir) {
        List<File> out = new ArrayList<File>(SET.length);
        for (int i = 0; i < SET.length; i++) {
            out.add(get(visionDir, SET[i]));
        }
        return out;
    }

    /** 3장이 모두 있으면 true. */
    public static boolean isComplete(File visionDir) {
        for (int i = 0; i < SET.length; i++) {
            if (get(visionDir, SET[i]) == null) {
                return false;
            }
        }
        return true;
    }

    /** 낱장 PNG 바이트 - 없으면 null. */
    public static byte[] read(File visionDir, Frame f) {
        return readFile(get(visionDir, f));
    }

    /**
     * <b>기본 조회(바이트)</b> - 3장 전부. {@link #getAll}과 같은 순서 / 크기 3,
     * 없는 장은 null.
     */
    public static List<byte[]> readAll(File visionDir) {
        List<byte[]> out = new ArrayList<byte[]>(SET.length);
        for (int i = 0; i < SET.length; i++) {
            out.add(read(visionDir, SET[i]));
        }
        return out;
    }

    /** 실제로 존재하는 파일만 (없는 장 제외). */
    public static List<File> getExisting(File visionDir) {
        List<File> out = new ArrayList<File>();
        for (int i = 0; i < SET.length; i++) {
            File f = get(visionDir, SET[i]);
            if (f != null) {
                out.add(f);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static byte[] readFile(File f) {
        if (f == null || !f.isFile()) {
            return null;
        }
        try {
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
        } catch (Exception ex) {
            return null;
        }
    }
}
