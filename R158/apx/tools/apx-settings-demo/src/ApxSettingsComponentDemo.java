import java.awt.image.BufferedImage;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;

import com.suresofttech.apx.core.config.ApxSettings;
import com.suresofttech.apx.core.rear.RearGrid;
import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.ui.widget.settings.rear.RearGridCanvas;
import com.suresofttech.apx.ui.widget.settings.vision.CameraCanvas;
import com.suresofttech.apx.ui.widget.settings.audio.AudioMeasureBar;
import com.suresofttech.apx.ui.widget.settings.audio.AudioScope;
import com.suresofttech.apx.ui.widget.settings.audio.AudioThresholdBar;
import com.suresofttech.apx.ui.widget.settings.audio.ExpectedTonePlayBar;
import com.suresofttech.apx.ui.widget.settings.audio.ExpectedWavBar;
import com.suresofttech.apx.ui.widget.settings.audio.MicSelectBar;
import com.suresofttech.apx.ui.widget.settings.audio.MicTestBar;
import com.suresofttech.apx.ui.widget.settings.rear.RearGridSizeBar;
import com.suresofttech.apx.ui.widget.settings.rear.RearLegendBar;
import com.suresofttech.apx.ui.widget.settings.vision.CameraSelectBar;
import com.suresofttech.apx.ui.widget.settings.vision.ReferenceImageBar;
import com.suresofttech.apx.ui.widget.settings.vision.RoiNcc;
import com.suresofttech.apx.ui.widget.settings.vision.VisionThresholdBar;

/**
 * APX Settings 컴포넌트 가이드 데모 — 설정 화면을 이루는 최소 단위 위젯을 하나씩 보여 준다.
 *
 * <p><b>사용법</b>: 왼쪽 트리에서 컴포넌트를 고르면, 오른쪽에 그 컴포넌트의
 * ① 역할 설명  ② 소스 파일 위치  ③ 코드 예시(기본/커스텀)  ④ 실제 미리보기가 나온다.
 *
 * <p><b>처음이면 이 용어부터</b>:
 * <ul>
 *   <li>ROI = 화면에서 "비교할 사각형 영역"(Region Of Interest)</li>
 *   <li>NCC = 두 이미지가 얼마나 닮았는지 0~1 점수(1에 가까울수록 똑같음)</li>
 *   <li>임계(threshold) = "이 값 이상이면 합격"이라는 기준선</li>
 *   <li>기대음 = 측정 때 마이크 소리와 맞춰 볼 정답 .wav 소리</li>
 *   <li>ApxSettings = 모든 설정값을 담아 두는 공유 저장소(각 위젯이 여기에 쓰고 읽음)</li>
 * </ul>
 *
 * <p>AudioMeasureBar ↔ AudioScope 는 데모 세션 동안 공유한다(측정 중 다른 탭으로 옮겨도 그래프 유지).
 */
public final class ApxSettingsComponentDemo {

    private interface DemoEntry {
        void create(Composite host);
    }

    private static final class Item {
        final String title;
        final String className;
        final String description;
        final DemoEntry factory;

        Item(String title, String className, String description, DemoEntry factory) {
            this.title = title;
            this.className = className;
            this.description = description;
            this.factory = factory;
        }
    }

    private final Display display;
    private final Shell shell;
    private final Text descText;
    private final Label classLabel;
    private final Composite host;
    private final ScrolledComposite hostScroll;

    /** 탭 전환 시에도 살리는 음향 측정/스코프 (미리보기에서 reparent). */
    private final Composite audioPark;
    private AudioMeasureBar sharedMeasure;
    private AudioScope sharedScope;

    public static void main(String[] args) {
        Display display = new Display();
        ApxSettingsComponentDemo demo = new ApxSettingsComponentDemo(display);
        Shell shell = demo.shell;
        shell.open();
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        display.dispose();
    }

