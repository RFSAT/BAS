#!/usr/bin/env python3
"""
Static checks for the semantic Kotlin mistakes that a resource-and-reference
gate cannot see.

WHY THIS EXISTS. Every one of these was found by the compiler in CI after
passing a full pass of the project's other checks, which verify resources,
binding ids, layout attributes, imports and brace balance. Those catch a lot
and were blind to all of this:

  * two companion objects in one class — legal-looking, and it makes every
    constant in the first one unresolvable, so the real error surfaces
    somewhere else entirely;
  * return@label naming a lambda that is no longer there, which is what a
    rename leaves behind;
  * a `when` over an enum that another module has since extended — the
    ported VTB catalogue covered VTB's three click units while this app has
    six.

Run from the project root. Exits non-zero on any finding, so it can gate a
build before the compiler is started.
"""
import glob, os, re, sys

def strip(src):
    out=[];i=0;n=len(src)
    while i<n:
        if src[i:i+2]=='//':
            j=src.find('\n',i); out.append('\n'*src.count('\n',i,j if j>=0 else n)); i=(j if j>=0 else n); continue
        if src[i:i+2]=='/*':
            j=src.find('*/',i+2); k=(j+2) if j>=0 else n; out.append('\n'*src.count('\n',i,k)); i=k; continue
        if src[i:i+3]=='"""':
            j=src.find('"""',i+3); k=(j+3) if j>=0 else n; out.append('\n'*src.count('\n',i,k)); i=k; continue
        c=src[i]
        if c=='`':
            j=src.find('`',i+1); i=(j+1) if j>=0 else n; out.append('X'); continue
        if c=='"':
            i+=1
            while i<n and src[i]!='"':
                if src[i]=='\\': i+=1
                i+=1
            i+=1; out.append('""'); continue
        out.append(c); i+=1
    return "".join(out)

problems=[]
files=glob.glob("app/src/main/java/**/*.kt",recursive=True)+glob.glob("app/src/test/**/*.kt",recursive=True)

# ---- 1. at most one companion object per class body ----
for f in files:
    code=strip(open(f).read())
    stack=[]        # one entry per open brace: the class name it opens, or None
    pending=None
    counts={}
    for m in re.finditer(r'\b(class|object|interface)\s+(\w+)|companion\s+object|\{|\}', code):
        t=m.group(0)
        if t.startswith(('class ','object ','interface ')):
            pending=m.group(2)
        elif t=='companion object':
            owner=next((x for x in reversed(stack) if x), None)
            key=(f,owner)
            counts[key]=counts.get(key,0)+1
            if counts[key]>1:
                line=code.count('\n',0,m.start())+1
                problems.append(f"{os.path.basename(f)}:{line}  {owner} has a second companion object")
            pending="<companion>"
        elif t=='{':
            stack.append(pending); pending=None
        elif t=='}':
            if stack: stack.pop()

# ---- 2. return@label must name an enclosing lambda ----
def _name_before_brace(code, i):
    j=i-1
    while j>=0 and code[j] in ' \t\r\n': j-=1
    if j>=0 and code[j]==')':
        depth=0
        while j>=0:
            if code[j]==')': depth+=1
            elif code[j]=='(':
                depth-=1
                if depth==0:
                    j-=1; break
            j-=1
        while j>=0 and code[j] in ' \t\r\n': j-=1
    elif j>=0 and code[j]=='(':
        j-=1
        while j>=0 and code[j] in ' \t\r\n': j-=1
    end=j+1
    while j>=0 and (code[j].isalnum() or code[j]=='_'): j-=1
    name=code[j+1:end]
    return name if name else None

for f in files:
    code=strip(open(f).read())
    stack=[]; i=0
    labels_at=[]
    while i < len(code):
        c=code[i]
        if c=='{':
            stack.append(_name_before_brace(code, i))
        elif c=='}':
            if stack: stack.pop()
        elif code.startswith('return@', i):
            m=re.match(r'return@(\w+)', code[i:])
            if m:
                lbl=m.group(1)
                if lbl not in [s for s in stack if s]:
                    line=code.count('\n',0,i)+1
                    problems.append(f"{os.path.basename(f)}:{line}  return@{lbl} but the enclosing lambdas are {[s for s in stack if s][-3:]}")
        i+=1

