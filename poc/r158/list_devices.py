"""
연결된 카메라/오디오 장치 목록 확인
- 핸드폰을 가상 웹캠(DroidCam/Iriun)으로 연결한 뒤 실행 → 폰의 카메라/마이크 인덱스 확인
"""
import cv2
import sounddevice as sd


def list_cameras(max_idx=6):
    print("=== 카메라 (cv2.VideoCapture 인덱스) ===")
    found = []
    for i in range(max_idx):
        cap = cv2.VideoCapture(i, cv2.CAP_DSHOW)  # Windows: DirectShow
        if cap.isOpened():
            ok, fr = cap.read()
            w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
            h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
            print(f"  [{i}] OPEN  해상도 {w}x{h}  프레임읽기 {'OK' if ok else '실패'}")
            found.append(i)
        cap.release()
    if not found:
        print("  (열리는 카메라 없음 — 가상 웹캠 앱 실행/USB 연결 확인)")
    return found


def list_audio():
    print("\n=== 오디오 입력 장치 (sounddevice) ===")
    for i, d in enumerate(sd.query_devices()):
        if d["max_input_channels"] > 0:
            print(f"  [{i}] {d['name']}  (in ch={d['max_input_channels']}, {int(d['default_samplerate'])}Hz)")


if __name__ == "__main__":
    list_cameras()
    list_audio()
    print("\n사용법: 폰 카메라 인덱스와 마이크 인덱스를 메모 →")
    print("  python realtime_r158.py --camera <idx> --audio <idx>")
