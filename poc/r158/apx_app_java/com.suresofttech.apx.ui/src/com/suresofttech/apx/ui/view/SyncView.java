package com.suresofttech.apx.ui.view;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.audio.AudioPlayer;
import com.suresofttech.apx.core.sync.SyncBus;
import com.suresofttech.apx.core.sync.SyncBus.Event;

/**
 * ⑤ 동기화 View — R158 정의(킥오프 p6 / R158_학습요약.md)의 세 시간을 구분해 판정. T0 = 기어 R 검출.
 * <ul>
 *   <li><b>판단 속도</b>(도구): 이벤트 발생→도구 검출 지연 ≤ 설정값(기본 30ms). 각 탭에서 최적화한 값.</li>
 *   <li><b>응답시간</b>(물리/차량): 각 이벤트 onset − T0 ≤ 0.6s.</li>
 *   <li><b>동기 오차</b>: 팝업·음향·CAN onset 의 max − min ≤ 설정값.</li>
 * </ul>
 * 크로스모달 지연차는 추후 캘리브레이션 보정 예정.
 */
public class SyncView extends ViewPart {

    private static final double RESP_TOL_MS = 600.0;          // 응답시간 한계 0.6s (물리, 고정)
    private static final Event BASE = Event.GEAR_R;           // T0 기준
    private static final Event[] ALL =
            { Event.GEAR_R, Event.CLUSTER_POPUP, Event.BEEP, Event.CAN };
    private static final String[] NAMES = { "기어 R (T0)", "클러스터 팝업", "PDW 경고음", "CAN 신호" };
    // 동기 대상 = 화면 출력(기어봉 R 표시 / 클러스터 팝업) + 음향 (+ CAN 추후). 검출된 것끼리 max−min.
    private static final Event[] SYNC = { Event.GEAR_R, Event.CLUSTER_POPUP, Event.BEEP };

    private Display display;
    private Label head;
    private Label[] judgeVal;     // 판단 속도(도구 검출 지연)
    private Label[] judgeVerdict;
    private Label[] respVal;      // 응답시간(T0→각)
    private Label[] respVerdict;
    private Label syncVal;        // 동기 오차(max−min)
    private Label syncVerdict;
    private Spinner tolSpin;      // 허용 오차(ms) — 판단·동기 공용
    private Spinner freqCalSpin;  // 캘리브 발사 톤 주파수(Hz)
    private Label calVal;         // 왕복 지연(발사→검출)
    private Color okColor;
    private Color ngColor;

    private static final int PLAY_SR = 44100;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        okColor = display.getSystemColor(SWT.COLOR_DARK_GREEN);
        ngColor = display.getSystemColor(SWT.COLOR_RED);
        parent.setLayout(new GridLayout(1, false));