# ---- 3. when over an enum must cover it, or have an else ----
# Enums are collected PER FILE. Two merged codebases can each declare an enum
# with the same simple name — BAS has detect.RegistrationOverlayView.Mode
# {BOX, CORNERS} and capture.TrailExtractor.Mode {VAPOR, TRACER, PELLET} — and
# keying them by name alone made the verdict depend on the order the files were
# globbed. A when in one file was checked against the other file's members.
enums_by_file={}
enums_global={}
_member_re = re.compile(r'^\s*([A-Z][A-Z0-9_]*)\s*(?:[(,;]|//|$)', re.M)
for f in files:
    src=open(f).read()
    local={}
    for m in re.finditer(r'enum class (\w+)[^{]*\{(.*?)\n\s*\}', src, re.S):
        name=m.group(1); body=m.group(2)
        # The LAST member carries no trailing comma or semicolon, so the old
        # pattern silently dropped it and reported it as an uncovered branch.
        members=set(_member_re.findall(body))
        if members:
            local[name]=members
            enums_global.setdefault(name, []).append(members)
    enums_by_file[f]=local
for f in files:
    code=strip(open(f).read())
    for m in re.finditer(r'when\s*\(([^()]*)\)\s*\{', code):
        start=m.end()-1; depth=0; j=start
        while j < len(code):
            if code[j]=='{': depth+=1
            elif code[j]=='}':
                depth-=1
                if depth==0: break
            j+=1
        body=code[start:j]
        if re.search(r'\belse\s*->', body): continue
        # a branch may list several members: "A.X, A.Y, A.Z ->"
        used=[]
        for branch in re.finditer(r'([^\n{}]+?)->', body):
            for t,v in re.findall(r'(\w+)\.([A-Z][A-Z0-9_]*)', branch.group(1)):
                used.append((t,v))
        if not used: continue
        types={t for t,_ in used}
        if len(types)!=1: continue
        t=types.pop()
        local=enums_by_file.get(f, {})
        if t in local:
            members=local[t]                       # declared right here — unambiguous
        else:
            variants=enums_global.get(t)
            if not variants: continue
            distinct=[set(x) for x in {frozenset(v) for v in variants}]
            if len(distinct)!=1: continue          # same name, different enums: cannot resolve
            members=distinct[0]
        covered={v for _,v in used}
        missing=members-covered
        if missing:
            line=code.count('\n',0,m.start())+1
            problems.append(f"{os.path.basename(f)}:{line}  when over {t} is missing {sorted(missing)} and has no else")

# ---- 4. every binding.<id> must exist in that screen's layout ----
#
# View binding generates one field per android:id, so referring to an id the
# layout does not have is a compile error reported against the GENERATED
# class, some way from the layout edit that caused it. Renaming a control and
# missing one of its uses is the usual way in, and that happens in the middle
# of interface work — exactly when the Android toolchain is least available
# for a quick check.
LAYOUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                          "app/src/main/res/layout")

def _ids_of(layout, seen=None):
    """Ids a layout declares, following <include> so merged screens count."""
    seen = seen if seen is not None else set()
    if layout in seen: return set()
    seen.add(layout)
    path = os.path.join(LAYOUT_DIR, layout + ".xml")
    if not os.path.exists(path): return set()
    text = open(path, encoding="utf-8").read()
    found = set(re.findall(r'@\+id/([A-Za-z_][A-Za-z0-9_]*)', text))
    for inc in re.findall(r'<include[^>]*layout="@layout/([A-Za-z0-9_]+)"', text):
        found |= _ids_of(inc, seen)
    return found

for f in files:
    code = strip(open(f).read())
    m = re.search(r'\b(Activity[A-Za-z0-9]*Binding)\.inflate', code)
    if not m: continue
    name = m.group(1)[len("Activity"):-len("Binding")]
    # ActivityResultsBinding -> activity_results. The underscore after
    # "activity" is NOT optional: without it every lookup silently found no
    # layout, every file was skipped, and the gate reported success on a
    # reference to a control that had been deleted.
    layout = "activity_" + re.sub(r'(?<!^)([A-Z])', r'_\1', name).lower()
    declared = _ids_of(layout)
    if not declared: continue
    lines = code.splitlines()
    for u in sorted(set(re.findall(r'\bbinding\.([a-z][A-Za-z0-9_]*)', code)) - declared - {"root"}):
        ln = next((n for n, l in enumerate(lines, 1) if re.search(r'\bbinding\.' + u + r'\b', l)), 0)
        problems.append(f"{os.path.basename(f)}:{ln}  binding.{u} is not an id in {layout}.xml")

