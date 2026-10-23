#!/usr/bin/env python3
"""按「方法签名」在 APK 的 dex 里定位混淆类。

用途：第三方 App 更新后 Hook 静默失效，用它确认「目标方法还在不在、在哪一层」。

## 用法

    # 1) 先解出 dex（只解需要的那几个也行）
    mkdir -p out/dex && cd out/dex
    unzip -o -q /path/to/app.apk 'classes*.dex'

    # 2) 按签名找类（参数写成 dex 类型描述符：Z=boolean, I=int, Ljava/lang/String;）
    python tools/dex_anchor_scan.py --dex-dir out/dex \
        --method showDialog --params Z,Ljava/lang/String; --ret V

    # 3) 想看继承链：先找到 manifest 里稳定的组件类名，再让它爬父类
    python tools/dex_anchor_scan.py --dex-dir out/dex \
        --super-chain Lcom/netease/cloudmusic/activity/PlayerActivity;

## 输出

    classes14.dex  Lcom/netease/cloudmusic/activity/ma;  showDialog(Z,String)V
    PlayerActivity -> t8 -> u4 -> ma -> m -> i -> ... -> Landroid/app/Activity;

## 注意

- 参数类型用 **dex 描述符**，不是 Java 类名：`boolean` 写 `Z`、
  `java.lang.String` 写 `Ljava/lang/String;`、`int` 写 `I`。
- 一个 APK 有多个 dex 时要全部解出来，目标类可能在任意一个里。
- 这是**离线**分析工具，和运行时无关；运行时定位请用 `AnchorFinder`。
"""
import argparse
import glob
import os
import struct
import sys


def uleb(buf, off):
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, off


class Dex:
    def __init__(self, path):
        self.path = path
        self.d = open(path, 'rb').read()
        d = self.d
        self.string_size, s_off = struct.unpack_from('<II', d, 0x38)
        self.type_size, t_off = struct.unpack_from('<II', d, 0x40)
        self.proto_size, p_off = struct.unpack_from('<II', d, 0x48)
        self.field_size, f_off = struct.unpack_from('<II', d, 0x50)
        self.method_size, m_off = struct.unpack_from('<II', d, 0x58)
        self.class_size, c_off = struct.unpack_from('<II', d, 0x60)
        self.s_off, self.t_off = s_off, t_off
        self.p_off, self.m_off, self.f_off = p_off, m_off, f_off
        self.c_off = c_off
        self._cache = {}

    def string(self, idx):
        if idx in self._cache:
            return self._cache[idx]
        off = struct.unpack_from('<I', self.d, self.s_off + 4 * idx)[0]
        _, p = uleb(self.d, off)
        end = self.d.index(b'\x00', p)
        val = self.d[p:end].decode('utf-8', 'replace')
        self._cache[idx] = val
        return val

    def type_name(self, idx):
        if idx >= self.type_size:
            return '?'
        return self.string(struct.unpack_from('<I', self.d, self.t_off + 4 * idx)[0])

    def method_desc(self, midx):
        cls, proto, name = struct.unpack_from('<HHI', self.d, self.m_off + 8 * midx)
        ret, params = '', []
        if proto < self.proto_size:
            _, ret_idx, params_off = struct.unpack_from('<III', self.d, self.p_off + 12 * proto)
            ret = self.type_name(ret_idx)
            if params_off:
                n = struct.unpack_from('<I', self.d, params_off)[0]
                params = [self.type_name(struct.unpack_from('<H', self.d, params_off + 4 + 2 * i)[0])
                          for i in range(n)]
        return self.type_name(cls), self.string(name), params, ret

    def classes(self):
        """遍历 (类名, 父类名, class_data_off)"""
        for ci in range(self.class_size):
            base = self.c_off + 32 * ci
            name = self.type_name(struct.unpack_from('<I', self.d, base)[0])
            super_name = self.type_name(struct.unpack_from('<I', self.d, base + 8)[0])
            cdo = struct.unpack_from('<I', self.d, base + 24)[0]
            yield name, super_name, cdo

    def methods(self, cdo):
        if not cdo:
            return
        p = cdo
        sf, p = uleb(self.d, p)
        inf, p = uleb(self.d, p)
        dm, p = uleb(self.d, p)
        vm, p = uleb(self.d, p)
        for _ in range(sf + inf):
            _, p = uleb(self.d, p)
            _, p = uleb(self.d, p)
        for count in (dm, vm):
            midx = 0
            for _ in range(count):
                diff, p = uleb(self.d, p)
                midx += diff
                _, p = uleb(self.d, p)
                _, p = uleb(self.d, p)
                yield midx


def load_all(dex_dir):
    files = sorted(glob.glob(os.path.join(dex_dir, '*.dex')),
                   key=lambda p: (len(p), p))
    if not files:
        sys.exit(f'在 {dex_dir} 里没找到 .dex 文件 —— 先用 unzip 解出 classes*.dex')
    return [Dex(f) for f in files]


def scan_signature(dexes, method, params, ret):
    want_params = [p for p in params.split(',') if p] if params else []
    hits = []
    for dex in dexes:
        for cls, _sup, cdo in dex.classes():
            if not cdo:
                continue
            for midx in dex.methods(cdo):
                _c, name, ps, r = dex.method_desc(midx)
                if name != method:
                    continue
                if want_params and ps != want_params:
                    continue
                if ret and r != ret:
                    continue
                hits.append((os.path.basename(dex.path), cls,
                             f"{name}({','.join(ps)}){r}"))
    return list(dict.fromkeys(hits))


def print_super_chain(dexes, start):
    sup = {}
    for dex in dexes:
        for cls, super_name, _cdo in dex.classes():
            sup[cls] = super_name
    cur = start
    if cur not in sup:
        sys.exit(f'类不存在：{start}（检查是否写成了 dot 形式，需 dex 描述符 L...;）')
    chain, depth = [], 0
    while cur in sup and depth < 32:
        chain.append(cur)
        cur = sup[cur]
        depth += 1
    print(' -> '.join(chain))


def main():
    ap = argparse.ArgumentParser(description='按方法签名 / 继承链定位混淆类')
    ap.add_argument('--dex-dir', required=True, help='解出的 classes*.dex 所在目录')
    ap.add_argument('--method', help='方法名')
    ap.add_argument('--params', default='', help='参数 dex 描述符，逗号分隔，如 Z,Ljava/lang/String;')
    ap.add_argument('--ret', default='', help='返回类型 dex 描述符，如 V')
    ap.add_argument('--super-chain', help='打印某个类的继承链，参数为 dex 描述符')
    args = ap.parse_args()

    dexes = load_all(args.dex_dir)

    if args.super_chain:
        print_super_chain(dexes, args.super_chain)
        return

    if not args.method:
        sys.exit('要么给 --method，要么给 --super-chain')

    hits = scan_signature(dexes, args.method, args.params, args.ret)
    if not hits:
        print('没有匹配的类 —— 方法签名可能已变化，需按文档第七节重新定位')
        return
    for f, cls, sig in hits:
        print(f'{f}\t{cls}\t{sig}')
    if len(hits) > 1:
        print(f'\n⚠️ 命中 {len(hits)} 个候选，需要再加判据（继承链 / 字符串特征 / 多方法组合）',
              file=sys.stderr)


if __name__ == '__main__':
    main()
