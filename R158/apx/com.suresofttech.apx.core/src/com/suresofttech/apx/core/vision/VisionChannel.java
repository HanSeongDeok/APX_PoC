package com.suresofttech.apx.core.vision;

/**
 * 비전 입력 채널 - 클러스터 화면 / 기어봉 화면.
 * 웹캠 / ROI / 판정기(NCC/YOLO)를 채널마다 따로 둔다.
 */
public enum VisionChannel {
    CLUSTER("클러스터"),
    GEAR("기어봉");

    public final String label;

    VisionChannel(String label) {
        this.label = label;
    }
}
