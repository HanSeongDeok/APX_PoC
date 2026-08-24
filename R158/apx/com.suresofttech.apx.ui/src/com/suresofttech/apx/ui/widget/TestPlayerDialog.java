package com.suresofttech.apx.ui.widget;

import java.io.File;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

/**
 * 테스트용 사진 플레이어 (파이썬 cluster_player.py / gear_player.py 대응).
 * 별도 창에 테스트 이미지를 크게 띄워 웹캠으로 촬영해 검출을 시험한다.
 * 하단 버튼 또는 숫자 키로 이미지 전환, Space는 자동순환. 비모달이라 앱과 동시 사용.
 */
public final class TestPlayerDialog {

    private static TestPlayerDialog clusterOpen;
    private static TestPlayerDialog gearOpen;

    private final Shell parent;
    private final String title;
    private final String[][] items; // {라벨, 경로}

    private Display display;
    private Shell shell;
    private Canvas view;
    private Button autoBtn;
    private Image image;
    private int index;
    private boolean auto;

    /** items: {라벨, 이미지경로} 배열. */
    public TestPlayerDialog(Shell parent, String title, String[][] items) {
        this.parent = parent;
        this.title = title;
        this.items = items == null ? new String[0][0] : items;
    }

    /** 클러스터 일반/팝업. 이미 열려 있으면 앞으로. */
    public static void openCluster(Shell parent) {
        clusterOpen = openOrRaise(clusterOpen, parent, "클러스터 테스트 화면", clusterItems());
    }

    /** 기어봉 P/R/N/D. 이미 열려 있으면 앞으로. */
    public static void openGear(Shell parent) {
        gearOpen = openOrRaise(gearOpen, parent, "기어 테스트 화면", gearItems());
    }

    private static TestPlayerDialog openOrRaise(TestPlayerDialog existing,
        Shell parent, String title, String[][] items) {
        if (existing != null && existing.isOpen()) {
            existing.activate();
            return existing;
        }
        TestPlayerDialog dlg = new TestPlayerDialog(parent, title, items);
        dlg.open();
        return dlg;
    }

    public static String[][] clusterItems() {
        String dir = imageDir();
        return new String[][] {
            { "일반", new File(dir, "hyundai_cluster.png").getAbsolutePath() },
            { "팝업", new File(dir, "hyundai_cluster_popup.png").getAbsolutePath() },
        };
    }

    public static String[][] gearItems() {
        String dir = imageDir();
        return new String[][] {
            { "P", new File(dir, "hyundai_P.png").getAbsolutePath() },
            { "R", new File(dir, "hyundai_R.png").getAbsolutePath() },
            { "N", new File(dir, "hyundai_N.png").getAbsolutePath() },
            { "D", new File(dir, "hyundai_D.png").getAbsolutePath() },
        };
    }

    /** {@code APX_TEST_IMAGES} 또는 {@code c:/DEV/apx}. */
    public static String imageDir() {
        String env = System.getenv("APX_TEST_IMAGES");
        if (env != null && !env.trim().isEmpty() && new File(env).isDirectory()) {
            return env;
        }
        File def = new File("c:/DEV/apx");
        if (def.isDirectory()) {
            return def.getAbsolutePath();
        }
        return new File(".").getAbsolutePath();
    }

    public boolean isOpen() {
        return shell != null && !shell.isDisposed();
    }

    public void activate() {
        if (isOpen()) {
            shell.setActive();
        }
    }

