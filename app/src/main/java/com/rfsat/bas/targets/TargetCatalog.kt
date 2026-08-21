package com.rfsat.bas.targets

import com.rfsat.bas.targets.TargetFace.Companion.evenRings

/**
 * ============================================================================
 *  BUILT-IN TARGET FACE CATALOGUE
 * ============================================================================
 *
 * ON THE PROVENANCE OF THESE NUMBERS. Two tiers, and the distinction is
 * carried in the data as [TargetFace.verified] rather than buried in a
 * comment, because it changes what the app is entitled to claim:
 *
 *   verified = true   ISSF faces. Their dimensions are fixed by a published
 *                     table (ISSF Rules, Section 6.3 and the target annexes)
 *                     and are specified as a ten-ring diameter plus a
 *                     constant ring pitch, which is exactly how [evenRings]
 *                     reconstructs them. These are safe to score against.
 *
 *   verified = false  Everything else. NRA/CMP high-power and smallbore,
 *                     F-Class, IPSC, IDPA and the European national faces
 *                     are the commonly published figures, but the American
 *                     rulebooks revise target dimensions periodically and
 *                     the practical-shooting silhouettes are specified by
 *                     drawing rather than by a dimension table. The app
 *                     surfaces these as "verify against your rulebook", and
 *                     every dimension is editable.
 *
 * A user edit never overwrites a built-in: [TargetRepository.saveCustom]
 * stores a copy with a new id. That way a correction made today cannot
 * silently change what a session scored last month was measured against.
 *
 * ADDING A FACE. Concentric ring faces need only the ten-ring diameter and
 * the pitch. Practical faces are built from named dimensions in
 * [PracticalGeometry] so that correcting one figure rebuilds the polygon
 * rather than requiring a vertex list to be retyped.
 */
object TargetCatalog {

    private const val IN = 25.4 // mm per inch, for the American faces

    // =====================================================================
    //  ISSF — the reference faces. Metric by definition.
    // =====================================================================

    /** 10 m Air Rifle. Ten ring 0.5 mm, pitch 2.5 mm, black = rings 4-10. */
    val ISSF_AR10 = TargetFace(
        id = "issf_ar_10m",
        name = "ISSF 10 m Air Rifle",
        governingBody = "ISSF",
        discipline = "Air Rifle",
        nominalDistanceM = 10.0,
        faceWidthMm = 80.0, faceHeightMm = 80.0,
        rings = evenRings(tenRingDiameterMm = 0.5, pitchMm = 2.5),
        blackDiameterMm = 30.5,
        // The ten ring is a 0.5 mm dot — smaller than the 4.5 mm pellet, so
        // it is shot away entirely and no separate paper inner ten exists.
        // The inner ten on this face is the decimal 10.9, which the decimal
        // formula produces directly.
        innerTenDiameterMm = 0.0,
        scoringMode = ScoringMode.RING_DECIMAL,
        verified = true,
        notes = "Card 80 x 80 mm. Decimal scoring per ISSF electronic-target practice; " +
            "10.9 corresponds to a shot centre within 0.25 mm."
    )

    /** 10 m Air Pistol. Ten ring 11.5 mm, pitch 8 mm, black = rings 7-10. */
    val ISSF_AP10 = TargetFace(
        id = "issf_ap_10m",
        name = "ISSF 10 m Air Pistol",
        governingBody = "ISSF",
        discipline = "Air Pistol",
        nominalDistanceM = 10.0,
        faceWidthMm = 170.0, faceHeightMm = 170.0,
        rings = evenRings(tenRingDiameterMm = 11.5, pitchMm = 8.0),
        blackDiameterMm = 59.5,
        innerTenDiameterMm = 5.0,
        scoringMode = ScoringMode.RING_DECIMAL,
        verified = true,
        notes = "Card 170 x 170 mm. 10.9 corresponds to a shot centre within 0.8 mm."
    )

