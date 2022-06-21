resolvers += Resolver.sonatypeRepo("public")

addSbtPlugin("edu.gemini"         % "sbt-lucuma"               % "0.8.6")
addSbtPlugin("com.geirsson"       % "sbt-ci-release"           % "1.5.7")
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.10.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.2.0")
// sbt revolver lets launching applications from the sbt console
addSbtPlugin("io.spray"           % "sbt-revolver"             % "0.9.1")
addSbtPlugin("com.timushev.sbt"   % "sbt-updates"              % "0.6.3")
addSbtPlugin("ch.epfl.scala"      % "sbt-scalajs-bundler"      % "0.20.0")
// Support making distributions
addSbtPlugin("com.typesafe.sbt"   % "sbt-native-packager"      % "1.7.6")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6")

// Extract metadata from sbt and make it available to the code
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")
