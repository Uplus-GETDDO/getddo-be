#!/usr/bin/env python3
"""검증된 비밀 아닌 호스트 지표 설정만 새 임시 env에 추가한다."""
import argparse
import os
from pathlib import Path
import stat


def inject(path, server, enabled):
    if server not in ("a", "c"):
        raise ValueError("server must be a or c")
    if enabled not in ("true", "false"):
        raise ValueError("enabled must be true or false")
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.geteuid():
        raise ValueError("env must be an owned regular 600 file")
    settings = {"GETDDO_METRICS_SERVER": server, "GETDDO_METRICS_ENABLED": enabled,
                "GETDDO_METRICS_ENVIRONMENT": "dev", "GETDDO_METRICS_NAMESPACE": "GETDDO/API",
                "GETDDO_METRICS_STEP": "60s"}
    content = path.read_text()
    if any(line.split("=", 1)[0] in settings for line in content.splitlines()):
        raise ValueError("per-host metrics settings must not come from shared parameters")
    with path.open("a") as output:
        for name, value in settings.items():
            output.write(name + "=" + value + "\n")
        output.flush()
        os.fsync(output.fileno())


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, required=True)
    parser.add_argument("--server", choices=("a", "c"), required=True)
    parser.add_argument("--enabled", choices=("true", "false"), required=True)
    args = parser.parse_args()
    inject(args.file, args.server, args.enabled)
