// DroidMate, an automated execution generator for Android apps.
// Copyright (C) 2012-2018. Saarland University
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
// Current Maintainers:
// Nataniel Borges Jr. <nataniel dot borges at cispa dot saarland>
// Jenny Hotzkow <jenny dot hotzkow at cispa dot saarland>
//
// Former Maintainers:
// Konrad Jamrozik <jamrozik at st dot cs dot uni-saarland dot de>
//
// web: www.droidmate.org

package org.droidmate.coverage

import org.droidmate.ApkContentManager
import org.droidmate.device.android_sdk.Apk
import org.droidmate.device.android_sdk.IApk
import org.droidmate.helpClasses.Helper
import org.droidmate.instrumentation.Runtime
import org.droidmate.legacy.Resource
import org.droidmate.legacy.asEnvDir
import org.droidmate.legacy.deleteDirectoryRecursively
import org.droidmate.manifest.ManifestConstants
import org.droidmate.misc.DroidmateException
import org.droidmate.misc.EnvironmentConstants
import org.droidmate.misc.JarsignerWrapper
import org.droidmate.misc.SysCmdExecutor
import org.json.JSONObject
import org.slf4j.LoggerFactory
import soot.Body
import soot.BodyTransformer
import soot.PackManager
import soot.Scene
import soot.SootClass
import soot.Transform
import soot.jimple.internal.JIdentityStmt
import soot.options.Options
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.streams.asSequence

/**
 * Instrument statements in an apk.
 *
 * @author Original code by Manuel Benz (https://github.com/mbenz89)
 */
class Instrumenter(private val stagingDir: Path, private val onlyCoverAppPackageName: Boolean) {
    companion object {
        private val log by lazy { LoggerFactory.getLogger(this::class.java) }

        @JvmStatic
        fun main(args: Array<String>) {
            if (args.isEmpty() || args.size > 3) {
                println(
                    "Usage instructions: \n" +
                            "-- <APK> <ONLY-APP-PACKAGE>\n" +
                            "-- <APK> <ONLY-APP-PACKAGE <DESTINATION DIR>\n" +
                            "If not destination directory is specified, the file will be saved alongside the original APK"
                )
                return
            }

            val apkPath = Paths.get(args[0])

            val apkFile = if (Files.isDirectory(apkPath)) {
                Files.list(apkPath)
                    .asSequence()
                    .filter { it.fileName.toString().endsWith(".apk") }
                    .filterNot { it.fileName.toString().endsWith("-instrumented.apk") }
                    .first()
            } else {
                apkPath
            }.toAbsolutePath()

            val onlyAppPackage = args[1].toLowerCase() == "true" || args[1].toLowerCase() == "1"

            val dstDir = if (args.size > 2) {
                Paths.get(args[2])
            } else {
                apkFile.parent
            }

            assert(Files.isRegularFile(apkFile))

            val stagingDir = Files.createTempDirectory("staging")
            val instrumentationResult = try {
                val apk = Apk.fromFile(apkFile)
                Instrumenter(stagingDir, onlyAppPackage).instrument(apk, dstDir)
            } finally {
                stagingDir.deleteDirectoryRecursively()
            }
            println(
                "Compiled apk moved to: ${instrumentationResult.first}\n" +
                        "Instrumentation results written to: ${instrumentationResult.second}"
            )
        }
    }

    private val sysCmdExecutor = SysCmdExecutor()

    private val jarsignerWrapper = JarsignerWrapper(
        sysCmdExecutor,
        EnvironmentConstants.jarsigner.toAbsolutePath(),
        Resource("debug.keystore").extractTo(stagingDir)
    )

    private val allMethods = HashMap<Long, String>()
    private val helperClasses = listOf(
        "MonitorTcpServer",
        "Runtime",
        "SerializationHelper",
        "TcpServerBase\$1",
        "TcpServerBase\$MonitorServerRunnable",
        "TcpServerBase"
    )

