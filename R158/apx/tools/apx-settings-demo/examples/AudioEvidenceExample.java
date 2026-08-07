import java.io.File;

import com.suresofttech.apx.core.measure.MeasureEvidence;
import com.suresofttech.apx.core.measure.MeasureSession;

/**
 * 음향 검측 증거 API 예시.
 *
 * <p>디스크 규약 — {@code <증거루트>/audio/}:
 * <ul>
 *   <li>{@code full.wav} — 측정 전체</li>
 *   <li>{@code clip.wav} — PASS 초록 밴드 시작~해제 구간 ({@link MeasureEvidence#setAudioPassSpan})</li>
 *   <li>{@code wave_pass.png} — PASS 시점 파형 스냅샷</li>
 *   <li>{@code wave_full.png} — 측정 종료 시점 전체 파형</li>
 * </ul>
 *
 * <p>구간 추출: {@link MeasureEvidence#saveRange} / {@link MeasureEvidence#getRangeWavBytes}
 *
 * <p>경로(이 파일):
 * {@code apx-settings-demo/examples/AudioEvidenceExample.java}
 *
 * <p>※ 컴파일 대상이 아닌 읽기용 샘플입니다. 실제 호출은 Kickoff / AudioMonitorView 참고.
 */
public final class AudioEvidenceExample {

    private AudioEvidenceExample() {
    }

    /**
     * 세션 증거 버퍼를 규약 폴더에 저장하고, 필요 시 구간 WAV를 따로 뽑는다.
     *
     * @param evidenceRoot 증거 루트 (아래에 audio/ 를 둔다)
     */
    public static void saveConventionFiles(File evidenceRoot) throws Exception {
        MeasureEvidence ev = MeasureSession.get().getEvidence();
        if (ev == null) {
            return;
        }

        // AudioScope 초록 PASS 밴드와 동일 구간이어야 clip.wav 가 의미 있다
        // (모니터가 falling edge / flush 때 setAudioPassSpan 호출)
        // ev.setAudioPassSpan(passStartMs, passEndMs);

        File audioDir = new File(evidenceRoot, "audio");
        // full.wav + wave_pass.png + wave_full.png + (구간 있으면) clip.wav
        ev.saveTo(audioDir);

        // 구간만 따로 뽑고 싶을 때
        Double start = ev.getAudioPassStartMs();
        Double end = ev.getAudioPassEndMs();
        if (start != null && end != null && end.doubleValue() > start.doubleValue()) {
            File clip = ev.saveRange(new File(audioDir, "clip.wav"),
                    start.doubleValue(), end.doubleValue());
            System.out.println("clip.wav → " + clip);

            // 파일 없이 바이트만
            byte[] wavBytes = ev.getRangeWavBytes(start.doubleValue(), end.doubleValue());
            System.out.println("range bytes = "
                    + (wavBytes == null ? 0 : wavBytes.length));
        }

        // 개별 저장도 가능
        // ev.saveFull(new File(audioDir, "full.wav"));
    }
}
