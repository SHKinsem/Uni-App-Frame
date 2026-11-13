"""Local publisher configuration is external to the public framework repository."""
import os
from pathlib import Path


def runtime_resources(root):
    configured = os.environ.get('UNI_FRAME_RUNTIME_DIR')
    return Path(configured).resolve() if configured else Path(root) / 'android/host/src/main/res/raw'