    private val excludedPackages = listOf(
        "android.support.",
        "com.google.",
        "com.android.",
        "android.java."
    )

    private lateinit var helperSootClasses: List<SootClass>
    private lateinit var runtime: Runtime
    private lateinit var apkContentManager: ApkContentManager

    /**
     * <p>
     * Inlines apk at path {@code apkPath} and puts its inlined version in {@code outputDir}.
     *
     * </p><p>
     * For example, if {@code apkPath} is:
     *
     *   /abc/def/calc.apk
     *
     * and {@code outputDir} is:
     *
     *   /abc/def/out/
     *
     * then the output inlined apk will have path
     *
     *   /abc/def/out/calc-inlined.apk
     *
     * </p>
     *
     * @param apk Apk to be instrumented
     * @param outputDir Directory where the APK file will be stored
     * @return A pair of paths, where the first path is the APK path and the second path is a
     *         JSON file containing all instrumented statements
     */
    fun instrument(apk: IApk, outputDir: Path): Pair<Path, Path> {
        if (!Files.exists(outputDir))
            Files.createDirectories(outputDir)
        assert(Files.isDirectory(outputDir))

        val workDir = Files.createTempDirectory("coverage")

        try {
            allMethods.clear()

            val apkToolDir = workDir.resolve("apkTool")
            Files.createDirectories(apkToolDir)
            apkContentManager = ApkContentManager(apk.path, apkToolDir, workDir)
            apkContentManager.extractApk(true)

            // Add internet permission
            Helper.initializeManifestInfo(apk.path.toString())

            // The apk will need internet permissions to make sure that the TCP communication works
            if (!Helper.hasPermission(ManifestConstants.PERMISSION_INET)) {
                apkContentManager.addPermissionsToApp(ManifestConstants.PERMISSION_INET)
            }

            val tmpOutApk = workDir.resolve(apk.fileName)
            apkContentManager.buildApk(tmpOutApk)

            val sootDir = workDir.resolve("soot")

            configSoot(tmpOutApk, sootDir)

            val instrumentedApk = instrumentAndSign(apk, sootDir)
            val outputApk = outputDir.resolve(
                instrumentedApk.fileName.toString()
                    .replace(".apk", "-instrumented.apk")
            )

            Files.move(instrumentedApk, outputApk, StandardCopyOption.REPLACE_EXISTING)
            val instrumentedStatements = writeInstrumentationList(apk, outputDir)

            return Pair(outputApk, instrumentedStatements)
        } finally {
            workDir.deleteDirectoryRecursively()
        }
    }

    /**
     * Note: Whenever you change the files in Runtime.PACKAGE, recompile them and replace the existing .class'es
     * in the resources folder.
     */
    @Throws(IOException::class)
    private fun configSoot(processingApk: Path, sootOutputDir: Path) {
        Options.v().set_allow_phantom_refs(true)
        Options.v().set_src_prec(Options.src_prec_apk)
        Options.v().set_output_dir(sootOutputDir.toString())
        Options.v().set_debug(true)
        Options.v().set_validate(true)
        Options.v().set_output_format(Options.output_format_dex)

        val processDirs = ArrayList<String>()
        processDirs.add(processingApk.toString())

        val resourceDir = stagingDir
            .resolve("Runtime")

        val helperDir = resourceDir
            .resolve(Runtime.PACKAGE.replace('.', '/'))

        helperClasses
            .filter { !it.contains("\$") }
            .forEach { Resource("$it.class").extractTo(helperDir) }

        processDirs.add(resourceDir.toString())

        // Consider using multiplex, but it crashed for some apps
        // Options.v().set_process_multiple_dex(true)
        Options.v().set_process_dir(processDirs)

        Options.v().set_android_jars("ANDROID_HOME".asEnvDir.resolve("platforms").toString())

        Options.v().set_force_overwrite(true)
        Scene.v().loadNecessaryClasses()

        runtime = Runtime.v(
            Paths.get(
                EnvironmentConstants.AVD_dir_for_temp_files,
                EnvironmentConstants.coverage_port_file_name
            )
        )
        helperSootClasses = helperClasses
            .map { Scene.v().getSootClass("${Runtime.PACKAGE}.$it") }
    }