    /** 50 m Rifle. Ten ring 10.4 mm, pitch 8 mm, black = rings 4-10. */
    val ISSF_R50 = TargetFace(
        id = "issf_rifle_50m",
        name = "ISSF 50 m Rifle",
        governingBody = "ISSF",
        discipline = "Rimfire Rifle",
        nominalDistanceM = 50.0,
        faceWidthMm = 500.0, faceHeightMm = 500.0,
        rings = evenRings(tenRingDiameterMm = 10.4, pitchMm = 8.0),
        blackDiameterMm = 112.4,
        innerTenDiameterMm = 5.0,
        scoringMode = ScoringMode.RING_DECIMAL,
        verified = true,
        notes = "Card 500 x 500 mm. Scored with the 5.6 mm .22 gauge."
    )

    /**
     * 25 m Precision Pistol face, also used for 50 m Pistol and for the
     * precision stage of 25 m Sport/Standard/Centre Fire Pistol. Ten ring
     * 50 mm, pitch 25 mm, black = rings 7-10.
     */
    val ISSF_P25_PRECISION = TargetFace(
        id = "issf_pistol_precision",
        name = "ISSF 25/50 m Precision Pistol",
        governingBody = "ISSF",
        discipline = "Rimfire Pistol",
        nominalDistanceM = 25.0,
        faceWidthMm = 550.0, faceHeightMm = 520.0,
        rings = evenRings(tenRingDiameterMm = 50.0, pitchMm = 25.0),
        blackDiameterMm = 200.0,
        innerTenDiameterMm = 25.0,
        scoringMode = ScoringMode.RING_INTEGER,
        verified = true,
        notes = "Card 550 x 520 mm. Also the 50 m Pistol face."
    )

    /**
     * 25 m Rapid Fire Pistol. Only rings 5-10 are printed; ten ring 100 mm,
     * pitch 40 mm, and the entire 500 mm scoring area is black.
     */
    val ISSF_P25_RAPID = TargetFace(
        id = "issf_pistol_rapidfire",
        name = "ISSF 25 m Rapid Fire Pistol",
        governingBody = "ISSF",
        discipline = "Rimfire Pistol",
        nominalDistanceM = 25.0,
        faceWidthMm = 500.0, faceHeightMm = 700.0,
        rings = (10 downTo 5).map { v -> Ring(v, 100.0 + 2.0 * 40.0 * (10 - v)) },
        blackDiameterMm = 500.0,
        innerTenDiameterMm = 50.0,
        scoringMode = ScoringMode.RING_INTEGER,
        verified = true,
        notes = "Card 500 x 700 mm, scoring area entirely black. Rings 5-10 only; " +
            "a hit outside the 5 ring is a miss."
    )

    /** 300 m Rifle. Ten ring 100 mm, pitch 50 mm, black = rings 5-10. */
    val ISSF_R300 = TargetFace(
        id = "issf_rifle_300m",
        name = "ISSF 300 m Rifle",
        governingBody = "ISSF",
        discipline = "Centrefire Rifle",
        nominalDistanceM = 300.0,
        faceWidthMm = 1300.0, faceHeightMm = 1300.0,
        rings = evenRings(tenRingDiameterMm = 100.0, pitchMm = 50.0),
        blackDiameterMm = 600.0,
        innerTenDiameterMm = 50.0,
        scoringMode = ScoringMode.RING_INTEGER,
        verified = true,
        notes = "The 300 m face is the .223/.308 competition target under ISSF rules."
    )

    // =====================================================================
    //  NRA / CMP high power. Inch-defined, unevenly pitched — so integer
    //  scoring only; scoreDecimal correctly refuses these.
    // =====================================================================

    private fun inchRings(vararg pairs: Pair<Int, Double>): List<Ring> =
        pairs.map { (v, inches) -> Ring(v, inches * IN) }

    /** NRA/CMP SR, 200 yards reduced high power. */
    val NRA_SR_200 = TargetFace(
        id = "nra_sr_200yd",
        name = "NRA/CMP SR — 200 yd High Power",
        governingBody = "NRA",
        discipline = "Centrefire Rifle",
        nominalDistanceM = 182.88, // 200 yd
        faceWidthMm = 1000.0, faceHeightMm = 1000.0,
        rings = inchRings(10 to 7.0, 9 to 12.0, 8 to 18.0, 7 to 24.0, 6 to 30.0, 5 to 36.0),
        blackDiameterMm = 13.0 * IN,
        innerTenDiameterMm = 3.0 * IN,
        innerTenLabel = "X",
        scoringMode = ScoringMode.RING_INTEGER,
        verified = false,
        notes = "X 3\", 10 7\", 9 12\", 8 18\", 7 24\", 6 30\", 5 36\"; aiming black 13\". " +
            "Card dimensions here are the printed scoring area plus margin — verify against " +
            "the NRA High Power rulebook in force."
    )

