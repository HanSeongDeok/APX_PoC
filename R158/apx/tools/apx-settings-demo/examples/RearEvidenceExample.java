import java.io.File;
import java.util.Arrays;
import java.util.List;

import com.suresofttech.apx.core.rear.Verdict;
import com.suresofttech.apx.core.rear.VerdictResult;
import com.suresofttech.apx.ui.widget.settings.rear.RearGridCanvas;

/**
 * 후방 검측 증거(스냅샷) API 예시.
 *
 * <p>규약 파일명: {@code <tcId>_c<col>_r<row>_<PASS|FAIL>_<cols>x<rows>.png}
 * <br>저장 폴더: 보통 {@code <증거루트>/rear/}
 *
 * <p>조회:
 * <ul>
 *   <li>{@link RearGridCanvas#getSnapshot} — TC 1개</li>
 *   <li>{@link RearGridCanvas#getSnapshots} — TC 여러 개(없는 항목은 null)</li>
 *   <li>{@link RearGridCanvas#getCombinedSnapshot} — 한 판으로 합친 PNG
 *       (격자 cols×rows 가 서로 다르면 합칠 수 없음)</li>
 * </ul>
 *
 * <p>경로(이 파일):
 * {@code apx-settings-demo/examples/RearEvidenceExample.java}
 *
 * <p>※ 컴파일 대상이 아닌 읽기용 샘플입니다. 실제 호출은 Kickoff / RearMonitorView 참고.
 */
public final class RearEvidenceExample {

    private RearEvidenceExample() {
    }

    /**
     * @param canvas 후방 격자 캔버스 (모니터/설정에서 이미 생성된 인스턴스)
     * @param evidenceRoot 증거 루트 폴더 (아래에 rear/ 를 둔다)
     */
    public static void saveAndQuery(RearGridCanvas canvas, File evidenceRoot) {
        // 1) 스냅샷 저장 폴더 지정
        File rearDir = new File(evidenceRoot, "rear");
        canvas.setSnapshotDir(rearDir);

        // 2) 측정 중단 시 포인트별 PASS/FAIL 스냅샷 저장
        //    파일명 예: TC_REAR_01_c2_r3_PASS_4x6.png
        String tcId = "TC_REAR_01";
        VerdictResult r = new VerdictResult(2, 3, Verdict.PASS);
        File saved = canvas.saveVerdictSnapshot(r, tcId);
        System.out.println("saved → " + saved);

        // 3) 단일 조회
        File one = canvas.getSnapshot(tcId);
        System.out.println("getSnapshot → " + one);

        // 4) 배치 조회 (없는 tcId 자리는 null)
        List<String> ids = Arrays.asList("TC_REAR_01", "TC_REAR_02");
        List<File> many = canvas.getSnapshots(ids);
        for (int i = 0; i < many.size(); i++) {
            System.out.println("  " + ids.get(i) + " → " + many.get(i));
        }

        // 5) 통합 조회 — 동일 격자 규격(cols×rows)만 한 판으로 합침
        //    규격이 섞이면 해당 항목은 건너뛰거나 결과가 null 이 될 수 있음
        File combined = canvas.getCombinedSnapshot(ids);
        System.out.println("getCombinedSnapshot → " + combined);
    }
}
