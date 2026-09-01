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

import com.suresofttech.apx.core.measure.MeasureSession;



/**
 * 테스트용 사진 플레이어 (파이썬 cluster_player.py / gear_player.py 대응).
 * 별도 창에 테스트 이미지를 크게 띄워 웹캠으로 촬영해 검출을 시험한다.
 * 하단 버튼 또는 숫자 키로 이미지 전환, Space는 자동순환. 비모달이라 앱과 동시 사용.
 */
public final class TestPlayerDialog {

    private static final int ROLE_NONE = 0;
    private static final int ROLE_CLUSTER = 1;
    private static final int ROLE_GEAR = 2;

    /** 이 기어 라벨로 바뀌는 순간이 자극 발사(T0) 시점. */
    private static final String GEAR_TRIGGER_LABEL = "R";
    private static final String CLUSTER_POPUP_LABEL = "팝업";
    private static final String CLUSTER_NORMAL_LABEL = "일반";

    /**
     * 기어 단수 → 클러스터가 보여야 할 화면.
     *
     * <p>실차에서 R을 넣으면 후방 팝업이 뜨고, R을 빼면 팝업이 사라져 일반 화면으로
     * 돌아간다. 자극 창도 같아야 한다 — R만 팝업으로 바꾸고 나머지를 그대로 두면
     * P → R → N 으로 옮겼을 때 클러스터가 팝업에 <b>머물러</b> 실차와 달라진다.
     *
     * <p>여기 없는 라벨은 {@link #CLUSTER_NORMAL_LABEL}로 간다.
     */
    private static String clusterLabelForGear(String gearLabel) {
        return GEAR_TRIGGER_LABEL.equals(gearLabel) ? CLUSTER_POPUP_LABEL : CLUSTER_NORMAL_LABEL;
    }

    private static TestPlayerDialog clusterOpen;
    private static TestPlayerDialog gearOpen;

    private final Shell parent;
    private final String title;
    private final String[][] items; // {라벨, 경로}
    /** 두 창을 묶는 역활 구분 — 정적 필드 대입 순서에 안 흔들리게 인스턴스에 둔다. */
    private final int role;

    private Display display;
    private Shell shell;
    private Canvas view;
    private Button autoBtn;
    private Image[] images;
    private Image image;
    private int index;
    private boolean shown;
    private boolean auto;

    /** items: {라벨, 이미지경로} 배열. */
    public TestPlayerDialog(Shell parent, String title, String[][] items) {
        this(parent, title, items, ROLE_NONE);
    }

    private TestPlayerDialog(Shell parent, String title, String[][] items, int role) {
        this.parent = parent;
        this.title = title;
        this.items = items == null ? new String[0][0] : items;
        this.role = role;
    }

    /** 클러스터 일반/팝업. 이미 열려 있으면 앞으로. */
    public static void openCluster(Shell parent) {
        clusterOpen = openOrRaise(clusterOpen, parent,
                "클러스터 테스트 화면", clusterItems(), ROLE_CLUSTER);
    }

    /** 기어 테스트 화면이 지금 R인가. 측정 시작 때 이미 R이면 T0를 그때 찍는다. */
    public static boolean isGearShowingR() {
        TestPlayerDialog g = gearOpen;
        if (g == null || !g.isOpen() || !g.shown || g.items == null) {
            return false;
        }
        if (g.index < 0 || g.index >= g.items.length || g.items[g.index] == null) {
            return false;
        }
        return GEAR_TRIGGER_LABEL.equals(g.items[g.index][0]);
    }

    /** 기어봉 P/R/N/D. 이미 열려 있으면 앞으로. */
    public static void openGear(Shell parent) {
        gearOpen = openOrRaise(gearOpen, parent,
                "기어 테스트 화면", gearItems(), ROLE_GEAR);
    }

    /**
     * 측정 시작 전 클러스터 창을 연다.
     * 기어가 이미 R이면 팝업을 맞춰 두고, 아니면 일반 화면으로 둔다.
     * 항상 일반으로 되돌리면 R인 채로 시작해도 클러스터만 늦게 뜬다.
     */
    public static void prepareClusterPopup(Shell parent) {
        if (parent == null || parent.isDisposed()) {
            return;
        }
        ensureCluster(parent);
        clusterOpen.setAuto(false);
        if (isGearShowingR()) {
            int popup = clusterOpen.indexOfLabel(CLUSTER_POPUP_LABEL);
            clusterOpen.show(popup >= 0 ? popup : 0);
        } else {
            clusterOpen.show(0);
        }
    }

    /**
     * 기어봉 R <b>검출</b>로 클러스터 팝업을 띄운다 - <b>대타 경로</b>.
     *
     * <p>정상 경로는 기어 테스트 화면이 R로 바뀌는 순간 자극으로 함께 내보내는 것이다
     * ({@code MeasureSession.markStimulus} + {@code syncCluster}). 그 경우
     * {@code MeasureSession} 이 이 콜백을 부르지 않는다.
     *
     * <p>테스트 화면 없이 실제 기어봉을 촬영하는 구성에서만 쓰인다. 이때는 검출 시점에
     * 띄우므로 자극이 우리 판정 지연만큼 늦고, 그만큼 동기 값이 낙관적으로 나온다.
     */
    public static void showClusterPopup(final Shell parent) {
        if (parent == null || parent.isDisposed()) {
            return;
        }
        final Display targetDisplay = parent.getDisplay();
        Runnable action = new Runnable() {
            public void run() {
                if (parent.isDisposed()) {
                    return;
                }
                ensureCluster(parent);
                clusterOpen.setAuto(false);
                clusterOpen.show(clusterOpen.indexOfLabel(CLUSTER_POPUP_LABEL));
            }
        };
        if (Display.getCurrent() == targetDisplay) {
            action.run();
        } else {
            targetDisplay.asyncExec(action);
        }
    }