        head = new Label(parent, SWT.WRAP);
        head.setText("동기화 측정 — T0 = 기어 R 검출");
        head.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        Composite tolRow = new Composite(parent, SWT.NONE);
        tolRow.setLayout(new GridLayout(3, false));
        tolRow.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        new Label(tolRow, SWT.NONE).setText("허용 오차(판단·동기) ≤");
        tolSpin = new Spinner(tolRow, SWT.BORDER);
        tolSpin.setMinimum(1);
        tolSpin.setMaximum(500);
        tolSpin.setIncrement(5);
        tolSpin.setSelection(30);            // 기본 30ms — 시험모드에서 40 등으로 조절
        tolSpin.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                refresh();
            }
        });
        new Label(tolRow, SWT.NONE).setText("ms");

        // ── 이벤트별: 판단 속도(도구, ≤설정) + 응답시간(물리, ≤0.6s) ──
        Group g = new Group(parent, SWT.NONE);
        g.setText("이벤트별 — 판단 속도(도구 검출) / 응답시간(T0 대비)");
        g.setLayout(new GridLayout(5, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        header(g, "이벤트");
        header(g, "판단속도");
        header(g, "판정(≤설정)");
        header(g, "응답(T0→)");
        header(g, "판정(≤0.6s)");

        judgeVal = new Label[ALL.length];
        judgeVerdict = new Label[ALL.length];
        respVal = new Label[ALL.length];
        respVerdict = new Label[ALL.length];
        for (int i = 0; i < ALL.length; i++) {
            new Label(g, SWT.NONE).setText(NAMES[i]);
            judgeVal[i] = mkVal(g, 90);
            judgeVerdict[i] = mkVal(g, 100);
            respVal[i] = mkVal(g, 90);
            respVerdict[i] = mkVal(g, 90);
        }

        // ── 동기 오차 (팝업·음향·CAN max − min ≤ 설정값) — 진짜 PASS 조건 ──
        Group gs = new Group(parent, SWT.NONE);
        gs.setText("동기 오차 (발생시각=PASS−판단속도, 기어R·팝업·음향 max−min · CAN 추후)");
        gs.setLayout(new GridLayout(2, false));
        gs.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        syncVal = mkVal(gs, 140);
        syncVerdict = mkVal(gs, 160);

        // ── 캘리브 (음향 발사 → 검출 왕복 지연) ──
        Group gc = new Group(parent, SWT.NONE);
        gc.setText("캘리브 (음향 발사 → 검출 왕복 지연 = 출력버퍼+음향경로+D_mic+판단)");
        gc.setLayout(new GridLayout(5, false));
        gc.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        new Label(gc, SWT.NONE).setText("발사 주파수");
        freqCalSpin = new Spinner(gc, SWT.BORDER);
        freqCalSpin.setMinimum(200);
        freqCalSpin.setMaximum(8000);
        freqCalSpin.setIncrement(100);
        freqCalSpin.setSelection(2000);
        new Label(gc, SWT.NONE).setText("Hz");
        Button fire = new Button(gc, SWT.PUSH);
        fire.setText("🔊 음향 발사");
        fire.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                double emit = AudioPlayer.playTone(freqCalSpin.getSelection(), 0.15, PLAY_SR);
                SyncBus.get().setAudioEmit(emit);   // 발사시각 기록 — BEEP은 마이크 검출 시 실제 시각으로 기록됨
            }
        });
        calVal = mkVal(gc, 200);

        Button reset = new Button(parent, SWT.PUSH);
        reset.setText("새 측정 (리셋)");
        reset.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        reset.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                SyncBus.get().reset();
                refresh();
            }
        });

        Label note = new Label(parent, SWT.WRAP);
        note.setText("※ 판단속도=도구가 이벤트를 검출하는 지연 / 응답=T0 대비 물리 지연 / 동기=출력 이벤트 상호 간격(max−min). "
                + "크로스모달 지연차는 캘리브레이션 보정 후 정확.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        refresh();
        scheduleTick();
    }

    private static void header(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
    }

    private Label mkVal(Composite parent, int width) {
        Label l = new Label(parent, SWT.NONE);
        GridData d = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        d.widthHint = width;
        l.setLayoutData(d);
        return l;
    }

    private void scheduleTick() {
        if (display == null || display.isDisposed()) {
            return;
        }
        display.timerExec(100, new Runnable() {
            public void run() {
                if (head == null || head.isDisposed()) {
                    return;
                }
                display.timerExec(100, this);
                refresh();
            }
        });
    }

    private void refresh() {
        if (head == null || head.isDisposed()) {
            return;
        }
        SyncBus bus = SyncBus.get();
        int tol = tolSpin.getSelection();

        for (int i = 0; i < ALL.length; i++) {
            Event e = ALL[i];
            // 판단 속도(도구 검출 지연)
            double judge = bus.judgeMsOf(e);
            if (bus.has(e) && !Double.isNaN(judge)) {
                judgeVal[i].setText(String.format("%.0f ms", judge));
                boolean ok = judge <= tol;
                setVerdict(judgeVerdict[i], ok ? ("≤" + tol + " ✅") : (">" + tol + " ❌"), ok, true);
            } else {
                judgeVal[i].setText(bus.has(e) ? "—" : "대기");
                setVerdict(judgeVerdict[i], "—", false, false);
            }
            // 응답시간(T0 → 각). 기어 R 자신은 기준.
            if (e == BASE) {
                respVal[i].setText("(기준)");
                setVerdict(respVerdict[i], "", false, false);
            } else {
                double off = bus.offsetMs(BASE, e);
                if (Double.isNaN(off)) {
                    respVal[i].setText("대기");
                    setVerdict(respVerdict[i], "—", false, false);
                } else {
                    respVal[i].setText(String.format("%+.0f ms", off));
                    boolean ok = off >= 0 && off <= RESP_TOL_MS;
                    setVerdict(respVerdict[i], ok ? "≤0.6s ✅" : ">0.6s ❌", ok, true);
                }
            }
        }

        // 동기 오차 (검출된 다운스트림 max−min ≤ 설정값) — 진짜 PASS 조건
        double spread = spreadMs();
        if (Double.isNaN(spread)) {
            syncVal.setText("대기 (2개↑ 필요)");
            setVerdict(syncVerdict, "—", false, false);
        } else {
            syncVal.setText(String.format("스프레드 %.1f ms", spread));
            boolean ok = spread <= tol;
            setVerdict(syncVerdict, ok ? ("≤" + tol + "ms  PASS ✅") : (">" + tol + "ms  FAIL ❌"), ok, true);
        }

        // 캘리브: 음향 발사(emit) → 마이크 검출(BEEP) 왕복 지연
        double emit = bus.audioEmit();
        double beep = bus.stampOf(Event.BEEP);
        if (!Double.isNaN(emit) && !Double.isNaN(beep) && beep >= emit) {
            double rt = (beep - emit) * 1000.0;                 // 출력버퍼+음향경로+D_mic+판단
            double judge = bus.judgeMsOf(Event.BEEP);
            String hw = Double.isNaN(judge)
                    ? "" : String.format("  (판단 %.0f 제외 ≈ HW %.0f)", judge, rt - judge);
            calVal.setText(String.format("왕복 %.0f ms%s", rt, hw));
        } else if (!Double.isNaN(emit)) {
            calVal.setText("발사됨 · 검출 대기");
        } else {
            calVal.setText("—");
        }
    }

    private void setVerdict(Label l, String text, boolean ok, boolean colored) {
        l.setText(text);
        l.setForeground(!colored ? null : (ok ? okColor : ngColor));
    }

    /**
     * 동기 대상(비전 팝업·음향)의 <b>발생 시각</b> max − min (ms). 2개 미만이면 NaN.
     * <p>L1 보정: 발생 ≈ PASS − 판단속도. 도구 검출 지연차를 빼서 "실제 발생 동기"에 근사.
     * (하드웨어 D_cap/D_mic는 미보정 — L2 캘리브레이션 예정.)
     */
    private double spreadMs() {
        SyncBus bus = SyncBus.get();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int n = 0;
        for (Event e : SYNC) {
            double t = bus.stampOf(e);
            if (!Double.isNaN(t)) {
                double judge = bus.judgeMsOf(e);
                double occ = Double.isNaN(judge) ? t : (t - judge / 1000.0);   // 발생 ≈ PASS − 판단속도
                min = Math.min(min, occ);
                max = Math.max(max, occ);
                n++;
            }
        }
        return (n < 2) ? Double.NaN : (max - min) * 1000.0;
    }

    @Override
    public void setFocus() {
        if (head != null && !head.isDisposed()) {
            head.setFocus();
        }
    }
}
