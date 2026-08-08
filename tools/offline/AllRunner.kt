fun main() {
    val classes = listOf(
        "com.rfsat.bas.DetectorRegressionTest", "com.rfsat.bas.EllipseFitTest",
        "com.rfsat.bas.RegistrationBoxTest", "com.rfsat.bas.RingFinderTest",
        "com.rfsat.bas.PunctureCheckTest", "com.rfsat.bas.SourceHoleDetectorTest",
        "com.rfsat.bas.T0002CorpusTest", "com.rfsat.bas.FixedSightTest",
        "com.rfsat.bas.CorrectionConsistencyTest",
        "com.rfsat.bas.AspectCorrectionTest",
        "com.rfsat.bas.SpsDimensionsTest",
        "com.rfsat.bas.LensDistortionTest",
        "com.rfsat.bas.CameraProfileTest",
        "com.rfsat.bas.SecondOpinionTest",
        "com.rfsat.bas.ScoringGeometryTest", "com.rfsat.bas.ScoringRulesTest",
        "com.rfsat.bas.ShotDistributionTest", "com.rfsat.bas.NameWrapTest", "com.rfsat.bas.ScaleChoiceTest", "com.rfsat.bas.NineMillimetreCatalogueTest", "com.rfsat.bas.HoleAccuracyTest"
    )
    var pass = 0; var fail = 0
    val failures = ArrayList<String>()
    for (cn in classes) {
        val c = Class.forName(cn)
        val inst = c.getDeclaredConstructor().newInstance()
        var p = 0; var f = 0
        for (m in c.declaredMethods.sortedBy { it.name }) {
            if (!m.isAnnotationPresent(org.junit.Test::class.java)) continue
            try { m.invoke(inst); p++ }
            catch (e: Throwable) { f++; failures += "${cn.substringAfterLast('.')}.${m.name}\n      ${e.cause ?: e}" }
        }
        pass += p; fail += f
        println("%-28s %2d passed %s".format(cn.substringAfterLast('.'), p, if (f > 0) "$f FAILED" else ""))
    }
    if (failures.isNotEmpty()) { println("\nFailures:"); failures.forEach { println("  - $it") } }
    println("\nTOTAL: $pass passed, $fail failed")
    if (fail > 0) System.exit(1)
}
