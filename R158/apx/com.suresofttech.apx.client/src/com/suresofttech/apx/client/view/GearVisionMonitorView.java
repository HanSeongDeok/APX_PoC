package com.suresofttech.apx.client.view;

import com.suresofttech.apx.core.vision.VisionChannel;

/** 기어봉 전용 라이브 비전 모니터 View. */
public class GearVisionMonitorView extends VisionMonitorView {

    public static final String ID = "com.suresofttech.apx.client.view.gearVisionMonitor";

    public GearVisionMonitorView() {
        super(VisionChannel.GEAR);
    }
}
