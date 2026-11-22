#!/usr/bin/env python3
"""get_xbe.py - copy default.xbe out of an Xbox disc image (.iso), nothing else.

    python3 get_xbe.py "Tony Hawk's Pro Skater 2x (USA).iso" [default.xbe]

Python 3 only, no other packages: runs on a PC, a Mac, or a phone in Termux. The image is only read.
Handles a plain XISO and a full disc dump (the game partition at 0x18300000, 0x0FD90000 or 0x02080000).
"""
import hashlib, struct, sys

SECTOR = 2048
MAGIC = b'MICROSOFT*XBOX*MEDIA'
BASES = (0, 0x18300000, 0x0FD90000, 0x02080000)
THPS2X_USA_SHA1 = '469818e015aed2730641c0bdcbd2557d8a6b8bb4'


def find_partition(f):
    for base in BASES:
        f.seek(base + 32 * SECTOR)
        head = f.read(0x1C)
        if head[:20] == MAGIC:
            root_sector, root_size = struct.unpack('<II', head[20:28])
            return base, root_sector, root_size
    raise SystemExit('not an Xbox disc image (no XDVDFS volume found)')


def find_in_dir(f, base, sector, size, want):
    """The directory is a binary tree of entries; offsets to the left/right child in 4-byte units."""
    f.seek(base + sector * SECTOR)
    table = f.read(size)
    todo, seen = [0], set()
    while todo:
        off = todo.pop()
        if off in seen or off + 14 > len(table):
            continue
        seen.add(off)
        left, right, fsec, fsize, attr, nlen = struct.unpack('<HHIIBB', table[off:off + 14])
        if left == 0xFFFF and right == 0xFFFF:
            continue                      # padding
        name = table[off + 14:off + 14 + nlen].decode('latin-1')
        if name.lower() == want.lower() and not attr & 0x10:
            return fsec, fsize
        if left: todo.append(left * 4)
        if right: todo.append(right * 4)
    return None


def main():
    if len(sys.argv) < 2:
        print(__doc__); return 1
    src = sys.argv[1]
    dst = sys.argv[2] if len(sys.argv) > 2 else 'default.xbe'
    with open(src, 'rb') as f:
        base, root_sector, root_size = find_partition(f)
        hit = find_in_dir(f, base, root_sector, root_size, 'default.xbe')
        if not hit:
            raise SystemExit('default.xbe not found in the image')
        sec, size = hit
        f.seek(base + sec * SECTOR)
        data = f.read(size)
    if data[:4] != b'XBEH':
        raise SystemExit('the file found is not an XBE')
    with open(dst, 'wb') as o:
        o.write(data)
    sha1 = hashlib.sha1(data).hexdigest()
    print(f'{dst}: {size:,} bytes, SHA-1 {sha1}')
    print('THPS 2X (USA): yes' if sha1 == THPS2X_USA_SHA1 else 'Not the THPS 2X (USA) version the port supports.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
