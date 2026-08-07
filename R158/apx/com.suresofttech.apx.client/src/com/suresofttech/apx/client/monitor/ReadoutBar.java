package com.suresofttech.apx.client.monitor;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;

/**
 * 모니터 판독값 표시 공통 골격 — 굵은 상태 헤더 + "이름 : 값" 2열 격자.
 *
 * <p>세션·워크벤치를 모르고 core POJO/원시값만 받는다(위젯 라이브러리 승격용).
 * 값이 실제로 바뀐 틱에만 다시 배치해 폴링(≈80ms)에서도 깜빡임·부하가 없다.
 */
public abstract class ReadoutBar extends Composite {

    /** 값 미정 표기. */
    public static final String DASH = "—";

    /** 헤더/값 색 상태 — 대기(회색). */
    public static final int STATE_IDLE = 0;
    /** 진행 중(주황). */
    public static final int STATE_BUSY = 1;
    /** 충족·합격(초록). */
    public static final int STATE_PASS = 2;
    /** 미달·불합격(빨강). */
    public static final int STATE_FAIL = 3;

    private final Font boldFont;
    private final Label headLbl;
    private final Composite fields;
    private boolean dirty;

    protected ReadoutBar(Composite parent, String title) {
        super(parent, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 4;
        gl.marginHeight = 2;
        gl.verticalSpacing = 2;
        setLayout(gl);

        boldFont = deriveBold();
        headLbl = new Label(this, SWT.NONE);
        headLbl.setFont(boldFont);
        headLbl.setText(title);
        headLbl.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        fields = new Composite(this, SWT.NONE);
        GridLayout fl = new GridLayout(2, false);
        fl.marginWidth = 0;
        fl.marginHeight = 0;
        fl.horizontalSpacing = 6;
        fl.verticalSpacing = 1;
        fields.setLayout(fl);
        fields.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                if (boldFont != null && !boldFont.isDisposed()) {
                    boldFont.dispose();
                }
            }
        });
    }

    /** "이름 : 값" 한 줄 추가 — 반환된 Label이 값 칸. */
    protected Label field(String name) {
        Label n = new Label(fields, SWT.NONE);
        n.setText(name);
        n.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Label v = new Label(fields, SWT.NONE);
        v.setText(DASH);
        v.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return v;
    }

    /** 상태 헤더 — 색은 {@code STATE_*}. */
    protected void head(String text, int state) {
        setText(headLbl, text, state);
    }

    /** 값 칸 갱신(기본색). */
    protected void set(Label v, String text) {
        setText(v, text, STATE_IDLE);
    }

    /**
     * 값 칸 갱신 + 색. 좁은 View에서 잘릴 수 있어 툴팁에 전문을 남긴다.
     * 실제로 바뀐 경우만 dirty로 표시 — {@link #commit()}에서 한 번만 배치.
     */
    protected void set(Label v, String text, int state) {
        setText(v, text, state);
    }

    /** 값 미정으로 되돌림. */
    protected void clear(Label v) {
        setText(v, DASH, STATE_IDLE);
    }

    /** 이번 갱신에서 바뀐 값이 있으면 다시 배치. 매 update 끝에 호출. */
    protected void commit() {
        if (!dirty || isDisposed()) {
            return;
        }
        dirty = false;
        fields.layout(true);
        layout(true);
    }

    private void setText(Label l, String text, int state) {
        if (l == null || l.isDisposed()) {
            return;
        }
        String t = (text == null || text.isEmpty()) ? DASH : text;
        if (!t.equals(l.getText())) {
            l.setText(t);
            l.setToolTipText(t);
            dirty = true;
        }
        l.setForeground(stateColor(state));
    }

    private org.eclipse.swt.graphics.Color stateColor(int state) {
        switch (state) {
            case STATE_PASS:
                return getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN);
            case STATE_FAIL:
                return getDisplay().getSystemColor(SWT.COLOR_RED);
            case STATE_BUSY:
                return getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW);
            default:
                return null;   // 위젯 기본 전경색
        }
    }

    private Font deriveBold() {
        FontData[] fd = getFont().getFontData();
        for (int i = 0; i < fd.length; i++) {
            fd[i].setStyle(fd[i].getStyle() | SWT.BOLD);
        }
        return new Font(getDisplay(), fd);
    }

    // ── 포맷 헬퍼 ────────────────────────────────────────────────

    protected static String f1(double v) {
        return String.format("%.1f", Double.valueOf(v));
    }

    protected static String f2(double v) {
        return String.format("%.2f", Double.valueOf(v));
    }

    /** 임계 대비 표기: {@code 0.87 / 임계 0.60 충족}. */
    protected static String vsThr(double value, double thr) {
        return f2(value) + " / 임계 " + f2(thr) + (value >= thr ? "  충족" : "  미달");
    }

    protected static int thrState(double value, double thr) {
        return value >= thr ? STATE_PASS : STATE_FAIL;
    }

    /** 파일 경로에서 이름만. null/빈값이면 {@link #DASH}. */
    protected static String baseName(String path) {
        if (path == null || path.isEmpty()) {
            return DASH;
        }
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 && i + 1 < path.length() ? path.substring(i + 1) : path;
    }
}
