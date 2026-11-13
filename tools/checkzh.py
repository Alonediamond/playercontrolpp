"""列出仍含英文注释的源码文件（按英文注释行数排序）。用法：python tools/checkzh.py [--show 文件名片段]"""
import io
import os
import re
import sys

ROOT = 'src/main/java'
# 允许保留的英文：标识符、类名、注解、语言键等
ALLOW = re.compile(
    r'^[\s*/]*(?:@\w+|\{@\w+.*\}|<[^>]+>|[\w.$]+\(\)|[A-Za-z_$][\w.$]*|[-=|·\d\s.,:;()\[\]{}<>/*"\'`&%+#!?]+)*$'
)
CJK = re.compile(r'[一-鿿]')
# 一行里出现两个及以上「小写英文单词」且没有中文 -> 判定为英文注释
WORDS = re.compile(r'[A-Za-z]{3,}')

STOP = {
    'true', 'false', 'null', 'int', 'boolean', 'void', 'static', 'final', 'class',
    'the', 'and', 'for', 'not', 'see', 'code', 'link', 'return', 'param', 'org',
    'com', 'net', 'java', 'util', 'minecraft', 'playercontrolpp', 'tick', 'ticks',
}


def english_comment_lines(path):
    out = []
    with io.open(path, encoding='utf-8') as f:
        lines = f.read().split('\n')
    in_block = False
    for i, line in enumerate(lines, 1):
        s = line.strip()
        is_comment = False
        if in_block:
            is_comment = True
            if '*/' in s:
                in_block = False
        elif s.startswith('/*'):
            is_comment = True
            if '*/' not in s:
                in_block = True
        elif s.startswith('//'):
            is_comment = True
        elif '//' in s and not s.startswith('import'):
            s = s[s.index('//'):]
            is_comment = True
        if not is_comment:
            continue
        if CJK.search(s):
            continue
        if s.startswith('//#') or s.startswith('//$$'):
            continue
        words = [w for w in WORDS.findall(s) if w.lower() not in STOP]
        if len(words) >= 3:
            out.append((i, line.rstrip()))
    return out


def main():
    show = None
    if len(sys.argv) > 2 and sys.argv[1] == '--show':
        show = sys.argv[2]

    rows = []
    for dirpath, _dirs, files in os.walk(ROOT):
        for name in files:
            if not name.endswith('.java'):
                continue
            path = os.path.join(dirpath, name)
            hits = english_comment_lines(path)
            if hits:
                rows.append((len(hits), path.replace('\\', '/'), hits))

    rows.sort(reverse=True)
    total = 0
    for n, path, hits in rows:
        total += n
        short = path.split('com/alonediamond/playercontrolpp/')[-1]
        print('%4d  %s' % (n, short))
        if show and show in path:
            for ln, text in hits:
                print('      %d: %s' % (ln, text))
    print('---- %d files, %d english comment lines ----' % (len(rows), total))


if __name__ == '__main__':
    main()
