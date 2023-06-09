resolvers ++= Resolver.sonatypeOssRepos("public")

addDependencyTreePlugin
addSbtPlugin("edu.gemini"       % "sbt-lucuma-app"      % "0.10.13")
// sbt revolver lets launching applications from the sbt console
addSbtPlugin("io.spray"         % "sbt-revolver"        % "0.10.0")
addSbtPlugin("com.timushev.sbt" % "sbt-updates"         % "0.6.4")
addSbtPlugin("ch.epfl.scala"    % "sbt-scalajs-bundler" % "0.21.1")
// Support making distributions
addSbtPlugin("com.github.sbt"   % "sbt-native-packager" % "1.9.16")

// Extract metadata from sbt and make it available to the code
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")
