#!/usr/bin/env python3
"""android_pack.py - THPS 2X for Android: the shell APK (CI artifact thps2x-android-shell) plus the game
(libmain.so, built from the player's own default.xbe by tools/android_build.sh) -> an installable APK.

    tools/android_pack.py <shell.apk> <libmain.so> <out.apk> [--key key.json]

Every entry of the shell is copied as it is (compression kept), its old signature dropped, and
lib/arm64-v8a/libmain.so added (more libraries with --lib NAME=FILE). The result is signed with APK
Signature Scheme v2 (RSA-2048, SHA-256, self-signed certificate) using the Python standard library only.
The key (key.json, made on first use) must stay the same for later builds to install over earlier ones.
"""
import argparse, hashlib, json, os, secrets, struct, sys, zlib

BLOCK = 1 << 20

# ---------------- RSA key, DER, self-signed certificate ----------------

def _is_prime(n):
    if n < 2: return False
    for p in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37):
        if n % p == 0: return n == p
    d, r = n - 1, 0
    while d % 2 == 0: d //= 2; r += 1
    for _ in range(40):
        x = pow(secrets.randbelow(n - 3) + 2, d, n)
        if x in (1, n - 1): continue
        for _ in range(r - 1):
            x = pow(x, 2, n)
            if x == n - 1: break
        else: return False
    return True

def _prime(bits):
    while True:
        p = secrets.randbits(bits) | (3 << (bits - 2)) | 1
        if p % 65537 != 1 and _is_prime(p): return p

def gen_key():
    p, q = _prime(1024), _prime(1024)
    n, e = p * q, 65537
    return {'n': n, 'e': e, 'd': pow(e, -1, (p - 1) * (q - 1))}

