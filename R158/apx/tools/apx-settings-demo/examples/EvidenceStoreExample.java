import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.suresofttech.apx.core.measure.EvidenceBundle;
import com.suresofttech.apx.core.measure.EvidenceStore;
import com.suresofttech.apx.core.measure.MeasureEvidence;

/**
 * TC별 파일 증거 Store API 예시 — DB 없이 {@code <루트>/<tcId>/} 가 row.
 *
 * <pre>
 * EvidenceStore store = EvidenceStore.at(root);
 * File dir = store.prepare("AESOP-TC-01");
 * // … MeasureEvidence.saveTo(new File(dir,"audio")) + EvidenceBundle.writeMeta(dir, …)
 * EvidenceBundle b = store.open("AESOP-TC-01");
 * List&lt;double[]&gt; spans = store.getAudioPassSpans("AESOP-TC-01");
 * </pre>
 *
 * <p>경로: {@code apx-settings-demo/examples/EvidenceStoreExample.java}
 * <p>※ 컴파일 대상이 아닌 읽기용 샘플. 실제 저장은 KickoffView 참고.
 */
public final class EvidenceStoreExample {

    private EvidenceStoreExample() {
    }

    /**
     * 측정 증거를 TC 폴더에 쓰고, 클라가 나중에 PASS 위치를 읽는 흐름.
     *
     * @param evidenceRoot 증거 루트 (아래에 TC 폴더가 생김)
     * @param tcId         Aesop 측정 TC id
     * @param ev           측정 종료 후 세션 증거(wav·클립 구간 등)
     */
    public static File saveAndReadPass(File evidenceRoot, String tcId, MeasureEvidence ev)
            throws Exception {
        EvidenceStore store = EvidenceStore.at(evidenceRoot);
        File dir = store.prepare(tcId);
        if (dir == null || ev == null) {
            return null;
        }

        // 채널별 파일
        ev.saveTo(new File(dir, EvidenceBundle.AUDIO_DIR));

        // PASS 시각·밴드 → meta.properties (파일이 곧 DB)
        List<double[]> spans = Collections.emptyList();
        Double start = ev.getAudioPassStartMs();
        Double end = ev.getAudioPassEndMs();
        if (start != null && end != null && end.doubleValue() > start.doubleValue()) {
            spans = Arrays.asList(new double[] { start.doubleValue(), end.doubleValue() });
        }
        EvidenceBundle.writeMeta(dir,
                false, "example",
                ev.getAudioPassMs(), ev.getVisionPassMs(),
                null, false, 0,
                start, end,
                spans, null, 0);

        // ── 클라 요청: 첫 TC로 돌아가 PASS 위치 읽기 ──
        Long passMs = store.getAudioPassMs(tcId);
        List<double[]> bands = store.getAudioPassSpans(tcId);
        System.out.println("TC=" + tcId + " audioPassMs=" + passMs
                + " spans=" + bands.size()
                + " listed=" + store.listTcIds());

        EvidenceBundle reopened = store.open(tcId);
        System.out.println("reopen root=" + (reopened == null ? null : reopened.getRoot()));
        return dir;
    }
}
