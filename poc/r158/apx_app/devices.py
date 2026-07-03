"""연결된 웹캠/마이크 열거 헬퍼 (세팅 화면용)."""
import cv2

try:
    import sounddevice as sd
except Exception:
    sd = None


def list_cameras(max_index=6):
    """인덱스 0..max_index-1 을 열어보고 잡히는 카메라 목록 반환.
    반환: [(index, label)]  — label 예 '카메라 0 (1280x720)'. (열어보는 방식이라 약간 느림)"""
    found = []
    for i in range(max_index):
        cap = cv2.VideoCapture(i, cv2.CAP_DSHOW)
        if cap.isOpened():
            ok, fr = cap.read()
            if ok and fr is not None:
                h, w = fr.shape[:2]
                found.append((i, f"카메라 {i} ({w}x{h})"))
        cap.release()
    return found


def list_microphones():
    """입력 채널 있는 오디오 장치 목록. 반환: [(index, name)]."""
    if sd is None:
        return []
    out = []
    try:
        for idx, d in enumerate(sd.query_devices()):
            if d.get("max_input_channels", 0) > 0:
                out.append((idx, d["name"]))
    except Exception:
        pass
    return out


class MicMeter:
    """선택 마이크의 입력 레벨(RMS 0~1)을 실시간 측정. UI QTimer가 level 읽음."""

    def __init__(self):
        self.level = 0.0
        self._stream = None

    @property
    def available(self):
        return sd is not None

    def start(self, device_index):
        self.stop()
        if sd is None:
            return False
        try:
            self._stream = sd.InputStream(
                device=device_index, channels=1, samplerate=16000,
                blocksize=1024, callback=self._cb)
            self._stream.start()
            return True
        except Exception:
            self._stream = None
            return False

    def _cb(self, indata, frames, t, status):
        import numpy as np
        self.level = float(np.sqrt(np.mean(indata[:, 0] ** 2)))

    def stop(self):
        if self._stream is not None:
            try:
                self._stream.stop(); self._stream.close()
            except Exception:
                pass
            self._stream = None
        self.level = 0.0
