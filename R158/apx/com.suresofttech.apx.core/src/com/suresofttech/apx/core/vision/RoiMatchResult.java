package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;

/**
 * 고정 ROI 유사도 검출 결과 (기어 R / 클러스터 팝업 공용).
 * state: "aligning" | "ok". hit = psc >= simThr (R 체결 / 팝업 등장).
 * canonImage: 표시용 정렬 화면(BufferedImage) — ui는 OpenCV Mat를 몰라도 되게 중립 타입으로 제공.
 */
public final class RoiMatchResult {

    public String state;
    public BufferedImage canonImage;  // 표시용 정렬 화면 (SWT는 이걸 ImageData로 변환)

    public int[] roi;                 // {y1,y2,x1,x2}
    public double ncc;
    public double ssim;
    public double psc;                // max(ncc, ssim)
    public double simThr;
    public boolean hit;

    public double procMs;             // 이 프레임 판단(처리) 속도 — 매 프레임 라이브
    public double frameGapMs;
    public Double analysisMs;         // 전환 순간(없으면 null)
    /** 자체 판단(ms) = frameGap(=1/fps) + analysis. 전환 확정 시에만. 물리지연(D_cap) 미포함. */
    public Double passMs;

    public Integer lockInliers;
    public double[] lockAng;          // {roll, scale, persp}

    public RoiMatchResult(String state) {
        this.state = state;
    }
}