    public void open() {
        if (isOpen()) {
            activate();
            return;
        }
        display = parent.getDisplay();
        shell = new Shell(parent, SWT.SHELL_TRIM); // 비모달 / 리사이즈 가능
        shell.setText(title);
        shell.setSize(820, 660);
        shell.setLayout(new GridLayout(1, false));

        view = new Canvas(shell, SWT.DOUBLE_BUFFERED);
        view.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        view.addPaintListener(new PaintListener() {
                public void paintControl(PaintEvent e) {
                    paint(e.gc);
            }
        });

        Composite btns = new Composite(shell, SWT.NONE);
        btns.setLayout(new GridLayout(items.length + 1, false));
        btns.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            Button b = new Button(btns, SWT.PUSH);
            b.setText((i + 1) + ": " + items[i][0]);
            b.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            b.addSelectionListener(new SelectionAdapter() {
                    public void widgetSelected(SelectionEvent e) {
                        setAuto(false);
                        show(idx);
                }
            });
        }
        autoBtn = new Button(btns, SWT.TOGGLE);
        autoBtn.setText("자동순환 (Space)");
        autoBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        autoBtn.addSelectionListener(new SelectionAdapter() {
                public void widgetSelected(SelectionEvent e) {
                    setAuto(autoBtn.getSelection());
            }
        });

        Label hint = new Label(shell, SWT.CENTER);
        hint.setText("단축키: 1~" + items.length + " 선택 / Space 자동순환 / Q / Esc 닫기");
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        final Listener keyFilter = new Listener() {
            public void handleEvent(Event e) {
                if (shell == null || shell.isDisposed()) {
                    return;
                }
                Control fc = display.getFocusControl();
                if (fc == null || fc.getShell() != shell) {
                    return;
                }
                if (e.keyCode == SWT.ESC || Character.toLowerCase(e.character) == 'q') {
                    shell.close();
                    e.doit = false;
                } else if (e.character == ' ') {
                    setAuto(!auto);
                    e.doit = false;
                } else if (e.character >= '1' && e.character <= '9') {
                    int idx = e.character - '1';
                    if (idx < items.length) {
                        setAuto(false);
                        show(idx);
                        e.doit = false;
                    }
                }
            }
        };
        display.addFilter(SWT.KeyDown, keyFilter);

        shell.addDisposeListener(new DisposeListener() {
                public void widgetDisposed(DisposeEvent e) {
                    display.removeFilter(SWT.KeyDown, keyFilter);
                    if (image != null) {
                        image.dispose();
                        image = null;
                }
            }
        });

        show(0);
        shell.open();
    }

    private void setAuto(boolean on) {
        auto = on;
        if (autoBtn != null && !autoBtn.isDisposed()) {
            autoBtn.setSelection(on);
        }
        if (auto) {
            scheduleCycle();
        }
    }

    private void show(int i) {
        if (items.length == 0) {
            return;
        }
        index = ((i % items.length) + items.length) % items.length;
        Image old = image;
        Image next = null;
        try {
            if (new File(items[index][1]).exists()) {
                next = new Image(display, items[index][1]);
            }
        } catch (Exception ex) {
            next = null;
        }
        image = next;
        if (old != null) {
            old.dispose();
        }
        if (!shell.isDisposed()) {
            shell.setText(title + " - " + items[index][0]);
            view.redraw();
        }
    }

    private void scheduleCycle() {
        display.timerExec(3000, new Runnable() {
                public void run() {
                    if (shell == null || shell.isDisposed() || !auto) {
                        return;
                }
                    show(index + 1);
                    display.timerExec(3000, this);
            }
        });
    }

    private void paint(GC gc) {
        Point sz = view.getSize();
        gc.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        gc.fillRectangle(0, 0, sz.x, sz.y);
        if (image == null) {
            gc.setForeground(display.getSystemColor(SWT.COLOR_GRAY));
            String msg = "이미지 없음: " + (items.length > 0 ? items[index][1] : "-");
            Point ext = gc.textExtent(msg);
            gc.drawText(msg, (sz.x - ext.x) / 2, (sz.y - ext.y) / 2, true);
            return;
        }
        Rectangle b = image.getBounds();
        double s = Math.min(sz.x / (double) b.width, sz.y / (double) b.height);
        int dw = Math.max(1, (int) (b.width * s));
        int dh = Math.max(1, (int) (b.height * s));
        gc.setInterpolation(SWT.HIGH);
        gc.drawImage(image, 0, 0, b.width, b.height, (sz.x - dw) / 2, (sz.y - dh) / 2, dw, dh);
    }
}