    /** NRA MR-1, 600 yards. */
    val NRA_MR1_600 = TargetFace(
        id = "nra_mr1_600yd",
        name = "NRA MR-1 — 600 yd High Power",
        governingBody = "NRA",
        discipline = "Centrefire Rifle",
        nominalDistanceM = 548.64, // 600 yd
        faceWidthMm = 1900.0, faceHeightMm = 1900.0,
        rings = inchRings(10 to 12.0, 9 to 18.0, 8 to 24.0, 7 to 30.0, 6 to 36.0),
        blackDiameterMm = 18.0 * IN,
        innerTenDiameterMm = 6.0 * IN,
        innerTenLabel = "X",
        scoringMode = ScoringMode.RING_INTEGER,
        verified = false,
        notes = "X 6\", 10 12\", 9 18\", 8 24\", 7 30\", 6 36\"; aiming black 18\"."
    )

    /** NRA LR, 1000 yards. */
    val NRA_LR_1000 = TargetFace(
        id = "nra_lr_1000yd",
        name = "NRA LR — 1000 yd High Power",
        governingBody = "NRA",
        discipline = "Centrefire Rifle",
        nominalDistanceM = 914.4, // 1000 yd
        faceWidthMm = 2200.0, faceHeightMm = 2200.0,
        rings = inchRings(10 to 20.0, 9 to 30.0, 8 to 44.0, 7 to 60.0, 6 to 72.0),
        blackDiameterMm = 44.0 * IN,
        innerTenDiameterMm = 10.0 * IN,
        innerTenLabel = "X",
        scoringMode = ScoringMode.RING_INTEGER,
        verified = false,
        notes = "X 10\", 10 20\", 9 30\", 8 44\", 7 60\", 6 72\"; aiming black 44\"."
    )

    /** F-Class 600 yd: the MR face with the halved F-Class ring set. */
    val FCLASS_600 = TargetFace(
        id = "fclass_600yd",
        name = "F-Class MR-FC — 600 yd",
        governingBody = "ICFRA",
        discipline = "Centrefire Rifle",
        nominalDistanceM = 548.64,
        faceWidthMm = 1900.0, faceHeightMm = 1900.0,
        rings = inchRings(10 to 6.0, 9 to 12.0, 8 to 18.0, 7 to 24.0, 6 to 30.0),
        blackDiameterMm = 18.0 * IN,
        innerTenDiameterMm = 3.0 * IN,
        innerTenLabel = "X",
        scoringMode = ScoringMode.RING_INTEGER,
        verified = false,
        notes = "F-Class halves the high-power ring diameters and adds a half-MOA X ring: " +
            "X 3\", 10 6\", 9 12\", 8 18\", 7 24\", 6 30\"."
    )

    /** F-Class 1000 yd. */
    val FCLASS_1000 = TargetFace(
        id = "fclass_1000yd",
        name = "F-Class LR-FC — 1000 yd",
        governingBody = "ICFRA",
        discipline = "Centrefire Rifle",
        nominalDistanceM = 914.4,
        faceWidthMm = 2200.0, faceHeightMm = 2200.0,
        rings = inchRings(10 to 10.0, 9 to 20.0, 8 to 30.0, 7 to 44.0, 6 to 60.0),
        blackDiameterMm = 30.0 * IN,
        innerTenDiameterMm = 5.0 * IN,
        innerTenLabel = "X",
        scoringMode = ScoringMode.RING_INTEGER,
        verified = false,
        notes = "X 5\", 10 10\", 9 20\", 8 30\", 7 44\", 6 60\"."
    )

