package com.suresofttech.apx.ui.widget.settings.vision;

import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.swt.graphics.RGB;

/**
 * 클라이언트가 정한 ROI 표시 스타일의 <b>공용 기준</b> — 설정 화면과 라이브 모니터가 같이 쓴다.
 *
 * <p>클라이언트가 설정 화면에서 ROI 색·굵기를 커스텀하면 모니터 화면도 같아야 한다.
 * 그런데 스타일은 SWT {@code RGB}라 {@link com.suresofttech.apx.core.config.ApxSettings}
 * (core, SWT 무의존)에 둘 수 없다. 그래서 ui 모듈에 이 홀더를 둔다.
 *
 * <p>사용법 — 클라이언트가 시작 시 한 번 심는다.
 * <pre>
 * RoiNcc.Style st = new RoiNcc.Style();
 * st.hit = new RGB(0, 200, 0);
 * st.roiLineWidth = 3;
 * RoiStyles.set(st);
 * </pre>
 * 그 뒤 {@code new RoiNcc(canvas, null, ch)} 로 만든 오버레이는 이 스타일을 쓰고,
 * 나중에 {@link #set} 으로 바뀌면 <b>이미 만들어진 것들도 따라 바뀐다</b>. 설정 View와
 * 모니터 View 중 무엇이 먼저 생성되든 결과가 같아야 하므로 통지가 필요하다.
 */
public final class RoiStyles {

    public interface Listener {
        void onRoiStyleChanged(RoiNcc.Style style);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<Listener>();

    private static volatile RoiNcc.Style shared = new RoiNcc.Style();

    private RoiStyles() {
    }

    /** 현재 공용 스타일 사본. 호출자가 고쳐도 원본에 영향 없다. */
    public static RoiNcc.Style get() {
        return copy(shared);
    }

    /** 공용 스타일 지정 + 통지. null이면 기본값으로 되돌린다. */
    public static void set(RoiNcc.Style style) {
        shared = (style == null) ? new RoiNcc.Style() : copy(style);
        for (Listener l : LISTENERS) {
            try {
                l.onRoiStyleChanged(copy(shared));
            } catch (RuntimeException ex) {
                // 한 구독자가 죽어도 나머지는 통지받아야 한다
            }
        }
    }

    public static void addListener(Listener l) {
        if (l != null) {
            LISTENERS.add(l);
        }
    }

    public static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    private static RoiNcc.Style copy(RoiNcc.Style s) {
        RoiNcc.Style out = new RoiNcc.Style();
        if (s == null) {
            return out;
        }
        out.hit = copy(s.hit, out.hit);
        out.miss = copy(s.miss, out.miss);
        out.drag = copy(s.drag, out.drag);
        out.roiLineWidth = s.roiLineWidth;
        out.dragThickness = s.dragThickness;
        return out;
    }

    private static RGB copy(RGB src, RGB fallback) {
        return (src == null) ? fallback : new RGB(src.red, src.green, src.blue);
    }
}
