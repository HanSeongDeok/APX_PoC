package com.suresofttech.apx.core.vision;

/**
 * 영상 엔진 이식 예정 표식 (Gear / Cluster / Overlay / Evidence).
 *
 * <p>파이썬 apx_app/engine/{gear,cluster,evidence}.py 및 ui/overlay.py 의 영상 처리는
 * OpenCV 에 의존한다. Java 이식 시 <b>JavaCV(bytedeco)</b> 또는 org.opencv 바인딩 필요.
 * 오디오 엔진과 달리 순수 JDK 로는 못 옮기므로, 타겟 플랫폼에 OpenCV 번들을 추가한 뒤
 * 여기에 Gear.java / Cluster.java 를 채운다.
 *
 * <p>이식 대상 요약:
 * <ul>
 *   <li>Gear: ORB 정렬 + R단 채도 앵커/밝기 비교 판정 (gear.py, 227줄)</li>
 *   <li>Cluster: 팝업 경고창 템플릿/NCC·SSIM 검출 (cluster.py, 187줄)</li>
 *   <li>Evidence: 판정 근거 CSV/스냅샷 기록 (evidence.py, 40줄) — OpenCV 최소</li>
 * </ul>
 *
 * <p>cv2 → JavaCV 매핑: VideoCapture→org.bytedeco.opencv FrameGrabber,
 * matchTemplate→opencv_imgproc.matchTemplate, ORB→opencv_features2d.ORB.
 */
public final class VisionPortTodo {
    private VisionPortTodo() {}
}
