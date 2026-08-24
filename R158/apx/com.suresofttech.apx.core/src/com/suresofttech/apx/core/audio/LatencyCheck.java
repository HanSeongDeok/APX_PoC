package com.suresofttech.apx.core.audio;

/**
 * 최초 음향 판정 분석 시간 측정 (마이크 없이 결정적 시뮬레이션).
 *
 * <p>두 가지를 잰다:
 * <ol>
 *   <li><b>검출 지연(오디오 타임라인)</b> - 실제 '삐'가 시작된 순간부터 알고리즘이
 *       일치를 확정한 순간까지. 블록(2048샘플) 단위 처리라 블록 크기만큼 양자화됨.</li>
 *   <li><b>순수 연산 비용</b> - feed() 한 블록 처리에 걸리는 실제 CPU 시간과
 *       실시간 대비 배속(1보다 작아야 실시간 처리 가능).</li>
 * </ol>
 *
 * <p>주의: 이건 <b>알고리즘 자체의 기여분</b>. 실제 현장 지연에는 마이크 하드웨어 /
 * OS 버퍼링이 추가된다(그건 실기기 앱에서만 측정 가능).
 *
 *   javac -source 8 -target 8 -d out8 (모든 src)   후
 *   java -cp out8 com.suresofttech.apx.core.audio.LatencyCheck [sr]
 */
public final class LatencyCheck {

    private static final int BLOCK = 2048;

    public static void main(String[] args) {
        int sr = (args.length > 0) ? Integer.parseInt(args[0]) : 44100;
        double silenceSec = 0.30;                        // 배경 설정용 선행 무음

        // 기대음 = 근접 경고음(삐 반복). 템플릿도 이 파형으로 생성.
        double[] warn = Tone.proximityWarning(sr);
        BeepMatcher m = new BeepMatcher(warn, sr);       // 기본 임계 0.5/0.5
        m.arm();

        // 입력 스트림 = [무음][경고음] 이어붙임. 삐 시작 = 무음 끝.
        int silence = (int) (silenceSec * sr);
        double[] stream = new double[silence + warn.length];
        System.arraycopy(warn, 0, stream, silence, warn.length);
        double trueOnsetSec = silence / (double) sr;     // 실제 삐 시작 시각

        // 블록 단위로 흘려보내며 판정. now = 블록 끝의 오디오 시각.
        double matchAudioSec = Double.NaN;
        for (int off = 0; off + BLOCK <= stream.length; off += BLOCK) {
            double[] block = new double[BLOCK];
            System.arraycopy(stream, off, block, 0, BLOCK);
            double nowSec = (off + BLOCK) / (double) sr;  // 블록 처리 완료 시각
            MatchResult r = m.feed(block, nowSec);
            if (r.match) {
                matchAudioSec = r.onsetT;                 // = nowSec (확정된 블록 끝)
                break;
            }
        }

        System.out.println("=== 최초 음향 판정 분석 시간 (sr=" + sr + ") ===");
        System.out.printf("블록 크기        : %d 샘플 (%.1f ms)%n", BLOCK, BLOCK * 1000.0 / sr);
        System.out.printf("실제 삐 시작     : %.3f s%n", trueOnsetSec);
        if (Double.isNaN(matchAudioSec)) {
            System.out.println("판정            : 미검출 (스트림 종료까지 확정 안 됨)");
            return;
        }
        double latencyMs = (matchAudioSec - trueOnsetSec) * 1000.0;
        System.out.printf("판정 확정 시각   : %.3f s%n", matchAudioSec);
        System.out.printf(">> 검출 지연     : %.1f ms  (삐 시작 → 일치 확정)%n", latencyMs);

        // --- 콜드스타트 시나리오 (라이브 앱과 동일: 단일음 이미 재생 중 → 측정 시작) ---
        coldStart(sr);

        // --- 정상상태 연산 비용 (JIT 워밍업 후 측정) ---
        // 확정 직전 블록 수는 적어 워밍업이 섞임 → 별도 matcher로 전체 스트림 반복 측정.
        steadyStateCost(warn, sr);
    }

    /** 콜드스타트: 무음 없이 단일음이 블록0부터 들어옴(버튼=듣기 시작). 검출지연 = 확정 시각. */
    private static void coldStart(int sr) {
        double[] tone = new double[(int) (2.0 * sr)];       // 2초 연속 2000Hz
        for (int i = 0; i < tone.length; i++) {
            tone[i] = 0.8 * Math.sin(2 * Math.PI * 2000.0 * i / sr);
        }
        double[] tmpl = new double[(int) (0.15 * sr)];      // 기대음 템플릿(단일음)
        for (int i = 0; i < tmpl.length; i++) {
            tmpl[i] = 0.8 * Math.sin(2 * Math.PI * 2000.0 * i / sr);
        }
        BeepMatcher m = new BeepMatcher(tmpl, sr);
        m.arm();
        double detectSec = Double.NaN;
        for (int off = 0; off + BLOCK <= tone.length; off += BLOCK) {
            double[] b = new double[BLOCK];
            System.arraycopy(tone, off, b, 0, BLOCK);
            double nowSec = (off + BLOCK) / (double) sr;    // 버튼(샘플0) 이후 경과
            MatchResult r = m.feed(b, nowSec);
            if (r.match) {
                detectSec = r.onsetT;
                break;
            }
        }
        System.out.println("--- 콜드스타트 (단일음, 버튼=듣기 시작) ---");
        if (Double.isNaN(detectSec)) {
            System.out.println("검출 지연        : 미확정");
        } else {
            System.out.printf("검출 지연        : %.1f ms  (측정 시작 → 확정)%n", detectSec * 1000.0);
        }
    }

    private static void steadyStateCost(double[] warn, int sr) {
        BeepMatcher wm = new BeepMatcher(warn, sr);
        // 워밍업 2회 (JIT 컴파일 유도, 측정 제외)
        for (int pass = 0; pass < 2; pass++) {
            for (int off = 0; off + BLOCK <= warn.length; off += BLOCK) {
                double[] b = new double[BLOCK];
                System.arraycopy(warn, off, b, 0, BLOCK);
                wm.feed(b, 0.0);
            }
        }
        // 측정 1회
        long nanos = 0;
        int blocks = 0;
        for (int off = 0; off + BLOCK <= warn.length; off += BLOCK) {
            double[] b = new double[BLOCK];
            System.arraycopy(warn, off, b, 0, BLOCK);
            long t0 = System.nanoTime();
            wm.feed(b, 0.0);
            nanos += System.nanoTime() - t0;
            blocks++;
        }
        double avgUs = nanos / 1000.0 / blocks;
        double rtFactor = (nanos / 1e6) / (blocks * BLOCK * 1000.0 / sr);
        System.out.println("--- 순수 연산 비용 (워밍업 후 정상상태) ---");
        System.out.printf("처리 블록 수     : %d%n", blocks);
        System.out.printf("블록당 평균      : %.1f us  (블록 %.1f ms 중)%n", avgUs, BLOCK * 1000.0 / sr);
        System.out.printf("실시간 대비 배속 : %.3f  (<1 이면 실시간 처리 여유)%n", rtFactor);
    }

    private LatencyCheck() {
    }
}
