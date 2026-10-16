"""実機での動作確認を自動で行う。

バニラのサーバーを起動し、Fabric と PanoramaWindow を入れた実際のクライアントで接続する。
Mod の動作確認の機能 (-Dpanorama_window.selftest) が、取り込みが何周か終わるのを待ってパノラマウィンドウの中身を
PNG に書き出し、向きを変えてもう一度書き出してからゲームを終了するので、その画像とログを調べる。

使い方:
    python tools/verify/verify.py --minecraft 1.16.5 --mod dist/build/libs/PanoramaWindow-3.0.0.jar
    python tools/verify/verify.py --minecraft 1.20.1 --mod ... --shaders --shader-pack complementary-reimagined

クライアントやサーバーのファイルは MarumaSign の確認と同じ場所 (~/.marumasign-verify) に置き、使い回す。
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

import shadermods

REPO = Path(__file__).resolve().parents[2]
DEFAULT_ROOT = Path(os.environ.get("PANORAMA_VERIFY_ROOT", Path.home() / ".marumasign-verify"))
PLAYER = "Tester"
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

_opener = urllib.request.build_opener()
_opener.addheaders = [("User-Agent", "PanoramaWindow-verify/1.0 (+https://github.com/malken21/PanoramaWindow)")]
urllib.request.install_opener(_opener)


def log(message):
    print(f"[verify] {message}", flush=True)


def version_key(version):
    return tuple(int(part) for part in re.findall(r"\d+", version))


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def download(url, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    with urllib.request.urlopen(url, timeout=300) as response, temporary.open("wb") as file:
        shutil.copyfileobj(response, file)
    temporary.replace(path)


def java_major_for(minecraft):
    key = version_key(minecraft)
    if key >= (26, 0, 0):
        return 25
    if key >= (1, 20, 5):
        return 21
    if key >= (1, 17, 0):
        return 17
    return 8


def find_java(major, main_dir):
    candidates = []
    component = {8: "jre-legacy", 17: "java-runtime-gamma", 21: "java-runtime-delta", 25: "java-runtime-epsilon"}.get(major)
    if component:
        candidates += list((main_dir / "jvm").glob(f"{component}/bin/java.exe"))
    for base in (Path.home() / ".jdks", Path.home() / ".gradle" / "jdks", Path("C:/Program Files/Java")):
        if base.is_dir():
            candidates += list(base.glob("*/bin/java.exe")) + list(base.glob("*/*/bin/java.exe"))
    for java in candidates:
        try:
            output = subprocess.run([str(java), "-version"], capture_output=True, text=True, timeout=30).stderr
        except (OSError, subprocess.SubprocessError):
            continue
        match = re.search(r'version "(?:1\.)?(\d+)', output)
        if match and int(match.group(1)) == major:
            return java
    raise RuntimeError(f"Java {major} was not found")


# ---------- サーバー ----------

class Server:
    def __init__(self, minecraft, root, main_dir, port):
        self.minecraft = minecraft
        self.directory = root / "servers" / minecraft
        self.main_dir = main_dir
        self.port = port
        self.process = None
        self.lines = []
        self.joined = threading.Event()
        self.ready = threading.Event()

    def prepare(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        jar = self.directory / "server.jar"
        if not jar.exists():
            manifest = fetch_json(MANIFEST_URL)
            entry = next((v for v in manifest["versions"] if v["id"] == self.minecraft), None)
            if entry is None:
                raise RuntimeError(f"Unknown Minecraft version: {self.minecraft}")
            log(f"downloading server {self.minecraft}")
            download(fetch_json(entry["url"])["downloads"]["server"]["url"], jar)
        shutil.rmtree(self.directory / "world", ignore_errors=True)
        for name in ("logs", "ops.json"):
            target = self.directory / name
            if target.is_dir():
                shutil.rmtree(target, ignore_errors=True)
            elif target.exists():
                target.unlink()
        (self.directory / "eula.txt").write_text("eula=true\n")
        (self.directory / "server.properties").write_text("\n".join([
            f"server-port={self.port}",
            "server-ip=127.0.0.1",
            "online-mode=false",
            "enforce-secure-profile=false",
            "white-list=false",
            "enforce-whitelist=false",
            "level-type=flat",
            "generate-structures=false",
            "spawn-protection=0",
            "gamemode=creative",
            "force-gamemode=true",
            "difficulty=peaceful",
            "spawn-monsters=false",
            "spawn-animals=false",
            "spawn-npcs=false",
            "view-distance=4",
            "max-players=2",
            "motd=PanoramaWindow verify",
            "",
        ]))

    def start(self):
        java = find_java(java_major_for(self.minecraft), self.main_dir)
        log(f"starting server {self.minecraft} with {java}")
        self.process = subprocess.Popen(
            [str(java), "-Xmx1G", "-jar", "server.jar", "nogui"],
            cwd=self.directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
        )
        threading.Thread(target=self._read, daemon=True).start()
        if not self.ready.wait(300):
            raise RuntimeError("The server did not start. Last lines:\n" + "\n".join(self.lines[-20:]))

    def _read(self):
        for line in self.process.stdout:
            line = line.rstrip()
            self.lines.append(line)
            if "Done (" in line:
                self.ready.set()
            if f"{PLAYER} joined the game" in line:
                self.joined.set()

    def command(self, text):
        if self.process and self.process.poll() is None:
            self.process.stdin.write(text + "\n")
            self.process.stdin.flush()

    def on_join(self):
        """目印になるものを置く: 色の違う 4 本の柱と、名札の付いた防具立て (ビルボードの確認用)。"""
        time.sleep(2)
        self.command("time set noon")
        self.command("weather clear")
        for dx, dz, block in ((6, 0, "red_wool"), (-6, 0, "blue_wool"), (0, 6, "lime_wool"), (0, -6, "yellow_wool")):
            self.command(f"execute at {PLAYER} run fill ~{dx} ~ ~{dz} ~{dx} ~4 ~{dz} minecraft:{block}")
        if version_key(self.minecraft) >= (1, 21, 5):
            name = 'CustomName:"Panorama"'
        else:
            name = "CustomName:'\"Panorama\"'"
        self.command(f"execute at {PLAYER} run summon minecraft:armor_stand ~3 ~ ~3 {{{name},CustomNameVisible:1b,NoGravity:1b}}")

    def stop(self):
        if self.process is None:
            return
        if self.process.poll() is None:
            self.command("stop")
            try:
                self.process.wait(60)
            except subprocess.TimeoutExpired:
                self.process.kill()


# ---------- クライアント ----------

OPTIONS = """onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
tutorialStep:none
pauseOnLostFocus:false
renderDistance:4
soundCategory_master:0.0
"""


def prepare_work_dir(work_dir, mod, config, extra_options=()):
    shutil.rmtree(work_dir, ignore_errors=True)
    (work_dir / "mods").mkdir(parents=True, exist_ok=True)
    if mod is not None:
        shutil.copy2(mod, work_dir / "mods" / mod.name)
    (work_dir / "options.txt").write_text(OPTIONS + "".join(line + "\n" for line in extra_options))
    (work_dir / "config").mkdir(exist_ok=True)
    if config:
        (work_dir / "config" / "panorama_window.json").write_text(json.dumps(config, indent=2))


def minecraft_window_box():
    """Minecraft のウィンドウの位置 (左, 上, 右, 下)。見つからなければ None。"""
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.windll.user32
    found = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def visit(window, _):
        if user32.IsWindowVisible(window):
            title = ctypes.create_unicode_buffer(256)
            user32.GetWindowTextW(window, title, 256)
            if title.value.startswith("Minecraft"):
                rect = wintypes.RECT()
                user32.GetWindowRect(window, ctypes.byref(rect))
                if rect.right - rect.left > 200:
                    found.append((window, (rect.left, rect.top, rect.right, rect.bottom)))
        return True

    user32.EnumWindows(visit, 0)
    if not found:
        return None
    window, box = found[0]
    user32.ShowWindow(window, 9)  # SW_RESTORE
    user32.SetForegroundWindow(window)
    time.sleep(0.7)
    return box


def grab_minecraft(path):
    try:
        from PIL import ImageGrab
    except ImportError:
        return False
    box = minecraft_window_box()
    if box is None:
        return False
    try:
        ImageGrab.grab(bbox=box, all_screens=True).save(path)
        return True
    except Exception:
        return False


def settings_visible(path):
    """設定画面 (青いタイトルバーと、ベージュの面) が写っているか。"""
    from PIL import Image

    with Image.open(path) as image:
        small = image.convert("RGB").resize((320, 180))
        blue = face = 0
        for r, g, b in small.getdata():
            if r < 40 and 60 < g < 130 and b > 200:
                blue += 1
            if abs(r - 0xEC) < 12 and abs(g - 0xE9) < 12 and abs(b - 0xD8) < 12:
                face += 1
        return blue > 150 and face > 3000, f"title={blue} face={face}"


def close_minecraft_window():
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.windll.user32

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def visit(window, _):
        title = ctypes.create_unicode_buffer(256)
        user32.GetWindowTextW(window, title, 256)
        if title.value.startswith("Minecraft"):
            user32.PostMessageW(window, 0x0010, 0, 0)  # WM_CLOSE
        return True

    user32.EnumWindows(visit, 0)


# ---------- 画像の確認 ----------

def image_stats(path):
    """平均の明るさと、明るさのばらつき (何か描かれていれば大きい)。"""
    from PIL import Image, ImageStat

    with Image.open(path) as image:
        grey = image.convert("L").resize((256, 128))
        stat = ImageStat.Stat(grey)
        return stat.mean[0], stat.stddev[0]


def image_difference(a, b):
    """2 枚の画像の違い (0 = 同じ)。"""
    from PIL import Image, ImageChops, ImageStat

    with Image.open(a) as first, Image.open(b) as second:
        size = (256, 128)
        diff = ImageChops.difference(first.convert("RGB").resize(size), second.convert("RGB").resize(size))
        return sum(ImageStat.Stat(diff).mean) / 3.0


def ground_white(path):
    """地平線のすぐ下 (どの向きでも地面のはず) の帯のうち、白っぽい画素の割合。面が霧や空で抜けると大きくなる。"""
    from PIL import Image

    with Image.open(path) as image:
        rgb = image.convert("RGB")
        w, h = rgb.size
        band = rgb.crop((0, int(h * 0.55), w, int(h * 0.70))).resize((128, 16))
        pixels = list(band.getdata())
        return sum(1 for r, g, b in pixels if r > 200 and g > 200 and b > 200) / len(pixels)


def faces_check(result_dir):
    """横向きの 4 面の下の方が、平らな草の地面 (緑が強い) になっているか。影 Mod で地面が抜けると白くなる。
    面を書き出していなければ (OpenGL 以外) None。"""
    from PIL import Image, ImageStat

    faces = [result_dir / f"face-{i}.png" for i in range(6)]
    if not all(face.is_file() for face in faces):
        return None, "no face dump"
    details = []
    ok = True
    for i in range(4):
        with Image.open(faces[i]) as image:
            rgb = image.convert("RGB")
            w, h = rgb.size
            r, g, b = ImageStat.Stat(rgb.crop((0, int(h * 0.8), w, h))).mean
            green = g > r + 10 and g > b + 10
            ok &= green
            details.append(f"face{i}=({r:.0f},{g:.0f},{b:.0f})")
    # 見比べやすいように 6 面を並べた画像も作る
    sheet = Image.new("RGB", (250 * 6, 240), (255, 0, 255))
    for i, face in enumerate(faces):
        with Image.open(face) as image:
            sheet.paste(image.convert("RGB").resize((240, 240)), (i * 250, 0))
    sheet.save(result_dir / "faces-sheet.png")
    return ok, " ".join(details)


LEVEL_PATTERN = re.compile(r"(\[|/)(WARN|ERROR|FATAL)(\]|:)")


def scan_log(path):
    """この Mod に関係する警告・エラーと、Mixin の失敗を集める。"""
    if not path.is_file():
        return ["(no client log)"]
    findings = []
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    for index, line in enumerate(lines):
        lower = line.lower()
        related = "panorama" in lower or "mixin" in lower and ("fail" in lower or "error" in lower)
        gl_error = "OpenGL debug message" in line and "type=ERROR" in line
        if (LEVEL_PATTERN.search(line) and related) or gl_error or ("Exception" in line and related):
            findings.append(line[:400])
            # 例外は、続く数行も残す
            findings += [follow[:300] for follow in lines[index + 1:index + 4] if follow.startswith(("\t", "Caused"))]
    return findings


def run(args):
    root = Path(args.root)
    main_dir = root / "main"
    shaders = bool(args.shaders)
    variant = f"panorama-{args.minecraft}" + ("-shaders" if shaders else "") + (f"-{args.name}" if args.name else "")
    if shaders and shadermods.variant(args.shader_pack, None):
        variant += "-" + shadermods.variant(args.shader_pack, None)
    work_dir = root / "runs" / variant
    result_dir = work_dir / "selftest"
    mod = Path(args.mod).resolve()
    config = json.loads(args.config) if args.config else None
    extra_options = list(args.option or [])
    if args.vulkan:
        # 新しい作業フォルダは「前回の起動が正常に終わっていない」扱いになり、Vulkan の指定が既定に戻されるので、
        # 正常に終わったことにしておく
        extra_options += ["startedCleanly:true", 'preferredGraphicsBackend:"vulkan"']
        # version の行が無いと古い形式として変換され、指定が消える。クライアントの jar からデータバージョンを読む
        import zipfile
        client = main_dir / "versions" / args.minecraft / f"{args.minecraft}.jar"
        with zipfile.ZipFile(client) as archive:
            extra_options.insert(0, "version:" + str(json.loads(archive.read("version.json"))["world_version"]))
    prepare_work_dir(work_dir, mod, config, extra_options)
    result = {"minecraft": args.minecraft, "mod": mod.name, "ok": False, "detail": "", "work_dir": str(work_dir)}

    if shaders:
        setup = shadermods.resolve("fabric", args.minecraft, root / "shadermods", args.shader_pack)
        if setup is None:
            result["detail"] = "No shader mod is available for this version"
            return result
        shadermods.install(setup, root / "shadermods", work_dir)
        result["shader_mod"] = setup["name"]
        result["shader_pack"] = args.shader_pack
        log(f"shader mod: {setup['name']} {[item for item, _ in setup['mods']]} + {setup['pack'][0]}")
    for extra in args.extra_mod or []:
        shutil.copy2(extra, work_dir / "mods" / Path(extra).name)

    base = ["portablemc", "--timeout", "60", "--main-dir", str(main_dir), "--work-dir", str(work_dir), "--output", "human"]
    spec = f"fabric:{args.minecraft}:{args.loader_version}" if args.loader_version else f"fabric:{args.minecraft}"
    log(f"installing {spec}")
    failure = ""
    for _ in range(3):
        try:
            install = subprocess.run(base + ["start", "--dry", spec, "-u", PLAYER], capture_output=True, text=True,
                                     encoding="utf-8", errors="replace", timeout=1500)
        except subprocess.TimeoutExpired:
            failure = "timed out"
            continue
        if install.returncode == 0:
            failure = ""
            break
        failure = (install.stdout + install.stderr)[-1500:]
    if failure:
        result["detail"] = "Launcher install failed: " + failure
        return result

    server = Server(args.minecraft, root, main_dir, args.port)
    client = None
    try:
        server.prepare()
        server.start()
        jvm_args = " ".join(["-Xmx3G", f"-Dpanorama_window.selftest={result_dir}"]
                            + os.environ.get("PANORAMA_VERIFY_JVM_ARGS", "").split())
        command = base + ["start", spec, "-u", PLAYER, "-s", "127.0.0.1", "-p", str(args.port),
                          "--resolution", args.resolution, f"--jvm-args={jvm_args}"]
        log("starting client")
        client_log = open(work_dir / "launcher-output.log", "w", encoding="utf-8", errors="replace")
        client = subprocess.Popen(command, stdout=client_log, stderr=subprocess.STDOUT)

        deadline = time.time() + args.timeout
        joined = False
        settings_shot = False
        while time.time() < deadline:
            if not settings_shot and (result_dir / "settings-open.txt").is_file():
                settings_shot = True
                time.sleep(1.0)
                shot = work_dir / "screenshot-settings.png"
                if grab_minecraft(shot):
                    result["settings_screenshot"] = str(shot)
                    result["settings_visible"], result["settings_detail"] = settings_visible(shot)
            if client.poll() is not None:
                break
            if server.joined.is_set() and not joined:
                joined = True
                log("player joined")
                threading.Thread(target=server.on_join, daemon=True).start()
            if (result_dir / "result.txt").is_file():
                # 自分で終了するのを少し待つ
                try:
                    client.wait(60)
                except subprocess.TimeoutExpired:
                    pass
                break
            time.sleep(0.5)
        else:
            result["detail"] = "Timed out"
        if client.poll() is None and sys.platform == "win32":
            close_minecraft_window()
            try:
                client.wait(45)
            except subprocess.TimeoutExpired:
                pass
        if client.poll() is None:
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(client.pid)], capture_output=True)
        client_log.close()
    finally:
        server.stop()
        (work_dir / "server-output.log").write_text("\n".join(server.lines), encoding="utf-8")

    result["joined"] = server.joined.is_set()
    result_file = result_dir / "result.txt"
    if result_file.is_file():
        values = dict(line.split("=", 1) for line in result_file.read_text(encoding="utf-8").splitlines() if "=" in line)
        result["selftest"] = values
        result["ok"] = values.get("status") == "OK"
        result["detail"] = values.get("status", "")
    elif not result["detail"]:
        result["detail"] = "The client exited without a self test result"

    # 画像: 何かが描かれていて、横を向くと変わり、見上げても変わる
    shots = [result_dir / f"panorama-{i}.png" for i in range(3)]
    if all(shot.is_file() for shot in shots):
        stats = [image_stats(shot) for shot in shots]
        result["images"] = [{"mean": round(m, 1), "stddev": round(s, 1)} for m, s in stats]
        result["turn_difference"] = round(image_difference(shots[0], shots[1]), 2)
        result["look_up_difference"] = round(image_difference(shots[1], shots[2]), 2)
        result["ground_white"] = [round(ground_white(shot), 2) for shot in shots[:2]]
        result["images_ok"] = (all(s > 8 and 10 < m < 245 for m, s in stats)
                               and result["turn_difference"] > 3 and result["look_up_difference"] > 3
                               and all(value < 0.05 for value in result["ground_white"]))
    else:
        result["images_ok"] = False
        result["images"] = [str(shot) + (" (missing)" if not shot.is_file() else "") for shot in shots]

    result["faces_ok"], result["faces_detail"] = faces_check(result_dir)

    client_log_file = work_dir / "logs" / "latest.log"
    if shaders:
        text = client_log_file.read_text(encoding="utf-8", errors="replace") if client_log_file.is_file() else ""
        result["shaders_active"] = shadermods.is_active(text)
    result["findings"] = scan_log(client_log_file)
    selftest = result.get("selftest", {})
    result["spout_ok"] = selftest.get("spout", "").startswith("registered") or sys.platform != "win32"
    result["clean"] = (result["ok"] and result["images_ok"] and not result["findings"]
                       and bool(result.get("settings_visible")) and result["spout_ok"]
                       and result["faces_ok"] is not False
                       and (not shaders or bool(result.get("shaders_active"))))
    (work_dir / "result.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--minecraft", required=True)
    parser.add_argument("--loader-version", default="")
    parser.add_argument("--mod", required=True)
    parser.add_argument("--extra-mod", action="append", help="一緒に入れる Mod (Sodium など)")
    parser.add_argument("--config", default="", help="config/panorama_window.json の内容 (JSON)")
    parser.add_argument("--option", action="append",
                        help='options.txt に足す行 (例: preferredGraphicsBackend:"vulkan" で 26.2 以降を Vulkan で動かす)')
    parser.add_argument("--shaders", action="store_true", help="Iris とシェーダーパックを入れて確認する")
    parser.add_argument("--vulkan", action="store_true", help="Minecraft 26.2 以降を Vulkan で動かす")
    parser.add_argument("--shader-pack", default=shadermods.SHADER_PACK)
    parser.add_argument("--name", default="", help="結果のフォルダ名に付ける文字列")
    parser.add_argument("--resolution", default="1280x720")
    parser.add_argument("--port", type=int, default=25571)
    parser.add_argument("--timeout", type=int, default=480)
    parser.add_argument("--root", default=str(DEFAULT_ROOT))
    args = parser.parse_args()
    result = run(args)
    print(json.dumps(result, indent=2, ensure_ascii=False))
    sys.exit(0 if result.get("clean") else 1)


if __name__ == "__main__":
    main()
