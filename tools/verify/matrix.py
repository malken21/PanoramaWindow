"""verify.py を複数のバージョンで続けて実行し、結果を 1 つの表にまとめる。

    python tools/verify/matrix.py                       # 既定の代表的なバージョン
    python tools/verify/matrix.py 1.16.5 1.21.11 26.2   # バージョンを指定
    python tools/verify/matrix.py --shaders             # Iris とシェーダーパックを入れて確認
"""

import argparse
import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# 世代ごとに 1 つ以上 (targets.json の世代の境目を必ず含める)
DEFAULT_VERSIONS = [
    "1.14.4", "1.15.2", "1.16.1", "1.16.5", "1.17.1", "1.18.2", "1.19.2", "1.19.3", "1.20.1", "1.20.4", "1.20.6",
    "1.21.1", "1.21.3", "1.21.4", "1.21.5", "1.21.8", "1.21.10", "1.21.11", "26.1.2", "26.2", "26.3",
]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("versions", nargs="*")
    parser.add_argument("--mod", default=None)
    parser.add_argument("--shaders", action="store_true")
    parser.add_argument("--shader-pack", default=None)
    parser.add_argument("--config", default="")
    parser.add_argument("--name", default="")
    parser.add_argument("--option", action="append", help="options.txt に足す行")
    parser.add_argument("--vulkan", action="store_true", help="Minecraft 26.2 以降を Vulkan で動かす")
    parser.add_argument("--summary", default=str(REPO / "build" / "verify-summary.json"))
    args = parser.parse_args()

    mod = args.mod or str(sorted((REPO / "dist" / "build" / "libs").glob("PanoramaWindow-*.jar"))[-1])
    summary = {}
    for version in args.versions or DEFAULT_VERSIONS:
        command = [sys.executable, str(Path(__file__).with_name("verify.py")), "--minecraft", version, "--mod", mod]
        if args.shaders:
            command.append("--shaders")
        if args.shader_pack:
            command += ["--shader-pack", args.shader_pack]
        if args.config:
            command += ["--config", args.config]
        if args.name:
            command += ["--name", args.name]
        if args.vulkan:
            command.append("--vulkan")
        for option in args.option or []:
            command += ["--option", option]
        print(f"=== {version}", flush=True)
        process = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
        output = process.stdout
        try:
            result = json.loads(output[output.index("\n{") + 1:])
        except ValueError:
            result = {"clean": False, "detail": (output + process.stderr)[-1500:]}
        summary[version] = result
        state = "OK" if result.get("clean") else "NG"
        print(f"{version}: {state} {result.get('detail', '')} images_ok={result.get('images_ok')} "
              f"findings={len(result.get('findings', []))} settings={result.get('settings_visible')} faces={result.get('faces_ok')} spout={result.get('spout_ok')} "
              f"shaders_active={result.get('shaders_active')}", flush=True)
        for line in result.get("findings", [])[:8]:
            print("    " + line, flush=True)
        Path(args.summary).parent.mkdir(parents=True, exist_ok=True)
        Path(args.summary).write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    failed = [version for version, result in summary.items() if not result.get("clean")]
    print("failed: " + (", ".join(failed) if failed else "none"))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
