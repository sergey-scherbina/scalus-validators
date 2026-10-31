addSbtPlugin("org.scalus" % "scalus-sbt-plugin" % "1.4.0")

// the contracts in okay-uplc, a debugger for Cardano scripts: okayUplcServe, okayUplcVerify
resolvers += "okay-cardano" at "https://okay-effects.github.io/okay-cardano/maven"
addSbtPlugin("io.github.okay-effects" % "sbt-okay-uplc" % "0.1.0")
