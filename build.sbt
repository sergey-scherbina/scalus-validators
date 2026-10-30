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
        "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test
      ),
      addCompilerPlugin(("org.scalus" % "scalus-plugin" % scalusVersion).cross(CrossVersion.full))
    )
