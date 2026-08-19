"""
Stamp the User Guide with a release, fix its contents page, and render the PDF.

    python3 tools/update_user_guide.py <version> <build> [docs/BAS-User-Guide_vX.docx]

WHAT THIS DOES AND DOES NOT DO. It owns the two things that are mechanical and
therefore always get forgotten: the version on the title page and in the
footer, and the page numbers in the contents list. It does NOT write the
guide's prose — new sections are still written by hand, because a release note
is not a user guide and a script cannot tell the difference.

WHY THE PAGE NUMBERS NEED A SCRIPT AT ALL. The contents list is static: each
row carries a cached number in its last text run. Word refreshes those when it
feels like it and LibreOffice does not, so a guide edited anywhere but the last
page ships with a contents list pointing at the wrong pages. Adding one
paragraph on page 6 is enough. So the numbers are read back out of the rendered
PDF — where the headings actually landed — and written into the rows.

The document is EDITED, never regenerated: unzip, patch document.xml, rezip.
That is what keeps the title page, the RFSAT logo and every style the
originals rather than an imitation of them.
"""
import os, re, shutil, subprocess, sys, tempfile, zipfile

T = re.compile(r'<w:t(?:\s[^>]*)?>(.*?)</w:t>', re.S)
SOFFICE = "/sessions/festive-affectionate-einstein/mnt/.claude/skills/docx/scripts/office/soffice.py"


def paras(doc):
    return list(re.finditer(r'<w:p\b.*?</w:p>', doc, re.S))


def text_of(p):
    return "".join(T.findall(p)).strip()


def row_label(p):
    """A contents row's visible label, with the cached page number stripped."""
    h = re.search(r'<w:hyperlink\b.*?</w:hyperlink>', p, re.S)
    if not h:
        return None
    return re.sub(r'\d+$', '', "".join(T.findall(h.group(0))).strip()).strip()


def stamp_version(doc, version, build):
    """Replace whatever version the title page and footer carry."""
    n = 0
    def title(m):
        nonlocal n
        n += 1
        return "Version %s (build %s) · %s" % (version, build, m.group(3))
    doc = re.sub(r'Version (\d+\.\d+\.\d+) \(build (\d+)\) · ([A-Za-z]+ \d{4})', title, doc)
    def footer(m):
        nonlocal n
        n += 1
        return "BAS %s — RFSAT" % version
    doc = re.sub(r'BAS (\d+\.\d+\.\d+) — RFSAT', footer, doc)
    return doc, n


def render_pdf(docx, outdir):
    subprocess.run([sys.executable, SOFFICE, "--headless", "--convert-to", "pdf", docx],
                   cwd=outdir, check=True, capture_output=True, timeout=600)
    return os.path.join(outdir, os.path.splitext(os.path.basename(docx))[0] + ".pdf")


def heading_pages(pdf, headings):
    """Which page each heading landed on, read out of the rendered PDF.

    THE CONTENTS PAGE HAS TO BE SKIPPED, or every heading is found there
    first — it lists them all — and every row ends up pointing at the
    contents. That is not hypothetical: the first version of this script did
    exactly that and stamped "2" against all forty-eight rows.

    The contents is identified by its dot leaders rather than by a fixed page
    number, so a title page that grows or shrinks does not break it.
    """
    n = int(re.search(r'Pages:\s+(\d+)',
                      subprocess.run(["pdfinfo", pdf], capture_output=True, text=True).stdout).group(1))
    # Contents ROWS are dropped, not contents PAGES. The list can spill onto
    # a page that also carries the first body headings, so excluding whole
    # pages loses those — which it did, leaving five rows pointing at the
    # contents. A row is recognisable by its dot leader, and nothing else in
    # the document has one.
    leader = re.compile(r'\.{4,}')
    pages = []
    for p in range(1, n + 1):
        raw = subprocess.run(["pdftotext", "-f", str(p), "-l", str(p), pdf, "-"],
                             capture_output=True, text=True).stdout
        body = [ln for ln in raw.split("\n") if not leader.search(ln)]
        pages.append(" ".join(body))
    found = {}
    for h in headings:
        probe = h[:34]
        for i in range(len(pages)):
            if probe in pages[i]:
                found[h] = i + 1
                break
    return found, n


def fix_contents(doc, pages):
    """Write the real page numbers into the contents rows."""
    edits, fixed = [], 0
    for m in paras(doc):
        p = m.group(0)
        if 'TOC1' not in p and 'TOC2' not in p:
            continue
        label = row_label(p)
        want = pages.get(label)
        if want is None:
            continue
        hits = list(T.finditer(p))
        if not hits:
            continue
        tgt = hits[-1]                      # the cached number is the last run
        edits.append((m.start(), m.end(), p[:tgt.start(1)] + str(want) + p[tgt.end(1):]))
        fixed += 1
    for start, end, new in reversed(edits):
        doc = doc[:start] + new + doc[end:]
    return doc, fixed


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    version, build = sys.argv[1], sys.argv[2]
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    docs = os.path.join(root, "docs")
    src = sys.argv[3] if len(sys.argv) > 3 else None
    if not src:
        cands = sorted(f for f in os.listdir(docs)
                       if f.startswith("BAS-User-Guide_v") and f.endswith(".docx"))
        if not cands:
            sys.exit("no BAS-User-Guide_v*.docx in docs/")
        src = os.path.join(docs, cands[-1])
    print("base: %s" % os.path.basename(src))

    work = tempfile.mkdtemp(prefix="guide")
    unpack = os.path.join(work, "src")
    with zipfile.ZipFile(src) as z:
        z.extractall(unpack)
    docxml = os.path.join(unpack, "word", "document.xml")
    doc = open(docxml, encoding="utf-8").read()

    doc, stamped = stamp_version(doc, version, build)
    print("version stamped in %d place(s)" % stamped)
    open(docxml, "w", encoding="utf-8").write(doc)

    def rezip(dest):
        if os.path.exists(dest):
            os.remove(dest)
        with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
            for dp, _, fs in os.walk(unpack):
                for f in fs:
                    full = os.path.join(dp, f)
                    z.write(full, os.path.relpath(full, unpack))

    # Render once to find out where the headings actually are, fix the
    # contents against that, then render again so the PDF carries the fix.
    tmp_docx = os.path.join(work, "probe.docx")
    rezip(tmp_docx)
    pdf = render_pdf(tmp_docx, work)
    headings = [text_of(m.group(0)) for m in paras(doc)
                if re.search(r'w:pStyle w:val="Heading[12]"', m.group(0))]
    pages, npages = heading_pages(pdf, headings)
    print("rendered %d pages; located %d/%d headings" % (npages, len(pages), len(headings)))

    doc, fixed = fix_contents(doc, pages)
    print("contents rows corrected: %d" % fixed)
    open(docxml, "w", encoding="utf-8").write(doc)

    out_docx = os.path.join(docs, "BAS-User-Guide_v%s.docx" % version)
    rezip(out_docx)
    final_pdf = render_pdf(out_docx, work)
    shutil.copy(final_pdf, os.path.join(docs, "BAS-User-Guide.pdf"))
    print("written: docs/%s" % os.path.basename(out_docx))
    print("written: docs/BAS-User-Guide.pdf")

    for f in os.listdir(docs):
        if f.startswith("BAS-User-Guide_v") and f != os.path.basename(out_docx):
            print("SUPERSEDED, delete by hand: docs/%s" % f)


if __name__ == "__main__":
    main()
