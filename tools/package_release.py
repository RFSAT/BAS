"""
Package a release twice: the whole tree, and only what changed.

Uploading through the GitHub web interface means adding files by hand, and the
full tree is now far too large for that. So each release also ships a DELTA —
the files that are new or modified since the previous revision — plus the list
of files to delete, which the web interface cannot infer.

    python3 tools/package_release.py <previous.zip> <version>

Writes BAS_v<version>.zip, BAS_v<version>_delta.zip and, inside the delta,
DELETED_FILES.txt naming anything to remove from the repository by hand.
"""
import hashlib, os, sys, zipfile

def digest(data): return hashlib.sha256(data).hexdigest()

def read_zip(path):
    """Map repo-relative path -> sha256, from a previously shipped zip."""
    out = {}
    if not path or not os.path.exists(path): return out
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.endswith("/"): continue
            rel = n.split("/", 1)[1] if "/" in n else n     # strip the BAS/ root
            out[rel] = digest(z.read(n))
    return out

def read_tree(root, skip=(".git", "build", ".gradle")):
    out = {}
    for dp, dirs, fs in os.walk(root):
        dirs[:] = [d for d in dirs if d not in skip]
        for f in fs:
            full = os.path.join(dp, f)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            with open(full, "rb") as fh: out[rel] = (digest(fh.read()), full)
    return out

def main():
    if len(sys.argv) < 3:
        print(__doc__); sys.exit(2)
    prev_zip, version = sys.argv[1], sys.argv[2]
    here = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    outdir = os.path.dirname(os.path.abspath(prev_zip)) or "."

    prev = read_zip(prev_zip)
    cur = read_tree(here)

    added    = sorted(p for p in cur if p not in prev)
    modified = sorted(p for p in cur if p in prev and cur[p][0] != prev[p])
    deleted  = sorted(p for p in prev if p not in cur)

    full = os.path.join(outdir, "BAS_v%s.zip" % version)
    with zipfile.ZipFile(full, "w", zipfile.ZIP_DEFLATED) as z:
        for rel, (_, full_path) in sorted(cur.items()):
            z.write(full_path, "BAS/" + rel)

    delta = os.path.join(outdir, "BAS_v%s_delta.zip" % version)
    with zipfile.ZipFile(delta, "w", zipfile.ZIP_DEFLATED) as z:
        for rel in added + modified:
            z.write(cur[rel][1], "BAS/" + rel)
        note = ["# Files to DELETE from the GitHub repository by hand.",
                "# The web interface cannot infer a deletion from an upload.", ""]
        note += deleted if deleted else ["(nothing to delete in this revision)"]
        z.writestr("BAS/DELETED_FILES.txt", "\n".join(note) + "\n")

    print("full : %s  (%d files)" % (os.path.basename(full), len(cur)))
    print("delta: %s  (%d new, %d modified, %d to delete)"
          % (os.path.basename(delta), len(added), len(modified), len(deleted)))
    for p in added:    print("   NEW      ", p)
    for p in modified: print("   MODIFIED ", p)
    for p in deleted:  print("   DELETE   ", p)

if __name__ == "__main__":
    main()
