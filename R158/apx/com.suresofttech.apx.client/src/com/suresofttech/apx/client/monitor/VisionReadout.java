package com.suresofttech.apx.client.monitor;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.vision.RoiMatchResult;

/**
 * 비전 모니터 판독값 - 캔버스 HUD("NCC 0.00")보다 자세한 <b>판정 근거</b>.
 *
 * @deprecated 모니터 뷰에서 판독값 UI를 제공하지 않는다. {@link com.suresofttech.apx.client.view.VisionMonitorView} 참고.
 */
@Deprecated
public class VisionReadout extends ReadoutBar {

    private final Label simLbl;
    private final Label roiLbl;
    private final Label frameLbl;
    private final Label judgeLbl;
    private final Label passAtLbl;
    private final Label alignLbl;
    private final Label refLbl;
    private final Label evidenceLbl;

    private Long passAtMs;
    private boolean running;

    public VisionReadout(Composite parent) {
        super(parent, "비전: 대기");
        simLbl = field("유사도");
        roiLbl = field("ROI");
        frameLbl = field("프레임");
        judgeLbl = field("자체 판단");
        passAtLbl = field("PASS 시각");
        alignLbl = field("정렬");
        refLbl = field("기준");
        evidenceLbl = field("증거");
    }

    /** 기준 소스 표기 - 설정 변경/측정 시작 양쪽에서 호출. */
    public void setReference(boolean useReferenceImage, String refPath, double simThr) {
        set(refLbl, (useReferenceImage ? "기준영상 " + baseName(refPath) : "라이브 첫 프레임")
                + " / 임계 " + f2(simThr));
        commit();
    }

    /** 측정 시작 - 지표 초기화. */
    public void onStarted(boolean useReferenceImage, String refPath, double simThr) {
        running = true;
        passAtMs = null;
        setReference(useReferenceImage, refPath, simThr);
        clear(passAtLbl);
        clear(judgeLbl);
        set(evidenceLbl, "±3프레임 수집 중", STATE_BUSY);
        head("비전: 측정 중", STATE_BUSY);
        commit();
    }

    /**
     * 매 프레임 - 매칭 지표. 측정 전(설정 / 조준 중)에도 그대로 쓸 수 있다.
     * @param r 최신 결과(null이면 대기 표기)
     * @param fps 카메라 실측 fps (0 이하면 생략)
     */
    public void update(RoiMatchResult r, double fps) {
        if (r == null) {
            set(simLbl, DASH);
            set(frameLbl, DASH);
            head(running ? "비전: 프레임 없음" : "비전: 대기", running ? STATE_FAIL : STATE_IDLE);
            commit();
            return;
        }
        boolean aligning = !"ok".equals(r.state);
        if (aligning) {
            set(simLbl, "정렬 중", STATE_BUSY);
        } else {
            set(simLbl, vsThr(r.psc, r.simThr) + "  (NCC " + f2(r.ncc) + " / SSIM " + f2(r.ssim) + ")",
                    thrState(r.psc, r.simThr));
        }
        set(roiLbl, roiText(r.roi));
        set(frameLbl, (fps > 0 ? f1(fps) + " fps / " : "")
                + "간격 " + f1(r.frameGapMs) + " ms / 처리 " + f1(r.procMs) + " ms");
        if (passAtMs == null) {
            set(judgeLbl, "간격 " + f1(r.frameGapMs) + " ms (전환 대기)");
        }
        set(alignLbl, alignText(r), aligning ? STATE_BUSY : STATE_IDLE);
        head(headText(r, aligning), passAtMs != null ? STATE_PASS
                : (aligning ? STATE_BUSY : (r.hit ? STATE_PASS : STATE_BUSY)));
        commit();
    }

    /**
     * PASS 확정 - 검출 시각과 자체 판단 분해.
     * 검출 시각은 L2 캘리브 보정 없이 물리지연(D_cap)을 포함한 값이다.
     */
    public void setPass(Long passAtMs, Double judgeMs, Double gapMs, Double analysisMs) {
        this.passAtMs = passAtMs;
        if (passAtMs == null) {
            clear(passAtLbl);
            commit();
            return;
        }
        set(passAtLbl, passAtMs + " ms (물리지연 D_cap 포함)", STATE_PASS);
        if (judgeMs != null && gapMs != null && analysisMs != null) {
            set(judgeLbl, f1(judgeMs.doubleValue()) + " ms = 간격 " + f1(gapMs.doubleValue())
                    + " + 분석 " + f1(analysisMs.doubleValue()), STATE_PASS);
        } else if (judgeMs != null) {
            set(judgeLbl, f1(judgeMs.doubleValue()) + " ms", STATE_PASS);
        }
        commit();
    }

