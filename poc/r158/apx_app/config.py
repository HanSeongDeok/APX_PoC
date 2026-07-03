"""앱 설정 — 기준영상 경로 등 (Java 이식 시 properties/설정파일로 대응)."""
import os

# 기준영상 (차종별로 교체하는 자산)
GEAR_REF = r"c:\DEV\apx\hyundai_R.png"                  # 기어 R 켜진 정면
CLUSTER_REF = r"c:\DEV\apx\hyundai_cluster.png"         # 클러스터 일반(팝업 없음)
CLUSTER_POPUP_REF = r"c:\DEV\apx\hyundai_cluster_popup.png"  # 클러스터 팝업 켜짐

# 결과 저장 폴더
RESULTS_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "results")

# 카메라
DEFAULT_CAMERA = 0
