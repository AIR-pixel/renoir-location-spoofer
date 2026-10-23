import struct
import sys

p = sys.argv[1] if len(sys.argv) > 1 else r"C:\Users\air\Downloads\_extract_boot\boot.img"
d = open(p, "rb").read(2048)

if d[:8] != b"ANDROID!":
    print("not a boot image:", d[:16].hex())
    sys.exit(1)

hv = struct.unpack("<I", d[40:44])[0]
print("header_version:", hv)

if hv >= 3:
    kernel_size = struct.unpack("<I", d[8:12])[0]
    ramdisk_size = struct.unpack("<I", d[12:16])[0]
    os_version = struct.unpack("<I", d[16:20])[0]
    header_size = struct.unpack("<I", d[20:24])[0]
    print("kernel_size   :", kernel_size)
    print("ramdisk_size  :", ramdisk_size, "(0 means no ramdisk)")
    print("os_version    :", os_version, "-> Android", (os_version >> 11) & 0x7F, "patch", os_version & 0x7FF)
    print("header_size   :", header_size)
    cmd = d[44:44 + 1536].split(b"\x00")[0].decode("utf-8", "ignore")
    print("cmdline       :", repr(cmd))
    for token in cmd.split():
        if "verifiedbootstate" in token or "device_state" in token or "veritymode" in token or "bootconfig" in token:
            print("  [flag]", token)
else:
    print("kernel_size   :", struct.unpack("<I", d[8:12])[0])
    print("ramdisk_size  :", struct.unpack("<I", d[16:20])[0])
    print("cmdline       :", repr(d[64:576].split(b"\x00")[0].decode("utf-8", "ignore")))
