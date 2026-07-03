"""APX PoC 앱 진입점.

실행:
  python -m apx_app.main            # poc/r158 폴더에서
  python apx_app/main.py --camera 0
"""
import sys
import argparse
from PySide6.QtWidgets import QApplication

# 패키지/직접 실행 모두 지원
if __package__ in (None, ""):
    import os
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from apx_app.ui.main_window import MainWindow
    from apx_app import config
else:
    from .ui.main_window import MainWindow
    from . import config


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--camera", type=int, default=config.DEFAULT_CAMERA)
    args = ap.parse_args()

    app = QApplication(sys.argv)
    win = MainWindow(camera=args.camera)
    win.resize(1000, 620)
    win.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
