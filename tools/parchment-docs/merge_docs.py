# -*- coding: utf-8 -*-
"""把 Parchment 1.21.11 的 Javadoc 合并进 26.x 的未混淆反编译源码。

保守策略：只在「类名 + 方法名 + 参数个数」能唯一确定时注入，有歧义就跳过。
输出独立的 sources jar，不覆盖 Loom 的原始产物。
"""
import io
import json
import os
import re
import sys
import zipfile

from tree_sitter import Language, Parser
import tree_sitter_java

PARSER = Parser(Language(tree_sitter_java.language()))
CLASS_TYPES = {
    'class_declaration', 'interface_declaration', 'enum_declaration',
    'record_declaration', 'annotation_type_declaration',
}
METHOD_TYPES = {'method_declaration', 'constructor_declaration'}
PRIM = {'B': 'byte', 'C': 'char', 'D': 'double', 'F': 'float', 'I': 'int',
        'J': 'long', 'S': 'short', 'Z': 'boolean', 'V': 'void'}


def jvm_param_types(desc):
    if not desc or not desc.startswith('('):
        return None
    i, out = 1, []
    try:
        while desc[i] != ')':
            start = i
            while desc[i] == '[':
                i += 1
            if desc[i] == 'L':
                j = desc.index(';', i)
                out.append(desc[start:j + 1])
                i = j + 1
            else:
                out.append(desc[start:i + 1])
                i += 1
    except (IndexError, ValueError):
        return None
    return out


def simple_type(t):
    t = t.strip()
    dims = 0
    while t.startswith('['):
        dims += 1
        t = t[1:]
    if t.startswith('L'):
        name = t[1:-1].split('/')[-1].split('$')[-1]
    else:
        name = PRIM.get(t, t)
    return name, dims


def load_parchment(path):
    if path.lower().endswith('.zip'):
        with zipfile.ZipFile(path) as zf:
            data = json.loads(zf.read('parchment.json').decode('utf-8'))
    else:
        data = json.load(open(path, encoding='utf-8'))
    classes = {}
    for c in data.get('classes', []):
        methods = []
        for m in c.get('methods', []):
            types = jvm_param_types(m.get('descriptor', ''))
            params = [{'index': p.get('index'), 'name': p.get('name'), 'doc': p.get('javadoc')}
                      for p in m.get('parameters', []) if p.get('javadoc')]
            methods.append({
                'name': m.get('name'),
                'arity': None if types is None else len(types),
                'types': types,
                'doc': m.get('javadoc') or [],
                'params': params,
            })
        fields = {}
        for f in c.get('fields', []):
            if f.get('javadoc'):
                fields.setdefault(f.get('name'), []).append(f.get('javadoc'))
        classes[c['name']] = {
            'doc': c.get('javadoc') or [],
            'methods': methods,
            'fields': fields,
        }
    return classes


def sanitize(line):
    return line.replace('*/', '*&#47;').rstrip()


def render_doc(parch_doc, params, indent):
    if not parch_doc and not params:
        return b''
    out = [indent + '/**']
    for line in parch_doc:
        out.append(indent + ' *' + ((' ' + sanitize(line)) if line.strip() else ''))
    if params:
        if parch_doc:
            out.append(indent + ' *')
        for name, doc in params:
            out.append(indent + ' * @param ' + name + ' ' + sanitize(doc))
    out.append(indent + ' */')
    return ('\n'.join(out) + '\n').encode('utf-8')


def node_text(src, node):
    return src[node.start_byte:node.end_byte].decode('utf-8', 'replace')


def type_simple(src, type_node):
    txt = node_text(src, type_node)
    txt = re.sub(r'<.*', '', txt, flags=re.S)
    txt = re.sub(r'@\w+(\([^)]*\))?', '', txt).strip()
    parts = txt.split()
    base = parts[-1] if parts else txt
    base = base.split('.')[-1]
    dims = 0
    while base.endswith('[]'):
        dims += 1
        base = base[:-2]
    return base, dims


def line_start(src, pos):
    return src.rfind(b'\n', 0, pos) + 1