# ---- 5. android widget types used by simple name must be imported ----
#
# Kotlin has no implicit imports for the android.* packages, so referring to
# TextView without importing it is a compile error. It is an easy one to
# introduce when adding a findViewById to an activity that did not previously
# need that type, and neither the view-binding gate nor the offline test
# harness can see it: the activities are excluded from the harness because
# they need the whole Android framework to compile at all.
#
# Restricted to a known list of widget types rather than every capitalised
# name, because "is this a type that needs importing" is not decidable from
# the source alone and a gate with false positives gets ignored.
WIDGETS = [
    "TextView", "Button", "EditText", "ImageView", "ImageButton", "TableRow",
    "TableLayout", "LinearLayout", "FrameLayout", "Spinner", "CheckBox",
    "RadioButton", "SeekBar", "ProgressBar", "ScrollView", "ListView",
    "GridLayout", "Switch", "ArrayAdapter", "Toast",
]
for f in files:
    code = strip(open(f).read())
    for w in WIDGETS:
        # A SIMPLE-NAME use: not preceded by a dot or a word character, so
        # android.widget.TextView does not count. That distinction is the
        # whole check — a fully qualified use elsewhere in the file does NOT
        # make the bare name available, and treating it as if it did made an
        # earlier version of this gate pass over a genuine missing import.
        m = re.search(r'(?<![.\w])' + w + r'\b', code)
        if not m: continue
        if re.search(r'^import\s+[\w.]*\.' + w + r'$', code, re.M): continue
        if re.search(r'^import\s+[\w.]*\.' + w + r'\s+as\b', code, re.M): continue
        if re.search(r'\b(class|object|interface)\s+' + w + r'\b', code): continue
        ln = code.count('\n', 0, m.start()) + 1
        problems.append(f"{os.path.basename(f)}:{ln}  {w} is used by simple name but not imported")

# ---- 6. a private function may only be called from its own file ----
#
# Copying a few lines from one activity to another and keeping a helper name
# that only exists in the original is the single most repeated mistake in this
# project — it has reached CI three times. The offline harness cannot catch it
# because the activities need the whole Android framework to compile, so they
# are excluded from it.
#
# This check is sound rather than heuristic: a private function is visible
# only inside its own file, so calling one by an unqualified name from a
# DIFFERENT file is always an error, never a style question. It says nothing
# about names that exist nowhere at all — that needs a compiler.
# Names that are also ordinary members of the Kotlin standard library, so a
# bare call to one inside a builder or a lambda receiver means the stdlib
# member and not some private function of ours that happens to share a name.
STDLIB_COLLISIONS = {
    "add", "remove", "set", "get", "clear", "close", "contains", "put",
    "invoke", "apply", "also", "let", "run", "with", "to", "copy", "toString",
    "equals", "hashCode", "plus", "minus", "times", "div", "compareTo",
}

private_funs = {}          # name -> set of files declaring it
for f in files:
    code = strip(open(f).read())
    for m in re.finditer(r'\bprivate\s+(?:inline\s+|suspend\s+)*fun\s+(?:<[^>]*>\s*)?(\w+)\s*\(', code):
        private_funs.setdefault(m.group(1), set()).add(f)

for f in files:
    code = strip(open(f).read())
    lines = code.splitlines()
    # every function this file can legitimately reach by a bare name
    declared_here = set(re.findall(r'\bfun\s+(?:<[^>]*>\s*)?(\w+)\s*\(', code))
    # A bare name in this file may also be a PARAMETER or a PROPERTY holding a
    # function — FrameSource.start takes an onFrame lambda, and calling it is
    # not a call to SessionActivity's private onFrame. Anything bound by a
    # name here shadows the question entirely.
    bound_here = set(re.findall(r'\b(?:val|var)\s+(\w+)', code))
    bound_here |= set(re.findall(r'(?:fun[^(]*\(|,\s*)(\w+)\s*:\s*\(', code))
    for name, owners in private_funs.items():
        if f in owners or name in declared_here or name in bound_here:
            continue
        if name in STDLIB_COLLISIONS:
            continue
        # an unqualified call: not preceded by a dot, and not a declaration
        call = re.search(r'(?<![.\w])' + name + r'\s*[({]', code)
        if not call:
            continue
        ln = code.count('\n', 0, call.start()) + 1
        where = ", ".join(sorted(os.path.basename(o) for o in owners))
        problems.append(
            f"{os.path.basename(f)}:{ln}  {name}() is private to {where} and cannot be called here")


