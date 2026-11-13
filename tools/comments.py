"""注释块工具：dump 只打印英文注释块，apply 按块号整块替换。

    python tools/comments.py dump <java文件相对路径>
    python tools/comments.py apply <java文件相对路径> <译文json>

译文 json 形如 {"3": "新的整块注释（含缩进与 /** */）", ...}
"""
import io
import json
import re
import sys

CJK = re.compile(r'[一-鿿]')
WORDS = re.compile(r'[A-Za-z]{3,}')
STOP = {
    'true', 'false', 'null', 'int', 'boolean', 'void', 'static', 'final', 'class',
    'the', 'and', 'for', 'not', 'see', 'code', 'link', 'return', 'param', 'org',
    'com', 'net', 'java', 'util', 'minecraft', 'playercontrolpp',
}


def blocks(lines):
    """切出所有注释块：连续的 // 行算一块，/* ... */ 算一块。返回 [(start, end)] 0-based 闭区间。"""
    out = []
    i = 0
    n = len(lines)
    while i < n:
        s = lines[i].strip()
        if s.startswith('/*'):
            j = i
            while j < n and '*/' not in lines[j]:
                j += 1
            out.append((i, min(j, n - 1)))
            i = j + 1
            continue
        if s.startswith('//') and not s.startswith('//#') and not s.startswith('//$$'):
            j = i
            while (j + 1 < n and lines[j + 1].strip().startswith('//')
                   and not lines[j + 1].strip().startswith('//#')
                   and not lines[j + 1].strip().startswith('//$$')):
                j += 1
            out.append((i, j))
            i = j + 1
            continue
        i += 1
    return out


def is_english(text):
    if CJK.search(text):
        return False
    words = [w for w in WORDS.findall(text) if w.lower() not in STOP]
    return len(words) >= 3


def main():
    mode = sys.argv[1]
    path = sys.argv[2]
    with io.open(path, encoding='utf-8') as f:
        lines = f.read().split('\n')

    ranges = blocks(lines)

    if mode == 'dump':
        for idx, (a, b) in enumerate(ranges):
            text = '\n'.join(lines[a:b + 1])
            if not is_english(text):
                continue
            print('@@%d' % idx)
            print(text)
        return

    with io.open(sys.argv[3], encoding='utf-8') as f:
        tr = json.load(f)

    for key in sorted(tr, key=lambda k: -int(k)):
        a, b = ranges[int(key)]
        new = tr[key].split('\n')
        lines[a:b + 1] = new

    with io.open(path, 'w', encoding='utf-8', newline='') as f:
        f.write('\n'.join(lines))
    print('applied %d blocks to %s' % (len(tr), path))


if __name__ == '__main__':
    main()