    private ApxSettingsComponentDemo(Display display) {
        this.display = display;
        shell = new Shell(display);
        shell.setText("APX Settings — 최소 단위 가이드");
        shell.setSize(1100, 760);
        shell.setLayout(new FillLayout());

        SashForm sash = new SashForm(shell, SWT.HORIZONTAL);
        Tree tree = new Tree(sash, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        populateTree(tree);

        Composite right = new Composite(sash, SWT.NONE);
        right.setLayout(new GridLayout(1, false));

        classLabel = new Label(right, SWT.NONE);
        classLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        FontData[] fds = classLabel.getFont().getFontData();
        for (int i = 0; i < fds.length; i++) {
            fds[i].setStyle(SWT.BOLD);
            fds[i].setHeight(fds[i].getHeight() + 1);
        }
        classLabel.setFont(new Font(display, fds));

        // 설명 ↔ 미리보기 세로 분할 (사용자가 드래그로 크기 조절)
        SashForm rightSash = new SashForm(right, SWT.VERTICAL);
        rightSash.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        descText = new Text(rightSash, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);

        Group preview = new Group(rightSash, SWT.NONE);
        preview.setText("개별 컴포넌트 미리보기");
        preview.setLayout(new FillLayout());

        hostScroll = new ScrolledComposite(preview, SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        hostScroll.setExpandHorizontal(true);
        hostScroll.setExpandVertical(true);
        host = new Composite(hostScroll, SWT.NONE);
        GridLayout hostGl = new GridLayout(1, false);
        hostGl.marginWidth = 8;
        hostGl.marginHeight = 8;
        host.setLayout(hostGl);
        hostScroll.setContent(host);

        rightSash.setWeights(new int[] { 42, 58 });

        // 보이지 않는 보관소 — Measure/Scope 를 dispose 하지 않고 옮길 때 사용
        audioPark = new Composite(right, SWT.NONE);
        GridData parkGd = new GridData(0, 0);
        parkGd.exclude = true;
        audioPark.setLayoutData(parkGd);
        audioPark.setVisible(false);
        audioPark.setLayout(new GridLayout(1, false));

        sash.setWeights(new int[] { 26, 74 });

        tree.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                Object data = e.item.getData();
                if (data instanceof Item) {
                    showItem((Item) data);
                }
            }
        });