# ---------------------------------------------------------------------------
#  Gate 7: no break or continue inside an inline lambda.
#
#  This compiles on a modern kotlinc and FAILS IN CI, which is the worst
#  combination there is. "break continue in inline lambdas" arrived in Kotlin
#  language version 2.2; this project builds on 2.1, so
#
#      val x = f() ?: run { count++; continue }
#
#  passed every local check and stopped the release build. The offline
#  type-checker cannot catch it either, because it uses whatever compiler is
#  installed rather than the one Gradle pins.
# ---------------------------------------------------------------------------
INLINE_LAMBDAS = ("run", "let", "also", "apply", "forEach", "takeIf", "takeUnless",
                  "repeat", "with", "use", "onFailure", "onSuccess", "runCatching")

for f in files:
    code = strip(open(f).read())
    for m in re.finditer(r'\b(' + "|".join(INLINE_LAMBDAS) + r')\s*(?:\([^()]*\))?\s*\{', code):
        depth = 0
        i = code.index('{', m.start())
        j = i
        while j < len(code):
            if code[j] == '{': depth += 1
            elif code[j] == '}':
                depth -= 1
                if depth == 0: break
            j += 1
        body = code[i:j]
        # a nested loop inside the lambda makes its own break/continue legal
        if re.search(r'\b(for|while)\s*[({]', body):
            continue
        bc = re.search(r'(?<![.\w])(break|continue)\b', body)
        if bc:
            ln = code.count('\n', 0, i + bc.start()) + 1
            problems.append(
                f"{os.path.basename(f)}:{ln}  {bc.group(1)} inside an inline "
                f"{m.group(1)} lambda needs Kotlin 2.2; this project builds on 2.1")

print(f"{len(files)} Kotlin files checked by the semantic, view-binding, import, visibility and language-level gates")

# ---- 8. imports must follow the package line (before any top-level decl) ----
# Kotlin requires every import immediately after `package`; a top-level const,
# val, fun, class or object placed above the imports makes the compiler reject
# every import below it, with a message that names the import, not the stray
# declaration that caused it.
_DECL = re.compile(r'^(?:@[\w.]+(?:\([^)]*\))?\s*)*'
                   r'(?:public |private |internal |protected |expect |actual |external |'
                   r'abstract |final |open |sealed |data |enum |annotation |inline |value |'
                   r'lateinit |const )*'
                   r'(?:val|var|fun|class|object|interface|typealias)\b')
for f in files:
    src = open(f).read().splitlines()
    first_decl = next((i for i, l in enumerate(src) if _DECL.match(l)), -1)
    if first_decl >= 0:
        for j in range(first_decl + 1, len(src)):
            if src[j].startswith('import '):
                problems.append(f"{os.path.basename(f)}:{j+1}  import after a top-level declaration — imports must follow the package line")
                break


# ---- 9. top-level object/class used unqualified across packages ----
# A reference like RangeSettings.foo() resolves only if RangeSettings is in the
# same package, imported, or fully qualified. The heuristic gates don't resolve
# symbols, so this cross-package slip reaches the compiler as an error reported
# at the use site with no hint of the missing import. This gate catches it.
import re as _re9
_decl_pkgs = {}
_file_pkg = {}
_declre = _re9.compile(r'^(?:public |internal |private |open |sealed |abstract |data |enum |value |)*(?:object|class|interface) (\w+)')
for f in files:
    _lines = open(f).read().splitlines()
    _pkg = next((l[len("package "):].strip() for l in _lines if l.startswith("package ")), "")
    _file_pkg[f] = _pkg
    for l in _lines:
        m = _declre.match(l)
        if m:
            _decl_pkgs.setdefault(m.group(1), set()).add(_pkg)
