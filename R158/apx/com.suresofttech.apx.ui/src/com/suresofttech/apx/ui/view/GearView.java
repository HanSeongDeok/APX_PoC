package com.suresofttech.apx.ui.view;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.core.vision.RoiMatchDetector;
import com.suresofttech.apx.core.vision.RoiMatchResult;
import com.suresofttech.apx.ui.widget.CameraCanvas;
import com.suresofttech.apx.ui.widget.TestPlayerDialog;

/**
 * ② 기어봉 View — R 체결 판정.
 * 설정 탭({@link ApxSettings})의 웹캠·기준이미지·ROI·임계를 재사용하고 NCC를 표시한다.
 */
public class GearView extends ViewPart {

    private static final String DEFAULT_REF = "c:/DEV/apx/hyundai_R.png";

    private Display display;
    private RoiMatchDetector det;
    private String refPath;
    private volatile RoiMatchResult last;
    private boolean applyingSettings;

    private CameraCanvas canvas;
    private Label metrics;
    private Label refLabel;

    // 드래그(ROI 지정) 상태 — 위젯 좌표
    private boolean dragging;
    private int dragX0, dragY0, dragX1, dragY1;

    private final ApxSettings.Listener settingsListener = new ApxSettings.Listener() {
        public void onSettingsChanged(final ApxSettings s) {
            if (display == null || display.isDisposed()) {
                return;
            }
            display.asyncExec(new Runnable() {
                public void run() {
                    applyFromSettings(s);
                }
            });
        }
    };

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        parent.setLayout(new GridLayout(2, false));

        canvas = new CameraCanvas(parent);
        canvas.setPlaceholder("설정에서 웹캠·기준이미지를 지정하세요");
        canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        canvas.setOverlay(new CameraCanvas.Overlay() {
            public void paint(GC gc, double scale, int dx, int dy) {
                drawOverlay(gc, scale, dx, dy);
            }
        });
        canvas.addMouseListener(new MouseAdapter() {
            public void mouseDown(MouseEvent e) {
                // 설정에서 기준이미지 ON이면 ROI는 설정 값 고정(모니터 모드)
                if (ApxSettings.get().isUseReferenceImage() && ApxSettings.get().getRoiNorm() != null) {
                    return;
                }
                dragging = true;
                dragX0 = dragX1 = e.x;
                dragY0 = dragY1 = e.y;
            }

            public void mouseUp(MouseEvent e) {
                if (!dragging) {
                    return;
                }
                dragging = false;
                dragX1 = e.x;
                dragY1 = e.y;
                commitRoiFromDrag();
            }
        });
        canvas.addMouseMoveListener(new MouseMoveListener() {
            public void mouseMove(MouseEvent e) {
                if (dragging) {
                    dragX1 = e.x;
                    dragY1 = e.y;
                    canvas.redraw();
                }
            }
        });

        buildSide(parent);

