"""판단 시점 전후 프레임 증거 수집 (before/after N프레임 스냅샷 + 시각).
기어·클러스터 공용. Java 이식 시 동일 구조."""
import collections


class EvidenceCapture:
    """매 프레임 push() 하고, 판단 순간 trigger() 호출.
    trigger 시점의 (before 프레임 전) 스냅샷 + 판단 프레임 + (after 프레임 후) 스냅샷을 모은다.
    after 프레임까지 채워지면 self.evidence 완성 (reset 전까지 유지)."""

    def __init__(self, before=3, after=3):
        self.before = before
        self.after = after
        self.ring = collections.deque(maxlen=before + 1)   # 현재 + before개 이전
        self._collecting = None
        self.evidence = None      # {"pre":(img,t), "decide":(img,t), "post":(img,t)}

    def push(self, img, t):
        self.ring.append((img, t))

    def trigger(self):
        """판단 순간 호출 — 직전 push()로 현재 프레임이 ring[-1]에 있어야 함."""
        pre = self.ring[0]                 # before개 전(버퍼에 덜 찼으면 가장 오래된 것)
        decide = self.ring[-1]             # 현재(판단 프레임)
        self._collecting = {"pre": pre, "decide": decide, "post": None, "count": 0}

    def step_after(self, img, t):
        """판단 이후 프레임마다 호출 — after번째 프레임을 post로 확정."""
        if self._collecting is None or self._collecting["post"] is not None:
            return
        self._collecting["count"] += 1
        if self._collecting["count"] >= self.after:
            self._collecting["post"] = (img, t)
            self.evidence = self._collecting
            self._collecting = None

    def reset(self):
        self._collecting = None
        self.evidence = None
        self.ring.clear()