def line_indent(src, pos):
    start = line_start(src, pos)
    prefix = src[start:pos]
    if prefix.strip(b' \t') == b'':
        return prefix.decode('utf-8', 'replace')
    return re.match(rb'[ \t]*', src[start:]).group(0).decode('utf-8', 'replace')


def types_match(m, src_types):
    """参数类型简单名 + 是否数组必须一致；无法判断的源参数视为通过。"""
    if m['types'] is None or len(m['types']) != len(src_types):
        return False
    for jvm, (sname, sdims) in zip(m['types'], src_types):
        if sname == '?':
            continue
        jname, jdims = simple_type(jvm)
        if jname != sname or (jdims > 0) != (sdims > 0):
            return False
    return True


def slot_width(jvm):
    return 2 if jvm in ('J', 'D') else 1


def slot_to_ordinal(types, is_static, slot):
    """把 Parchment 的 JVM 局部变量槽位换成参数序号（this/长整型占两槽）。"""
    if types is None or slot is None:
        return None
    cur = 0 if is_static else 1
    for i, t in enumerate(types):
        if cur == slot:
            return i
        cur += slot_width(t)
    return None


def resolve_params(entry, src_names, is_static, arity):
    """按槽位把 @param 换成本版本源码里的实际参数名。"""
    out, used = [], set()
    for p in entry['params']:
        ordinal = slot_to_ordinal(entry['types'], is_static, p.get('index'))
        if ordinal is None or ordinal >= arity:
            if p.get('name') in src_names:
                ordinal = src_names.index(p['name'])
            else:
                continue
        if ordinal in used:
            continue
        used.add(ordinal)
        name = src_names[ordinal] if ordinal < len(src_names) else p.get('name')
        if name:
            out.append((name, p['doc']))
    return out


def match_method(entry, name, arity, src_types):
    cands = [m for m in entry['methods']
             if m['name'] == name and m['arity'] == arity and (m['doc'] or m['params'])]
    if not cands:
        return None, 'none'
    typed = [m for m in cands if types_match(m, src_types)]
    if not typed:
        return None, 'type_mismatch'
    if len(typed) == 1:
        return typed[0], 'ok'
    return None, 'ambiguous'


def iter_body_members(body):
    """枚举体把普通成员包在 enum_body_declarations 里，这里展开一层。"""
    for ch in body.named_children:
        if ch.type == 'enum_body_declarations':
            yield from ch.named_children
        else:
            yield ch


class Walker:
    def __init__(self, src, parch, stats):
        self.src = src
        self.parch = parch
        self.stats = stats
        self.inserts = []

    def add(self, pos, text):
        if text:
            self.inserts.append((pos, text))

    def walk_class(self, node, bin_name):
        entry = self.parch.get(bin_name)
        if entry is not None:
            self.stats['classes_seen'] += 1
            pos = line_start(self.src, node.start_byte)
            self.add(pos, render_doc(entry['doc'], [], line_indent(self.src, node.start_byte)))
            if entry['doc']:
                self.stats['class_doc'] += 1
        body = node.child_by_field_name('body')
        if body is None:
            return
        for ch in iter_body_members(body):
            if ch.type in CLASS_TYPES:
                name_node = ch.child_by_field_name('name')
                if name_node is not None:
                    self.walk_class(ch, bin_name + '$' + node_text(self.src, name_node))
            elif ch.type in METHOD_TYPES:
                if entry is not None:
                    self.method(ch, entry, bin_name)
            elif ch.type == 'field_declaration':
                if entry is not None:
                    self.field(ch, entry)

    def method(self, node, entry, bin_name):
        self.stats['methods_seen'] += 1
        name_node = node.child_by_field_name('name')
        if name_node is None:
            return
        name = '<init>' if node.type == 'constructor_declaration' else node_text(self.src, name_node)
        params = node.child_by_field_name('parameters')
        src_types, src_names, arity = [], [], 0
        if params is not None:
            for p in params.named_children:
                if p.type == 'receiver_parameter':
                    continue
                if p.type in ('formal_parameter', 'spread_parameter'):
                    arity += 1
                    n = p.child_by_field_name('name')
                    src_names.append(node_text(self.src, n) if n is not None else None)
                    t = p.child_by_field_name('type')
                    if t is None:
                        src_types.append(('?', 0))
                    else:
                        base, dims = type_simple(self.src, t)
                        if p.type == 'spread_parameter':
                            dims += 1
                        src_types.append((base, dims))
        m, why = match_method(entry, name, arity, src_types)
        if m is None:
            self.stats[why] += 1
            return
        mods = next((c for c in node.children if c.type == 'modifiers'), None)
        is_static = bool(mods and 'static' in node_text(self.src, mods))
        params_out = resolve_params(m, src_names, is_static, arity)
        indent = line_indent(self.src, node.start_byte)
        self.add(line_start(self.src, node.start_byte),
                 render_doc(m['doc'], params_out, indent))
        self.stats['method_doc'] += 1

    def field(self, node, entry):
        for v in node.named_children:
            if v.type != 'variable_declarator':
                continue
            n = v.child_by_field_name('name')
            if n is None:
                continue
            docs = entry['fields'].get(node_text(self.src, n))
            if docs:
                indent = line_indent(self.src, node.start_byte)
                self.add(line_start(self.src, node.start_byte), render_doc(docs[0], [], indent))
                self.stats['field_doc'] += 1
                return


