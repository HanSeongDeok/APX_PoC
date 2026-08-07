import java.io.File;

import com.suresofttech.apx.core.measure.MeasureSession;
import com.suresofttech.apx.core.vision.EvidenceCapture;
import com.suresofttech.apx.ui.widget.settings.vision.RoiNcc;

/**
 * 비전 검측 증거(±1프레임) API 예시.
 *
 * <p>디스크 규약 — {@code <증거루트>/vision/}:
 * <ul>
 *   <li>{@code evidence_pre_-1f.png} — 판정 직전 1프레임</li>
 *   <li>{@code evidence_decide.png} — 판정 프레임</li>
 *   <li>{@code evidence_post_+1f.png} — 판정 직후 1프레임</li>
 *   <li>{@code full.avi} + {@code frames.csv} — FULL 녹화·시각 인덱스</li>
 *   <li>{@code matches.csv} — 프레임별 ROI hit/ncc (결과 스크럽 PASS/FAIL 색)</li>
 * </ul>
 * meta.properties 에 {@code roiNorm}/{@code simThr} 도 함께 저장된다.
 *
 * <p>흐름:
 * <ol>
 *   <li>RoiNcc detector가 측정 중 ±1f 를 버퍼에 쌓음</li>
 *   <li>중단 직전 flush → {@link MeasureSession#acceptVisionFrameEvidence}</li>
 *   <li>{@link MeasureSession#saveVisionFrameEvidenceTo} 로 PNG 3장 저장</li>
 * </ol>
 *
 * <p>※ FULL 녹화(전체 영상)는 향후 확장. 현재는 ±1f 정지 프레임 3장.
 *
 * <p>경로(이 파일):
 * {@code apx-settings-demo/examples/VisionEvidenceExample.java}
 *
 * <p>※ 컴파일 대상이 아닌 읽기용 샘플입니다. 실제 호출은 Kickoff / VisionMonitorView 참고.
 */
public final class VisionEvidenceExample {

    private VisionEvidenceExample() {
    }

    /**
     * 모니터의 RoiNcc에서 수확한 증거를 세션에 넘긴 뒤 폴더에 저장.
     *
     * @param roiNcc 비전 모니터에 붙어 있는 ROI 매처
     * @param evidenceRoot 증거 루트 (아래에 vision/ 를 둔다)
     */
    public static void harvestAndSave(RoiNcc roiNcc, File evidenceRoot) {
        MeasureSession session = MeasureSession.get();

        // 1) detector 버퍼 flush 후 ±1f 증거 조회 → 세션에 수용
        if (roiNcc != null) {
            roiNcc.flushEvidence();
            EvidenceCapture.Evidence frames = roiNcc.getEvidence();
            if (frames != null) {
                session.acceptVisionFrameEvidence(frames);
            }
        }

        // 2) 조회 (메모리)
        EvidenceCapture.Evidence held = session.getVisionFrameEvidence();
        if (held == null) {
            System.out.println("비전 ±1f 증거 없음");
            return;
        }
        System.out.println("pre=" + (held.pre != null)
                + " decide=" + (held.decide != null)
                + " post=" + (held.post != null));

        // 3) 규약 파일명으로 저장
        File visionDir = new File(evidenceRoot, "vision");
        session.saveVisionFrameEvidenceTo(visionDir);
        System.out.println("saved under " + visionDir.getAbsolutePath());
        // → evidence_pre_-1f.png / evidence_decide.png / evidence_post_+1f.png
    }
}
