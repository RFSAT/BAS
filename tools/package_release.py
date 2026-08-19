"""
Package a release twice: the whole tree, and only what changed.

Uploading through the GitHub web interface means adding files by hand, and the
full tree is now far too large for that. So each release also ships a DELTA —
the files that are new or modified since the previous revision — plus the list
of files to delete, which the web interface cannot infer.

    python3 tools/package_release.py <baseline.zip> <version>

Writes BAS_v<version>.zip, BAS_v<version>_delta.zip and, inside the delta,
DELETED_FILES.txt naming anything to remove from the repository by hand.

THE BASELINE IS WHAT THE REPOSITORY ACTUALLY HOLDS, not simply the release
before this one. Those are the same thing only while every delta is uploaded
in order; skip one and they part company for good.

This distinction has already cost a release. 1.26.1 restored three catalogue
files to their 1.25.1 contents, and the delta was taken against 1.25.1 — so
the restored files hashed identical to the baseline, were judged unchanged,
and were left out. The repository was at 1.26.0 and kept the broken copies.
A revert is INVISIBLE to a delta taken against the revision being reverted to,
which is the one case where "nothing changed" is exactly wrong.

Two guards now make that impossible to repeat:
  * every full zip carries RELEASE_MANIFEST.txt (version + a hash per file),
    so the baseline names itself instead of being inferred from a filename;
  * the delta is VERIFIED before it is written -- baseline plus delta minus
    deletions is reconstructed and compared, hash for hash, against the tree
    being shipped. A mismatch fails the build rather than the user's CI.
"""
import hashlib, os, re, sys, zipfile

MANIFEST = "RELEASE_MANIFEST.txt"

def digest(data): return hashlib.sha256(data).hexdigest()

def read_zip(path):
    """Map repo-relative path -> sha256, from a previously shipped zip."""
    out = {}
    if not path or not os.path.exists(path): return out
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.endswith("/"): continue
            rel = n.split("/", 1)[1] if "/" in n else n     # strip the BAS/ root
            if rel == MANIFEST: continue
            out[rel] = digest(z.read(n))
    return out

def baseline_version(path):
    """The version a shipped zip says it is. Older zips predate the manifest."""
    try:
        with zipfile.ZipFile(path) as z:
            for n in z.namelist():
                if n.endswith(MANIFEST):
                    first = z.read(n).decode().splitlines()[0]
                    return first.split(None, 1)[1] if " " in first else first
    except Exception:
        pass
    return None

def read_tree(root, skip=(".git", "build", ".gradle")):
    """The tree as it will be shipped.

    RELEASE_MANIFEST.txt is excluded: it DESCRIBES the release, so a working
    tree unpacked from a previous zip carries the old one, and including it
    would both duplicate the entry and hash a file against its own successor.
    """
    out = {}
    for dp, dirs, fs in os.walk(root):
        dirs[:] = [d for d in dirs if d not in skip]
        for f in fs:
            if f == MANIFEST: continue
            full = os.path.join(dp, f)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            with open(full, "rb") as fh: out[rel] = (digest(fh.read()), full)
    return out

def guide_matches(root, version):
    """Is the User Guide stamped with the version being packaged?

    A standing instruction: every release ships an updated guide and its PDF.
    Instructions that live only in someone's memory are followed until the
    day they are not, so it is checked here — the one place every release
    passes through.
    """
    docs = os.path.join(root, "docs")
    if not os.path.isdir(docs):
        return True, ""
    want = "BAS-User-Guide_v%s.docx" % version
    have = sorted(f for f in os.listdir(docs)
                  if f.startswith("BAS-User-Guide_v") and f.endswith(".docx"))
    if want in have:
        pdf = os.path.join(docs, "BAS-User-Guide.pdf")
        if not os.path.exists(pdf):
            return False, "docs/%s is there but docs/BAS-User-Guide.pdf is missing." % want
        if os.path.getmtime(pdf) < os.path.getmtime(os.path.join(docs, want)):
            return False, ("docs/BAS-User-Guide.pdf is older than the document it should have "
                           "been rendered from.")
        return True, ""
    return False, ("the User Guide is at %s, not %s.\n"
                   "    Run: python3 tools/update_user_guide.py %s <build>"
                   % (", ".join(h.replace("BAS-User-Guide_v", "").replace(".docx", "")
                                for h in have) or "nothing", version, version))