for f in files:
    _src = open(f).read()
    _pkg = _file_pkg[f]
    _imported = {imp.rsplit(".", 1)[-1] for imp in _re9.findall(r'^import ([\w.]+)', _src, _re9.M)}
    # Block comments have to be TRACKED, not pattern-matched line by line.
    # The old test skipped lines starting with "*", which covers the middle of
    # a KDoc but not its first line — "/** Mirrors StringLabels.remember" was
    # read as code and reported as a missing import. A gate that fires on
    # prose teaches people to work around it, which costs more than the gate
    # was ever worth.
    _in_block = False
    for i, l in enumerate(_src.splitlines()):
        ls = l.lstrip()
        _opened = _in_block
        if "/*" in l and "*/" not in l.split("/*", 1)[1]:
            _in_block = True
        if "*/" in l:
            _in_block = False
        if _opened or ls.startswith("/*") or ls.startswith("*"):
            continue
        if ls.startswith("//") or ls.startswith("package ") or ls.startswith("import "):
            continue
        for m in _re9.finditer(r'(?<![.\w])([A-Z]\w+)\.', l):
            name = m.group(1)
            if name in _decl_pkgs and _pkg not in _decl_pkgs[name] and name not in _imported:
                problems.append(f"{os.path.basename(f)}:{i+1}  '{name}' used unqualified but is declared in {sorted(_decl_pkgs[name])} (add an import or fully qualify)")
                break
        else:
            continue
        break


# ---- 10. Android string resources: escaping and well-formedness ----
# aapt2 treats \ ' and " specially inside a <string>. A bare apostrophe fails
# the build with "Invalid unicode escape sequence in string", reported against
# the resource rather than the edit that caused it — and no Kotlin gate can see
# it, because the fault is in res/values, not in code.
import glob as _g10, re as _re10, xml.etree.ElementTree as _ET10
_VALUES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "app/src/main/res/values")
for _vf in sorted(_g10.glob(os.path.join(_VALUES, "*.xml"))):
    _raw = open(_vf, encoding="utf-8").read()
    try:
        _ET10.fromstring(_raw)
    except Exception as _e:
        problems.append(f"{os.path.basename(_vf)}  not well-formed XML: {_e}")
        continue
    for _m in _re10.finditer(r'<string name="([^"]+)"[^>]*>(.*?)</string>', _raw, _re10.S):
        _name, _body = _m.group(1), _m.group(2)
        if _body.startswith('"') and _body.endswith('"'):
            continue            # fully quoted strings may hold bare apostrophes
        for _ch, _what in (("'", "apostrophe"), ('"', "double quote")):
            if _re10.search(r"(?<!\\)" + _re10.escape(_ch), _body):
                _line = _raw.count("\n", 0, _m.start()) + 1
                problems.append(
                    f"{os.path.basename(_vf)}:{_line}  string/{_name} has an unescaped {_what} "
                    f"— write \\{_ch} (aapt2 rejects it)")
                break


# ---------------------------------------------------------------- gate 11
#
# `this` passed as an argument inside a coroutine builder.
#
# launch/async/withContext take a CoroutineScope RECEIVER, so inside them
# `this` stops being the Activity and becomes the scope. Passing it where a
# Context is wanted fails to compile and reads perfectly:
#
#     lifecycleScope.launch {
#         Something.needsContext(this)      // CoroutineScope, not Activity
#     }
#
# The fix is always this@TheActivity — and the surrounding code usually
# already uses that form a few lines up, which is exactly what makes this
# easy to reintroduce by pasting a call in from somewhere else. It cost a
# CI round trip in 1.29.0.
#
# Only a bare `this` standing as a whole argument is flagged; `this@X` and
# `this.foo` are the correct forms and are left alone.
_coro_open = re.compile(r'\b(?:launch|async)\s*(?:\([^)]*\))?\s*\{|\bwithContext\s*\([^)]*\)\s*\{')
_bare_this = re.compile(r'[(,]\s*this\s*[,)]')
for _f in [x for x in files if (os.sep + "test" + os.sep) not in x]:
    _depth = 0
    for _n, _line in enumerate(open(_f, encoding="utf-8", errors="ignore").read().split("\n"), 1):
        _code = _line.split("//")[0]
        if _depth > 0:
            if _bare_this.search(_code):
                problems.append(f"{os.path.basename(_f)}:{_n}  `this` inside a coroutine "
                                f"builder is the CoroutineScope — use this@ClassName")
            _depth += _code.count("{") - _code.count("}")
            if _depth < 0:
                _depth = 0
            continue
        if _coro_open.search(_code):
            _depth = max(1, 1 + _code.count("{") - _code.count("}") - 1)