def _der(tag, body):
    l = len(body)
    ln = bytes([l]) if l < 0x80 else (lambda b: bytes([0x80 | len(b)]) + b)(l.to_bytes((l.bit_length() + 7) // 8, 'big'))
    return bytes([tag]) + ln + body
def _int(v): return _der(0x02, v.to_bytes(v.bit_length() // 8 + 1, 'big'))
def _seq(*xs): return _der(0x30, b''.join(xs))
def _oid(s):
    a = [int(x) for x in s.split('.')]; out = bytes([a[0] * 40 + a[1]])
    for v in a[2:]:
        b = [v & 0x7f]; v >>= 7
        while v: b.append(0x80 | (v & 0x7f)); v >>= 7
        out += bytes(reversed(b))
    return _der(0x06, out)
NULL = b'\x05\x00'
SHA256_RSA = _seq(_oid('1.2.840.113549.1.1.11'), NULL)

def spki(k): return _seq(_seq(_oid('1.2.840.113549.1.1.1'), NULL), _der(0x03, b'\0' + _seq(_int(k['n']), _int(k['e']))))

def rsa_sign(k, msg):
    di = bytes.fromhex('3031300d060960864801650304020105000420') + hashlib.sha256(msg).digest()
    kl = (k['n'].bit_length() + 7) // 8
    em = b'\x00\x01' + b'\xff' * (kl - len(di) - 3) + b'\x00' + di
    return pow(int.from_bytes(em, 'big'), k['d'], k['n']).to_bytes(kl, 'big')

def cert(k):
    name = _seq(_der(0x31, _seq(_oid('2.5.4.3'), _der(0x0c, b'THPS 2X Android'))))
    validity = _seq(_der(0x17, b'260101000000Z'), _der(0x18, b'20760101000000Z'))
    tbs = _seq(_der(0xa0, _int(2)), _int(secrets.randbits(62) | 1), SHA256_RSA, name, validity, name, spki(k))
    return _seq(tbs, SHA256_RSA, _der(0x03, b'\0' + rsa_sign(k, tbs)))

def load_key(path):
    if not os.path.exists(path):
        k = gen_key()
        with open(path, 'w') as f:
            json.dump({'n': str(k['n']), 'e': str(k['e']), 'd': str(k['d']), 'cert': cert(k).hex()}, f)
        print(f'new signing key: {path} (keep it: later builds must be signed with it)')
    j = json.load(open(path))
    return {a: int(j[a]) for a in ('n', 'e', 'd')}, bytes.fromhex(j['cert'])

# ---------------- ZIP copy ----------------

def read_entries(f):
    f.seek(0, 2); size = f.tell()
    f.seek(size - 22); eocd = f.read(22)
    if eocd[:4] != b'PK\x05\x06': raise SystemExit('not an APK (no end of central directory, or a ZIP comment)')
    count, cd_size, cd_off = struct.unpack('<HII', eocd[10:20])
    f.seek(cd_off); cd = f.read(cd_size)
    out, p = [], 0
    for _ in range(count):
        (sig, vmade, _vneed, flags, method, mtime, mdate, crc, csize, usize, nlen, xlen, clen,
         _disk, _iattr, eattr, loff) = struct.unpack('<IHHHHHHIIIHHHHHII', cd[p:p + 46])
        assert sig == 0x02014b50
        out.append(dict(name=cd[p + 46:p + 46 + nlen], method=method, mtime=mtime, mdate=mdate, crc=crc,
                        csize=csize, usize=usize, loff=loff, flags=flags & ~0x8, eattr=eattr, vmade=vmade))
        p += 46 + nlen + xlen + clen
    return out

def is_old_signature(name):
    n = name.upper()
    return n.startswith('META-INF/') and n.endswith(('.SF', '.RSA', '.DSA', '.EC', '/MANIFEST.MF'))

def write_apk(src_path, out_path, add):
    """add: {entry name: file path}, stored uncompressed (page-aligned libraries)"""
    src = open(src_path, 'rb')
    entries = [e for e in read_entries(src) if not is_old_signature(e['name'].decode()) and e['name'].decode() not in add]
    o = open(out_path + '.unsigned', 'wb')
    cd = []

    def local(e, extra):
        o.write(struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, e['flags'], e['method'], e['mtime'], e['mdate'],
                            e['crc'], e['csize'], e['usize'], len(e['name']), len(extra)) + e['name'] + extra)

    def align_extra(e):
        if e['method'] != 0: return b''
        n = e['name'].decode()
        al = 16384 if n.startswith('lib/') and n.endswith('.so') else 4
        pad = -(o.tell() + 30 + len(e['name'])) % al
        if 0 < pad < 4: pad += al
        return struct.pack('<HH', 0xd935, pad - 4) + bytes(pad - 4) if pad else b''

    for e in entries:
        src.seek(e['loff']); lh = src.read(30)
        ln, lx = struct.unpack('<HH', lh[26:30])
        src.seek(e['loff'] + 30 + ln + lx)
        extra = align_extra(e); off = o.tell(); local(e, extra)
        left = e['csize']
        while left:
            b = src.read(min(BLOCK, left)); o.write(b); left -= len(b)
        cd.append((e, off))
    for name, path in add.items():
        data = open(path, 'rb').read()
        e = dict(name=name.encode(), method=0, mtime=0, mdate=0x5821, crc=zlib.crc32(data), csize=len(data),
                 usize=len(data), flags=0, eattr=0o100644 << 16, vmade=0x031e)
        extra = align_extra(e); off = o.tell(); local(e, extra); o.write(data)
        cd.append((e, off))
    cd_start = o.tell()
    cdb = b''.join(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, e['vmade'], 20, e['flags'], e['method'], e['mtime'],
                               e['mdate'], e['crc'], e['csize'], e['usize'], len(e['name']), 0, 0, 0, 0, e['eattr'], off)
                   + e['name'] for e, off in cd)
    o.write(cdb)
    o.write(struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(cd), len(cd), len(cdb), cd_start, 0))
    o.close()
    return cd_start

# ---------------- APK Signature Scheme v2 ----------------

def sign_v2(unsigned, out_path, cd_start, key, crt):
    def lp(b): return struct.pack('<I', len(b)) + b
    def dg(c): return hashlib.sha256(b'\xa5' + struct.pack('<I', len(c)) + c).digest()
    with open(unsigned, 'rb') as u:
        chunks, left = [], cd_start
        while left:
            b = u.read(min(BLOCK, left)); chunks.append(dg(b)); left -= len(b)
        tail = u.read()
    cdb, end = tail[:-22], tail[-22:]
    chunks += [dg(cdb[i:i + BLOCK]) for i in range(0, len(cdb), BLOCK)]
    chunks.append(dg(end))
    digest = hashlib.sha256(b'\x5a' + struct.pack('<I', len(chunks)) + b''.join(chunks)).digest()
    alg = struct.pack('<I', 0x0103)                       # RSASSA-PKCS1-v1_5 with SHA2-256
    signed = lp(lp(alg + lp(digest))) + lp(lp(crt)) + lp(b'')
    sig = lp(lp(alg + lp(rsa_sign(key, signed))))
    signers = lp(lp(lp(signed) + sig + lp(spki(key))))
    pair = struct.pack('<QI', 4 + len(signers), 0x7109871a) + signers
    bsz = struct.pack('<Q', len(pair) + 24)
    block = bsz + pair + bsz + b'APK Sig Block 42'
    with open(unsigned, 'rb') as u, open(out_path, 'wb') as w:
        left = cd_start
        while left:
            b = u.read(min(BLOCK, left)); w.write(b); left -= len(b)
        w.write(block); w.write(cdb)
        w.write(end[:16] + struct.pack('<I', cd_start + len(block)) + end[20:])
    os.remove(unsigned)

def main():
    ap = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    ap.add_argument('shell'); ap.add_argument('libmain'); ap.add_argument('out')
    ap.add_argument('--key', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'private', 'android-key.json'))
    ap.add_argument('--lib', action='append', default=[], metavar='NAME=FILE', help='another library for lib/arm64-v8a/')
    a = ap.parse_args()
    add = {'lib/arm64-v8a/libmain.so': a.libmain}
    for x in a.lib:
        n, _, f = x.partition('=')
        add[f'lib/arm64-v8a/{n}'] = f
    os.makedirs(os.path.dirname(os.path.abspath(a.key)), exist_ok=True)
    key, crt = load_key(a.key)
    cd_start = write_apk(a.shell, a.out, add)
    sign_v2(a.out + '.unsigned', a.out, cd_start, key, crt)
    print(f'{a.out}: {os.path.getsize(a.out):,} bytes, signed')

if __name__ == '__main__':
    sys.exit(main())