    private fun IApk.instrumentWithSoot(sootDir: Path): Path {
        log.info("Start instrumenting coverage...")

        val transformer = ITransformer(this, onlyCoverAppPackageName)

        PackManager.v().getPack("jtp").add(transformer)

        PackManager.v().runPacks()
        PackManager.v().writeOutput()

        val instrumentedApk = sootDir.resolve(this.fileName)
        log.info("Instrumentation finished: $instrumentedApk")

        if (!Files.exists(instrumentedApk))
            throw DroidmateException("Failed to instrument $this. Instrumented APK not found.")

        return instrumentedApk
    }

    private fun instrumentAndSign(apk: IApk, sootOutputDir: Path): Path {
        val instrumentedApk = apk.instrumentWithSoot(sootOutputDir)

        log.info("Signing APK")
        val signedApk = jarsignerWrapper.signWithDebugKey(instrumentedApk)
        log.info("Signed APK at: $signedApk")
        return signedApk
    }

    /**
     * Custom statement instrumentation transformer.
     * Each statement is uniquely assigned by an incrementing long counter (2^64 universe).
     */
    inner class ITransformer(apk: IApk, onlyCoverAppPackageName: Boolean) :
        Transform("jtp.androcov", object : BodyTransformer() {

            private var counter: Long = 0
            private val refinedPackageName = refinePackageName(apk)

            override fun internalTransform(b: Body, phaseName: String, options: MutableMap<String, String>) {
                val units = b.units
                // Important to use snapshotIterator here
                // Skip if the current class is one of the classes we use to instrument the coverage
                if (helperSootClasses.any { it === b.method.declaringClass }) {
                    return
                }
                // Skip if the current class belongs to the Android OS
                if (excludedPackages.any { b.method.declaringClass.toString().startsWith(it) }) {
                    return
                }

                val methodSig = b.method.signature

                if (!onlyCoverAppPackageName ||
                    (onlyCoverAppPackageName && methodSig.startsWith("<$refinedPackageName"))
                ) {

                    // Perform instrumentation here
                    val iterator = units.snapshotIterator()
                    while (iterator.hasNext()) {
                        val u = iterator.next()
                        // Instrument statements
                        if (u !is JIdentityStmt) {
                            val id = counter
                            allMethods[id] = "$u"
                            val logStatement = runtime.makeCallToStatementPoint("$id")
                            units.insertBefore(logStatement, u)
                            counter++
                        }
                    }
                    b.validate()
                }
            }
        })

    @Throws(IOException::class)
    private fun writeInstrumentationList(apk: IApk, outputDir: Path): Path {
        val outputMap = HashMap<String, Any>()
        outputMap["outputAPK"] = apk.fileName
        outputMap[INSTRUMENTATION_FILE_METHODS_PROP] = allMethods
        val instrumentResultFile = outputDir.resolve("${apk.fileName}$INSTRUMENTATION_FILE_SUFFIX")
        val resultJson = JSONObject(outputMap)

        Files.write(instrumentResultFile, resultJson.toString(4).toByteArray())

        return instrumentResultFile
    }

    /**
     * In the package has more than 2 parts, it returns the 2 first parts
     * @param apk Apk to extract the package name
     * @return The first to 2 segments of the package name
     */
    private fun refinePackageName(apk: IApk): String {
        val parts = apk.packageName.split("\\.".toRegex())
            .dropLastWhile { it.isEmpty() }
            .toTypedArray()

        return if (parts.size > 2)
            "${parts[0]}.${parts[1]}"
        else
            apk.packageName
    }
}