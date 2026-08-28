package com.suresofttech.apx.core.vision;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

/**
 * 채널별 <b>라이브 기준 프레임</b> 보관소 — 설정에서 잡은 기준을 측정이 그대로 쓰게 한다.
 *
 * <p>"기준 이미지 사용"이 꺼져 있으면 기준은 파일이 아니라 <b>웹캠 프레임 한 장</b>이다.
 * 예전에는 설정 프리뷰와 측정이 각각 자기 시점의 {@code CameraService.latest()}를 기준으로
 * 등록했다. 그래서 설정에서 정상 화면을 기준으로 잡아 뒀어도, 시뮬레이션 시작 버튼을 누른
 * 순간의 화면이 새 기준이 되어 버렸다(그 사이 화면이 R로 바뀌어 있으면 기준이 뒤집힌다).
 *
 * <p>이제 설정 프리뷰가 기준을 등록할 때 여기에 같이 올려 두고, 측정은 여기 있는 것을
 * 먼저 쓴다. 없을 때만(설정 화면을 한 번도 안 열었을 때) 현재 프레임으로 떨어진다.
 *
 * <p>SWT 무의존(core). AWT {@link BufferedImage} 만 다룬다.
 */
public final class VisionReference {

    private static final Map<VisionChannel, BufferedImage> REFS =
            new EnumMap<VisionChannel, BufferedImage>(VisionChannel.class);

    private VisionReference() {
    }

    /**
     * 기준 프레임 등록. 호출자가 들고 있는 이미지가 나중에 바뀌어도 안전하도록 복사해 둔다.
     * null 이면 해당 채널을 비운다.
     */
    public static synchronized void set(VisionChannel ch, BufferedImage img) {
        if (ch == null) {
            return;
        }
        if (img == null) {
            REFS.remove(ch);
            return;
        }
        REFS.put(ch, copy(img));
    }

    /** 등록된 기준 프레임. 없으면 null. 반환본은 사본이라 호출자가 마음대로 써도 된다. */
    public static synchronized BufferedImage get(VisionChannel ch) {
        BufferedImage img = (ch == null) ? null : REFS.get(ch);
        return img == null ? null : copy(img);
    }

    public static synchronized boolean has(VisionChannel ch) {
        return ch != null && REFS.containsKey(ch);
    }

    public static synchronized void clear(VisionChannel ch) {
        if (ch != null) {
            REFS.remove(ch);
        }
    }

    public static synchronized void clearAll() {
        REFS.clear();
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage out = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(src, 0, 0, null);
        return out;
    }
}
