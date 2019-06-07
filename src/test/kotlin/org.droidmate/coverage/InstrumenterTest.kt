package org.droidmate.coverage

import org.droidmate.device.android_sdk.Apk
import java.io.File
import org.json.JSONObject
import org.junit.Before
import org.junit.After
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.junit.runners.MethodSorters
import soot.G
import java.nio.file.Files
import java.nio.file.Paths

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(JUnit4::class)
class InstrumenterTest {
    val baseDir = "src/test/kotlin/org.droidmate/coverage"

    @Before
    fun setUp() {
        // Make sure no files from previous test are present
        var file_json = File(baseDir + "/app.apk.json")

        if (file_json.exists()) {
            file_json.delete()
        }

        var file_instrApk = File(baseDir + "/app-instrumented.apk")

        if (file_instrApk.exists()) {
            file_instrApk.delete()
        }
    }

    @After
    fun tearDown() {
        // Reset soot after each test. If we don't do this, a shutdown ThreadExecutor in PackManager.java will raise an exception because we attempt to re-use it.
        G.reset()
    }

    @Test
    fun `Test for main method`() {
        var args: Array<String> = arrayOf(
            "/whatever/path",
            "--apk=" + baseDir + "/app.apk",
            "--outputDir=" + baseDir
        )

        // call method under test
        Instrumenter.main(args)

        // Check 1: json and instrApk exist
        val jsonPath = Paths.get(baseDir + "/app.apk.json")
        val instrApkPath = Paths.get(baseDir + "/app-instrumented.apk")
        assert(Files.exists(jsonPath) && Files.exists(instrApkPath))

        // Check 2: json content
        val jsonData = String(Files.readAllBytes(jsonPath))
        val jObj = JSONObject(jsonData)

        val outputAPK = jObj.getString("outputAPK")
        val allMethods = jObj.getJSONObject("allMethods")

        assert(outputAPK.toString() == "app.apk")
        assert(allMethods != null && allMethods.length() > 0)
    }

    @Test
    fun `Test for instrument method`() {
        val outPath = Paths.get(baseDir)
        val apkPath = Paths.get(baseDir + "/app.apk")
        val apk = Apk.fromFile(apkPath)

        val a = Instrumenter(outPath, false, true)

        // call method under test
        val (instrApk, instrJSON) = a.instrument(apk, outPath)

        // Check 1: json and instrumentedApk exist
        assert(Files.exists(instrApk) && Files.exists(instrJSON))

        // Check 2: json content
        val jsonData = String(Files.readAllBytes(instrJSON))
        val jObj = JSONObject(jsonData)

        val outputAPK = jObj.getString("outputAPK")
        val allMethods = jObj.getJSONObject("allMethods")

        assert(outputAPK.toString() == "app.apk")
        assert(allMethods != null && allMethods.length() > 0)
    }
}