"""앱 설정 — 기준영상 경로 등 (Java 이식 시 properties/설정파일로 대응)."""
import os

# 기준영상 (차종별로 교체하는 자산)
GEAR_REF = r"c:\DEV\apx\hyundai_R.png"                  # 기어 R 켜진 정면
CLUSTER_REF = r"c:\DEV\apx\hyundai_cluster.png"         # 클러스터 일반(팝업 없음)
CLUSTER_POPUP_REF = r"c:\DEV\apx\hyundai_cluster_popup.png"  # 클러스터 팝업 켜짐

# 프로젝트 루트 (poc/r158) 및 결과 폴더
PROJECT_ROOT = os.path.dirname(os.path.dirname(__file__))
RESULTS_DIR = os.path.join(PROJECT_ROOT, "results")

# 테스트 플레이어 스크립트 (기어봉/클러스터 움직이는 창)
GEAR_PLAYER = os.path.join(PROJECT_ROOT, "gear_player.py")
CLUSTER_PLAYER = os.path.join(PROJECT_ROOT, "cluster_player.py")

# 카메라
DEFAULT_CAMERA = 0
