"""matrix.py の結果 (build/verify-*.json) を docs/verification.md にまとめる。

    python tools/verify/report.py
"""

import datetime
import json
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

SECTIONS = [
    ("verify-summary.json", "Without shader mods", None),
    ("verify-shaders.json", "With Iris + BSL Shaders", "shader"),
    ("verify-complementary.json", "With Iris + Complementary Reimagined", "shader"),
    ("verify-optifabric.json", "With OptiFabric + OptiFine + BSL Shaders (Fabric Loader 0.15.11)", "shader"),
    ("verify-canvas.json", "With Canvas (its own pipeline, no shader pack)", "shader"),
    ("verify-vulkan.json", "Vulkan backend (26.2+)", None),
]

# Overscan の比較: (結果のファイル, 影 Mod, Overscan)。同じ時刻・同じパックで Overscan だけ変えて撮る
OVERSCAN_RUNS = [
    ("verify-godrays-iris-1.0.json", "Iris", "1.0"),
    ("verify-godrays-iris-1.5.json", "Iris", "1.5"),
    ("verify-godrays-optifabric-1.0.json", "OptiFabric", "1.0"),
    ("verify-godrays-optifabric-1.5.json", "OptiFabric", "1.5"),
    ("verify-godrays-optifabric-2.0.json", "OptiFabric", "2.0"),
]
# 太陽 (東の空の高いところ。パックが太陽の通り道を南へ傾けるので、東・南・上の 3 面の角の近く) に近い境目
SUN_SEAMS = ["up|E", "E|S", "up|S"]


def version_key(version):
    return tuple(int(part) for part in re.findall(r"\d+", version))


def cell(result, kind):
    if result.get("clean"):
        state = "OK"
    elif result.get("ok"):
        state = "Check"
    else:
        state = "Failed"
    notes = []
    selftest = result.get("selftest", {})
    if selftest.get("mode"):
        notes.append(f"{selftest.get('mode')} {selftest.get('faceSize', '?')} px, FOV {selftest.get('fov', '?')}")
    if kind == "shader" and result.get("shader_mod"):
        notes.append(", ".join(re.sub(r"\.jar$", "", name) for name in result.get("shader_files", []))
                     or result["shader_mod"])
        if result["shader_mod"] == "Canvas":
            notes.append(f"pipeline {result.get('shader_pack') or 'canvas_basic'}")
    if not result.get("clean"):
        failed = [name for name, value in (("images", result.get("images_ok")), ("faces", result.get("faces_ok")),
                                           ("settings", result.get("settings_visible")), ("spout", result.get("spout_ok")))
                  if value is False or value is None and name != "faces"]
        if failed:
            notes.append("failed: " + ", ".join(failed))
        if result.get("findings"):
            notes.append(f"{len(result['findings'])} log finding(s)")
    return state, "; ".join(notes)


def overscan_section():
    """光の筋が面の境目でつながるか (Overscan 1.0 と 1.5 の比較)。"""
    rows = []
    for file, shader_mod, overscan in OVERSCAN_RUNS:
        path = REPO / "build" / file
        if not path.is_file():
            continue
        for version, result in sorted(json.loads(path.read_text(encoding="utf-8")).items(),
                                      key=lambda item: version_key(item[0]), reverse=True):
            seams = result.get("seams") or {}
            if not seams:
                rows.append(f"| {version} | {shader_mod} | {overscan} | " + " | ".join("-" for _ in SUN_SEAMS)
                            + f" | - | {result.get('detail', '')} |")
                continue
            others = [value[1] for name, value in seams.items() if name not in SUN_SEAMS]
            rows.append(f"| {version} | {shader_mod} | {overscan} | "
                        + " | ".join(str(seams[name][1]) if name in seams else "-" for name in SUN_SEAMS)
                        + f" | {max(others) if others else '-'} | {'OK' if result.get('clean') else 'Check'} |")
    if not rows:
        return []
    return [
        "## Overscan and screen-space god rays",
        "",
        "Sildur's Vibrant Extreme draws its god rays and sun glow in screen space (radial sampling towards the",
        "sun's screen position, faded towards the screen edge). The world clock is frozen at `--time 2000`:",
        "the sun is about 40° up in the east, and the pack tilts the sun path towards the south, so the sun",
        "sits near the corner of the east, south and up faces. Each value is the colour step across a face",
        "seam divided by the step between neighbouring pixels inside the faces (1 = as smooth as the inside",
        "of a face; larger = a visible edge; E/S is never 1 because the test armour stand stands on that",
        "seam). Images: `faces-sheet.png` and `panorama-*.png` in each run's `selftest` folder.",
        "",
        "![Sun region of the panorama with OptiFabric at Overscan 1.0, 1.5 and 2.0](images/overscan-godrays.jpg)",
        "",
        "In the image above (OptiFabric, regenerate it with the runs if they change): at 1.0 the sun glow",
        "stops at the up/east seam (the arc above the sun) and at the east/south seam (the vertical step); at",
        "1.5 the arc is nearly gone but the vertical step remains, because seen from the south face the sun",
        "is still outside the 113° render; at 2.0 (127°) both are gone. With Iris the same pack showed no",
        "clear edge at either setting. The yellowish translucent blobs some faces show are the pack's",
        "screen-space lens flares, which each face draws at its own mirrored position - Overscan cannot fix",
        "those.",
        "",
        # "|" は表の区切りになるので、境目の名前は "/" で書く
        "| Minecraft | Shader mod | Overscan | " + " | ".join(name.replace("|", "/") for name in SUN_SEAMS)
        + " | other seams (max) | Run |",
        "| --- | --- | --- | " + " | ".join("---" for _ in SUN_SEAMS) + " | --- | --- |",
    ] + rows + [""]


def main():
    lines = [
        "# Verification",
        "",
        "Results of `tools/verify/matrix.py` on real clients. Generated by `tools/verify/report.py` - do not",
        "edit by hand. What a run does and what **OK** means: [Development](development.md#the-real-client-self-test).",
        "",
        f"Generated: {datetime.date.today().isoformat()}",
        "",
    ]
    for file, title, kind in SECTIONS:
        path = REPO / "build" / file
        if not path.is_file():
            continue
        summary = json.loads(path.read_text(encoding="utf-8"))
        lines += [f"## {title}", "", "| Minecraft | Result | Notes |", "| --- | --- | --- |"]
        for version in sorted(summary, key=version_key, reverse=True):
            state, notes = cell(summary[version], kind)
            lines.append(f"| {version} | {state} | {notes} |")
        lines.append("")
    lines += overscan_section()
    (REPO / "docs" / "verification.md").write_text("\n".join(lines), encoding="utf-8", newline="\n")
    print("wrote docs/verification.md")


if __name__ == "__main__":
    main()
