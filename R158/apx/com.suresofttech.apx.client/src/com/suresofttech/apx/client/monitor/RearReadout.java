package com.suresofttech.apx.client.monitor;

import java.util.List;

import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

import com.suresofttech.apx.core.rear.Verdict;
import com.suresofttech.apx.core.rear.VerdictResult;
import com.suresofttech.apx.ui.widget.settings.rear.RearGridCanvas;

/**
 * 후방 모니터 판독값 — 격자 그림만으로는 안 보이는 <b>지정·판정 상태</b>.
 *
 * @deprecated 모니터 뷰에서 판독값 UI를 제공하지 않는다. {@link com.suresofttech.apx.client.view.RearMonitorView} 참고.
 */
@Deprecated
public class RearReadout extends ReadoutBar {

    private final Label gridLbl;
    private final Label selectedLbl;
    private final Label countLbl;
    private final Label pointsLbl;
    private final Label finalLbl;
    private final Label evidenceLbl;
    private final Label legendLbl;

    /** 포인트 목록에 나열할 최대 개수 — 넘으면 "외 N점". */
    private static final int POINTS_LIMIT = 6;

    private int selectedCount;
    private boolean running;

    public RearReadout(Composite parent) {
        super(parent, "후방: 대기");
        gridLbl = field("격자");
        selectedLbl = field("지정");
        countLbl = field("판정 집계");
        pointsLbl = field("포인트");
        finalLbl = field("최종");
        evidenceLbl = field("증거");
        legendLbl = field("범례");
        set(legendLbl, legendText());
    }

    /** 격자·지정 포인트 — 설정 적용/측정 시작 양쪽에서 호출. */
    public void setGrid(int cols, int rows, List<int[]> selected) {
        selectedCount = selected == null ? 0 : selected.size();
        set(gridLbl, cols + " × " + rows + " (" + (cols * rows) + "칸)");
        set(selectedLbl, selectedCount + "점" + (selectedCount == 0 ? " — 설정에서 지정 필요" : ""),
                selectedCount == 0 ? STATE_FAIL : STATE_IDLE);
        commit();
    }

    /** 측정 시작 — 지정 포인트는 중단 전까지 측정중. */
    public void onStarted(int cols, int rows, List<int[]> selected) {
        running = true;
        setGrid(cols, rows, selected);
        clear(finalLbl);
        set(evidenceLbl, "중단 시 저장", STATE_BUSY);
        head("후방: 측정 중 — 지정 " + selectedCount + "점", STATE_BUSY);
        commit();
    }

    /** 판정 갱신 — 집계·좌표별 상태. */
    public void setVerdicts(List<VerdictResult> results) {
        int measuring = 0;
        int pass = 0;
        int fail = 0;
        int none = 0;
        StringBuilder pts = new StringBuilder();
        int listed = 0;
        int total = results == null ? 0 : results.size();
        for (int i = 0; i < total; i++) {
            VerdictResult r = results.get(i);
            if (r == null) {
                continue;
            }
            Verdict v = r.getVerdict();
            if (v == Verdict.PASS) {
                pass++;
            } else if (v == Verdict.FAIL) {
                fail++;
            } else if (v == Verdict.MEASURING) {
                measuring++;
            } else {
                none++;
            }
            if (listed < POINTS_LIMIT) {
                if (listed > 0) {
                    pts.append(" · ");
                }
                pts.append("c").append(r.getPoint().x).append("r").append(r.getPoint().y)
                        .append(' ').append(label(v));
                listed++;
            }
        }
        if (total > listed) {
            pts.append(" 외 ").append(total - listed).append("점");
        }
        set(countLbl, "측정중 " + measuring + " · 합격 " + pass + " · 불합격 " + fail
                + " · 미판정 " + none, countState(pass, fail, measuring));
        set(pointsLbl, pts.length() == 0 ? DASH : pts.toString());
        if (!running) {
            head("후방: 합격 " + pass + " / 불합격 " + fail,
                    fail > 0 ? STATE_FAIL : (pass > 0 ? STATE_PASS : STATE_IDLE));
        }
        commit();
    }

    /**
     * 중단 시 최종 판정 — Kickoff 동기 결과를 그대로 표기.
     * @param overallMs 최종 PASS 시각(ms). 없으면 null
     */
    public void setFinal(boolean pass, Long overallMs, String summary) {
        running = false;
        StringBuilder sb = new StringBuilder(pass ? "PASS" : "FAIL");
        if (overallMs != null) {
            sb.append(" @ ").append(overallMs).append(" ms");
        }
        if (summary != null && !summary.isEmpty()) {
            sb.append(" — ").append(summary);
        }
        set(finalLbl, sb.toString(), pass ? STATE_PASS : STATE_FAIL);
        // setVerdicts 이후에 불리므로 헤더도 최종 판정으로 덮는다(측정 중 표기 잔류 방지).
        head("후방: " + (pass ? "PASS" : "FAIL"), pass ? STATE_PASS : STATE_FAIL);
        commit();
    }

    /** 저장된 증거 스냅샷 요약 ({@code <tcId>_c_r_VERDICT_WxH.png}). */
    public void setEvidenceNote(String note) {
        set(evidenceLbl, note, STATE_PASS);
        commit();
    }

    /** 대기 상태로 초기화. */
    public void reset() {
        running = false;
        head("후방: 대기", STATE_IDLE);
        clear(countLbl);
        clear(pointsLbl);
        clear(finalLbl);
        clear(evidenceLbl);
        commit();
    }

    private static int countState(int pass, int fail, int measuring) {
        if (fail > 0) {
            return STATE_FAIL;
        }
        if (measuring > 0) {
            return STATE_BUSY;
        }
        return pass > 0 ? STATE_PASS : STATE_IDLE;
    }

    private static String label(Verdict v) {
        if (v == Verdict.PASS) {
            return RearGridCanvas.DEFAULT_LEGEND_NAMES[2];
        }
        if (v == Verdict.FAIL) {
            return RearGridCanvas.DEFAULT_LEGEND_NAMES[3];
        }
        if (v == Verdict.MEASURING) {
            return RearGridCanvas.DEFAULT_LEGEND_NAMES[1];
        }
        return "미판정";
    }

    /** 캔버스 기본 범례와 같은 이름·순서(선택/측정중/합격/불합격). */
    private static String legendText() {
        String[] n = RearGridCanvas.DEFAULT_LEGEND_NAMES;
        return n[0] + " 하늘 · " + n[1] + " 노랑 · " + n[2] + " 초록 · " + n[3] + " 빨강";
    }
}