# ---------------------------------------------------------------- gate 12
#
# A view with no layout_width/layout_height that no style supplies.
#
# Android does not fail this at build time. It throws
# UnsupportedOperationException DURING INFLATION, so the screen crashes the
# first time it is opened on a device — which is exactly how it shipped in
# 1.31.0: two CheckBoxes copied from a section whose style happened to set
# the dimensions, pasted next to a style that did not.
#
# Styles are read from values/ first, so a view is only reported when
# neither the tag nor its style provides the attribute. <merge>, <include>
# and the root element are skipped: the root's dimensions come from whoever
# inflates it.
_style_dims = {}
_style_parent = {}
for _vf in glob.glob("app/src/main/res/values/*.xml"):
    try:
        _t = _ET10.parse(_vf).getroot()
    except Exception:
        continue
    for _st in _t.findall("style"):
        _name = _st.get("name", "")
        _has = {i.get("name") for i in _st.findall("item")}
        _style_dims[_name] = ("android:layout_width" in _has, "android:layout_height" in _has)
        _style_parent[_name] = _st.get("parent", "")

def _style_gives(style_attr, which):
    """Does this style, or anything it inherits from, set the dimension?"""
    if not style_attr:
        return False
    name = style_attr.replace("@style/", "")
    seen = set()
    while name and name not in seen:
        seen.add(name)
        w, h = _style_dims.get(name, (False, False))
        if (w if which == "w" else h):
            return True
        # An explicit parent="..." WINS: Android uses it instead of the
        # dotted-name convention, and Sts.SetupColon (parent Sts.SetupLabel)
        # is exactly that case. Following only the dots reported half the
        # existing layout as broken, which is how a gate loses its audience.
        explicit = _style_parent.get(name, "")
        if explicit:
            name = explicit.replace("@style/", "")
        else:
            name = name.rsplit(".", 1)[0] if "." in name else ""
    return False

_ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
for _lf in glob.glob("app/src/main/res/layout/*.xml"):
    try:
        _tree = _ET10.parse(_lf)
    except Exception:
        continue
    _root = _tree.getroot()
    for _parent in _tree.iter():
        for _el in list(_parent):
            if _el.tag in ("merge", "include", "requestFocus") or not isinstance(_el.tag, str):
                continue
            # TableRow is a real exception, not an oversight: the framework
            # forces its width to match_parent and its height to wrap_content
            # and IGNORES anything declared, so omitting them is correct.
            # A cell's width is likewise decided by the column, not by itself.
            if _el.tag == "TableRow":
                continue
            _in_row = (_parent.tag == "TableRow")
            _style = _el.get("style")
            for _which, _attr in (("w", "layout_width"), ("h", "layout_height")):
                if _el.get(_ANDROID_NS + _attr):
                    continue
                if _in_row and _which == "w":
                    continue
                if _style_gives(_style, _which):
                    continue
                problems.append(
                    f"{os.path.basename(_lf)}  <{_el.tag} "
                    f"{(_el.get(_ANDROID_NS + 'id') or '').replace('@+id/', '')}> has no "
                    f"android:{_attr} and style={_style or 'none'} does not supply one "
                    f"— this throws at inflation, not at build")


# ---------------------------------------------------------------- gate 13
#
# AGP 9 and the standalone Kotlin Android plugin cannot both be applied.
#
# AGP 9 compiles Kotlin itself and registers the `kotlin` extension, so
# applying org.jetbrains.kotlin.android on top fails at configuration time
# with "Cannot add extension with name 'kotlin'". It is a one-line mistake to
# make when copying a plugins block from any older project or tutorial, and
# the error names the Kotlin plugin rather than the real cause.
_agp_major = None
_kotlin_plugin_at = []
for _bf in ("build.gradle.kts", "app/build.gradle.kts"):
    if not os.path.exists(_bf):
        continue
    for _n, _l in enumerate(open(_bf, encoding="utf-8").read().split("\n"), 1):
        _code = _l.split("//")[0]
        _m = re.search(r'com\.android\.application"\)\s*version\s*"(\d+)\.', _code)
        if _m:
            _agp_major = int(_m.group(1))
        if re.search(r'id\("org\.jetbrains\.kotlin\.android"\)|kotlin\("android"\)', _code):
            _kotlin_plugin_at.append(f"{_bf}:{_n}")