    /** NRA A-17, 50-foot smallbore rifle. Evenly pitched, so decimal works. */
    val NRA_A17_50FT = TargetFace(
        id = "nra_a17_50ft",
        name = "NRA A-17 — 50 ft Smallbore Rifle",
        governingBody = "NRA",
        discipline = "Rimfire Rifle",
        nominalDistanceM = 15.24, // 50 ft
        faceWidthMm = 180.0, faceHeightMm = 180.0,
        rings = evenRings(tenRingDiameterMm = 0.090 * IN, pitchMm = 0.100 * IN),
        blackDiameterMm = 0.890 * IN,
        innerTenDiameterMm = 0.0,
        scoringMode = ScoringMode.RING_INTEGER,
        verified = false,
        notes = "10 ring 0.090\", rings at 0.100\" radial pitch, black 0.890\" (6 ring). " +
            "Verify against the current NRA Smallbore rulebook."
    )

    /** NRA A-23/5, 50 yd smallbore rifle. */
    val NRA_A23_50YD = TargetFace(
        id = "nra_a23_50yd",
        name = "NRA A-23/5 — 50 yd Smallbore Rifle",
        governingBody = "NRA",
        discipline = "Rimfire Rifle",
        nominalDistanceM = 45.72, // 50 yd
        faceWidthMm = 300.0, faceHeightMm = 300.0,
        rings = evenRings(tenRingDiameterMm = 0.90 * IN, pitchMm = 0.445 * IN),
        blackDiameterMm = 3.57 * IN,
        innerTenDiameterMm = 0.0,
        scoringMode = ScoringMode.RING_INTEGER,
        verified = false,
        notes = "10 ring 0.90\", radial pitch 0.445\". Verify against the current rulebook."
    )

    // =====================================================================
    //  European national federations.
    //
    //  IMPORTANT and often misunderstood: PZSS (Poland), DSB (Germany) and
    //  BDS all shoot the ISSF faces for the Olympic disciplines. They do not
    //  have their own air-rifle or air-pistol target. What differs is the
    //  COURSE OF FIRE and the classification tables, which live in RuleSet,
    //  not here. Only the genuinely national faces are listed below.
    // =====================================================================

    /** German 100 m rifle target (100 m Scheibe), used by DSB and BDS. */
    val DE_100M = TargetFace(
        id = "de_rifle_100m",
        name = "DSB/BDS 100 m Rifle",
        governingBody = "DSB",
        discipline = "Centrefire Rifle",
        nominalDistanceM = 100.0,
        faceWidthMm = 550.0, faceHeightMm = 520.0,
        rings = evenRings(tenRingDiameterMm = 50.0, pitchMm = 25.0),
        blackDiameterMm = 200.0,
        innerTenDiameterMm = 25.0,
        scoringMode = ScoringMode.RING_INTEGER,
        verified = false,
        notes = "Ten ring 50 mm, pitch 25 mm, black 200 mm. Geometrically identical to the " +
            "ISSF precision pistol face, shot at 100 m. Verify against the current Sportordnung."
    )

    /** German 50 m rifle target for KK-Auflage and similar. */
    val DE_KK_50M = TargetFace(
        id = "de_kk_50m",
        name = "DSB/BDS 50 m Kleinkaliber",
        governingBody = "DSB",
        discipline = "Rimfire Rifle",
        nominalDistanceM = 50.0,
        faceWidthMm = 500.0, faceHeightMm = 500.0,
        rings = evenRings(tenRingDiameterMm = 10.4, pitchMm = 8.0),
        blackDiameterMm = 112.4,
        innerTenDiameterMm = 5.0,
        scoringMode = ScoringMode.RING_INTEGER,
        verified = false,
        notes = "The ISSF 50 m rifle geometry shot under national rules."
    )

    // =====================================================================
    //  Practical shooting. Zone geometry rather than rings.
    // =====================================================================

    val IPSC_CLASSIC = PracticalGeometry.ipscClassic()
    val IPSC_MINI = PracticalGeometry.ipscMini()
    val IDPA_TARGET = PracticalGeometry.idpa()

    // =====================================================================
    //  Steel / hit-miss, for PRS and NRL22 stage practice.
    // =====================================================================

