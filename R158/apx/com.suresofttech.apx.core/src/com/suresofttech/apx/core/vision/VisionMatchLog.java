package com.suresofttech.apx.core.vision;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 측정 중 프레임별 ROI 매칭 시계열 — 결과 스크럽에서 PASS/FAIL 색 복원용.
 *
 * <p>파일: {@code vision/matches.csv} — {@code tMs,hit,ncc} (측정 시작=0).
 */
public final class VisionMatchLog {

    public static final String FILE_NAME = "matches.csv";

    /** 한 시점의 매칭 샘플. */
    public static final class Sample {
        public final double tMs;
        public final boolean hit;
        public final double ncc;

        public Sample(double tMs, boolean hit, double ncc) {
            this.tMs = tMs;
            this.hit = hit;
            this.ncc = ncc;
        }
    }

    private final List<Sample> samples = new ArrayList<Sample>();

    public synchronized void clear() {
        samples.clear();
    }

    public synchronized void add(double tMs, boolean hit, double ncc) {
        samples.add(new Sample(tMs, hit, ncc));
    }

    public synchronized int size() {
        return samples.size();
    }

    public synchronized boolean isEmpty() {
        return samples.isEmpty();
    }

    /**
     * {@code tMs} 이하에서 가장 가까운(최신) 샘플. 없으면 null.
     */
    public synchronized Sample at(double tMs) {
        if (samples.isEmpty()) {
            return null;
        }
        int lo = 0;
        int hi = samples.size() - 1;
        if (tMs < samples.get(0).tMs) {
            return samples.get(0);
        }
        if (tMs >= samples.get(hi).tMs) {
            return samples.get(hi);
        }
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            double t = samples.get(mid).tMs;
            if (t <= tMs) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return samples.get(Math.max(0, hi));
    }

    /**
     * hit=true 연속 구간 목록. 각 원소 {@code {startMs, endMs}}.
     * 단일 샘플 구간은 최소 폭 1ms.
     */
    public synchronized List<double[]> passSpans() {
        List<double[]> out = new ArrayList<double[]>();
        double start = -1;
        double last = -1;
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            if (s.hit) {
                if (start < 0) {
                    start = s.tMs;
                }
                last = s.tMs;
            } else if (start >= 0) {
                out.add(new double[] { start, Math.max(start + 1, last) });
                start = -1;
                last = -1;
            }
        }
        if (start >= 0) {
            out.add(new double[] { start, Math.max(start + 1, last) });
        }
        return out;
    }

    public synchronized void save(File visionDir) throws Exception {
        if (visionDir == null) {
            return;
        }
        if (!visionDir.exists() && !visionDir.mkdirs()) {
            return;
        }
        File f = new File(visionDir, FILE_NAME);
        PrintWriter pw = new PrintWriter(f, "UTF-8");
        try {
            pw.println("tMs,hit,ncc");
            for (int i = 0; i < samples.size(); i++) {
                Sample s = samples.get(i);
                pw.print(s.tMs);
                pw.print(',');
                pw.print(s.hit ? '1' : '0');
                pw.print(',');
                pw.println(s.ncc);
            }
        } finally {
            pw.close();
        }
    }

    /** {@code vision/matches.csv} 로드. 없거나 실패하면 빈 로그. */
    public static VisionMatchLog load(File visionDir) {
        VisionMatchLog log = new VisionMatchLog();
        if (visionDir == null) {
            return log;
        }
        File f = new File(visionDir, FILE_NAME);
        if (!f.isFile()) {
            return log;
        }
        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                String line = br.readLine(); // header
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    String[] p = line.split(",");
                    if (p.length < 3) {
                        continue;
                    }
                    double t = Double.parseDouble(p[0].trim());
                    boolean hit = "1".equals(p[1].trim()) || "true".equalsIgnoreCase(p[1].trim());
                    double ncc = Double.parseDouble(p[2].trim());
                    log.samples.add(new Sample(t, hit, ncc));
                }
            } finally {
                br.close();
            }
        } catch (Exception ignored) {
            // 손상된 로그는 빈 상태로
        }
        return log;
    }

    public synchronized List<Sample> snapshot() {
        return Collections.unmodifiableList(new ArrayList<Sample>(samples));
    }
}
