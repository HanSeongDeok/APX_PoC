package com.suresofttech.apx.core.vision;

import java.util.ArrayDeque;
import java.util.Deque;

import org.opencv.core.Mat;

/**
 * 판단 시점 전후 프레임 증거 수집 (파이썬 evidence.py 이식). 기어 / 클러스터 공용.
 *
 * <p>매 프레임 {@link #push}, 판단 순간 {@link #trigger}. trigger 시점의 before개 전 스냅샷 +
 * 판단 프레임 + after개 후 스냅샷을 모은다. Mat는 clone 보관하며, 링에서 밀려나는 프레임은
 * 즉시 release(네이티브 메모리 누수 방지). 확정된 증거 Mat는 {@link #reset} 전까지 유지.
 */
public final class EvidenceCapture {

    /** 스냅샷 = 프레임 + 시각(초). */
    public static final class Snap {
        public final Mat img;
        public final double t;

        Snap(Mat img, double t) {
            this.img = img;
            this.t = t;
        }
    }

    /** 판단 전/중/후 3장. */
    public static final class Evidence {
        public final Snap pre;
        public final Snap decide;
        public final Snap post;

        Evidence(Snap pre, Snap decide, Snap post) {
            this.pre = pre;
            this.decide = decide;
            this.post = post;
        }
    }

    private final int before;
    private final int after;
    private final Deque<Snap> ring = new ArrayDeque<Snap>();   // 현재 + before개 이전

    private Snap collectPre;
    private Snap collectDecide;
    private int collectCount = -1;      // -1 = 수집 안 함
    private Evidence evidence;

    public EvidenceCapture(int before, int after) {
        this.before = before;
        this.after = after;
    }

    /** 매 프레임 호출 - img(clone 권장)를 링에 넣고, 넘치면 오래된 것 release. */
    public void push(Mat img, double t) {
        ring.addLast(new Snap(img, t));
        while (ring.size() > before + 1) {
            Snap old = ring.pollFirst();
            releaseIfUnused(old);
        }
    }

    /** 판단 순간 - 직전 push 로 현재 프레임이 ring 마지막에 있어야 함. */
    public void trigger() {
        if (ring.isEmpty()) {
            return;
        }
        collectPre = ring.peekFirst();       // before개 전(덜 찼으면 가장 오래된 것)
        collectDecide = ring.peekLast();     // 현재(판단 프레임)
        collectCount = 0;
    }

    /** 판단 이후 프레임마다 호출 - after번째를 post로 확정. */
    public void stepAfter(Mat img, double t) {
        if (collectCount < 0) {
            return;
        }
        collectCount++;
        if (collectCount >= after) {
            evidence = new Evidence(collectPre, collectDecide, new Snap(img, t));
            collectPre = null;
            collectDecide = null;
            collectCount = -1;
        }
    }

    public Evidence getEvidence() {
        return evidence;
    }

    /**
     * 측정 중단 시 호출 - post가 아직 안 찼어도 pre/decide를 확정한다.
     * post가 없으면 decide를 post로 복제(파이썬 stop 시 부분 증거와 동일 목적).
     */
    public void flush() {
        if (evidence != null) {
            return;
        }
        if (collectDecide == null) {
            return;
        }
        Snap post = (ring != null && !ring.isEmpty()) ? ring.peekLast() : collectDecide;
        evidence = new Evidence(collectPre, collectDecide, post);
        collectPre = null;
        collectDecide = null;
        collectCount = -1;
    }

    public void reset() {
        collectPre = null;
        collectDecide = null;
        collectCount = -1;
        for (Snap s : ring) {
            releaseIfUnused(s);
        }
        ring.clear();
        evidence = null;
    }

    /** 확정 증거(pre/decide)에 참여한 Mat는 release하지 않는다. */
    private void releaseIfUnused(Snap s) {
        if (s == null || s.img == null) {
            return;
        }
        if (collectPre == s || collectDecide == s) {
            return;
        }
        if (evidence != null && (evidence.pre == s || evidence.decide == s || evidence.post == s)) {
            return;
        }
        s.img.release();
    }
}