def process_source(src, entry_name, parch, stats):
    tree = PARSER.parse(src)
    if tree.root_node.has_error:
        stats['parse_err_before'] += 1
    walker = Walker(src, parch, stats)
    top_path = entry_name[:-5] if entry_name.endswith('.java') else entry_name
    package, _, direct = top_path.rpartition('/')
    for ch in tree.root_node.named_children:
        if ch.type in CLASS_TYPES:
            name_node = ch.child_by_field_name('name')
            if name_node is not None:
                simple = node_text(src, name_node)
                if simple == direct:
                    bin_name = top_path
                elif package:
                    bin_name = package + '/' + simple
                else:
                    bin_name = simple
                walker.walk_class(ch, bin_name)
    if not walker.inserts:
        return src, 0
    seen = set()
    final = []
    for pos, text in walker.inserts:
        if pos in seen:
            continue
        seen.add(pos)
        if src[:pos].rstrip().endswith(b'*/'):
            continue
        final.append((pos, text))
    for pos, text in sorted(final, key=lambda x: x[0], reverse=True):
        src = src[:pos] + text + src[pos:]
    return src, len(final)


def main():
    parch_path, src_jar, out_jar, label = sys.argv[1:5]
    parch = load_parchment(parch_path)
    stats = {
        'classes_seen': 0, 'class_doc': 0, 'method_doc': 0, 'field_doc': 0,
        'methods_seen': 0, 'ambiguous': 0, 'none': 0, 'type_mismatch': 0,
        'files': 0, 'parse_err_before': 0, 'parse_err_after': 0,
    }
    zin = zipfile.ZipFile(src_jar, 'r')
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename.endswith('.java'):
                new, changed = process_source(data, item.filename, parch, stats)
                if changed:
                    stats['files'] += 1
                if PARSER.parse(new).root_node.has_error:
                    stats['parse_err_after'] += 1
                data = new
            zout.writestr(item, data)
    zin.close()
    os.makedirs(os.path.dirname(out_jar), exist_ok=True)
    with open(out_jar, 'wb') as fh:
        fh.write(buf.getvalue())
    print(f'== {label} ==')
    print(f"files modified: {stats['files']}, parse errors before/after: "
          f"{stats['parse_err_before']}/{stats['parse_err_after']}")
    print(f"class docs: {stats['class_doc']}, method docs: {stats['method_doc']}, "
          f"field docs: {stats['field_doc']}")
    print(f"methods seen: {stats['methods_seen']}, ambiguous skipped: {stats['ambiguous']}, "
          f"type mismatch: {stats['type_mismatch']}, no candidate: {stats['none']}")
    print(f"output: {out_jar}")


if __name__ == '__main__':
    main()
