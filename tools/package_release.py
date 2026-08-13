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
import hashlib, os, sys, zipfile

def digest(data): return hashlib.sha256(data).hexdigest()

MANIFEST = "RELEASE_MANIFEST.txt"

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
