
/*
 build.sbt adapted from https://github.com/pbassiner/sbt-multi-project-example/blob/master/build.sbt
*/


name := "bwhc-data-entry-service"
ThisBuild / organization := "de.bwhc"
ThisBuild / scalaVersion := "2.13.8"
ThisBuild / version      := "1.1-SNAPSHOT"


//-----------------------------------------------------------------------------
// PROJECTS
//-----------------------------------------------------------------------------

lazy val global = project
  .in(file("."))
  .settings(
    settings,
    publish / skip := true
  )
  .aggregate(
     api,
     generators,
     impl,
     deps,
     tests
  )


lazy val api = project
  .settings(
    name := "data-entry-service-api",
    settings,
    libraryDependencies ++= Seq(
      dependencies.bwhc_utils,
      dependencies.hgnc_catalog_api,
      dependencies.icd_catalogs_api,
      dependencies.med_catalog_api
    )
  )


lazy val generators = project
  .settings(
    name := "mtb-dto-generators",
    settings,
    libraryDependencies ++= Seq(
      dependencies.generators
    )
  )
  .dependsOn(
    api
  )


lazy val impl = project
  .settings(
    name := "data-entry-service-impl",
    settings,
    libraryDependencies ++= Seq(
      dependencies.scalatest,
      dependencies.hgnc_catalog_impl,
      dependencies.icd_catalogs_impl,
      dependencies.med_catalog_impl
    )
  )
  .dependsOn(
    api,
    generators % Test,
  )


lazy val deps = project
  .in(file("dependencies"))
  .settings(
    name := "data-entry-service-dependencies",
    settings,
    libraryDependencies ++= Seq(
      dependencies.repo_utils,
    )
  )
  .dependsOn(impl)



lazy val tests = project
  .settings(
    name := "tests",
    settings,
    libraryDependencies ++= Seq(
      dependencies.scalatest,
      dependencies.slf4j,
      dependencies.hgnc_catalog_impl,
      dependencies.icd_catalogs_impl,
      dependencies.med_catalog_impl
    ),
    publish / skip := true
  )
  .dependsOn(
    api,
    generators % Test,
    impl % Test,
    deps % Test,
  )


//-----------------------------------------------------------------------------
// DEPENDENCIES
//-----------------------------------------------------------------------------

lazy val dependencies =
  new {
    val scalatest          = "org.scalatest"     %% "scalatest"               % "3.1.1" % Test
    val slf4j              = "org.slf4j"         %  "slf4j-api"               % "1.7.32"
    val play_json          = "com.typesafe.play" %% "play-json"               % "2.8.1"
    val generators         = "de.ekut.tbi"       %% "generators"              % "0.1-SNAPSHOT"
    val repo_utils         = "de.ekut.tbi"       %% "repository-utils"        % "1.0-SNAPSHOT" 
    val bwhc_utils         = "de.bwhc"           %% "utils"                   % "1.1"
    val hgnc_catalog_api   = "de.bwhc"           %% "hgnc-api"                % "1.0"
    val icd_catalogs_api   = "de.bwhc"           %% "icd-catalogs-api"        % "1.0"
    val med_catalog_api    = "de.bwhc"           %% "medication-catalog-api"  % "1.0"
    val hgnc_catalog_impl  = "de.bwhc"           %% "hgnc-impl"               % "1.0" % Test
    val icd_catalogs_impl  = "de.bwhc"           %% "icd-catalogs-impl"       % "1.0" % Test
    val med_catalog_impl   = "de.bwhc"           %% "medication-catalog-impl" % "1.0" % Test
  }


//-----------------------------------------------------------------------------
// SETTINGS
//-----------------------------------------------------------------------------

lazy val settings = commonSettings


lazy val compilerOptions = Seq(
  "-encoding", "utf8",
  "-unchecked",
  "-feature",
  "-language:postfixOps",
  "-Xfatal-warnings",
  "-deprecation",
)

lazy val commonSettings = Seq(
  scalacOptions ++= compilerOptions,
  resolvers ++= Seq("Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository") ++
    Resolver.sonatypeOssRepos("releases") ++
    Resolver.sonatypeOssRepos("snapshots")
)