        ApxSettings s = ApxSettings.get();
        String seed = s.getVisionRefPath();
        if (seed != null && new File(seed).isFile()) {
            setRef(seed);
        } else if (new File(DEFAULT_REF).exists()) {
            setRef(DEFAULT_REF);
        }
        applyFromSettings(s);
        ApxSettings.get().addListener(settingsListener);
        startPoll();
        installShortcuts(parent);
    }

    /** 설정 탭 값 → 검출기 반영 (기준이미지·ROI·임계). */
    private void applyFromSettings(ApxSettings s) {
        if (canvas == null || canvas.isDisposed()) {
            return;
        }
        applyingSettings = true;
        try {
            String path = s.getVisionRefPath();
            if (path != null && new File(path).isFile()
                    && (refPath == null || !path.equals(refPath))) {
                setRef(path);
            }
            if (det != null) {
                int[] roi = s.getRoi(det.canonWidth(), det.canonHeight());
                if (roi != null) {
                    det.setRoi(roi);
                }
                det.setSimThr(s.getSimThr());
            }
        } finally {
            applyingSettings = false;
        }
    }

    /** 파이썬 앱과 동일 단축키 — 이 View에 포커스 있을 때만(4 View 동시 표시라 스코프 필요).
     *  C=정렬리셋, R=판정리셋, −/+(=)=임계, D=보고서 저장. */
    private void installShortcuts(final Composite root) {
        final Listener f = new Listener() {
            public void handleEvent(Event e) {
                if (det == null || root.isDisposed()) {
                    return;
                }
                Control fc = display.getFocusControl();
                if (fc == null || !isDescendant(fc, root)) {
                    return;                              // 다른 View 포커스면 무시
                }
                switch (Character.toLowerCase(e.character)) {
                    case 'c':
                        det.resetAlignment();
                        break;
                    case 'r':
                        det.resetJudgment();
                        break;
                    case 'd':
                        saveReport();
                        break;
                    case '-':
                        det.setSimThr(det.getSimThr() - 0.02);
                        ApxSettings.get().setSimThr(det.getSimThr());
                        break;
                    case '+':
                    case '=':
                        det.setSimThr(det.getSimThr() + 0.02);
                        ApxSettings.get().setSimThr(det.getSimThr());
                        break;
                    default:
                        return;
                }
                e.doit = false;
            }
        };
        display.addFilter(SWT.KeyDown, f);
        root.addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent ev) {
                display.removeFilter(SWT.KeyDown, f);
            }
        });
    }

    private static boolean isDescendant(Control c, Control ancestor) {
        while (c != null) {
            if (c == ancestor) {
                return true;
            }
            c = c.getParent();
        }
        return false;
    }

    private void buildSide(Composite parent) {
        Composite side = new Composite(parent, SWT.NONE);
        side.setLayout(new GridLayout(2, true));
        GridData sd = new GridData(SWT.FILL, SWT.FILL, false, true);
        sd.widthHint = 300;
        side.setLayoutData(sd);

        metrics = new Label(side, SWT.WRAP);
        metrics.setText("기준 이미지를 지정하고, 영상 위에서 R 표시 영역을 드래그하세요");
        GridData md = new GridData(SWT.FILL, SWT.TOP, true, true, 2, 1);
        md.heightHint = 150;
        metrics.setLayoutData(md);

        addBtn(side, "정렬 리셋 (C)", new Runnable() {
            public void run() {
                if (det != null) {
                    det.resetAlignment();
                }
            }
        });
        addBtn(side, "판정 리셋 (R)", new Runnable() {
            public void run() {
                if (det != null) {
                    det.resetJudgment();
                }
            }
        });
        addBtn(side, "임계 −", new Runnable() {
            public void run() {
                if (det != null) {
                    det.setSimThr(det.getSimThr() - 0.02);
                    ApxSettings.get().setSimThr(det.getSimThr());
                }
            }
        });
        addBtn(side, "임계 +", new Runnable() {
            public void run() {
                if (det != null) {
                    det.setSimThr(det.getSimThr() + 0.02);
                    ApxSettings.get().setSimThr(det.getSimThr());
                }
            }
        });
        addBtn(side, "보고서 저장 (D)", new Runnable() {
            public void run() {
                saveReport();
            }
        });
        addBtn(side, "웹캠 캡처 (기준 등록)", new Runnable() {
            public void run() {
                captureRef();
            }
        });

        Button player = new Button(side, SWT.PUSH);
        player.setText("▶ 테스트 화면 열기 (P·R·N·D)");
        player.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        player.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                new TestPlayerDialog(canvas.getShell(), "기어 테스트 화면", new String[][] {
                        { "P", "c:/DEV/apx/hyundai_P.png" },
                        { "R", "c:/DEV/apx/hyundai_R.png" },
                        { "N", "c:/DEV/apx/hyundai_N.png" },
                        { "D", "c:/DEV/apx/hyundai_D.png" },
                }).open();
            }
        });

        Group refBox = new Group(side, SWT.NONE);
        refBox.setText("기준 이미지 (R 체결 정면)");
        refBox.setLayout(new GridLayout(1, false));
        refBox.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        refLabel = new Label(refBox, SWT.WRAP);
        refLabel.setText("(미지정)");
        refLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button pick = new Button(refBox, SWT.PUSH);
        pick.setText("파일…");
        pick.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                pickRef();
            }
        });
    }

    private void addBtn(Composite parent, String text, final Runnable action) {
        Button b = new Button(parent, SWT.PUSH);
        b.setText(text);
        b.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        b.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                action.run();
            }
        });
    }

    // ---- 기준 이미지 ----
    private void pickRef() {
        FileDialog dlg = new FileDialog(canvas.getShell(), SWT.OPEN);
        dlg.setFilterExtensions(new String[] { "*.png;*.jpg;*.jpeg;*.bmp" });
        dlg.setFilterNames(new String[] { "이미지 (*.png;*.jpg;*.jpeg;*.bmp)" });
        String p = dlg.open();
        if (p != null) {
            setRef(p);
        }
    }

    private void setRef(String path) {
        try {
            ApxSettings s = ApxSettings.get();
            double thr = s.getSimThr();
            det = new RoiMatchDetector(path, null, thr);
            det.setRoi(s.getRoi(det.canonWidth(), det.canonHeight()));
            refPath = path;
            refLabel.setText(new File(path).getName());
            canvas.setPlaceholder("설정 웹캠 프레임 재사용 · ROI/임계는 설정과 동기");
            if (!applyingSettings) {
                s.setVisionRefPath(path);
            }
        } catch (Exception ex) {
            det = null;
            refLabel.setText("로드 실패: " + ex.getMessage());
        }
    }

    /** 현재 웹캠 화면을 기준(기대) 이미지로 등록 — 파이썬 웹캠 캡처 대응. ROI·임계는 유지. */
    private void captureRef() {
        java.awt.image.BufferedImage bi = CameraService.get().latest();
        if (bi == null) {
            info("웹캠 프레임이 없습니다 (① 설정에서 카메라를 켜세요)");
            return;
        }
        double thr = (det != null) ? det.getSimThr() : RoiMatchDetector.DEFAULT_SIM;
        int[] roi = ApxSettings.get().getRoi(bi.getWidth(), bi.getHeight());
        det = new RoiMatchDetector(bi, roi, thr);
        refPath = "(웹캠 캡처)";
        refLabel.setText("(웹캠 캡처 화면)");
        canvas.setPlaceholder("웹캠 화면을 기준으로 등록됨 · R 영역 드래그");
    }

    // ---- 드래그 → ROI ----
    private void commitRoiFromDrag() {
        if (det == null) {
            canvas.redraw();
            return;
        }
        int[] a = widgetToCanon(dragX0, dragY0);
        int[] b = widgetToCanon(dragX1, dragY1);
        int y1 = Math.min(a[1], b[1]);
        int y2 = Math.max(a[1], b[1]) + 1;
        int x1 = Math.min(a[0], b[0]);
        int x2 = Math.max(a[0], b[0]) + 1;
        y2 = Math.min(det.canonHeight(), Math.max(y1 + 1, y2));
        x2 = Math.min(det.canonWidth(), Math.max(x1 + 1, x2));
        if (y2 - y1 >= 6 && x2 - x1 >= 6) {
            int[] roi = new int[] { y1, y2, x1, x2 };
            det.setRoi(roi);
            ApxSettings.get().setRoi(roi, det.canonWidth(), det.canonHeight());
        }
        canvas.redraw();
    }

    private int[] widgetToCanon(int wx, int wy) {
        int cw = det != null ? det.canonWidth() : 640;
        int ch = det != null ? det.canonHeight() : 480;
        Point sz = canvas.getSize();
        double s = Math.min(sz.x / (double) cw, sz.y / (double) ch);
        if (s <= 0) {
            return new int[] { 0, 0 };
        }
        int dx = (int) ((sz.x - cw * s) / 2);
        int dy = (int) ((sz.y - ch * s) / 2);
        int cx = (int) Math.round((wx - dx) / s);
        int cy = (int) Math.round((wy - dy) / s);
        cx = Math.max(0, Math.min(cw - 1, cx));
        cy = Math.max(0, Math.min(ch - 1, cy));
        return new int[] { cx, cy };
    }

    // ---- 폴링 ----
    /** 폴링 주기(ms). 프레임(≈33ms)보다 촘촘히 폴링해 폴링 양자화 지연을 줄인다. 중복 프레임은 검출기가 스킵. */
    private static final int POLL_MS = 4;

    private void startPoll() {
        display.timerExec(POLL_MS, new Runnable() {
            public void run() {
                if (canvas == null || canvas.isDisposed()) {
                    return;
                }
                display.timerExec(POLL_MS, this);   // 재예약을 작업 앞으로 → 주기 = max(POLL_MS, 작업시간)
                tick();
            }
        });
    }

    private void tick() {
        java.awt.image.BufferedImage bi = CameraService.get().latest();
        if (det == null || bi == null) {
            return;
        }
        RoiMatchResult r = det.process(bi);      // 내부에서 Mat 변환·해제 (ui는 OpenCV 무의존)
        if (r.hit) {
            double judge = (r.passMs != null) ? r.passMs : Double.NaN;   // 판단 속도(전환지연 ms)
            com.suresofttech.apx.core.sync.SyncBus.get()
                    .mark(com.suresofttech.apx.core.sync.SyncBus.Event.GEAR_R,
                            com.suresofttech.apx.core.sync.SyncBus.now(), judge);   // 동기화 버스(최초만)
        }
        if (r == last) {
            return;                              // 중복 프레임(캐시) → 재그리기·HUD 스킵 (UI 부하↓, 폴링 빠르게 유지)
        }
        last = r;
        canvas.setFrame(r.canonImage);
        metrics.setText(hud(r));
    }

    // ---- 오버레이 (ROI + 드래그 러버밴드) ----
    private void drawOverlay(GC gc, double scale, int dx, int dy) {
        RoiMatchResult r = last;
        if (r != null && r.roi != null) {
            int wx = (int) Math.round(dx + r.roi[2] * scale);
            int wy = (int) Math.round(dy + r.roi[0] * scale);
            int ww = (int) Math.round((r.roi[3] - r.roi[2]) * scale);
            int wh = (int) Math.round((r.roi[1] - r.roi[0]) * scale);
            gc.setForeground(display.getSystemColor(r.hit ? SWT.COLOR_GREEN : SWT.COLOR_YELLOW));
            gc.setLineWidth(2);
            gc.drawRectangle(wx, wy, ww, wh);
        }
        if (dragging) {
            gc.setForeground(display.getSystemColor(SWT.COLOR_CYAN));
            gc.setLineWidth(1);
            gc.drawRectangle(Math.min(dragX0, dragX1), Math.min(dragY0, dragY1),
                    Math.abs(dragX1 - dragX0), Math.abs(dragY1 - dragY0));
        }
    }

    // ---- HUD ----
    private String hud(RoiMatchResult r) {
        if (!"ok".equals(r.state)) {
            return "상태: 정렬 중 (ORB) — 웹캠에 기어봉을 비추세요";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[1] 판정 : %s%n     매칭도(NCC) %.3f  %s  임계 %.2f%n%n",
                r.hit ? "R 체결 → PASS" : "not R → FAIL",
                r.psc, (r.psc >= r.simThr ? "≥" : "<"), r.simThr));
        double camFps = com.suresofttech.apx.core.vision.CameraService.get().fps();
        sb.append(String.format("[측정] 판단 속도 : %.1f ms/frame  ·  프레임 간격 %.0f ms%n"
                + "        카메라 실측 : %.1f fps (≈%.0f ms/프레임)%n%n",
                r.procMs, r.frameGapMs, camFps, camFps > 0 ? 1000.0 / camFps : 0));
        if (r.passMs != null) {
            sb.append(String.format("[2] 전환 지연 : %.0f ms  (frame %.0f + 분석 %.0f)%n%n",
                    r.passMs, r.frameGapMs, r.analysisMs));
        } else {
            sb.append("[2] 전환 지연 : (R 전환 대기)\n\n");
        }
        sb.append(String.format("[3] SSIM (참고) : %.3f%n%n", r.ssim));
        if (r.lockAng != null) {
            sb.append(String.format("[4] ORB 정렬 : inliers %s%n     roll %.1f° / scale %.2f",
                    r.lockInliers, r.lockAng[0], r.lockAng[1]));
        }
        return sb.toString();
    }

    // ---- 보고서 저장 ----
    private void saveReport() {
        RoiMatchResult r = last;
        if (r == null || !"ok".equals(r.state)) {
            info("측정 데이터가 없습니다");
            return;
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        FileDialog dlg = new FileDialog(canvas.getShell(), SWT.SAVE);
        dlg.setFilterExtensions(new String[] { "*.txt" });
        dlg.setFileName("gear_result_" + ts + ".txt");
        dlg.setOverwrite(true);
        String path = dlg.open();
        if (path == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append(" UN R158 기어 R단 검출 (이미지 유사도)\n");
        sb.append("========================================\n");
        sb.append("시각        : ").append(ts).append("\n");
        sb.append("기준 이미지 : ").append(refPath == null ? "-" : new File(refPath).getName()).append("\n");
        sb.append("판정        : ").append(r.hit ? "R 체결 (PASS)" : "not R (FAIL)").append("\n\n");
        sb.append(String.format("[1] 유사도(NCC) : %.3f  [>= %.2f]  (SSIM %.3f)%n", r.psc, r.simThr, r.ssim));
        if (r.passMs != null) {
            sb.append(String.format("[2] 전환 지연 : %.0f ms (frame %.0f + 분석 %.0f)%n",
                    r.passMs, r.frameGapMs, r.analysisMs));
        }
        if (r.lockAng != null) {
            sb.append(String.format("[3] ORB 정렬 : inliers %s, roll %.1f, scale %.2f%n",
                    r.lockInliers, r.lockAng[0], r.lockAng[1]));
        }
        sb.append("========================================\n");
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
            w.write(sb.toString());
            info("보고서 저장됨:\n" + path);
        } catch (Exception ex) {
            info("저장 실패: " + ex.getMessage());
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Exception ignore) {
                    // 무시
                }
            }
        }
    }

    private void info(String msg) {
        MessageBox mb = new MessageBox(canvas.getShell(), SWT.ICON_INFORMATION | SWT.OK);
        mb.setText("기어");
        mb.setMessage(msg);
        mb.open();
    }

    @Override
    public void dispose() {
        ApxSettings.get().removeListener(settingsListener);
        super.dispose();
    }

    @Override
    public void setFocus() {
        if (canvas != null && !canvas.isDisposed()) {
            canvas.setFocus();
        }
    }
}
