"""按控件 id 点击设备上的按钮，边界当场现取，避免布局漂移导致点空。

用法：python tools/ui_tap.py btnSave [--dump]
"""
import os
import re
import subprocess
import sys
import time

ENV = {**os.environ, "MSYS_NO_PATHCONV": "1"}


def sh(*args, check=True):
    p = subprocess.run(["adb", *args], capture_output=True, text=True,
                       encoding="utf-8", errors="replace", env=ENV, timeout=60)
    if check and p.returncode != 0:
        print(f"[warn] adb {' '.join(args)} -> {p.returncode}: {p.stderr.strip()[:200]}")
    return p.stdout


def dump_ui() -> str:
    sh("shell", "su", "-c", "uiautomator dump /sdcard/u.xml >/dev/null 2>&1")
    return sh("shell", "su", "-c", "cat /sdcard/u.xml")


def find(xml: str, res_id: str):
    """返回 (bounds, text)；bounds = (x1, y1, x2, y2)。res_id 支持 "text:广州" 形式。"""
    if res_id.startswith("text:"):
        want = res_id[5:]
        for node in xml.split("<"):
            t = re.search(r'text="([^"]*)"', node)
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if t and b and t.group(1) == want:
                return tuple(int(x) for x in b.groups()), want
        return None, None

    key = f'com.air.locspoof:id/{res_id}"'
    for node in xml.split("<"):
        if key not in node:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        t = re.search(r'text="([^"]*)"', node)
        if b:
            return tuple(int(x) for x in b.groups()), (t.group(1) if t else "")
    return None, None


def main():
    target = sys.argv[1]
    xml = dump_ui()
    bounds, text = find(xml, target)
    if not bounds:
        print(f"找不到控件 {target}")
        for line in xml.split("<"):
            m = re.search(r'resource-id="com.air.locspoof:id/(\w+)"', line)
            if m:
                print("  可见控件:", m.group(1))
        return 1

    x = (bounds[0] + bounds[2]) // 2
    y = (bounds[1] + bounds[3]) // 2
    lat, lat_t = find(xml, "etLat")
    lon, lon_t = find(xml, "etLon")
    print(f"点击 {target} '{text}' @ ({x},{y}) bounds={bounds}")
    print(f"  点击前 etLat={lat_t} etLon={lon_t}")

    sh("shell", "su", "-c", f"input tap {x} {y}")
    time.sleep(3)

    xml2 = dump_ui()
    _, lat2 = find(xml2, "etLat")
    _, lon2 = find(xml2, "etLon")
    print(f"  点击后 etLat={lat2} etLon={lon2}")

    if "--dump" in sys.argv:
        for n in xml2.split("<"):
            m = re.search(r'resource-id="com.air.locspoof:id/(\w+)"', n)
            t = re.search(r'text="([^"]*)"', n)
            if m and t and t.group(1):
                print(f"    {m.group(1)} = {t.group(1)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
