"""影 Mod (シェーダーを使えるようにする Mod) を入れた状態で確認するための準備。

ローダーと Minecraft のバージョンに合う影 Mod と、それが必要とする Mod、シェーダーパックを取得して、
起動フォルダに置き、シェーダーを有効にする設定を書く。

- Fabric: Iris (と Sodium)。shader_mod に "optifine" を指定すると OptiFabric + OptiFine (1.16 - 1.20.4)、
  "canvas" を指定すると Canvas (1.17.1 - 1.20.2。シェーダーパックではなく、Canvas 自身の描画パイプラインを使う)
- NeoForge: Iris (と Sodium)
- Forge 1.16.5 以降: Oculus (と Rubidium / Embeddium)
- Forge 1.7.10 - 1.15.2: OptiFine
- LiteLoader: OptiFine (Mod としてではなく、起動用のプロファイルに組み込む)
"""

import json
import re
import shutil
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

MODRINTH = "https://api.modrinth.com/v2"
# OptiFine は公式サイトから機械的には取得できないため、ランチャーが使っているミラーから取得する
OPTIFINE = "https://bmclapi2.bangbang93.com/optifine"
# OptiFabric は CurseForge にしかない。CurseForge のサイトが使っている API (キーが要らない) で一覧を取る
CURSEFORGE = "https://www.curseforge.com/api/v1/mods"
OPTIFABRIC_PROJECT = 322385
USER_AGENT = "PanoramaWindow-verify/1.0 (local testing)"

# シェーダーパック。古い OptiFine から新しい Iris まで、幅広いバージョンで動くもの
SHADER_PACK = "bsl-shaders"

# 1 つの版に品質違いのファイルが並ぶパックで、使うファイル (名前の終わり)。
# Sildur's Vibrant の Extreme は画面上の太陽の位置から光の筋 (god rays) を描く (Extreme-VL は立体的な光で、画面に依らない)
PACK_FILES = {"sildurs-vibrant-shaders": " Extreme.zip"}

# シェーダーが有効になったことを示すログの行
ACTIVE_PATTERNS = [
    re.compile(r"Using shaderpack: "),               # Iris / Oculus
    re.compile(r"\[Shaders\] Loaded shaderpack: "),  # OptiFine
    re.compile(r"Loaded shaderpack: "),
    re.compile(r"Canvas Renderer is initializing"),  # Canvas (常に独自の描画パイプライン)
]


def version_key(version):
    return tuple(int(part) for part in re.findall(r"\d+", version))


