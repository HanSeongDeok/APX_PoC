package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;

/**
 * 비전 판정기 공통 계약 - 프레임 1장 → {@link RoiMatchResult}.
 *
 * <p>구현체는 두 가지다.
 * <ul>
 *   <li>{@link RoiMatchDetector} - <b>NCC</b>(정규화 상호상관). 기준 영상과 픽셀을 직접 비교하는
 *       결정론적 수식이라 "유사도 0.87 ≥ 임계 0.75" 처럼 근거가 숫자로 남는다.</li>
 *   <li>{@link YoloVisionJudge} - <b>YOLO</b>(학습 모델). 기준 영상 없이 학습된 패턴으로
 *       "그 클래스일 확률"을 낸다.</li>
 * </ul>
 *
 * <p><b>두 점수는 의미가 다르다</b>(유사도 vs 확률). 다만 <b>0~1 점수를 임계와 비교해
 * PASS/FAIL을 정한다</b>는 구조가 같아, 같은 결과 객체 / 같은 UI로 바꿔 끼울 수 있다.
 * 그래서 {@link RoiMatchResult#ncc} / {@link RoiMatchResult#psc} 에는 구현별 점수가 들어간다.
 */
public interface VisionJudge {

    /** 판정기 이름 - 증거 / HUD 표기용 ("NCC" / "YOLO"). */
    String name();

    /** 프레임 1장 판정. 같은 프레임이 다시 들어오면 직전 결과를 그대로 돌려줄 수 있다. */
    RoiMatchResult process(BufferedImage bi);

    /** 판정 좌표계 크기(px) - ROI 는 이 좌표계 기준. */
    int canonWidth();

    int canonHeight();

    /** PASS 임계(0~1). NCC=유사도 임계, YOLO=확률 임계. */
    double getSimThr();

    void setSimThr(double v);

    /** 비교 영역 {y1,y2,x1,x2}. null이면 구현 기본값(중앙). */
    int[] getRoi();

    void setRoi(int[] roi);

    /** ORB 정렬 사용 여부. YOLO 처럼 정렬이 불필요한 구현은 무시할 수 있다. */
    void setAlignEnabled(boolean enabled);

    boolean isAlignEnabled();

    /** PASS 래치 해제 - 다시 최초 PASS 시각을 잡는다. */
    void resetJudgment();

    /** 정렬 상태 해제(정렬을 안 쓰는 구현은 no-op). */
    void resetAlignment();

    /** 판정 전 / 중 / 후 프레임 증거. */
    EvidenceCapture.Evidence getEvidence();

    /** 측정 중단 시 - post 미완이어도 pre/decide 확정. */
    void flushEvidence();
}