    private fun steel(id: String, name: String, diameterMm: Double, distanceM: Double, body: String) =
        TargetFace(
            id = id, name = name, governingBody = body, discipline = "Centrefire Rifle",
            nominalDistanceM = distanceM,
            faceWidthMm = diameterMm * 1.2, faceHeightMm = diameterMm * 1.2,
            rings = listOf(Ring(1, diameterMm)),
            blackDiameterMm = 0.0,
            scoringMode = ScoringMode.HIT_MISS,
            verified = false,
            notes = "Steel plate: an impact anywhere on the plate scores one point, " +
                "everything else is a miss. Edit the diameter to match the plate in use."
        )

    val STEEL_2MOA_100 = steel("steel_2moa_100m", "Steel plate 2 MOA @ 100 m", 58.0, 100.0, "NRL22")
    val STEEL_IPSC_FULL = steel("steel_ipsc_full", "Full-size IPSC steel", 450.0, 300.0, "PRS")
    val STEEL_300MM = steel("steel_300mm", "300 mm round steel", 300.0, 400.0, "PRS")


    // ---------------------------------------------------------------------
    //  NRA conventional (bullseye) pistol faces
    //
    //  Ring ladders from the NRA conventional pistol rulebook, as tabulated
    //  at indecorous.com/bullseye/rings.html. Diameters in inches; the app
    //  works in millimetres, so each is multiplied by IN at the point of use
    //  rather than pre-converted, which keeps the published figure readable
    //  in the source.
    //
    //  BLACK DIAMETERS ARE THE WEAK PART and are marked unverified. Sources
    //  disagree about the B-6/B-8 black: some call it the 9 ring (5.54 in),
    //  others say the black is eight inches across. The larger figure is used
    //  because an aiming mark that is too small makes the detector look for a
    //  disc that is not there, while one slightly too large still contains
    //  it. Measure your own card and edit the face if it matters.
    // ---------------------------------------------------------------------

    private fun ringsIn(vararg pairs: Pair<Int, Double>): List<Ring> =
        pairs.map { (v, inches) -> Ring(v, inches * IN) }

    /** NRA B-2, 50 ft slow fire pistol. */
    val NRA_B2_50FT = TargetFace(
        id = "nra_b2_50ft",
        name = "NRA B-2 — 50 ft Slow Fire Pistol",
        governingBody = "NRA",
        discipline = "Pistol",
        nominalDistanceM = 15.24,
        faceWidthMm = 9.5 * IN, faceHeightMm = 9.5 * IN,
        rings = ringsIn(10 to 0.90, 9 to 1.54, 8 to 2.23, 7 to 3.07,
                        6 to 4.16, 5 to 5.56, 4 to 7.33),
        blackDiameterMm = 0.0,
        scoringMode = ScoringMode.RING_INTEGER,
        identifiable = false,   // see TargetFace.identifiable
        verified = false,
        notes = "10 0.90\", 9 1.54\", 8 2.23\", 7 3.07\", 6 4.16\", 5 5.56\", 4 7.33\". " +
            "No X ring. Black diameter not recorded — set it from your own card if auto-detect " +
            "struggles."
    )

    /** NRA B-3, 50 ft timed and rapid fire pistol. */
    val NRA_B3_50FT = TargetFace(
        id = "nra_b3_50ft",
        name = "NRA B-3 — 50 ft Timed/Rapid Pistol",
        governingBody = "NRA",
        discipline = "Pistol",
        nominalDistanceM = 15.24,
        faceWidthMm = 10.5 * IN, faceHeightMm = 10.5 * IN,
        rings = ringsIn(10 to 1.80, 9 to 3.06, 8 to 4.46, 7 to 6.14, 6 to 8.32),
        blackDiameterMm = 0.0,
        innerTenDiameterMm = 0.90 * IN,
        innerTenLabel = "X",
        scoringMode = ScoringMode.RING_INTEGER,
        identifiable = false,   // see TargetFace.identifiable
        verified = false,
        notes = "X 0.90\", 10 1.80\", 9 3.06\", 8 4.46\", 7 6.14\", 6 8.32\"."
    )

