val scalusVersion = "1.4.0"

lazy val root = (project in file("."))
    .enablePlugins(ScalusBlueprintPlugin)
    .settings(
      name := "scalus-validators",
      organization := "io.github.sergey-scherbina",
      scalaVersion := "3.9.0",
      scalacOptions ++= Seq("-deprecation", "-feature"),
      libraryDependencies ++= Seq(
        "org.scalus" %% "scalus" % scalusVersion,
        "org.scalus" %% "scalus-cardano-ledger" % scalusVersion,
        "org.scalus" %% "scalus-testkit" % scalusVersion % Test,
        "org.scalatest" %% "scalatest" % "3.2.20" % Test,
        "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test,
        // a failing case written as a file okay-uplc opens (CaseFilesSpec)
        "io.github.okay-effects" %% "okay-cardano" % "0.1.0" % Test
      ),
      resolvers ++= Seq(
        "okay-effects" at "https://okay-effects.github.io/okay-simple/maven",
        "okay-scala" at "https://okay-effects.github.io/okay-scala/maven",
        "okay-http" at "https://okay-effects.github.io/okay-http/maven",
        "okay-cardano" at "https://okay-effects.github.io/okay-cardano/maven"
      ),
      addCompilerPlugin(("org.scalus" % "scalus-plugin" % scalusVersion).cross(CrossVersion.full))
    )
