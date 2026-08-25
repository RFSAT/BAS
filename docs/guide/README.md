# Documentation — how it is maintained

Two documents are maintained for this project, each in two forms: the editable
`*_v<version>.docx`, and the PDF that is published. NEITHER IS COMMITTED HERE
and neither travels in a release archive — they are built alongside a release
and published to the RFSAT portal:

* <https://www.rfsat.com/download/BAS-User-Guide.pdf>
* <https://www.rfsat.com/download/BAS-Programmer-Reference.pdf>

The published filename carries no version, so those two links keep working
across every reissue; the version is on the title page, where a reader can see
it. `docs/` itself holds only this note and the style template.

* **BAS-User-Guide** — for shooters. Integrates the STS and VTB user guides
  the same way the app integrates the two applications: STS's structure and
  safety-first opening as the base, VTB's ballistics chapters folded in, and
  the material that exists only in BAS (cameras, rangefinders, Range mode)
  added where it belongs.
* **BAS-Programmer-Reference** — for whoever maintains the code. Based on the
  VTB Programmer's Reference, extended with the scoring half inherited from
  STS and everything added since the merge.

## Styling

Both documents use the STS template exactly: its `styles.xml` (Heading 1-6,
Title, List Paragraph, Hyperlink — navy headings on Calibri body text), the
RFSAT logo on the title page beside the wordmark, a running header naming the
document, and a page number in the footer. Both are kept in
`docs/guide/template/` so a reissue cannot drift from the house style.

## The document is the source

There is no generator, and there must not be one. STS had one and it cost the
guide its formatting twice: the RFSAT logo on the title page, the Word heading
styles and the author's page breaks were all replaced by the script's own,
because a script that emits a document from scratch cannot preserve what it
was never told about.

The first edition of each BAS document was generated, because there was
nothing yet to preserve. From here the order reverses:

1. The author edits the document in Word — layout, styles, images, page
   breaks, new sections, anything.
2. That document comes back and becomes the new `*_v<version>.docx` here.
3. Small wording changes between editions are made by substituting strings
   inside the document, never by rewriting paragraphs from a script.

## What must never be changed by tooling

* the logo on the first page
* the heading styles, which come from the document's own template
* the page breaks
* any other formatting the author has set

If a change needs a new paragraph, a new table or a moved section, it is made
in Word and comes back as a new baseline.

## Converting to PDF

    soffice --headless --convert-to pdf docs/BAS-User-Guide_v<version>.docx

Rename the output to the unversioned name before publishing.

---

## Currency

The documents built from these sources describe **1.17.0**. Everything added
since is recorded in `../WHATS-NEW-SINCE-1.17.md`, which is written to be the
source material for the next rebuild rather than a substitute for it: the
guides should absorb it, not link to it.