if _agp_major is not None and _agp_major >= 9 and _kotlin_plugin_at:
    for _where in _kotlin_plugin_at:
        problems.append(
            f"{_where}  AGP {_agp_major} has built-in Kotlin — applying "
            f"org.jetbrains.kotlin.android as well fails with \"Cannot add extension "
            f"with name 'kotlin'\"")


# ---------------------------------------------------------------- gate 14
#
# A Gson-persisted type whose package is not protected in proguard-rules.pro.
#
# R8 renames fields it is not told to keep. Gson stores FIELD NAMES, so a
# renamed field silently changes a stored JSON key: the release build works
# perfectly on a fresh install and loses every existing profile, target, rule
# and session on an upgrade. There is no crash and no build error, it only
# happens in release builds, and unit tests run unminified so they cannot see
# it either.
#
# Which is why this is checked mechanically: the rules were derived by
# auditing every fromJson/TypeToken call site, and that audit has to be
# repeated every time someone persists a new type. Here it is repeated on
# every build instead.
_pg = "app/proguard-rules.pro"
if os.path.exists(_pg):
    _pgsrc = open(_pg, encoding="utf-8").read()
    _protected = set(re.findall(r'-keepclassmembers class (com\.rfsat\.[\w.]+?)(?:\.\*\*)? *\{', _pgsrc))

    def _is_protected(pkg):
        return any(pkg == pr or pkg.startswith(pr + ".") or pr.startswith(pkg + ".")
                   for pr in _protected)

    _serialised = {}     # type name -> "file:line"
    _gson_re = re.compile(r'fromJson[^;\n]*?\b(\w+)::class\.java'
                          r'|getParameterized\([^)]*?\b(\w+)::class\.java\s*\)'
                          r'|TypeToken<[^>]*?\b([A-Z]\w+)[>,]')
    for _f in [x for x in files if (os.sep + "test" + os.sep) not in x]:
        for _n, _l in enumerate(open(_f, encoding="utf-8", errors="ignore").read().split("\n"), 1):
            _code = _l.split("//")[0]
            for _m in _gson_re.finditer(_code):
                _t = _m.group(1) or _m.group(2) or _m.group(3)
                if _t and _t not in ("List", "Map", "MutableList", "MutableMap", "String"):
                    _serialised.setdefault(_t, f"{os.path.basename(_f)}:{_n}")

    for _t, _where in sorted(_serialised.items()):
        _pkgs = _decl_pkgs.get(_t)
        if not _pkgs:
            continue          # declared outside this project (Gson's own, or a library)
        for _pkg in _pkgs:
            if _pkg.startswith("com.rfsat.") and not _is_protected(_pkg):
                problems.append(
                    f"{_where}  '{_t}' is persisted by Gson but {_pkg} has no "
                    f"-keepclassmembers rule — R8 will rename its fields and every "
                    f"stored copy will stop loading, in release builds only")


# ---------------------------------------------------------------- gate 15
#
# WITHDRAWN, and left here as a note so it is not attempted again the same
# way.
#
# The intent was to catch a local function called before it is declared —
# Kotlin does not hoist them, so `fun a()` calling a `fun b()` declared below
# it is an "Unresolved reference", which reads like a missing import rather
# than a scoping rule. It cost a CI run in 1.40.0.
#
# A text-level version cannot do it. Indentation does not distinguish a LOCAL
# function inside a function body, which must be declared first, from a METHOD
# of a nested object or class, which may be referenced before its declaration.
# KestrelProvider is full of the second kind inside anonymous
# BluetoothGattCallback objects, and the check reported all of them. It also
# failed to catch the case it was written for.
#
# Wrong in both directions is worse than absent: a gate that fires on correct
# code teaches people to route around it, and this one would have done that
# while still missing the bug. Doing it properly needs block-level parsing —
# function body versus class body — which is a real parser, not a regex.

print(("PROBLEMS:\n  "+"\n  ".join(problems)) if problems else "No problems found.")
sys.exit(1 if problems else 0)
