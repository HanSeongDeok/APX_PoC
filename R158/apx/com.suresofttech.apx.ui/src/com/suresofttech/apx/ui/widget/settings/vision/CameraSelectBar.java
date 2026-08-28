package com.suresofttech.apx.ui.widget.settings.vision;

import java.awt.image.BufferedImage;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import com.suresofttech.apx.core.vision.CameraService;
import com.suresofttech.apx.core.vision.VisionChannel;

/**
 * 웹캠 콤보 + 새로고침 - {@link CameraService} 사용.
 * 화면은 공용 {@link CameraCanvas}에 {@link #setCanvas}로 연결한다 (라이브 폴링 포함).
 *
 * <p><b>채널을 반드시 지정하세요.</b> 인자 하나짜리 생성자는 클러스터 채널로 붙는다.
 * 기어봉 바를 만들 때 이걸 쓰면 두 바가 같은 카메라를 잡는다.
 * <pre>
 * new CameraSelectBar(parent, VisionChannel.GEAR);
 * </pre>
 *
 * <p><b>크기</b>는 파라미터로 받지 않는다. 두 가지로 충분하기 때문이다.
 * <ul>
 *   <li><b>바 전체 폭</b> - 클라이언트가 {@code setLayoutData()} 로 정한다.
 *       단, 생성자가 심어 두는 기본값이 {@code GridData(FILL, CENTER, true, false)} 라
 *       {@code widthHint} 만 얹으면 <b>안 먹는다</b>(FILL+grab 이 컬럼 폭으로 늘려 버린다).
 *       줄이거나 고정하려면 정렬과 grab 을 같이 바꿔야 한다.
 *       <pre>
 * GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
 * gd.widthHint = 220;
 * bar.setLayoutData(gd);
 *       </pre></li>
 *   <li><b>새로고침 버튼 폭</b> - {@link Cfg#refreshText} 길이를 따라간다
 *       (실측: "새로고침" 60px, "카메라 목록 다시 읽기" 129px). 콤보가 나머지를 가져간다.</li>
 * </ul>
 * 높이는 열지 않았다. Combo/Button 높이는 네이티브 위젯과 폰트가 정하는 값이라
 * 강제하면 글자가 잘리거나 빈 공간만 생긴다.
 */
public class CameraSelectBar extends Composite {

    /** 클라이언트 주입 문구 - 기본값 유지, 필요한 것만 덮어쓴다. */
    public static final class Cfg {
        public String refreshText = "새로고침";
        /** 콤보 툴팁. null 이면 툴팁 없음. */
        public String comboTooltip =
                "클러스터와 기어봉은 서로 다른 웹캠을 고르세요 (USB / OBS/Iriun 시뮬 / 내장)";
        /** 새로고침 버튼 툴팁. null 이면 툴팁 없음. */
        public String refreshTooltip;
    }

    private static final int POLL_MS = 4;

    private final Combo camCombo;
    private final Display display;
    private final CameraService cameras;
    private List<CameraService.Cam> cams;
    private CameraCanvas canvas;
    private boolean polling;
    private BufferedImage lastBi;
    private final CameraService.DeviceListener deviceListener;

    /** 클러스터 채널. 기어봉 바는 {@link #CameraSelectBar(Composite, VisionChannel)} 를 쓸 것. */
    public CameraSelectBar(Composite parent) {
        this(parent, CameraService.get(), new Cfg());
    }

    public CameraSelectBar(Composite parent, CameraService cameras) {
        this(parent, cameras, new Cfg());
    }

    /** 채널 지정 - 클러스터/기어봉이 서로 다른 카메라를 잡게 하는 정상 경로. */
    public CameraSelectBar(Composite parent, VisionChannel channel) {
        this(parent, CameraService.of(channel), new Cfg());
    }

    public CameraSelectBar(Composite parent, VisionChannel channel, Cfg cfg) {
        this(parent, CameraService.of(channel), cfg);
    }

