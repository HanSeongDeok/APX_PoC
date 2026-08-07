package com.suresofttech.apx.client.view;

import java.io.ByteArrayInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.measure.MeasureSyncResult;

/**
 * 최근 측정 결과 View — DB 없이 {@link LastMeasureResult} 1건만 표시.
 * 측정 시각(검출·자체판단·동기) + 모니터 스냅샷
 * (음향=PASS 밴드 종료 시점, 비전=최초 PASS, 후방=overallPass).
 */
public class ResultView extends ViewPart {

    public static final String ID = "com.suresofttech.apx.client.view.result";

    private Display display;
    private ScrolledComposite scroll;
    private Composite body;
    private Label emptyLbl;
    private Label headerLbl;
    private Label audioTimeLbl;
    private Label visionTimeLbl;
    private Label syncLbl;
    private Label audioSnapLbl;
    private Label visionSnapLbl;
    private Label rearSnapLbl;
    private Label audioImgLbl;
    private Label visionImgLbl;
    private Label rearImgLbl;

    private Image audioImg;
    private Image visionImg;
    private Image rearImg;

    private LastMeasureResult.Listener resultListener;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(1, false));

        scroll = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
        scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroll.setExpandHorizontal(true);
        scroll.setExpandVertical(true);

        body = new Composite(scroll, SWT.NONE);
        body.setLayout(new GridLayout(1, false));
        scroll.setContent(body);

        emptyLbl = label(body, "아직 측정 결과가 없습니다. Kickoff에서 측정 시작 → 중단 하면 여기에 표시됩니다.");
        headerLbl = label(body, "");
        audioTimeLbl = label(body, "");
        visionTimeLbl = label(body, "");
        syncLbl = label(body, "");

        audioSnapLbl = section(body, "음향 모니터 — PASS 구간 종료 스냅샷");
        audioImgLbl = imageSlot(body);
        visionSnapLbl = section(body, "비전 모니터 — 최초 PASS 스냅샷");
        visionImgLbl = imageSlot(body);
        rearSnapLbl = section(body, "후방 모니터 — 최초 PASS 스냅샷");
        rearImgLbl = imageSlot(body);

        resultListener = new LastMeasureResult.Listener() {
            public void onResult(final LastMeasureResult result) {
                if (display.isDisposed()) {
                    return;
                }
                display.asyncExec(new Runnable() {
                    public void run() {
                        refresh(result);
                    }
                });
            }
        };
        LastMeasureResult.get().addListener(resultListener);
        refresh(LastMeasureResult.get());
    }

    private static Label label(Composite parent, String text) {
        Label l = new Label(parent, SWT.WRAP);
        l.setText(text);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 400;
        l.setLayoutData(gd);
        return l;
    }

    private static Label section(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.verticalIndent = 12;
        l.setLayoutData(gd);
        return l;
    }

    private static Label imageSlot(Composite parent) {
        Label l = new Label(parent, SWT.BORDER);
        l.setText("(없음)");
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 480;
        gd.heightHint = 180;
        l.setLayoutData(gd);
        return l;
    }

    private void refresh(LastMeasureResult r) {
        if (body == null || body.isDisposed()) {
            return;
        }
        if (r == null || !r.hasResult()) {
            emptyLbl.setText("아직 측정 결과가 없습니다. Kickoff에서 측정 시작 → 중단 하면 여기에 표시됩니다.");
            setVisible(true, emptyLbl);
            setVisible(false, headerLbl, audioTimeLbl, visionTimeLbl, syncLbl,
                    audioSnapLbl, visionSnapLbl, rearSnapLbl,
                    audioImgLbl, visionImgLbl, rearImgLbl);
            layoutScroll();
            return;
        }
        setVisible(false, emptyLbl);
        setVisible(true, headerLbl, audioTimeLbl, visionTimeLbl, syncLbl,
                audioSnapLbl, visionSnapLbl, rearSnapLbl,
                audioImgLbl, visionImgLbl, rearImgLbl);

        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(r.getStoppedAtEpochMs()));
        headerLbl.setText(String.format("최종: %s — %s  (%s)",
                r.isOverallPass() ? "PASS" : "FAIL", r.getSummary(), when));
        audioTimeLbl.setText(formatTimeLine("음향", r.getAudioPassMs(), r.getAudioJudgeMs(),
                r.getAudioGapMs(), r.getAudioAnalysisMs()));
        visionTimeLbl.setText(formatTimeLine("비전", r.getVisionPassMs(), r.getVisionJudgeMs(),
                r.getVisionGapMs(), r.getVisionAnalysisMs()));
        if (r.getSyncSpreadMs() != null) {
            syncLbl.setText(String.format("동기: %.0f ms %s (≤%.0fms)",
                    r.getSyncSpreadMs().doubleValue(),
                    r.isSyncOk() ? "OK" : "FAIL",
                    MeasureSyncResult.SYNC_TOL_MS));
        } else {
            syncLbl.setText("동기: — (PASS 시각 2개 미만)");
        }

        audioImg = setPng(audioImgLbl, audioImg, r.getAudioPassPng());
        visionImg = setPng(visionImgLbl, visionImg, r.getVisionPassPng());
        rearImg = setPng(rearImgLbl, rearImg, r.getRearPassPng());
        layoutScroll();
    }

    private void layoutScroll() {
        body.layout(true, true);
        Point size = body.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        scroll.setMinSize(size);
    }

    private static void setVisible(boolean vis, Label... labels) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] != null && !labels[i].isDisposed()) {
                labels[i].setVisible(vis);
                Object ld = labels[i].getLayoutData();
                if (ld instanceof GridData) {
                    ((GridData) ld).exclude = !vis;
                }
            }
        }
    }

    private static String formatTimeLine(String channel, Long passMs, Double judgeMs,
            Double gapMs, Double analysisMs) {
        if (passMs == null) {
            return channel + ": FAIL (미검출)";
        }
        if (judgeMs != null && gapMs != null && analysisMs != null) {
            return String.format("%s: PASS @ %d ms (자체판단 %.1f = 간격 %.1f + 분석 %.1f)",
                    channel, passMs.longValue(),
                    judgeMs.doubleValue(), gapMs.doubleValue(), analysisMs.doubleValue());
        }
        if (judgeMs != null) {
            return String.format("%s: PASS @ %d ms (자체판단 %.1f ms)",
                    channel, passMs.longValue(), judgeMs.doubleValue());
        }
        return String.format("%s: PASS @ %d ms", channel, passMs.longValue());
    }

    private Image setPng(Label slot, Image old, byte[] png) {
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }
        slot.setImage(null);
        if (png == null || png.length == 0) {
            slot.setText("(스냅샷 없음)");
            return null;
        }
        try {
            ImageData data = new ImageData(new ByteArrayInputStream(png));
            // 너무 크면 폭 제한
            int maxW = 560;
            if (data.width > maxW) {
                int h = Math.max(1, (int) (data.height * (maxW / (double) data.width)));
                data = data.scaledTo(maxW, h);
            }
            Image img = new Image(slot.getDisplay(), data);
            slot.setText("");
            slot.setImage(img);
            GridData gd = (GridData) slot.getLayoutData();
            if (gd != null) {
                gd.widthHint = img.getBounds().width;
                gd.heightHint = img.getBounds().height;
            }
            return img;
        } catch (Exception ex) {
            slot.setText("(이미지 로드 실패)");
            return null;
        }
    }

    @Override
    public void setFocus() {
        if (scroll != null && !scroll.isDisposed()) {
            scroll.setFocus();
        }
    }

    @Override
    public void dispose() {
        LastMeasureResult.get().removeListener(resultListener);
        if (audioImg != null && !audioImg.isDisposed()) {
            audioImg.dispose();
        }
        if (visionImg != null && !visionImg.isDisposed()) {
            visionImg.dispose();
        }
        if (rearImg != null && !rearImg.isDisposed()) {
            rearImg.dispose();
        }
        super.dispose();
    }
}