        TreeItem first = findFirstLeaf(tree.getItems());
        if (first != null) {
            tree.setSelection(first);
            showItem((Item) first.getData());
        }
    }

    private void ensureSharedAudio() {
        if (sharedMeasure != null && !sharedMeasure.isDisposed()) {
            return;
        }
        sharedMeasure = new AudioMeasureBar(audioPark);
        sharedScope = new AudioScope(audioPark, 5000.0); // 5000 = Y축 최대 주파수(Hz), 파형만 쓸 때도 생성 인자
        sharedScope.setShowPitch(false);  // 주파수(음정) 패널 off
        sharedScope.setShowTrend(false);  // 일치도 추이 패널 off → 파형만
        sharedMeasure.setScope(sharedScope);
    }

    private void parkSharedAudio() {
        if (sharedMeasure != null && !sharedMeasure.isDisposed()
                && sharedMeasure.getParent() != audioPark) {
            sharedMeasure.setParent(audioPark);
        }
        if (sharedScope != null && !sharedScope.isDisposed()
                && sharedScope.getParent() != audioPark) {
            sharedScope.setParent(audioPark);
        }
    }

    private void showSharedMeasure(Composite parent) {
        ensureSharedAudio();
        sharedMeasure.setParent(parent);
        sharedMeasure.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
    }

    private void showSharedScope(Composite parent) {
        ensureSharedAudio();
        sharedScope.setParent(parent);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.minimumHeight = 180;
        gd.heightHint = 220;
        sharedScope.setLayoutData(gd);
    }

    private static TreeItem findFirstLeaf(TreeItem[] items) {
        for (int i = 0; i < items.length; i++) {
            TreeItem[] children = items[i].getItems();
            if (children.length == 0) {
                if (items[i].getData() instanceof Item) {
                    return items[i];
                }
            } else {
                TreeItem found = findFirstLeaf(children);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void populateTree(Tree tree) {
        TreeItem vision = category(tree, "Vision");
        add(vision, cameraSelectItem());
        add(vision, cameraCanvasItem());
        add(vision, roiNccItem());
        add(vision, referenceImageItem());
        add(vision, visionThresholdItem());
        vision.setExpanded(true);

        TreeItem audio = category(tree, "Audio");
        add(audio, micSelectItem());
        add(audio, micTestItem());
        add(audio, expectedWavItem());
        add(audio, audioMeasureItem());
        add(audio, audioScopeItem());
        add(audio, expectedToneItem());
        add(audio, audioThresholdItem());
        audio.setExpanded(true);

        TreeItem rear = category(tree, "Rear");
        add(rear, rearGridSizeItem());
        add(rear, rearLegendItem());
        add(rear, rearGridCanvasItem());
        rear.setExpanded(true);
    }

    private static TreeItem category(Tree tree, String name) {
        TreeItem item = new TreeItem(tree, SWT.NONE);
        item.setText(name);
        return item;
    }

    private static void add(TreeItem parent, Item item) {
        TreeItem ti = new TreeItem(parent, SWT.NONE);
        ti.setText(item.title);
        ti.setData(item);
    }

    private void showItem(Item item) {
        classLabel.setText(item.className);
        descText.setText(item.description);
        parkSharedAudio(); // dispose 전에 공유 위젯 대피
        org.eclipse.swt.widgets.Control[] children = host.getChildren();
        for (int i = 0; i < children.length; i++) {
            children[i].dispose();
        }
        item.factory.create(host);
        host.layout(true, true);
        hostScroll.setMinSize(host.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        hostScroll.layout(true, true);
    }

    /**
     * 역할 / 파일 / 예시(기본) / 예시(커스텀).
     * customEx 가 null 이면 커스텀 섹션 생략.
     */
    private static String doc(String role, String pathFromSrc, String defaultEx, String customEx) {
        StringBuilder sb = new StringBuilder();
        sb.append("역할:\n").append(role);
        sb.append("\n\n파일:\n  ").append(pathFromSrc);
        sb.append("\n\n예시 (기본):  ※ parent 등은 SWT Composite(부모 컨테이너)\n").append(defaultEx);
        if (customEx != null && customEx.length() > 0) {
            sb.append("\n\n예시 (커스텀):\n").append(customEx);
        }
        return sb.toString();
    }

    private static final String SRC_UI =
            "src/com/suresofttech/apx/ui/widget/";

    // ── Vision ─────────────────────────────────────────────

    private Item cameraSelectItem() {
        return new Item(
                "CameraSelectBar",
                "CameraSelectBar",
                doc("  웹캠 선택 콤보 + 새로고침 버튼.\n"
                                + "  고른 웹캠을 켜서, setCanvas로 연결한 화면(CameraCanvas)에 실시간 영상 공급.\n"
                                + "  ※ 웹캠 장치 자체는 ApxSettings가 아니라 CameraService가 보관.\n"
                                + "  (이 바는 '영상 공급'만 담당 — 설정 저장은 다른 바 몫)",
                        SRC_UI + "settings/vision/CameraSelectBar.java",
                        "CameraSelectBar cam = new CameraSelectBar(parent);\n"
                                + "CameraCanvas canvas = new CameraCanvas(parent);\n"
                                + "canvas.setPlaceholder(\"웹캠을 선택하세요\");\n"
                                + "cam.setCanvas(canvas);  // 프레임 → canvas\n"
                                + "cam.refreshCameras();   // 장치 목록 로드",
                        null),
                new DemoEntry() {
                    public void create(Composite parent) {
                        CameraSelectBar bar = new CameraSelectBar(parent);
                        bar.refreshCameras();
                    }
                });
    }

    private Item cameraCanvasItem() {
        return new Item(
                "CameraCanvas",
                "CameraCanvas",
                doc("  웹캠 영상을 보여 주는 '화면'만 담당하는 위젯(버튼 없음).\n"
                                + "  CameraSelectBar.setCanvas(canvas)로 연결 시 영상 입력.\n"
                                + "  ROI(비교할 사각형 영역) 지정 기능은 이 화면 위에 겹쳐 붙음(RoiNcc).\n"
                                + "  ※ 설정값 저장 없음 — 표시 전용.",
                        SRC_UI + "settings/vision/CameraCanvas.java",
                        "CameraCanvas canvas = new CameraCanvas(parent);\n"
                                + "canvas.setPlaceholder(\"웹캠을 선택하세요\");\n"
                                + "cam.setCanvas(canvas);",
                        null),
                new DemoEntry() {
                    public void create(Composite parent) {
                        CameraCanvas canvas = newCanvas(parent);
                        if (CameraService.get().currentIndex() < 0) {
                            canvas.setPlaceholder("CameraSelectBar에서 웹캠을 먼저 선택하세요");
                        } else {
                            canvas.setPlaceholder("(신호 대기…)");
                        }
                        attachLiveFromService(canvas);
                    }
                });
    }

    private Item roiNccItem() {
        return new Item(
                "RoiNcc",
                "RoiNcc",
                doc("  화면 위에서 마우스로 사각형(ROI=비교할 영역)을 그리고,\n"
                                + "  그 안이 기준과 얼마나 닮았는지 점수(NCC, 0~1) 계산.\n"
                                + "  자체 버튼 없이 CameraCanvas 위에 겹쳐 붙음.\n"
                                + "  ※ ApxSettings: 그린 사각형 좌표(ROI) 저장. 합격은 simThr(기준선) 이상.",
                        SRC_UI + "settings/vision/RoiNcc.java",
                        "CameraCanvas canvas = new CameraCanvas(parent);\n"
                                + "cam.setCanvas(canvas);\n"
                                + "\n"
                                + "RoiNcc roi = new RoiNcc(canvas); // 기본 색·선 두께",
                        "RoiNcc.Style st = new RoiNcc.Style();\n"
                                + "st.hit = new RGB(0, 200, 0);     // 일치 색\n"
                                + "st.miss = new RGB(220, 60, 60);  // 불일치 색\n"
                                + "st.drag = new RGB(0, 160, 255);  // 드래그 중 색\n"
                                + "st.roiLineWidth = 3;             // ROI 선 두께(px)\n"
                                + "st.dragThickness = 2;            // 드래그 상자 두께\n"
                                + "RoiNcc roi = new RoiNcc(canvas, st);"),
                new DemoEntry() {
                    public void create(Composite parent) {
                        CameraCanvas canvas = newCanvas(parent);
                        if (CameraService.get().currentIndex() < 0) {
                            canvas.setPlaceholder("CameraSelectBar에서 웹캠을 먼저 선택하세요");
                        } else {
                            canvas.setPlaceholder("(신호 대기…)");
                        }
                        attachLiveFromService(canvas);
                        new RoiNcc(canvas);
                    }
                });
    }

    private Item referenceImageItem() {
        return new Item(
                "ReferenceImageBar",
                "ReferenceImageBar",
                doc("  '기준 이미지 사용' 체크 + 이미지 파일 선택.\n"
                                + "  실시간 화면 대신 저장해 둔 사진 한 장을 기준으로 비교할 때 사용.\n"
                                + "  ※ ApxSettings: 사용 여부(useReferenceImage)·파일 경로(visionRefPath) 저장.\n"
                                + "  비전 비교가 이 값을 보고 '사진 기준 모드'로 동작.",
                        SRC_UI + "settings/vision/ReferenceImageBar.java",
                        "ReferenceImageBar ref = new ReferenceImageBar(parent);",
                        null),
                new DemoEntry() {
                    public void create(Composite parent) {
                        new ReferenceImageBar(parent);
                    }
                });
    }

    private Item visionThresholdItem() {
        return new Item(
                "VisionThresholdBar",
                "VisionThresholdBar",
                doc("  '얼마나 닮아야 합격'인지 기준선(임계)을 ± 버튼으로 조절 + 현재 점수(NCC) 라벨.\n"
                                + "  setRoiNcc(roi)로 연결해야 실시간 점수가 라벨에 표시.\n"
                                + "  ※ ApxSettings: simThr 저장. 비전 판정은 '점수 ≥ simThr'이면 합격.",
                        SRC_UI + "settings/vision/VisionThresholdBar.java",
                        "VisionThresholdBar thr = new VisionThresholdBar(parent);\n"
                                + "thr.setRoiNcc(roi); // 매칭 점수 소스",
                        "VisionThresholdBar.Cfg cfg = new VisionThresholdBar.Cfg();\n"
                                + "cfg.defaultThr = 0.75;       // 초기 임계 0~1 (0.75=75%)\n"
                                + "cfg.step = 0.05;             // ± 한 칸 증감폭\n"
                                + "cfg.minusText = \"− 정밀도\";\n"
                                + "cfg.plusText = \"+ 정밀도\";\n"
                                + "VisionThresholdBar thr = new VisionThresholdBar(parent, cfg);\n"
                                + "thr.setRoiNcc(roi);"),
                new DemoEntry() {
                    public void create(Composite parent) {
                        new VisionThresholdBar(parent);
                    }
                });
    }

    // ── Audio ──────────────────────────────────────────────

    private Item micSelectItem() {
        return new Item(
                "MicSelectBar",
                "MicSelectBar",
                doc("  마이크 선택 콤보 + 새로고침(선택만 담당).\n"
                                + "  ※ ApxSettings: 고른 마이크 이름을 micName으로 저장.\n"
                                + "  테스트·측정 바는 이 이름을 읽어 실제 마이크를 엶\n"
                                + "  (AudioCapture.findInputDevice).\n"
                                + "  → '고르는 바'와 '쓰는 바'는 설정값(micName)으로만 연결.",
                        SRC_UI + "settings/audio/MicSelectBar.java",
                        "MicSelectBar micBar = new MicSelectBar(parent);\n"
                                + "micBar.refreshMics();",
                        null),
                new DemoEntry() {
                    public void create(Composite parent) {
                        MicSelectBar bar = new MicSelectBar(parent);
                        bar.refreshMics();
                    }
                });
    }

    private Item micTestItem() {
        return new Item(
                "MicTestBar",
                "MicTestBar",
                doc("  입력 레벨 막대 + '마이크 테스트' 시작/정지 버튼.\n"
                                + "  테스트 ON 시 소리 크기에 따라 막대가 움직여, 마이크 입력 여부를 눈으로 확인.\n"
                                + "  ※ ApxSettings: micName을 읽어 마이크를 엶(MicSelectBar가 저장해 둔 값).",
                        SRC_UI + "settings/audio/MicTestBar.java",
                        "new MicTestBar(parent); // 장치는 ApxSettings.micName으로 해석",
                        null),
                new DemoEntry() {
                    public void create(Composite parent) {
                        new MicTestBar(parent);
                    }
                });
    }

    private Item expectedWavItem() {
        return new Item(
                "ExpectedWavBar",
                "ExpectedWavBar",
                doc("  비교 기준이 될 '기대 경고음(.wav)' 파일 선택 UI.\n"
                                + "  기대음 = 나중에 마이크로 들어온 소리와 맞춰 볼 '정답 소리'.\n"
                                + "  ※ ApxSettings: 파일 경로를 expectedWavPath로 저장.\n"
                                + "  측정 바(비교)와 재생 바(듣기)가 이 경로를 공유.",
                        SRC_UI + "settings/audio/ExpectedWavBar.java",
                        "new ExpectedWavBar(parent); // 기본 라벨",
                        "ExpectedWavBar.Cfg cfg = new ExpectedWavBar.Cfg();\n"
                                + "cfg.titleText = \"기대 경고음 파일 (.wav)\";\n"
                                + "cfg.placeholderText = \"경고음 .wav를 선택하세요\";\n"
                                + "new ExpectedWavBar(parent, cfg);"),
                new DemoEntry() {
                    public void create(Composite parent) {
                        new ExpectedWavBar(parent);
                    }
                });
    }

    private Item audioMeasureItem() {
        return new Item(
                "AudioMeasureBar",
                "AudioMeasureBar",
                doc("  '측정 / 초기화' 버튼 줄(3칸 — 3번째에 재생 버튼 추가 가능).\n"
                                + "  측정 ON 시 마이크를 열어 기대음과 실시간 비교 + setScope로 연결한 그래프에 파형 표시.\n"
                                + "  (setScope 없으면 버튼만 표시, 그래프 없음.)\n"
                                + "  ※ ApxSettings에서 기대음·마이크·합격 기준선을 읽어 사용.",
                        SRC_UI + "settings/audio/AudioMeasureBar.java",
                        "AudioMeasureBar measure = new AudioMeasureBar(parent);\n"
                                + "new ExpectedTonePlayBar(measure.getActionRow()); // 3번째 칸\n"
                                + "AudioScope scope = new AudioScope(parent, 5000.0);\n"
                                + "measure.setScope(scope);",
                        "AudioMeasureBar.Cfg cfg = new AudioMeasureBar.Cfg();\n"
                                + "cfg.measureText = \"측정 시작\";   // 토글 OFF\n"
                                + "cfg.measuringText = \"측정 중지\"; // 토글 ON\n"
                                + "cfg.resetText = \"리셋\";\n"
                                + "AudioMeasureBar measure = new AudioMeasureBar(parent, cfg);\n"
                                + "measure.setScope(scope);"),
                new DemoEntry() {
                    public void create(Composite parent) {
                        showSharedMeasure(parent);
                    }
                });
    }

    private Item audioScopeItem() {
        return new Item(
                "AudioScope",
                "AudioScope",
                doc("  측정 파형을 그려 주는 '그래프'만 담당하는 위젯(버튼 없음).\n"
                                + "  AudioMeasureBar.setScope(scope)로 연결해야 데이터 입력.\n"
                                + "  ※ 설정값 저장 없음 — 측정 바가 넘겨준 파형을 그리기만 함.\n"
                                + "  생성 인자 5000.0 = 주파수 그래프 Y축 최대(Hz). 파형만 써도 필요.",
                        SRC_UI + "settings/audio/AudioScope.java",
                        "AudioScope scope = new AudioScope(parent, 5000.0);\n"
                                + "scope.setShowPitch(false); // 음정 패널 off\n"
                                + "scope.setShowTrend(false); // 일치도 추이 off\n"
                                + "measure.setScope(scope);",
                        "AudioScope scope = new AudioScope(parent, 5000.0);\n"
                                + "scope.setShowPitch(false);\n"
                                + "scope.setShowTrend(false);\n"
                                + "scope.setTickMs(1000);                    // X축 눈금(ms)\n"
                                + "scope.setPassColor(0x2ecb5a);             // PASS 밴드 색\n"
                                + "scope.setPassAlpha(90);                  // 투명도 0~255\n"
                                + "scope.setWaveTitle(\"측정 파형 (커스텀)\");\n"
                                + "measure.setScope(scope);"),
                new DemoEntry() {
                    public void create(Composite parent) {
                        showSharedScope(parent);
                    }
                });
    }

    private Item expectedToneItem() {
        return new Item(
                "ExpectedTonePlayBar",
                "ExpectedTonePlayBar",
                doc("  고른 기대음(.wav)을 스피커로 들려주는 재생/정지 버튼.\n"
                                + "  보통 AudioMeasureBar의 세 번째 칸(getActionRow)에 부착.\n"
                                + "  ※ ApxSettings: expectedWavPath를 읽어 재생.\n"
                                + "  (ExpectedWavBar로 파일을 먼저 선택해야 소리 재생.)",
                        SRC_UI + "settings/audio/ExpectedTonePlayBar.java",
                        "// parent = measure.getActionRow() 권장 (3번째 칸)\n"
                                + "ExpectedTonePlayBar play = new ExpectedTonePlayBar(parent);\n"
                                + "play.setLayoutData(\n"
                                + "    new GridData(SWT.FILL, SWT.CENTER, true, false));",
                        "ExpectedTonePlayBar.Cfg cfg = new ExpectedTonePlayBar.Cfg();\n"
                                + "cfg.playText = \"기대음 듣기\";\n"
                                + "cfg.playingText = \"재생 정지\";\n"
                                + "new ExpectedTonePlayBar(parent, cfg);"),
                new DemoEntry() {
                    public void create(Composite parent) {
                        new ExpectedTonePlayBar(parent);
                    }
                });
    }

    private Item audioThresholdItem() {
        return new Item(
                "AudioThresholdBar",
                "AudioThresholdBar",
                doc("  음향 합격 기준선(임계)을 ± 버튼으로 조절.\n"
                                + "  주파수·파형 두 기준을 '한 값으로 똑같이' 조정.\n"
                                + "  ※ ApxSettings: audioFreqThr·audioWaveThr를 같은 값으로 저장.\n"
                                + "  측정 바가 '일치도 ≥ 임계'이면 합격 판정할 때 사용.",
                        SRC_UI + "settings/audio/AudioThresholdBar.java",
                        "new AudioThresholdBar(parent); // 기본 0.90 / step 0.05",
                        "AudioThresholdBar.Cfg cfg = new AudioThresholdBar.Cfg();\n"
                                + "cfg.defaultThr = 0.90;                 // 초기 임계 0~1\n"
                                + "cfg.step = 0.05;                       // ± 증감폭\n"
                                + "cfg.descText = \"PASS 기준 임계 (커스텀)\";\n"
                                + "cfg.minusText = \"− 완화\";\n"
                                + "cfg.plusText = \"+ 엄격\";\n"
                                + "new AudioThresholdBar(parent, cfg);"),
                new DemoEntry() {
                    public void create(Composite parent) {
                        new AudioThresholdBar(parent);
                    }
                });
    }

    // ── Rear ───────────────────────────────────────────────

    private Item rearGridSizeItem() {
        return new Item(
                "RearGridSizeBar",
                "RearGridSizeBar",
                doc("  후방 격자 크기 UI만 담당(프리셋 라디오+콤보 / 커스텀 스피너).\n"
                                + "  모드 전환 시 편집 컨트롤은 같은 자리에 하나만 표시.\n"
                                + "  setCanvas(canvas)로 연결하면:\n"
                                + "    · 크기 변경 → ApxSettings + canvas.setGrid\n"
                                + "    · 캔버스 Select 클릭 → ApxSettings.rearSelectedPoints\n"
                                + "  ※ ApxSettings: rearCols / rearRows / rearSizeMode\n"
                                + "    크기가 바뀌면 Select 포인트는 초기화된다.",
                        SRC_UI + "settings/rear/RearGridSizeBar.java",
                        "RearGridSizeBar size = new RearGridSizeBar(parent);\n"
                                + "size.setCanvas(canvas);",
                        "RearGridSizeBar.Cfg cfg = new RearGridSizeBar.Cfg();\n"
                                + "cfg.presetText = \"고정 크기\";   // 프리셋 라디오 문구\n"
                                + "cfg.customText = \"직접 입력\";   // 커스텀 라디오 문구\n"
                                + "cfg.sizeLabelText = \"격자 크기\";\n"
                                + "cfg.colsLabelText = \"가로(열)\";\n"
                                + "cfg.rowsLabelText = \"세로(행)\";\n"
                                + "cfg.applyText = \"격자 적용\";\n"
                                + "cfg.presets = new int[][] { {4,6}, {3,4}, {5,7}, {6,10} }; // 고정크기 목록\n"
                                + "RearGridSizeBar size = new RearGridSizeBar(parent, cfg);\n"
                                + "size.setCanvas(canvas);"),
                new DemoEntry() {
                    public void create(Composite parent) {
                        RearGridSizeBar.Cfg cfg = new RearGridSizeBar.Cfg();
                        cfg.presetText = "고정 크기";
                        cfg.customText = "직접 입력";
                        cfg.applyText = "격자 적용";
                        cfg.presets = new int[][] { { 4, 6 }, { 3, 4 }, { 5, 7 }, { 6, 10 } };
                        new RearGridSizeBar(parent, cfg);
                    }
                });
    }

    private Item rearLegendItem() {
        return new Item(
                "RearLegendBar",
                "RearLegendBar",
                doc("  범례 표시 on/off 체크박스만 담당.\n"
                                + "  setCanvas(canvas)로 연결하면 체크 즉시 canvas.setShowLegend 반영.\n"
                                + "  ※ ApxSettings: rearShowLegend 저장.\n"
                                + "  ※ 범례 그림 자체는 RearGridCanvas가 담당한다.",
                        SRC_UI + "settings/rear/RearLegendBar.java",
                        "RearLegendBar legend = new RearLegendBar(parent);\n"
                                + "legend.setCanvas(canvas);",
                        "RearLegendBar.Cfg cfg = new RearLegendBar.Cfg();\n"
                                + "cfg.legendText = \"상태 범례\";  // 체크박스 문구\n"
                                + "RearLegendBar legend = new RearLegendBar(parent, cfg);\n"
                                + "legend.setCanvas(canvas);"),
                new DemoEntry() {
                    public void create(Composite parent) {
                        ApxSettings s = ApxSettings.get();
                        RearLegendBar.Cfg cfg = new RearLegendBar.Cfg();
                        cfg.legendText = "상태 범례";
                        RearLegendBar legend = new RearLegendBar(parent, cfg);

                        // 미리보기용 캔버스 — 체크 시 이 판에 범례가 뜬다
                        RearGrid g = new RearGrid(s.getRearCols(), s.getRearRows());
                        g.selectPoints(s.getRearSelectedPoints());
                        RearGridCanvas canvas = new RearGridCanvas(parent, g);
                        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
                        gd.minimumHeight = 240;
                        gd.heightHint = 300;
                        canvas.setLayoutData(gd);
                        canvas.loadDefaultCarImage();

                        legend.setCanvas(canvas);   // '상태 범례' 체크 ↔ canvas 범례 on/off
                    }
                });
    }

    private Item rearGridCanvasItem() {
        return new Item(
                "RearGridCanvas",
                "RearGridCanvas",
                doc("  후방 최소 표시 단위 — 차량 후방 그림 + 검증 포인트 격자만.\n"
                                + "  (SizeBar / LegendBar는 이 미리보기에 붙이지 않음)\n"
                                + "\n"
                                + "  동작:\n"
                                + "    · 셀(점) 클릭 → Select 한 번에 하나만 (다시 클릭하면 해제)\n"
                                + "    · loadDefaultCarImage() → ui/ref/차량 후방 레이아웃_Default.png\n"
                                + "    · setShowLegend(true/false) → 판 오른쪽 범례 (격자 침범 없음)\n"
                                + "    · setOnChange(runnable) → Select 변경 콜백 (Settings 동기화 등)\n"
                                + "    · setGrid(RearGrid) → 크기/지정 교체 후 다시 그림\n"
                                + "    · setLegend(names, colors) → 범례 이름·색 커스텀\n"
                                + "\n"
                                + "  ※ 조립은 Client에서 SizeBar·LegendBar와 따로 연결한다.",
                        SRC_UI + "settings/rear/RearGridCanvas.java",
                        "RearGrid g = new RearGrid(4, 6);          // 열×행\n"
                                + "RearGridCanvas canvas = new RearGridCanvas(parent, g);\n"
                                + "canvas.loadDefaultCarImage();         // ui/ref/…_Default.png\n"
                                + "canvas.setShowLegend(false);          // 단독 미리보기면 off",
                        "ApxSettings s = ApxSettings.get();\n"
                                + "RearGrid g = new RearGrid(s.getRearCols(), s.getRearRows());\n"
                                + "g.selectPoints(s.getRearSelectedPoints()); // 저장된 Select 복원\n"
                                + "\n"
                                + "RearGridCanvas canvas = new RearGridCanvas(parent, g);\n"
                                + "canvas.loadDefaultCarImage();\n"
                                + "canvas.setShowLegend(s.isRearShowLegend()); // 범례 on/off\n"
                                + "canvas.setOnChange(new Runnable() {        // Select → Settings\n"
                                + "    public void run() {\n"
                                + "        s.setRearSelectedPoints(canvas.getGrid().selectedPoints());\n"
                                + "    }\n"
                                + "});\n"
                                + "canvas.setLegend(                          // 범례 이름·색 커스텀\n"
                                + "    new String[]{\"선택\",\"측정중\",\"합격\",\"불합격\"},\n"
                                + "    new RGB[]{new RGB(0,120,255), new RGB(230,200,40),\n"
                                + "              new RGB(40,170,70), new RGB(200,40,40)});\n"
                                + "// size.setCanvas(canvas);   // SizeBar와 연결 시\n"
                                + "// legend.setCanvas(canvas); // LegendBar와 연결 시"),
                new DemoEntry() {
                    public void create(Composite parent) {
                        ApxSettings s = ApxSettings.get();
                        RearGrid g = new RearGrid(s.getRearCols(), s.getRearRows());
                        g.selectPoints(s.getRearSelectedPoints());
                        RearGridCanvas canvas = new RearGridCanvas(parent, g);
                        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
                        gd.minimumHeight = 240;
                        gd.heightHint = 300;
                        canvas.setLayoutData(gd);
                        canvas.loadDefaultCarImage();
                        canvas.setShowLegend(true);
                        canvas.setLegend(
                                new String[] { "선택", "측정중", "합격", "불합격" },
                                new RGB[] { new RGB(0, 120, 255), new RGB(230, 200, 40),
                                        new RGB(40, 170, 70), new RGB(200, 40, 40) });
                    }
                });
    }

    private static CameraCanvas newCanvas(Composite parent) {
        CameraCanvas canvas = new CameraCanvas(parent);
        canvas.setPlaceholder("웹캠을 선택하세요");
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.minimumHeight = 240;
        gd.heightHint = 300;
        canvas.setLayoutData(gd);
        return canvas;
    }

    private static void attachLiveFromService(final CameraCanvas canvas) {
        final Display display = canvas.getDisplay();
        final boolean[] alive = new boolean[] { true };
        canvas.addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                alive[0] = false;
            }
        });
        display.timerExec(4, new Runnable() {
            public void run() {
                if (!alive[0] || canvas.isDisposed()) {
                    return;
                }
                display.timerExec(4, this);
                BufferedImage bi = CameraService.get().latest();
                if (bi != null) {
                    canvas.setFrame(bi);
                }
            }
        });
    }
}