    public CameraSelectBar(Composite parent, CameraService cameras, Cfg cfg) {
        super(parent, SWT.NONE);
        Cfg c = (cfg != null) ? cfg : new Cfg();
        this.cameras = cameras == null ? CameraService.get() : cameras;
        display = getDisplay();
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        setLayout(gl);
        setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        camCombo = new Combo(this, SWT.READ_ONLY | SWT.DROP_DOWN);
        camCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        if (c.comboTooltip != null) {
            camCombo.setToolTipText(c.comboTooltip);
        }
        camCombo.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                openSelectedCamera();
            }
        });
        Button refresh = new Button(this, SWT.PUSH);
        refresh.setText(c.refreshText);
        if (c.refreshTooltip != null) {
            refresh.setToolTipText(c.refreshTooltip);
        }
        refresh.addSelectionListener(new SelectionAdapter() {
            public void widgetSelected(SelectionEvent e) {
                refreshCameras();
            }
        });

        // USB 연결/분리 자동 반영 - 현장에서 케이블이 바뀌어도 수동 새로고침이 필요 없게.
        // 드라이버 스레드에서 오므로 UI 스레드로 넘긴다.
        deviceListener = new CameraService.DeviceListener() {
            public void onDevicesChanged(final boolean currentLost) {
                if (isDisposed()) {
                    return;
                }
                display.asyncExec(new Runnable() {
                    public void run() {
                        if (!isDisposed()) {
                            onDeviceHotplug(currentLost);
                        }
                    }
                });
            }
        };
        cameras.addDeviceListener(deviceListener);

        addDisposeListener(new DisposeListener() {
            public void widgetDisposed(DisposeEvent e) {
                polling = false;
                    cameras.removeDeviceListener(deviceListener);
            }
        });
    }

    /**
     * 장치 목록 변경 반영.
     * 쓰던 카메라가 빠졌으면 목록만 갱신해 알리고, 같은 이름으로 다시 꽂히면 그 장치로 재연결한다
     * (인덱스는 재연결 때 바뀌므로 이름으로 찾는다).
     */
    private void onDeviceHotplug(boolean currentLost) {
        String wanted = cameras.currentName();
        refreshCameras();
        if (currentLost && canvas != null && !canvas.isDisposed()) {
            canvas.setPlaceholder("웹캠 연결이 끊어졌습니다");
            lastBi = null;
            canvas.setFrame(null);
        } else if (wanted != null && !cameras.isOpen()) {
            cameras.reopenByName(wanted);
        }
    }

    /** 웹캠 화면({@link CameraCanvas}) - 장치 열기 후 여기로 프레임을 넣는다. */
    public void setCanvas(CameraCanvas canvas) {
        this.canvas = canvas;
        lastBi = null;
        startPoll();
    }

    public void refreshCameras() {
        cams = cameras.list();
        camCombo.removeAll();
        for (CameraService.Cam c : cams) {
            camCombo.add(c.name);
        }
        if (!cams.isEmpty()) {
            int cur = cameras.currentIndex();
            int sel = 0;
            for (int i = 0; i < cams.size(); i++) {
                if (cams.get(i).index == cur) {
                    sel = i;
                    break;
                }
            }
            camCombo.select(sel);
            if (cameras.isOpen()) {
                startPoll();
            } else {
                openFirstAvailable(sel);
            }
        } else if (canvas != null && !canvas.isDisposed()) {
            canvas.setPlaceholder("연결된 웹캠 없음");
            lastBi = null;
            canvas.setFrame(null);
        }
    }

    public void openSelectedCamera() {
        if (cams == null || cams.isEmpty()) {
            return;
        }
        int sel = Math.max(0, camCombo.getSelectionIndex());
        int idx = cams.get(sel).index;
        applyOpenResult(cameras.open(idx), idx);
    }

    /**
     * 목록을 새로 채운 뒤 - 이미 연 장치가 없으면 비어 있는 웹캠부터 고른다.
     * 클러스터가 첫 장치를 가져가면 기어봉이 같은 장치를 다시 열지 않고 다음 것으로 붙는다.
     */
    private void openFirstAvailable(int preferredSel) {
        int preferred = cams.get(Math.max(0, preferredSel)).index;
        if (cameras.open(preferred)) {
            applyOpenResult(true, preferred);
            return;
        }
        for (int i = 0; i < cams.size(); i++) {
            int idx = cams.get(i).index;
            if (idx == preferred) {
                continue;
            }
            if (cameras.open(idx)) {
                camCombo.select(i);
                applyOpenResult(true, idx);
                return;
            }
        }
        applyOpenResult(false, preferred);
    }

    private void applyOpenResult(boolean ok, int idx) {
        lastBi = null;
        startPoll();
        if (canvas == null || canvas.isDisposed()) {
            return;
        }
        if (ok) {
            canvas.setPlaceholder("(신호 대기…)");
            return;
        }
                canvas.setFrame(null);
        if (cameras.isHeldByOther(idx)) {
            canvas.setPlaceholder("다른 채널에서 사용 중 - 다른 웹캠을 선택하세요");
        } else {
            canvas.setPlaceholder("웹캠 열기 실패");
        }
    }

    public boolean setFocusToCombo() {
        if (camCombo != null && !camCombo.isDisposed()) {
            return camCombo.setFocus();
        }
        return false;
    }

    /**
     * CameraService 프레임 폴링.
     * 캔버스가 있으면 표시, 없어도 {@link CameraService#latest()}를 호출해
     * 캡처 스레드/캐시를 유지한다 (다른 View / 데모 탭이 이어 받기 위함).
     */
    private void startPoll() {
        if (polling) {
            return;
        }
        polling = true;
        display.timerExec(POLL_MS, new Runnable() {
            public void run() {
                if (!polling || isDisposed()) {
                    polling = false;
                    return;
                }
                display.timerExec(POLL_MS, this);
                    BufferedImage bi = cameras.latest();
                if (canvas == null || canvas.isDisposed()) {
                    return;
                }
                if (bi == lastBi) {
                    return;
                }
                lastBi = bi;
                canvas.setFrame(bi);
            }
        });
    }
}