    /** 클러스터 창 보장 — 이미지는 열 때 한 번만 읽는다. */
    private static void ensureCluster(Shell parent) {
        if (clusterOpen == null || !clusterOpen.isOpen()) {
            clusterOpen = new TestPlayerDialog(
                    parent, "클러스터 테스트 화면", clusterItems(), ROLE_CLUSTER);
            clusterOpen.open();
        }
    }

    private static TestPlayerDialog openOrRaise(TestPlayerDialog existing,
        Shell parent, String title, String[][] items, int role) {
        if (existing != null && existing.isOpen()) {
            existing.activate();
            return existing;
        }
        TestPlayerDialog dlg = new TestPlayerDialog(parent, title, items, role);
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
        preloadImages();
        shell = new Shell(parent, SWT.SHELL_TRIM); // 비모달 / 리사이즈 가능
        shell.setText(title);
        shell.setSize(820, 660);
        shell.setLayout(new GridLayout(1, false));
        // 측정 중에도 닫을 수 있다. 자극용 창을 못 닫게 붙잡으면 조작이 막히고,
        // 필요하면 메뉴에서 다시 열 수 있으므로 닫기를 거부할 이유가 없다.

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
                if (images != null) {
                    for (int i = 0; i < images.length; i++) {
                        if (images[i] != null && !images[i].isDisposed()) {
                            images[i].dispose();
                        }
                    }
                    images = null;
                    image = null;
                }
                if (clusterOpen == TestPlayerDialog.this) {
                    clusterOpen = null;
                }
                if (gearOpen == TestPlayerDialog.this) {
                    gearOpen = null;
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
        if (items.length == 0 || i < 0) {
            return;
        }
        int nextIndex = ((i % items.length) + items.length) % items.length;
        boolean changed = shown && nextIndex != index;
        index = nextIndex;
        image = images != null && index < images.length ? images[index] : null;
        shown = true;
        boolean isGear = (role == ROLE_GEAR);
        boolean gearToR = isGear && changed && GEAR_TRIGGER_LABEL.equals(items[index][0]);
        if (gearToR) {
            // T0 + 기대음. 화면 그리기보다 앞에 두어야 페인트가 T0에 안 얹힌다.
            MeasureSession.get().onTestGearToR();
        }
        if (!shell.isDisposed()) {
            shell.setText(title + " - " + items[index][0]);
            view.redraw();
            view.update();      // 즉시 그린다 — 다음 프레임까지 기다리지 않게
        }
        if (isGear) {
            // 어느 단수로 옮겨도 클러스터를 같이 맞춘다. R이면 팝업, 그 외는 일반.
            syncCluster(clusterLabelForGear(items[index][0]));
        }
    }

    /**
     * 기어 단수 전환과 <b>같은 순간</b> 클러스터를 해당 화면으로 바꾼다.
     *
     * <p>실차에서 기어를 R로 넣으면 클러스터가 즉시 후방 팝업이 되고, R을 빼면
     * 일반 화면으로 돌아가는 것과 같다. 도구의 판정 결과로 띄우는 것이 아니라
     * <b>자극으로 함께 내보내는</b> 것이 핵심이다. 그래야 T0({@code
     * MeasureSession.markStimulus})가 세 채널 공통의 기준점이 되고, 각 채널의
     * {@code 검출 − T0} 가 그 채널의 전체 지연이 된다.
     *
     * <p>클러스터 창이 닫혀 있거나 이미 그 화면이면 아무것도 하지 않는다.
     *
     * @param clusterLabel 클러스터에서 띄울 항목 라벨. 목록에 없으면 무시된다.
     */
    private static void syncCluster(String clusterLabel) {
        TestPlayerDialog c = clusterOpen;
        if (c == null || !c.isOpen()) {
            return;
        }
        int idx = c.indexOfLabel(clusterLabel);
        if (idx < 0 || idx == c.index) {
            return;
        }
        c.setAuto(false);
        c.show(idx);
    }

    /** 라벨로 항목 찾기. 없으면 -1. */
    private int indexOfLabel(String label) {
        for (int i = 0; i < items.length; i++) {
            if (label.equals(items[i][0])) {
                return i;
            }
        }
        return -1;
    }

    /** 테스트 중 전환 경로에서 파일 읽기가 발생하지 않도록 창을 열 때 모두 적재한다. */
    private void preloadImages() {
        images = new Image[items.length];
        for (int i = 0; i < items.length; i++) {
            try {
                if (new File(items[i][1]).isFile()) {
                    images[i] = new Image(display, items[i][1]);
                }
            } catch (Exception ex) {
                images[i] = null;
            }
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
