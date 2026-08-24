package com.suresofttech.apx.core.audio;

import java.util.Arrays;

/**
 * 측정 중 마이크 원본(mono double[-1,1))을 누적하는 레코더. SWT/RCP 무의존(core).
 *
 * <p>{@link AudioCapture} 콜백에서 {@link #feed(double[])} 로 블록을 계속 넣으면
 * growable 버퍼에 원본을 통째 보관한다. 저장은 {@link WavIo#save}, 특정 구간 전달은
 * {@link #extract(double, double)} 또는 {@link WavIo#saveRange} 로 한다.
 *
 * <p>feed()는 캡처 스레드, getSamples/extract()는 UI 스레드에서 호출될 수 있어 synchronized.
 *
 * <p>주의: 전체 보관이라 장시간 측정 시 메모리가 커진다(44.1kHz 10분 약 200MB).
 * 증거용 최근 N초만 필요하면 링버퍼 변형을 쓸 것.
 */
public final class AudioRecorder {

    private int sampleRate;
    private double[] buf = new double[0];
    private int len;

    /** 새 녹음 시작(버퍼 초기화). sampleRate 기준 4초분 선할당. */
    public synchronized void start(int sampleRate) {
        this.sampleRate = sampleRate;
        this.buf = new double[Math.max(1, sampleRate * 4)];
        this.len = 0;
    }

    /** 블록 append. start() 전이면 무시. */
    public synchronized void feed(double[] block) {
        if (block == null || block.length == 0 || sampleRate <= 0) {
            return;
        }
        ensure(len + block.length);
        System.arraycopy(block, 0, buf, len, block.length);
        len += block.length;
    }

    /** 녹음 종료(현 구현은 상태 표시용 no-op - 버퍼는 그대로 조회 가능). */
    public synchronized void stop() {
        // 버퍼 유지: stop 후에도 getSamples/extract 가능
    }

    public synchronized int getSampleRate() {
        return sampleRate;
    }

    public synchronized int getSampleCount() {
        return len;
    }

    public synchronized double getDurationMs() {
        return (sampleRate > 0) ? len * 1000.0 / sampleRate : 0.0;
    }

    /** 누적 원본 전체 복사본. */
    public synchronized double[] getSamples() {
        return Arrays.copyOf(buf, len);
    }

    /**
     * 특정 시간 구간 [startMs, endMs) 샘플 복사본을 반환(구간추출 API).
     * 범위는 [0, 녹음길이]로 클램프. 파일로 바로 쓰려면 {@link WavIo#saveRange}.
     */
    public synchronized double[] extract(double startMs, double endMs) {
        int from = clampIdx((int) Math.round(startMs / 1000.0 * sampleRate));
        int to = clampIdx((int) Math.round(endMs / 1000.0 * sampleRate));
        if (to < from) {
            to = from;
        }
        return Arrays.copyOfRange(buf, from, to);
    }

    private int clampIdx(int idx) {
        return (idx < 0) ? 0 : (idx > len ? len : idx);
    }

    private void ensure(int cap) {
        if (cap <= buf.length) {
            return;
        }
        int n = Math.max(cap, buf.length * 2);   // 분할상환 2배 성장
        buf = Arrays.copyOf(buf, n);
    }
}
