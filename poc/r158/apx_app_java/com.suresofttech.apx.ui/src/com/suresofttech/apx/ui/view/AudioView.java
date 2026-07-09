package com.suresofttech.apx.ui.view;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.ui.part.ViewPart;

import com.suresofttech.apx.core.audio.AudioCapture;
import com.suresofttech.apx.core.audio.BeepMatcher;
import com.suresofttech.apx.core.audio.MatchResult;
import com.suresofttech.apx.core.audio.WavIo;
import com.suresofttech.apx.ui.widget.ScopeCanvas;

/**
 * ④ 음향 검증 View — 파이썬 apx_app/ui/audio_tab.py 이식 (Step 1: 기능 UI).
 * 마이크 선택 → 기대 wav 로드 → 측정 → 주파수/파형 일치도 + PASS/FAIL, 임계 조절.
 * (스코프 그래프·보고서 저장은 다음 단계)
 */
public class AudioView extends ViewPart {

    private Display display;
    private BeepMatcher matcher;
    private final AudioCapture capture = new AudioCapture();
    private List<AudioCapture.Device> devices;
    private volatile MatchResult latest;
    private volatile MatchResult passed; // 최초 PASS 래치(오디오 콜백에서 설정) — 이 상태로 정지·표시
    private volatile long capturedSamples; // 측정 시작(버튼) 이후 누적 샘플 → 검출지연(콜드스타트) 계산
    private String beepPath;
    private ScopeCanvas scope;             // 실시간 파형·스펙트럼 (SWT GC, 무의존)

    private Combo micCombo;
    private Label beepInfo;
    private Label head;
    private Label detail;
    private ProgressBar freqBar;
    private ProgressBar waveBar;
    private Label freqVal;               // 주파수 일치도 수치(바 옆)
    private Label waveVal;               // 파형 일치도 수치(바 옆)
    private Spinner freqSpin;
    private Spinner waveSpin;
    private Button measureBtn;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        GridLayout gl = new GridLayout(1, false);
        parent.setLayout(gl);

        buildResultGroup(parent);
        buildScope(parent);
        buildMicGroup(parent);
        buildBeepGroup(parent);
        buildThresholdGroup(parent);
        buildButtons(parent);

