package org.droidmate.coverage

import com.natpryce.konfig.CommandLineOption
import com.natpryce.konfig.Configuration
import com.natpryce.konfig.ConfigurationProperties
import com.natpryce.konfig.overriding
import com.natpryce.konfig.parseArgs

object CommandLineConfigBuilder {
    fun build(args: Array<String>): Configuration = build(parseArgs(
        args,
        CommandLineOption(CommandLineConfig.apk,
            description = "Apk to be instrumented. If a directory is provided, take the first non-instrumented app",
            short = "apk",
            metavar = "Path"
        ),
        CommandLineOption(CommandLineConfig.onlyAppPackage,
            description = "Instrument only statements in the app package",
            short = "app",
            metavar = "Boolean"
        ),
        CommandLineOption(CommandLineConfig.printToLogcat,
            description = "Print logged statements to logcat. Note: When being used alongside onlyAppPackage=false this may result in a significant performance impact",
            short = "print",
            metavar = "Boolean"
        ),
        CommandLineOption(CommandLineConfig.outputDir,
            description = "Output directory for instrumented Apk",
            short = "out",
            metavar = "Path"
        )
    ).first)

    private fun build(cfgCommandLine: Configuration): Configuration {
        return cfgCommandLine overriding
                ConfigurationProperties.fromResource("coverageCommandLineConfig.properties")
    }
}