    /** NRA B-4, 20 yd slow fire pistol. */
    val NRA_B4_20YD = TargetFace(
        id = "nra_b4_20yd",
        name = "NRA B-4 — 20 yd Slow Fire Pistol",
        governingBody = "NRA",
        discipline = "Pistol",
        nominalDistanceM = 18.29,
        faceWidthMm = 11.5 * IN, faceHeightMm = 11.5 * IN,
        rings = ringsIn(10 to 1.12, 9 to 1.88, 8 to 2.72, 7 to 3.73,
                        6 to 5.04, 5 to 6.72, 4 to 8.84),
        blackDiameterMm = 0.0,
        scoringMode = ScoringMode.RING_INTEGER,
        identifiable = false,   // see TargetFace.identifiable
        verified = false,
        notes = "10 1.12\", 9 1.88\", 8 2.72\", 7 3.73\", 6 5.04\", 5 6.72\", 4 8.84\"."
    )

    /** NRA B-5, 20 yd timed and rapid fire pistol. */
    val NRA_B5_20YD = TargetFace(
        id = "nra_b5_20yd",
        name = "NRA B-5 — 20 yd Timed/Rapid Pistol",
        governingBody = "NRA",
        discipline = "Pistol",
        nominalDistanceM = 18.29,
        faceWidthMm = 12.5 * IN, faceHeightMm = 12.5 * IN,
        rings = ringsIn(10 to 2.25, 9 to 3.76, 8 to 5.44, 7 to 7.46, 6 to 10.08),
        blackDiameterMm = 0.0,
        innerTenDiameterMm = 1.12 * IN,
        innerTenLabel = "X",
        scoringMode = ScoringMode.RING_INTEGER,
        identifiable = false,   // see TargetFace.identifiable
        verified = false,
        notes = "X 1.12\", 10 2.25\", 9 3.76\", 8 5.44\", 7 7.46\", 6 10.08\"."
    )

    /** NRA B-6, 50 yd slow fire pistol. Same ladder as the B-8. */
    val NRA_B6_50YD = TargetFace(
        id = "nra_b6_50yd",
        name = "NRA B-6 — 50 yd Slow Fire Pistol",
        governingBody = "NRA",
        discipline = "Pistol",
        nominalDistanceM = 45.72,
        faceWidthMm = 24.0 * IN, faceHeightMm = 24.0 * IN,
        rings = ringsIn(10 to 3.36, 9 to 5.54, 8 to 8.00, 7 to 11.00,
                        6 to 14.80, 5 to 19.68),
        blackDiameterMm = 8.00 * IN,
        innerTenDiameterMm = 1.695 * IN,
        innerTenLabel = "X",
        scoringMode = ScoringMode.RING_INTEGER,
        identifiable = false,   // see TargetFace.identifiable
        verified = false,
        notes = "X 1.695\", 10 3.36\", 9 5.54\", 8 8.00\", 7 11.00\", 6 14.80\", 5 19.68\". " +
            "Black taken as the 8 ring; sources also describe it as the 9 ring."
    )

    /** NRA B-8, 25 yd timed and rapid fire pistol — the same rings as the
     *  B-6, shot at half the distance. */
    val NRA_B8_25YD = TargetFace(
        id = "nra_b8_25yd",
        name = "NRA B-8 — 25 yd Timed/Rapid Pistol",
        governingBody = "NRA",
        discipline = "Pistol",
        nominalDistanceM = 22.86,
        faceWidthMm = 21.0 * IN, faceHeightMm = 24.0 * IN,
        rings = ringsIn(10 to 3.36, 9 to 5.54, 8 to 8.00, 7 to 11.00,
                        6 to 14.80, 5 to 19.68),
        blackDiameterMm = 8.00 * IN,
        innerTenDiameterMm = 1.695 * IN,
        innerTenLabel = "X",
        scoringMode = ScoringMode.RING_INTEGER,
        identifiable = false,   // see TargetFace.identifiable
        verified = false,
        notes = "Identical rings to the B-6. X 1.695\", 10 3.36\", 9 5.54\", 8 8.00\", " +
            "7 11.00\", 6 14.80\", 5 19.68\"."
    )

