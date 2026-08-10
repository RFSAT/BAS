# Documentation — how it is maintained

`docs/` holds two documents, each in two forms: the editable
`*_v<version>.docx`, and the PDF that is published. The PDF filename carries
no version so a link keeps working across reissues; the version is on the
title page, where a reader can see it.

* **BAS-User-Guide** — for shooters. Integrates the STS and VTB user guides
  the same way the app integrates the two applications: STS's structure and
  safety-first opening as the base, VTB's ballistics chapters folded in, and
  the material that exists only in BAS (cameras, rangefinders, Range mode)
  added where it belongs.
* **BAS-Programmer-Reference** — for whoever maintains the code. Based on the
  VTB Programmer's Reference, extended with the scoring half inherited from
  STS and everything added since the merge.

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
