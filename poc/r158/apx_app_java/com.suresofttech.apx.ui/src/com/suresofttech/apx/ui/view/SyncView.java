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
import org.eclipse.swt.widgets.Monitor;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.ui.part.ViewPart;

import java.awt.image.BufferedImage;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

import com.suresofttech.apx.core.sync.Calibration;
import com.suresofttech.apx.core.sync.Calibration.Modality;
import com.suresofttech.apx.core.sync.SyncBus;
import com.suresofttech.apx.core.sync.SyncBus.Event;
import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.core.vision.FlashProbe;

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
    private Label syncRawVal;     // 원(raw) 스프레드 — L1만
    private Color okColor;
    private Color ngColor;

    private final Calibration cal = new Calibration();
    private Spinner nSpin;                     // 수집 반복 수(공용)
    private Label dcapVal;                     // 비전 D_cap 절대 지연
    private Label dmicVal;                     // 음향 D_mic 절대 지연(=D_cap+Δ)

    // ── ① 비전 D_cap 화면플래시 보정 (무음) ──
    private volatile boolean visionRunning;
    private volatile int visionLeft;
    private volatile int visionHits;          // 검출 성공 수(진단)
    private volatile double visionMaxDelta;   // 관측된 최대 밝기 변화(진단)
    private Shell flashShell;
    private static final double FLASH_DELTA = 15.0;    // 밝기 상승 임계(카메라가 화면 일부만 봐도 잡히게)
    private static final long FLASH_TIMEOUT_MS = 1200;
    private static final long FLASH_BASE_MS = 250;
    private static final long FLASH_GAP_MS = 500;

    // ── ② 동시자극 보정 (폰 플래시+삐 → Δ, D_mic=D_cap+Δ) ──
    private Label coincVal;                    // 진행/Δ
    private volatile boolean coincRunning;
    private volatile int coincPairs;
    private volatile String coincStatus = "";
    private static final double PAIR_WIN_S = 0.4;   // 플래시-삐 쌍 인정 시간창
    private static final double REFRACT_S = 0.5;    // 재검출 금지 간격
    private static final double FLASH_DELTA2 = 15;  // 밝기 상승 임계(폰 플래시)
    private static final double BEEP_AMP_MIN = 0.06;// 삐 최소 진폭

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
        gs.setText("동기 오차 (발생시각 max−min · 기어R·팝업·음향 · CAN 추후)");
        gs.setLayout(new GridLayout(2, false));
        gs.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        new Label(gs, SWT.NONE).setText("raw (L1: 판단속도만 보정)");
        syncRawVal = mkVal(gs, 200);
        new Label(gs, SWT.NONE).setText("보정 (L2: +물리지연 제거)");
        syncVal = mkVal(gs, 140);
        new Label(gs, SWT.NONE).setText("판정");
        syncVerdict = mkVal(gs, 200);

        // ── L2 물리지연 보정 : ① 화면플래시=D_cap → ② 동시자극=D_mic(=D_cap+Δ) ──
        Group gp = new Group(parent, SWT.NONE);
        gp.setText("L2 물리지연 보정 (① 화면플래시=D_cap → ② 폰 동시자극=D_mic · median/MAD)");
        gp.setLayout(new GridLayout(4, false));
        gp.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(gp, SWT.NONE).setText("반복 N");
        nSpin = new Spinner(gp, SWT.BORDER);
        nSpin.setValues(8, 1, 50, 0, 1, 5);
        new Label(gp, SWT.NONE).setText("");   // 폰 화면·음향 지연은 거의 상쇄 → 보정 불필요
        new Label(gp, SWT.NONE).setText("");

        // ① 비전 D_cap
        Button flashBtn = new Button(gp, SWT.PUSH);
        flashBtn.setText("🎥 ① D_cap 보정 (화면플래시)");
        flashBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                startVisionCal();
            }
        });
        new Label(gp, SWT.NONE).setText("비전 D_cap");
        dcapVal = mkVal(gp, 180);
        new Label(gp, SWT.NONE).setText("(웹캠을 이 화면으로)");

        // ② 음향 D_mic
        Button coinc = new Button(gp, SWT.PUSH);
        coinc.setText("🎬 ② D_mic 보정 (폰 동시자극)");
        coinc.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                startCoincidenceCal();
            }
        });
        new Label(gp, SWT.NONE).setText("음향 D_mic");
        dmicVal = mkVal(gp, 180);
        coincVal = mkVal(gp, 240);

        Button reset = new Button(parent, SWT.PUSH);
        reset.setText("새 측정 (리셋)");
        reset.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        reset.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                coincRunning = false;      // 동시자극 보정 중단
                visionRunning = false;     // 화면플래시 보정 중단
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

        // 동기 오차 — raw(L1) + 보정(L2). L2 = 각 모달 물리지연 상수 추가 제거.
        double rawSpread = spreadMs(false);
        syncRawVal.setText(Double.isNaN(rawSpread) ? "대기 (2개↑)"
                : String.format("스프레드 %.1f ms", rawSpread));
        double spread = spreadMs(true);
        if (Double.isNaN(spread)) {
            syncVal.setText("대기 (2개↑ 필요)");
            setVerdict(syncVerdict, "—", false, false);
        } else {
            syncVal.setText(String.format("스프레드 %.1f ms", spread));
            boolean ok = spread <= tol;
            setVerdict(syncVerdict, ok ? ("≤" + tol + "ms  PASS ✅") : (">" + tol + "ms  FAIL ❌"), ok, true);
        }

        // ① D_cap (비전) / ② D_mic (음향) 절대 지연
        dcapVal.setText(visionRunning
                ? String.format("남은%d · 검출%d · Δ밝기max %.0f", visionLeft, visionHits, visionMaxDelta)
                : (cal.count(Modality.VISION) == 0 && visionMaxDelta > 0
                        ? String.format("미검출 (Δ밝기max %.0f, 임계 %.0f)", visionMaxDelta, FLASH_DELTA)
                        : calText(Modality.VISION)));
        dmicVal.setText(calText(Modality.AUDIO));
        // 동시자극 진행/Δ
        coincVal.setText(coincRunning ? coincStatus : (coincStatus.isEmpty() ? "" : coincStatus));
    }

    /** 모달 상수±지터 표시 문자열. */
    private String calText(Modality m) {
        double c = cal.constMs(m);
        if (Double.isNaN(c)) {
            return "미측정";
        }
        double j = cal.jitterMs(m);
        String js = Double.isNaN(j) ? "" : String.format(" ±%.0f", j);
        return String.format("%.0f ms%s  (n=%d)", c, js, cal.count(m));
    }

    /**
     * ① 비전 D_cap 화면플래시 보정 — 워커에서 N회: 기준밝기 → 흰 플래시(발생시각) →
     * 카메라 밝기 상승 검출 → D_cap=검출−발생. 무음. 전제: 카메라 오픈 + 웹캠을 이 화면으로.
     */
    private void startVisionCal() {
        if (visionRunning || coincRunning) {
            return;
        }
        final CameraService camsvc = CameraService.get();
        if (!camsvc.isOpen()) {
            dcapVal.setText("카메라 미오픈 (① 설정)");
            return;
        }
        cal.clear(Modality.VISION);
        visionRunning = true;
        visionLeft = nSpin.getSelection();
        visionHits = 0;
        visionMaxDelta = 0;
        final int n = visionLeft;
        final FlashProbe probe = new FlashProbe(FLASH_DELTA);
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < n && visionRunning; i++) {
                        runOneFlash(camsvc, probe);
                        visionLeft--;
                        sleepMs(FLASH_GAP_MS);
                    }
                } finally {
                    visionRunning = false;
                    uiExec(new Runnable() {
                        public void run() {
                            hideFlash();
                        }
                    });
                }
            }
        }, "apx-vision-cal");
        t.setDaemon(true);
        t.start();
    }

    /** 플래시 1회: 기준밝기 → 플래시 표시(발생시각) → 상승 검출 → D_cap 표본. 워커 전용. */
    private void runOneFlash(final CameraService camsvc, FlashProbe probe) {
        probe.arm();
        uiExec(new Runnable() {
            public void run() {
                hideFlash();
            }
        });
        sleepMs(120);
        long tb = System.nanoTime();
        while ((System.nanoTime() - tb) / 1_000_000 < FLASH_BASE_MS && visionRunning) {
            probe.observeBaseline(camsvc.latest());
            sleepMs(15);
        }
        uiExecSync(new Runnable() {
            public void run() {
                showFlash();
            }
        });
        double flashT = SyncBus.now();
        long t0 = System.nanoTime();
        boolean got = false;
        while ((System.nanoTime() - t0) / 1_000_000 < FLASH_TIMEOUT_MS && visionRunning) {
            BufferedImage f = camsvc.latest();
            if (f != null) {
                double delta = FlashProbe.meanLuma(f) - probe.baseline();   // 진단: 밝기 변화
                if (delta > visionMaxDelta) {
                    visionMaxDelta = delta;
                }
            }
            if (probe.detect(camsvc.latest(), SyncBus.now())) {
                got = true;
                break;
            }
            sleepMs(5);
        }
        uiExec(new Runnable() {
            public void run() {
                hideFlash();
            }
        });
        if (got) {
            visionHits++;
            double hw = (probe.onsetT() - flashT) * 1000.0;   // 화면출력+D_cap
            if (hw >= 0) {
                cal.addSample(Modality.VISION, hw);
            }
        }
    }

    private void showFlash() {
        if (display == null || display.isDisposed()) {
            return;
        }
        if (flashShell == null || flashShell.isDisposed()) {
            flashShell = new Shell(display, SWT.NO_TRIM | SWT.ON_TOP);
            flashShell.setBackground(display.getSystemColor(SWT.COLOR_WHITE));
            Monitor m = display.getPrimaryMonitor();
            flashShell.setBounds(m.getBounds());
        }
        flashShell.setVisible(true);
        flashShell.setActive();
        flashShell.update();
    }

    private void hideFlash() {
        if (flashShell != null && !flashShell.isDisposed()) {
            flashShell.setVisible(false);
        }
    }

    private void uiExec(Runnable r) {
        if (display != null && !display.isDisposed()) {
            display.asyncExec(r);
        }
    }

    private void uiExecSync(Runnable r) {
        if (display != null && !display.isDisposed()) {
            display.syncExec(r);
        }
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * ② 음향 D_mic 보정 시작 — 폰 플래시+삐를 카메라·마이크가 동시 검출, Δ=삐−플래시 수집.
     * <b>D_mic = D_cap + (Δ − 폰출력지연)</b> 를 AUDIO 상수로 저장(D_cap=① 화면플래시 결과).
     * 전제: ① 먼저 D_cap 측정 + 카메라 오픈 + 폰을 웹캠에 보이고 마이크 근처에 둠.
     */
    private void startCoincidenceCal() {
        if (coincRunning || visionRunning) {
            return;
        }
        final CameraService cam = CameraService.get();
        if (!cam.isOpen()) {
            coincVal.setText("카메라 미오픈 (① 설정)");
            return;
        }
        final double dcap = cal.constMs(Modality.VISION);   // ① 결과
        if (Double.isNaN(dcap)) {
            coincVal.setText("먼저 ① D_cap 보정하세요");
            return;
        }
        cal.clear(Modality.AUDIO);
        coincRunning = true;
        coincPairs = 0;
        coincStatus = "시작…";
        final int target = nSpin.getSelection();
        Thread t = new Thread(new Runnable() {
            public void run() {
                runCoincidence(cam, target, dcap);
            }
        }, "apx-coinc-cal");
        t.setDaemon(true);
        t.start();
    }

    /** 마이크 라인 + 카메라 프레임을 동시 감시, 플래시·삐 쌍지어 D_mic=dcap+Δ 수집. 워커 전용. */
    private void runCoincidence(CameraService cam, int target, double dcap) {
        TargetDataLine line = null;
        try {
            AudioFormat fmt = new AudioFormat(44100f, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
            if (!AudioSystem.isLineSupported(info)) {
                coincStatus = "마이크 미지원";
                return;
            }
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(fmt, 8192);
            line.start();
            byte[] buf = new byte[512];       // 256 샘플 ≈ 5.8ms
            double baseLuma = Double.NaN;
            double baseAmp = 0.02;
            double pendFlash = Double.NaN;
            double pendBeep = Double.NaN;
            double lastFlash = -9;
            double lastBeep = -9;
            while (coincRunning && coincPairs < target) {
                int n = line.read(buf, 0, buf.length);
                double now = SyncBus.now();
                // 삐(에너지 온셋)
                double amp = peakAmp(buf, n);
                baseAmp = 0.995 * baseAmp + 0.005 * amp;
                if (amp > Math.max(BEEP_AMP_MIN, baseAmp * 5) && now - lastBeep > REFRACT_S) {
                    lastBeep = now;
                    pendBeep = now;
                }
                // 플래시(밝기 상승)
                BufferedImage img = cam.latest();
                if (img != null) {
                    double luma = FlashProbe.meanLuma(img);
                    if (Double.isNaN(baseLuma)) {
                        baseLuma = luma;
                    } else {
                        if (luma > baseLuma + FLASH_DELTA2 && now - lastFlash > REFRACT_S) {
                            lastFlash = now;
                            pendFlash = now;
                        }
                        baseLuma = 0.98 * baseLuma + 0.02 * Math.min(luma, baseLuma + 5); // 플래시에 안 끌림
                    }
                }
                // 쌍 매칭
                if (!Double.isNaN(pendFlash) && !Double.isNaN(pendBeep)) {
                    double d = pendBeep - pendFlash;
                    if (Math.abs(d) <= PAIR_WIN_S) {
                        // D_mic = D_cap + Δ  (폰 화면·음향 지연은 거의 상쇄 → 무보정)
                        cal.addSample(Modality.AUDIO, dcap + d * 1000.0);
                        coincPairs++;
                        pendFlash = Double.NaN;
                        pendBeep = Double.NaN;
                    } else if (pendFlash < pendBeep) {
                        pendFlash = Double.NaN;    // 오래된 쪽 폐기
                    } else {
                        pendBeep = Double.NaN;
                    }
                }
                coincStatus = "수집 " + coincPairs + "/" + target;
            }
            coincStatus = (coincPairs >= target) ? "완료" : "중단";
        } catch (Exception ex) {
            coincStatus = "오류: " + ex.getClass().getSimpleName();
        } finally {
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (Exception ignore) {
                    // 무시
                }
            }
            coincRunning = false;
        }
    }

    /** 16bit LE 블록의 최대 진폭(0..1). */
    private static double peakAmp(byte[] b, int n) {
        int peak = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            int a = Math.abs(s);
            if (a > peak) {
                peak = a;
            }
        }
        return peak / 32768.0;
    }

    private void setVerdict(Label l, String text, boolean ok, boolean colored) {
        l.setText(text);
        l.setForeground(!colored ? null : (ok ? okColor : ngColor));
    }

    /**
     * 동기 대상(비전 팝업·음향)의 <b>발생 시각</b> max − min (ms). 2개 미만이면 NaN.
     * <p>L1: 발생 ≈ PASS − 판단속도(도구 검출 지연 제거).
     * <p>L2({@code l2=true}): 추가로 모달 물리지연 상수(D_mic/D_cap, 중앙값)를 빼서
     * 카메라·마이크 파이프라인 지연차까지 제거 → 진짜 상호 동기. 상수 미측정 모달은 그대로.
     */
    private double spreadMs(boolean l2) {
        SyncBus bus = SyncBus.get();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        int n = 0;
        for (Event e : SYNC) {
            double t = bus.stampOf(e);
            if (!Double.isNaN(t)) {
                double judge = bus.judgeMsOf(e);
                double occ = Double.isNaN(judge) ? t : (t - judge / 1000.0);   // L1
                if (l2) {
                    double cst = cal.constMs(Calibration.modalityOf(e));       // L2: 물리지연 상수
                    if (!Double.isNaN(cst)) {
                        occ -= cst / 1000.0;
                    }
                }
                min = Math.min(min, occ);
                max = Math.max(max, occ);
                n++;
            }
        }
        return (n < 2) ? Double.NaN : (max - min) * 1000.0;
    }

    @Override
    public void dispose() {
        coincRunning = false;
        visionRunning = false;
        if (flashShell != null && !flashShell.isDisposed()) {
            flashShell.dispose();
        }
        super.dispose();
    }

    @Override
    public void setFocus() {
        if (head != null && !head.isDisposed()) {
            head.setFocus();
        }
    }
}