        refreshMics();
        scheduleTick();
    }

    // ---- 결과 ----
    private void buildResultGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("음향 검증 결과");
        g.setLayout(new GridLayout(2, false));    // [바 | 수치]
        g.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));

        head = new Label(g, SWT.NONE);
        head.setText("파형 및 주파수 일치도 검증 [측정 시작]");
        head.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        freqBar = new ProgressBar(g, SWT.HORIZONTAL);
        freqBar.setMinimum(0); freqBar.setMaximum(100);
        freqBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        freqVal = new Label(g, SWT.NONE);
        freqVal.setText("주파수 —");
        freqVal.setLayoutData(mkValGridData());

        waveBar = new ProgressBar(g, SWT.HORIZONTAL);
        waveBar.setMinimum(0); waveBar.setMaximum(100);
        waveBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        waveVal = new Label(g, SWT.NONE);
        waveVal.setText("파형 —");
        waveVal.setLayoutData(mkValGridData());

        detail = new Label(g, SWT.NONE);
        detail.setText("주파수/파형 일치도 대기");
        detail.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    }

    // ---- 실시간 파형·스펙트럼 스코프 ----
    private void buildScope(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("실시간 파형 · 스펙트럼");
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));   // 남는 세로 차지
        scope = new ScopeCanvas(g, 5000.0);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.minimumHeight = 240;
        scope.setLayoutData(gd);
    }

    private GridData mkValGridData() {
        GridData d = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        d.widthHint = 120;                        // 바 옆 수치 폭 고정(값 바뀌어도 안 흔들림)
        return d;
    }

    // ---- 마이크 ----
    private void buildMicGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("마이크");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        micCombo = new Combo(g, SWT.READ_ONLY | SWT.DROP_DOWN);
        micCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button refresh = new Button(g, SWT.PUSH);
        refresh.setText("새로고침");
        refresh.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                refreshMics();
            }
        });
    }

    // ---- 기대 beep ----
    private void buildBeepGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("기대 경고음 (.wav)");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        beepInfo = new Label(g, SWT.NONE);
        beepInfo.setText("wav 파일을 선택하세요");
        beepInfo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button pick = new Button(g, SWT.PUSH);
        pick.setText("파일…");
        pick.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                pickBeep();
            }
        });
    }

    // ---- 임계 (주파수 AND 파형) ----
    private void buildThresholdGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("PASS 임계 — 주파수 AND 파형 (각 0~1, 둘 다 넘어야 PASS)");
        g.setLayout(new GridLayout(4, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(g, SWT.NONE).setText("주파수 일치도 ≥");
        freqSpin = mkThrSpin(g);
        new Label(g, SWT.NONE).setText("파형 일치도 ≥");
        waveSpin = mkThrSpin(g);
    }

    private Spinner mkThrSpin(Composite parent) {
        Spinner sp = new Spinner(parent, SWT.BORDER);
        sp.setDigits(2);        // 값 50 → 0.50 표시
        sp.setMinimum(0);
        sp.setMaximum(100);
        sp.setIncrement(5);     // 0.05 단위
        sp.setSelection(50);    // 기본 0.50
        sp.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                applyThresholds();
            }
        });
        return sp;
    }

    // ---- 버튼 ----
    private void buildButtons(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(3, true));
        c.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        measureBtn = new Button(c, SWT.TOGGLE);
        measureBtn.setText("측정 시작");
        measureBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        measureBtn.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                toggleMeasure(measureBtn.getSelection());
            }
        });
        Button reset = new Button(c, SWT.PUSH);
        reset.setText("리셋");
        reset.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        reset.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                resetMeasure();
            }
        });
        Button save = new Button(c, SWT.PUSH);
        save.setText("보고서 저장");
        save.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        save.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                saveReport();
            }
        });
    }

    private void resetMeasure() {
        if (matcher != null) {
            matcher.arm();
        }
        latest = null;
        passed = null;                            // 래치 해제 → 다시 측정 가능
        if (head != null && !head.isDisposed()) {
            head.setText("파형 및 주파수 일치도 검증 [측정 시작]");
            head.setForeground(null);
            freqVal.setText("주파수 —");
            waveVal.setText("파형 —");
            freqBar.setSelection(0);
            waveBar.setSelection(0);
        }
    }

    // ---- 동작 ----
    private void refreshMics() {
        devices = AudioCapture.listInputDevices();
        micCombo.removeAll();
        for (AudioCapture.Device d : devices) {
            micCombo.add(d.name);
        }
        if (!devices.isEmpty()) {
            micCombo.select(0);
        }
    }

    private void pickBeep() {
        FileDialog dlg = new FileDialog(head.getShell(), SWT.OPEN);
        dlg.setFilterExtensions(new String[] { "*.wav" });
        dlg.setFilterNames(new String[] { "오디오 (*.wav)" });
        String p = dlg.open();
        if (p == null) {
            return;
        }
        try {
            WavIo.Wav wav = WavIo.load(p);
            matcher = new BeepMatcher(wav.samples, wav.sampleRate, 150.0, 4.0,
                    freqSpin.getSelection() / 100.0, waveSpin.getSelection() / 100.0, 0.12);
            beepPath = p;
            beepInfo.setText(new File(p).getName() + "  sr=" + wav.sampleRate
                    + "  " + String.format("%.2fs", wav.samples.length / (double) wav.sampleRate)
                    + "  주도 " + Math.round(matcher.getTargetFreq()) + "Hz");
        } catch (Exception ex) {
            beepInfo.setText("wav 로드 실패: " + ex.getMessage());
        }
    }

    private void applyThresholds() {
        if (matcher != null) {
            matcher.setFreqThr(freqSpin.getSelection() / 100.0);
            matcher.setWaveThr(waveSpin.getSelection() / 100.0);
        }
    }

    private void toggleMeasure(boolean on) {
        if (on) {
            if (matcher == null) {
                beepInfo.setText("먼저 기대 wav를 지정하세요");
                measureBtn.setSelection(false);
                return;
            }
            if (devices == null || devices.isEmpty()) {
                beepInfo.setText("마이크가 없습니다");
                measureBtn.setSelection(false);
                return;
            }
            AudioCapture.Device dev = devices.get(Math.max(0, micCombo.getSelectionIndex()));
            matcher.arm();
            latest = null;
            passed = null;                        // 새 측정 시작 → 래치 해제
            capturedSamples = 0;                  // 버튼 시점 = 샘플0 (콜드스타트 기준)
            try {
                capture.start(dev.info, matcher.getSampleRate(), new AudioCapture.BlockListener() {
                    public void onBlock(double[] block, double now) {
                        // 벽시계(now) 대신 캡처 시작 이후 누적 샘플로 시각 계산 → 버튼 이후 경과(초)
                        capturedSamples += block.length;
                        double t = capturedSamples / (double) matcher.getSampleRate();
                        MatchResult res = matcher.feed(block, t);
                        latest = res;
                        if (res.match && passed == null) {
                            passed = res;         // 최초 확정 블록을 콜백에서 즉시 래치(놓침 방지)
                        }
                    }
                });
                measureBtn.setText("측정 정지");
            } catch (Exception ex) {
                beepInfo.setText("마이크 열기 실패: " + ex.getMessage());
                measureBtn.setSelection(false);
            }
        } else {
            capture.stop();
            measureBtn.setText("측정 시작");
        }
    }

    // ---- 60ms UI 갱신 ----
    private void scheduleTick() {
        if (display == null || display.isDisposed()) {
            return;
        }
        display.timerExec(60, new Runnable() {
            public void run() {
                tick();
            }
        });
    }

    private void tick() {
        if (head == null || head.isDisposed()) {
            return;
        }
        // 래치(콜백에서 설정)됐는데 아직 측정 중이면 UI 스레드에서 정지(콜백 스레드 자기정지 회피).
        if (passed != null && measureBtn.getSelection()) {
            capture.stop();
            measureBtn.setSelection(false);
            measureBtn.setText("측정 시작");
        }

        if (matcher != null && scope != null && !scope.isDisposed()) {
            scope.setData(matcher.getBuffer(), matcher.getSampleRate(), matcher.getTargetFreq());
        }

        MatchResult r = (passed != null) ? passed : latest;
        if (r != null) {
            freqBar.setSelection((int) (r.freqSim * 100));
            waveBar.setSelection((int) (r.waveSim * 100));
            freqVal.setText(String.format("주파수 %.2f", r.freqSim));
            waveVal.setText(String.format("파형 %.2f", r.waveSim));
            String txt;
            int color;
            if (passed != null) {
                txt = String.format("BEEP = PASS (확정) · 검출 %.0f ms", passed.onsetT * 1000.0);
                color = SWT.COLOR_DARK_GREEN;
            } else if (!r.hasSound) {
                txt = "대기 (소리 없음/약함)";
                color = SWT.COLOR_DARK_GRAY;
            } else if (r.isPass) {
                txt = "일치 감지 → PASS";
                color = SWT.COLOR_DARK_GREEN;
            } else {
                txt = "불일치 → FAIL";
                color = SWT.COLOR_RED;
            }
            head.setText(txt);
            head.setForeground(display.getSystemColor(color));
            String fq = r.freqSim >= r.freqThr ? "✓" : "✗";
            String wv = r.waveSim >= r.waveThr ? "✓" : "✗";
            detail.setText(String.format(
                    "주파수 %.2f[≥%.2f]%s AND 파형 %.2f[≥%.2f]%s  ·  목표 %.0fHz  ·  신호세기 %.1f %s",
                    r.freqSim, r.freqThr, fq, r.waveSim, r.waveThr, wv,
                    r.targetFreq, r.energyRatio, r.hasSound ? "✓소리있음" : "·조용"));
        }
        scheduleTick();
    }

    // ---- 보고서 저장 (파이썬 audio_result.txt 형식) ----
    private void saveReport() {
        MatchResult r = (passed != null) ? passed : latest;
        if (r == null) {
            beepInfo.setText("측정 데이터가 없습니다");
            return;
        }
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        FileDialog dlg = new FileDialog(head.getShell(), SWT.SAVE);
        dlg.setFilterExtensions(new String[] { "*.txt" });
        dlg.setFilterNames(new String[] { "텍스트 (*.txt)" });
        dlg.setFileName("audio_result_" + ts + ".txt");
        dlg.setOverwrite(true);
        String path = dlg.open();
        if (path == null) {
            return;
        }
        String ok = r.isPass ? "PASS" : "FAIL";
        StringBuilder sb = new StringBuilder();
        sb.append("========================================\n");
        sb.append(" UN R158 음향(경고음) 일치 검증\n");
        sb.append("========================================\n");
        sb.append("시각            : ").append(ts).append("\n");
        sb.append("기대 beep       : ").append(beepPath == null ? "-" : new File(beepPath).getName()).append("\n");
        sb.append("판정            : ").append(passed != null ? "PASS (확정)" : ok).append("\n\n");
        sb.append(String.format("[1] 주파수 일치도 : %.3f  [>= %.2f]%n", r.freqSim, r.freqThr));
        sb.append(String.format("[2] 파형 일치도   : %.3f  [>= %.2f]%n", r.waveSim, r.waveThr));
        sb.append(String.format("[3] 합산(주+파)   : %.3f%n", r.combined));
        sb.append(String.format("[4] 목표 주파수   : %.0f Hz%n", r.targetFreq));
        sb.append("[5] 검출 지연     : ").append(
                passed != null ? String.format("%.0f ms (측정 시작→확정, 콜드스타트)", passed.onsetT * 1000.0)
                               : "- (미확정)").append("\n");
        sb.append("  결과          : ").append(ok).append("\n");
        sb.append("========================================\n");
        Writer w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
            w.write(sb.toString());
            beepInfo.setText("보고서 저장됨: " + path);
        } catch (Exception ex) {
            beepInfo.setText("보고서 저장 실패: " + ex.getMessage());
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

    @Override
    public void dispose() {
        capture.stop();
        super.dispose();
    }

    @Override
    public void setFocus() {
        if (measureBtn != null && !measureBtn.isDisposed()) {
            measureBtn.setFocus();
        }
    }
}
