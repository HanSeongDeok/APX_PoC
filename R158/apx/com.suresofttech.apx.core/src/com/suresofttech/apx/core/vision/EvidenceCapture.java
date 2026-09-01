package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

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
        public Mat img;
        private final BufferedImage buffered;
        public final double t;

        Snap(Mat img, double t) {
            this.img = img;
            this.buffered = null;
            this.t = t;
        }

        Snap(BufferedImage buffered, double t) {
            this.img = null;
            this.buffered = buffered;
            this.t = t;
        }

        void materialize() {
            if (img == null && buffered != null) {
                img = Cv.toMat(buffered);
            }
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

    /** 불변 웹캠 프레임 참조를 보관한다. 전체 Mat 변환은 증거 조회 시 한 번만 한다. */
    public void push(BufferedImage img, double t) {
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

    /** post 프레임을 기다리는 중인지. false면 호출자가 비싼 Mat 복제를 생략할 수 있다. */
    public boolean needsPostFrame() {
        return collectCount >= 0;
    }

    /**
     * 판단 이후 프레임마다 호출 - after번째를 post로 확정.
     * 전달된 Mat의 소유권을 넘겨받으며, 증거로 채택하지 않으면 즉시 release한다.
     */
    public void stepAfter(Mat img, double t) {
        if (collectCount < 0) {
            if (img != null) {
                img.release();
            }
            return;
        }
        collectCount++;
        if (collectCount >= after) {
            evidence = new Evidence(collectPre, collectDecide, new Snap(img, t));
            collectPre = null;
            collectDecide = null;
            collectCount = -1;
        } else if (img != null) {
            img.release();
        }
    }

    public void stepAfter(BufferedImage img, double t) {
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
        if (evidence != null) {
            materialize(evidence.pre);
            materialize(evidence.decide);
            materialize(evidence.post);
        }
        return evidence;
    }

    private static void materialize(Snap snap) {
        if (snap != null) {
            snap.materialize();
        }
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
        Set<Mat> released = Collections.newSetFromMap(new IdentityHashMap<Mat, Boolean>());
        for (Snap s : ring) {
            releaseOnce(s, released);
        }
        if (evidence != null) {
            releaseOnce(evidence.pre, released);
            releaseOnce(evidence.decide, released);
            releaseOnce(evidence.post, released);
        }
        collectPre = null;
        collectDecide = null;
        collectCount = -1;
        ring.clear();
        evidence = null;
    }

    private static void releaseOnce(Snap s, Set<Mat> released) {
        if (s != null && s.img != null && released.add(s.img)) {
            s.img.release();
        }
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
