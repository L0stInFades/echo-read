#!/usr/bin/env python3
"""LobeHub 的单色 SVG 图标 → tts-providers.json 里的 logo 字符串。

用法：python3 lobe2logo.py openai.svg        （打印结果，贴进目录里那一家的 "logo"）
图标来自 npm 包 @lobehub/icons-static-svg 的 icons/ 目录（MIT），取不带后缀的单色版。

logo 的写法：路径用 | 分隔，半透明的那一层前面写「不透明度@」；24×24 视口，奇偶填充。
坐标一个数都不改，只把写法规整成任何路径解析器都读得对的样子：
  - 弧线的两个标志位各自成词（原文常写成 `0 110 10.17`）；
  - 闭合（z）之后的起笔一律写成绝对的 M：有的解析器闭合后不把当前点复位到子路径起点，
    后面跟相对坐标就整段画偏（Compose 的 PathParser 出过这个问题，见 ui/Icons.kt）。
"""
import re
import sys
import xml.etree.ElementTree as ET

ARGS = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'A': 7, 'Z': 0}
NUMBER = re.compile(r'[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?')


def commands(d):
    """路径数据 → [(命令字母, [数字原文])]，连写的命令展开成一条一条。"""
    out, i, cmd = [], 0, None
    while i < len(d):
        if d[i] in ' ,\t\r\n':
            i += 1
        elif d[i].isalpha():
            cmd, i = d[i], i + 1
            if cmd in 'Zz':
                out.append(('z', []))
        else:
            args = []
            for k in range(ARGS[cmd.upper()]):
                while d[i] in ' ,\t\r\n':
                    i += 1
                # 弧线的第 4、5 个参数是标志位：只占一个字符，后面可以紧跟下一个数
                m = re.compile('[01]').match(d, i) if cmd in 'Aa' and k in (3, 4) else NUMBER.match(d, i)
                args.append(m.group())
                i = m.end()
            out.append((cmd, args))
            cmd = {'M': 'L', 'm': 'l'}.get(cmd, cmd)  # M 后面连写的坐标是直线
    return out


def num(v):
    return ('%.3f' % v).rstrip('0').rstrip('.') or '0'


def normalize(d):
    x = y = sx = sy = 0.0  # 当前点、子路径起点
    out, closed = '', False
    for cmd, args in commands(d):
        up, rel, v = cmd.upper(), cmd.islower(), [float(a) for a in args]
        if up == 'Z':
            x, y, closed = sx, sy, True
            out += 'z'
            continue
        if up == 'M':
            x, y = (x + v[0], y + v[1]) if rel else (v[0], v[1])
            sx, sy, closed = x, y, False
            out += 'M%s %s' % (num(x), num(y))
            continue
        if closed:  # 闭合后没有起笔就接着画：把起点明写出来
            out, closed = out + 'M%s %s' % (num(sx), num(sy)), False
        if up == 'H':
            x = x + v[0] if rel else v[0]
        elif up == 'V':
            y = y + v[0] if rel else v[0]
        else:
            x, y = (x + v[-2], y + v[-1]) if rel else (v[-2], v[-1])
        out += cmd + ''.join(a if k == 0 or a.startswith('-') else ' ' + a for k, a in enumerate(args))
    return out


def logo(svg):
    root = ET.parse(svg).getroot()
    assert root.get('viewBox') == '0 0 24 24', 'logo 的视口是 24×24'
    parts = []
    for el in root.iter('{http://www.w3.org/2000/svg}path'):
        alpha = float(el.get('fill-opacity', 1)) * float(el.get('opacity', 1))
        parts.append(('%s@' % num(alpha).lstrip('0') if alpha < 1 else '') + normalize(el.get('d')))
    return '|'.join(parts)


if __name__ == '__main__':
    print(logo(sys.argv[1]))