    /** NRA B-16, 25 yd slow fire pistol. */
    val NRA_B16_25YD = TargetFace(
        id = "nra_b16_25yd",
        name = "NRA B-16 — 25 yd Slow Fire Pistol",
        governingBody = "NRA",
        discipline = "Pistol",
        nominalDistanceM = 22.86,
        faceWidthMm = 12.0 * IN, faceHeightMm = 12.0 * IN,
        rings = ringsIn(10 to 1.51, 9 to 2.60, 8 to 3.82, 7 to 5.32,
                        6 to 7.22, 5 to 9.66),
        blackDiameterMm = 5.3125 * IN,
        innerTenDiameterMm = 0.67 * IN,
        innerTenLabel = "X",
        scoringMode = ScoringMode.RING_INTEGER,
        identifiable = false,   // see TargetFace.identifiable
        verified = false,
        notes = "X 0.67\", 10 1.51\", 9 2.60\", 8 3.82\", 7 5.32\", 6 7.22\", 5 9.66\". " +
            "Black 5 5/16\", which is the 7 ring."
    )

    // ---------------------------------------------------------------------
    //  Biathlon
    //
    //  Scored HIT_MISS because that is what biathlon is: the metal target
    //  falls or it does not, and a shot 1 mm inside the edge counts the same
    //  as one through the middle. Paper faces of these diameters are sold for
    //  practice, which is what this app can score.
    // ---------------------------------------------------------------------

    /** IBU biathlon, prone: 45 mm at 50 m. */
    val IBU_BIATHLON_PRONE = TargetFace(
        id = "ibu_biathlon_prone",
        name = "Biathlon — prone (45 mm)",
        governingBody = "IBU",
        discipline = "Biathlon",
        nominalDistanceM = 50.0,
        faceWidthMm = 120.0, faceHeightMm = 120.0,
        rings = listOf(Ring(1, 45.0)),
        blackDiameterMm = 45.0,
        scoringMode = ScoringMode.HIT_MISS,
        identifiable = false,   // see TargetFace.identifiable
        verified = false,
        notes = "45 mm ±0.3 mm at 50 m. A hit anywhere inside counts; there is no ring value " +
            "in biathlon."
    )

    /** IBU biathlon, standing: 115 mm at 50 m. */
    val IBU_BIATHLON_STANDING = TargetFace(
        id = "ibu_biathlon_standing",
        name = "Biathlon — standing (115 mm)",
        governingBody = "IBU",
        discipline = "Biathlon",
        nominalDistanceM = 50.0,
        faceWidthMm = 200.0, faceHeightMm = 200.0,
        rings = listOf(Ring(1, 115.0)),
        blackDiameterMm = 115.0,
        scoringMode = ScoringMode.HIT_MISS,
        identifiable = false,   // see TargetFace.identifiable
        verified = false,
        notes = "115 mm at 50 m. A hit anywhere inside counts."
    )

    // =====================================================================

    val builtIns: List<TargetFace> = listOf(
        ISSF_AR10, ISSF_AP10, ISSF_R50, ISSF_P25_PRECISION, ISSF_P25_RAPID, ISSF_R300,
        NRA_SR_200, NRA_MR1_600, NRA_LR_1000, FCLASS_600, FCLASS_1000,
        NRA_A17_50FT, NRA_A23_50YD,
        NRA_B2_50FT, NRA_B3_50FT, NRA_B4_20YD, NRA_B5_20YD,
        NRA_B6_50YD, NRA_B8_25YD, NRA_B16_25YD,
        IBU_BIATHLON_PRONE, IBU_BIATHLON_STANDING,
        DE_100M, DE_KK_50M,
        IPSC_CLASSIC, IPSC_MINI, IDPA_TARGET,
        STEEL_2MOA_100, STEEL_IPSC_FULL, STEEL_300MM
    )

    const val ALL = "All"

    fun byId(id: String): TargetFace? = builtIns.firstOrNull { it.id == id }

    fun disciplines(): List<String> = listOf(ALL) + builtIns.map { it.discipline }.distinct().sorted()
    fun bodies(): List<String> = listOf(ALL) + builtIns.map { it.governingBody }.distinct().sorted()

    fun filter(discipline: String, body: String, faces: List<TargetFace> = builtIns): List<TargetFace> =
        faces.filter {
            (discipline == ALL || it.discipline == discipline) &&
                (body == ALL || it.governingBody == body)
        }
}