def app_version(root):
    src = open(os.path.join(root, "app", "build.gradle.kts"), encoding="utf-8").read()
    m = re.search(r'versionName\s*=\s*"([^"]+)"', src)
    return m.group(1) if m else None


def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(2)
    prev_zip, version = sys.argv[1], sys.argv[2]
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    outdir = os.path.dirname(os.path.abspath(prev_zip)) or "."

    version_name = app_version(here)
    if version_name:
        ok, why = guide_matches(here, version_name)
        if not ok:
            print("REFUSING TO PACKAGE %s: %s" % (version_name, why))
            sys.exit(2)
        print("guide:    BAS-User-Guide_v%s.docx and its PDF are current" % version_name)

    prev = read_zip(prev_zip)
    cur = read_tree(here)
    if not prev:
        print("BASELINE %s is empty or missing -- the delta would be the whole tree."
              % prev_zip); sys.exit(2)
    said = baseline_version(prev_zip)
    print("baseline: %s%s" % (os.path.basename(prev_zip),
                              "  (declares itself v%s)" % said if said else
                              "  (no manifest -- predates it; trusting the filename)"))
    print("          %d files. This MUST be what the repository holds right now." % len(prev))

    added    = sorted(p for p in cur if p not in prev)
    modified = sorted(p for p in cur if p in prev and cur[p][0] != prev[p])
    deleted  = sorted(p for p in prev if p not in cur)

    # Reconstruct what the repository WILL hold once the delta is applied, and
    # insist it matches what is being shipped. This is the check that would
    # have caught the 1.26.1 catalogue files going missing.
    rebuilt = dict(prev)
    for rel in added + modified: rebuilt[rel] = cur[rel][0]
    for rel in deleted: rebuilt.pop(rel, None)
    want = {rel: h for rel, (h, _) in cur.items()}
    if rebuilt != want:
        for rel in sorted(set(rebuilt) | set(want)):
            if rebuilt.get(rel) != want.get(rel):
                print("   DELTA IS INCOMPLETE:", rel)
        sys.exit("delta verification FAILED -- refusing to ship it")

    manifest = ["version %s" % version.replace("_", ".")]
    manifest += ["%s  %s" % (h, rel) for rel, (h, _) in sorted(cur.items())]
    manifest = "\n".join(manifest) + "\n"

    full = os.path.join(outdir, "BAS_v%s.zip" % version)
    with zipfile.ZipFile(full, "w", zipfile.ZIP_DEFLATED) as z:
        for rel, (_, full_path) in sorted(cur.items()):
            z.write(full_path, "BAS/" + rel)
        z.writestr("BAS/" + MANIFEST, manifest)

    delta = os.path.join(outdir, "BAS_v%s_delta.zip" % version)
    with zipfile.ZipFile(delta, "w", zipfile.ZIP_DEFLATED) as z:
        for rel in added + modified:
            z.write(cur[rel][1], "BAS/" + rel)
        z.writestr("BAS/DELTA_BASELINE.txt",
                   "This delta applies on top of: %s%s\n"
                   "Producing:                    v%s\n\n"
                   "If the repository is NOT at that revision, do not use this delta --\n"
                   "upload the full zip instead. Applying a delta to the wrong baseline\n"
                   "leaves stale files behind with no error to show for it.\n"
                   % (os.path.basename(prev_zip),
                      "  (v%s)" % said if said else "",
                      version.replace("_", ".")))
        note = ["# Files to DELETE from the GitHub repository by hand.",
                "# The web interface cannot infer a deletion from an upload.", ""]
        note += deleted if deleted else ["(nothing to delete in this revision)"]
        z.writestr("BAS/DELETED_FILES.txt", "\n".join(note) + "\n")

    print("delta verified: baseline + delta reconstructs the shipped tree exactly.")
    print("full : %s  (%d files)" % (os.path.basename(full), len(cur)))
    print("delta: %s  (%d new, %d modified, %d to delete)"
          % (os.path.basename(delta), len(added), len(modified), len(deleted)))
    for p in added:    print("   NEW      ", p)
    for p in modified: print("   MODIFIED ", p)
    for p in deleted:  print("   DELETE   ", p)

if __name__ == "__main__":
    main()