    /**
     * 측정 중 카메라 / 해상도가 바뀌었을 때 - 녹화본이 레터박스로 이어 붙었음을 알린다.
     * 프레임별 원본 해상도는 {@code frames.csv}에 남아 있다.
     */
    public void setResolutionChanged(int resizedFrames, int recW, int recH) {
        if (resizedFrames <= 0) {
            return;
        }
        set(evidenceLbl, String.format("측정 중 해상도 변경 - %d프레임을 %d×%d로 맞춤(frames.csv에 원본 기록)",
                Integer.valueOf(resizedFrames), Integer.valueOf(recW), Integer.valueOf(recH)),
                STATE_BUSY);
        commit();
    }

    /** 측정 중단 - 헤더 고정 + ±3프레임 증거 확보 여부. */
    public void onStopped(boolean pass, boolean hasFrameEvidence) {
        running = false;
        set(evidenceLbl, hasFrameEvidence ? "pre/decide/post 확보" : "없음",
                hasFrameEvidence ? STATE_PASS : STATE_IDLE);
        if (pass && passAtMs != null) {
            head("비전: PASS @ " + passAtMs + " ms", STATE_PASS);
        } else if (pass) {
            head("비전: PASS", STATE_PASS);
        } else {
            head("비전: FAIL (팝업 미검출)", STATE_FAIL);
        }
        commit();
    }

    /**
     * 입력 끊김 표시 - 프레임이 한동안 안 들어올 때(웹캠 분리 / 드라이버 정지).
     * 마지막 지표는 남겨두고 상태만 바꾼다(끊기기 직전 값이 진단에 필요하다).
     * @param stalledMs 마지막 프레임 이후 경과
     */
    public void setStalled(long stalledMs) {
        set(frameLbl, "입력 끊김 - 마지막 프레임 " + (stalledMs / 1000) + "초 전", STATE_FAIL);
        head(running ? "비전: 입력 끊김 (측정 중)" : "비전: 입력 끊김", STATE_FAIL);
        commit();
    }

    /** 대기 상태로 초기화. */
    public void reset() {
        running = false;
        passAtMs = null;
        head("비전: 대기", STATE_IDLE);
        clear(simLbl);
        clear(roiLbl);
        clear(frameLbl);
        clear(judgeLbl);
        clear(passAtLbl);
        clear(alignLbl);
        clear(evidenceLbl);
        commit();
    }

    private String headText(RoiMatchResult r, boolean aligning) {
        if (passAtMs != null) {
            return "비전: PASS @ " + passAtMs + " ms";
        }
        if (aligning) {
            return "비전: 정렬 중";
        }
        String sim = " / 유사도 " + f2(r.psc);
        if (!running) {
            return (r.hit ? "비전: 일치" : "비전: 감시 중") + sim;
        }
        return (r.hit ? "비전: 일치" : "비전: 측정 중") + sim;
    }

    /** {@code roi = {y1,y2,x1,x2}} → {@code 320×180 (x 100..420, y 60..240)}. */
    private static String roiText(int[] roi) {
        if (roi == null || roi.length < 4) {
            return DASH;
        }
        int w = Math.max(0, roi[3] - roi[2]);
        int h = Math.max(0, roi[1] - roi[0]);
        return w + "×" + h + " (x " + roi[2] + ".." + roi[3] + ", y " + roi[0] + ".." + roi[1] + ")";
    }

    private static String alignText(RoiMatchResult r) {
        if (r.lockInliers == null && r.lockAng == null) {
            return "정렬 없음 (라이브 기준)";
        }
        StringBuilder sb = new StringBuilder();
        if (r.lockInliers != null) {
            sb.append("inliers ").append(r.lockInliers);
        }
        double[] a = r.lockAng;
        if (a != null && a.length >= 3) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append("roll ").append(f1(a[0])).append("° / scale ").append(f2(a[1]))
                    .append(" / persp ").append(f2(a[2]));
        }
        return sb.length() == 0 ? DASH : sb.toString();
    }
}
