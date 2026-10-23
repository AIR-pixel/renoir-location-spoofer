import gzip
import struct
import sys

PAGE = 4096


def align(n, a=PAGE):
    return (n + a - 1) // a * a


def parse(path):
    d = open(path, "rb").read()
    if d[:8] != b"ANDROID!":
        raise SystemExit(f"{path}: not a boot image")
    hv = struct.unpack("<I", d[40:44])[0]
    if hv < 3:
        raise SystemExit(f"{path}: header v{hv} not handled here")
    kernel_sz = struct.unpack("<I", d[8:12])[0]
    ramdisk_sz = struct.unpack("<I", d[12:16])[0]
    header_sz = struct.unpack("<I", d[20:24])[0]

    ram_off = align(header_sz) + align(kernel_sz)
    blob = d[ram_off:ram_off + ramdisk_sz]
    print(f"{path}")
    print(f"  hdr_ver={hv} kernel={kernel_sz} ramdisk={ramdisk_sz} ramdisk_off={ram_off}")
    print(f"  magic={blob[:4].hex()} (1f8b=gzip)")
    try:
        raw = gzip.decompress(blob) if blob[:2] == b"\x1f\x8b" else blob
    except Exception as e:
        print("  decompress failed:", e)
        return
    print(f"  cpio size={len(raw)}")

    marks = [b"magiskinit", b"overlay.d", b".backup/.magisk", b"magisk.xz", b"init-ld", b"stub.xz"]
    for m in marks:
        print(f"  contains {m.decode():18s}: {raw.count(m) > 0}  (x{raw.count(m)})")


for p in sys.argv[1:]:
    parse(p)
    print()