def fetch_json(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def download(url, path):
    if path.is_file() and path.stat().st_size > 0:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    temporary = path.with_suffix(path.suffix + ".part")
    with urllib.request.urlopen(request, timeout=300) as response, temporary.open("wb") as file:
        shutil.copyfileobj(response, file)
    temporary.replace(path)


def modrinth_version(project, loader, minecraft):
    """プロジェクトの、指定したローダーとバージョン向けの最新版。なければ None。"""
    query = urllib.parse.urlencode({"loaders": json.dumps([loader]), "game_versions": json.dumps([minecraft])})
    versions = fetch_json(f"{MODRINTH}/project/{project}/version?{query}")
    if not versions:
        return None
    # 正式版があればそれを、なければ最新のものを使う
    releases = [version for version in versions if version.get("version_type") == "release"]
    return (releases or versions)[0]


def numbers(text):
    """バージョンの文字列の先頭にある数字の並び。比較に使う。"""
    match = re.match(r"(?:mc[\d.]+-)?v?(\d+(?:\.\d+)*)", text.strip())
    return tuple(int(part) for part in match.group(1).split(".")) if match else ()


def satisfies(version, spec):
    """Fabric の書き方 (">=0.6.9" や "0.5.x" など) で指定された条件に合うかどうか。"""
    for part in spec.split():
        operator = re.match(r"[<>=~^]*", part).group(0)
        wanted = part[len(operator):]
        if wanted in ("*", ""):
            continue
        prefix = numbers(wanted.replace(".x", "").replace(".*", ""))
        if "x" in wanted or "*" in wanted or operator in ("~", "^"):
            # 先頭の数字が同じで、指定以上のもの
            fixed = prefix[:-1] if operator in ("~", "^") and len(prefix) > 1 else prefix
            if version[:len(fixed)] != fixed or (operator in ("~", "^") and version < prefix):
                return False
        elif operator == ">=" and version < prefix or operator == ">" and version <= prefix:
            return False
        elif operator == "<=" and version > prefix or operator == "<" and version >= prefix:
            return False
        elif operator in ("", "=") and version[:len(prefix)] != prefix:
            return False
    return True


def in_range(version, spec):
    """Forge / NeoForge の書き方 ("[0.3.1,)" など) で指定された範囲に入るかどうか。"""
    match = re.match(r"\s*([\[\(])([^,\]\)]*),?([^\]\)]*)([\]\)])\s*$", spec)
    if not match:
        prefix = numbers(spec)
        return not prefix or version[:len(prefix)] == prefix
    low, high = numbers(match.group(2)), numbers(match.group(3))
    if "," not in spec:
        return version[:len(low)] == low
    if low and (version < low or (match.group(1) == "(" and version == low)):
        return False
    if high and (version > high or (match.group(4) == ")" and version >= high)):
        return False
    return True


def declared_requirements(jar):
    """Mod の jar が、必要とする Mod について指定しているバージョンの条件。{Mod の ID: 判定する関数}"""
    found = {}
    try:
        with zipfile.ZipFile(jar) as archive:
            names = set(archive.namelist())
            if "fabric.mod.json" in names:
                depends = json.loads(archive.read("fabric.mod.json").decode("utf-8")).get("depends", {})
                for mod, value in depends.items():
                    specs = [str(item) for item in (value if isinstance(value, list) else [value])]
                    found[mod] = lambda version, specs=specs: any(satisfies(version, spec) for spec in specs)
            for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
                if name in names:
                    text = archive.read(name).decode("utf-8", errors="replace")
                    for mod, spec in re.findall(r'modId\s*=\s*"([^"]+)"[^\[]*?versionRange\s*=\s*"([^"]*)"', text):
                        found[mod] = lambda version, spec=spec: in_range(version, spec)
    except (OSError, ValueError, zipfile.BadZipFile):
        pass
    return found


def matching_version(project, loader, minecraft, requirements):
    """jar の中の指定に合う、最も新しい版。指定がないか、合うものがなければ None。"""
    slug = fetch_json(f"{MODRINTH}/project/{project}").get("slug", "")
    accepts = requirements.get(slug)
    if accepts is None:
        return None
    query = urllib.parse.urlencode({"loaders": json.dumps([loader]), "game_versions": json.dumps([minecraft])})
    versions = fetch_json(f"{MODRINTH}/project/{project}/version?{query}")
    matching = [version for version in versions if accepts(numbers(version.get("version_number", "")))]
    releases = [version for version in matching if version.get("version_type") == "release"]
    return (releases or matching or [None])[0]

def modrinth_files(project, loader, minecraft, seen, cache_dir, version=None):
    """Mod と、それが必要とする Mod のファイル (名前, URL) を集める。"""
    if version is None:
        version = modrinth_version(project, loader, minecraft)
    if version is None:
        return None
    files = []
    primary = next((file for file in version["files"] if file.get("primary")), version["files"][0])
    files.append((primary["filename"], primary["url"]))
    # 必要とする Mod の版は、jar の中の指定に合わせる (最新版同士だと合わないことがある)
    download(primary["url"], cache_dir / primary["filename"])
    requirements = declared_requirements(cache_dir / primary["filename"])
    for dependency in version.get("dependencies", []):
        target = dependency.get("project_id")
        if dependency.get("dependency_type") != "required" or not target or target in seen:
            continue
        seen.add(target)
        if dependency.get("version_id"):
            chosen = fetch_json(f"{MODRINTH}/version/{dependency['version_id']}")
            if minecraft not in chosen.get("game_versions", []) \
                    and fetch_json(f"{MODRINTH}/project/{target}").get("slug", "") not in requirements:
                # 指定された版が、このバージョンの Minecraft 向けでないことがある
                # (Oculus 1.19-1.2.5a は 1.19.2 向けの Rubidium 0.6.2b を指定していて、1.19 / 1.19.1 では Mod なしでも落ちる)。
                # jar の中に版の条件も書かれていなければ、このバージョン向けの最新版を使う
                chosen = modrinth_version(target, loader, minecraft) or chosen
        else:
            chosen = matching_version(target, loader, minecraft, requirements)
        required = modrinth_files(target, loader, minecraft, seen, cache_dir, chosen)
        if required is None:
            return None
        files += required
    return files


def optifine_file(minecraft):
    """Forge と一緒に使える OptiFine。なければ None。"""
    try:
        entries = fetch_json(f"{OPTIFINE}/{minecraft}")
    except OSError:
        return None
    # 試験版でないもののうち、最も新しいもの
    stable = [entry for entry in entries if not entry.get("filename", "").startswith("preview")]
    if not stable:
        return None
    entry = stable[-1]
    return entry["filename"], f"{OPTIFINE}/{minecraft}/{entry['type']}/{entry['patch']}"


def optifabric_file(minecraft, cache_dir):
    """このバージョンに対応する、最も新しい OptiFabric。なければ None。
    CurseForge の対応バージョンの表記は抜けていることがある (1.14.3 は 1.16.5 にも対応しているのに 1.19 以降だけ) ので、
    jar の中の fabric.mod.json で判定する。1.16.5 で 1.13.16 を使うと、OptiFine を読み込まないまま起動してしまう
    (Fabric Loader 0.15.11 で確認)"""
    files = fetch_json(f"{CURSEFORGE}/{OPTIFABRIC_PROJECT}/files?pageIndex=0&pageSize=50"
                       "&sort=dateCreated&sortDescending=true").get("data", [])
    for entry in files[:10]:
        file_id, name = entry["id"], entry["fileName"]
        url = f"https://mediafilez.forgecdn.net/files/{file_id // 1000}/{file_id % 1000}/{name}"
        download(url, cache_dir / name)
        with zipfile.ZipFile(cache_dir / name) as archive:
            wanted = json.loads(archive.read("fabric.mod.json").decode("utf-8")).get("depends", {}).get("minecraft", [])
        if minecraft in (wanted if isinstance(wanted, list) else [wanted]):
            return name, url
    return None


def pack_version(pack, loader, minecraft):
    """シェーダーパックの、指定したバージョン向けの最新版。その表記がなければ、パックの最新版。"""
    version = modrinth_version(pack, loader, minecraft)
    if version is None:
        # シェーダーパックは Minecraft のバージョンにあまり依存しないので、対応表にないだけのことが多い
        query = urllib.parse.urlencode({"loaders": json.dumps([loader])})
        versions = fetch_json(f"{MODRINTH}/project/{pack}/version?{query}")
        releases = [item for item in versions if item.get("version_type") == "release"]
        version = (releases or versions or [None])[0]
    if version is not None and pack in PACK_FILES:
        # 品質違いのファイルが別の版として並んでいるので、同じ版番号の中から使うファイルを探す
        query = urllib.parse.urlencode({"loaders": json.dumps([loader])})
        for item in fetch_json(f"{MODRINTH}/project/{pack}/version?{query}"):
            if item.get("version_number") != version.get("version_number"):
                continue
            wanted = [file for file in item["files"] if file["filename"].endswith(PACK_FILES[pack])]
            if wanted:
                return dict(item, files=wanted)
    return version


def variant(pack_slug=None, shader_mod=None):
    """既定と違う構成で確認するときに、結果の名前に付ける文字列。既定どおりなら空。"""
    parts = [shader_mod or "", "" if (pack_slug or SHADER_PACK) == SHADER_PACK else pack_slug]
    return "-".join(part for part in parts if part)


def resolve(loader, minecraft, cache_dir, pack_slug=SHADER_PACK, shader_mod=None):
    """影 Mod の構成を返す。この組み合わせで使える影 Mod がなければ None。

    shader_mod に "optifine" を指定すると、Forge 1.16.5 以降でも OptiFine を、Fabric では OptiFabric + OptiFine を使う。
    "canvas" を指定すると、Fabric で Canvas を使う (シェーダーパックは使わないので pack は None)。

    戻り値: {"name": 影 Mod の名前, "mods": [(ファイル名, URL)], "config": 設定ファイルの名前, "pack": (ファイル名, URL)}
    """
    library = None
    try:
        if shader_mod == "optifine" and loader not in ("forge", "liteloader", "fabric"):
            return None
        if shader_mod == "canvas":
            if loader != "fabric":
                return None
            version = modrinth_version("canvas", loader, minecraft)
            if version is None:
                return None
            primary = next((file for file in version["files"] if file.get("primary")), version["files"][0])
            # シェーダーパックの代わりに、Canvas に入っている描画パイプラインの名前 (canvas_basic / canvas_standard) を指定できる
            pipeline = pack_slug if pack_slug and pack_slug.startswith("canvas_") else None
            return {"name": "Canvas", "mods": [(primary["filename"], primary["url"])], "config": "config/canvas.json5",
                    "pack_slug": pipeline, "library": None, "pack": None, "pipeline": pipeline}
        if loader == "fabric" and shader_mod == "optifine":
            # OptiFabric が mods フォルダの OptiFine を読み込む。OptiFabric の版ごとに対応する OptiFine は決まっていて、
            # 各 Minecraft の最後の OptiFine に合わせてある
            optifabric = optifabric_file(minecraft, cache_dir)
            optifine = optifine_file(minecraft)
            mods = [optifabric, optifine] if optifabric and optifine else None
            name, config = "OptiFine", "optionsshaders.txt"
        elif loader in ("fabric", "neoforge"):
            mods = modrinth_files("iris", loader, minecraft, {"iris"}, cache_dir)
            name, config = "Iris", "config/iris.properties"
        elif loader == "forge" and version_key(minecraft) >= (1, 16, 5) and shader_mod != "optifine":
            mods = modrinth_files("oculus", loader, minecraft, {"oculus"}, cache_dir)
            name, config = "Oculus", "config/oculus.properties"
        elif loader == "forge":
            optifine = optifine_file(minecraft)
            mods = [optifine] if optifine else None
            name, config = "OptiFine", "optionsshaders.txt"
        elif loader == "liteloader":
            # LiteLoader には OptiFine を Mod として読み込む仕組みがないので、起動用のプロファイルに組み込む
            optifine = optifine_file(minecraft)
            if optifine is None:
                return None
            download(optifine[1], cache_dir / optifine[0])
            mods, library = [], cache_dir / optifine[0]
            name, config = "OptiFine", "optionsshaders.txt"
        else:
            return None
        if not mods and library is None:
            return None
        pack = pack_version(pack_slug, "optifine" if name == "OptiFine" else "iris", minecraft)
        if pack is None:
            return None
        return {"name": name, "mods": mods, "config": config, "pack_slug": pack_slug, "library": library,
                "pack": (pack["files"][0]["filename"], pack["files"][0]["url"])}
    except OSError:
        return None


def install(setup, cache_dir, work_dir):
    """影 Mod とシェーダーパックを起動フォルダに置き、シェーダーを有効にする。"""
    for filename, url in setup["mods"]:
        cached = cache_dir / filename
        download(url, cached)
        shutil.copy2(cached, work_dir / "mods" / filename)
    if setup["pack"] is None:
        if setup.get("pipeline"):
            # 足りない項目は Canvas が既定の値で補う
            config = work_dir / setup["config"]
            config.parent.mkdir(parents=True, exist_ok=True)
            config.write_text(f'{{\n\t"pipelineId": "canvas:pipelines/{setup["pipeline"]}.json5"\n}}\n')
        return
    pack_name, pack_url = setup["pack"]
    cached = cache_dir / pack_name
    download(pack_url, cached)
    (work_dir / "shaderpacks").mkdir(exist_ok=True)
    shutil.copy2(cached, work_dir / "shaderpacks" / pack_name)

    config = work_dir / setup["config"]
    config.parent.mkdir(parents=True, exist_ok=True)
    if setup["name"] == "OptiFine":
        config.write_text(f"shaderPack={pack_name}\n")
        # 一部の版 (1.8.8 など) は「高速レンダリング」が最初から有効で、そのままではシェーダーを読み込まない
        (work_dir / "optionsof.txt").write_text("ofFastRender:false\n")
    else:
        config.write_text(f"enableShaders=true\nshaderPack={pack_name}\n")


def is_active(log_text):
    """ログから、シェーダーが実際に有効になったかどうかを判定する。"""
    return any(pattern.search(log_text) for pattern in ACTIVE_PATTERNS)


if __name__ == "__main__":
    import sys

    # 引数: loader:version または loader:version:シェーダーパックの名前 または loader:version:シェーダーパックの名前:optifine
    for item in sys.argv[1:]:
        loader_name, minecraft_version, *rest = item.split(":")
        result = resolve(loader_name, minecraft_version, Path.home() / ".marumasign-verify" / "shadermods", *rest)
        if result is None:
            print(f"{item}: no shader mod")
        else:
            print(f"{item}: {result['name']} {[name for name, _ in result['mods']] or result['library'].name}"
                  f" + {result['pack'][0] if result['pack'] else '(no pack)'}")
