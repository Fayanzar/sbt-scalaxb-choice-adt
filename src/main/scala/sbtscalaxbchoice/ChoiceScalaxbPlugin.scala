package sbtscalaxbchoice

import sbt._
import Keys._
import scalaxb.{compiler => sc}
import sc.ConfigEntry._
import com.example.scalaxbchoice.ChoiceAwareDriver

/**
 * Minimal sbt-scalaxb-style plugin: same idea as the upstream sbt-scalaxb
 * plugin (github.com/eed3si9n/scalaxb/tree/master/sbt-scalaxb), but uses
 * ChoiceAwareDriver instead of scalaxb.compiler.Module.moduleByFileName, and
 * only exposes the handful of settings this project actually needs (package
 * naming and the xsd source directory) rather than the full ~30-setting
 * surface of the original, which is mostly about WSDL/HTTP-client codegen
 * this project doesn't use. Add more scalaxb Config entries in
 * choiceScalaxbGenerate below if you need them later (protocol splitting,
 * dispatch client, etc. - see ConfigEntry in the scalaxb source for the
 * full list).
 */
object ChoiceScalaxbKeys {
  lazy val choiceScalaxb = taskKey[Seq[File]](
    "Generate Scala sources from XSDs, with choice groups rendered as sealed-trait ADTs.")
  lazy val choiceScalaxbXsdSource = settingKey[File](
    "Directory to search (recursively) for .xsd files.")
  lazy val choiceScalaxbPackageName = settingKey[String](
    "Default package for generated sources (used for any namespace not listed in choiceScalaxbPackageNames).")
  lazy val choiceScalaxbPackageNames = settingKey[Map[String, String]](
    "Namespace URI -> package name overrides, same shape as upstream sbt-scalaxb's scalaxbPackageNames.")
}

object ChoiceScalaxbPlugin extends AutoPlugin {
  override def requires = plugins.JvmPlugin
  override def trigger = noTrigger

  object autoImport {
    val choiceScalaxb             = ChoiceScalaxbKeys.choiceScalaxb
    val choiceScalaxbXsdSource     = ChoiceScalaxbKeys.choiceScalaxbXsdSource
    val choiceScalaxbPackageName   = ChoiceScalaxbKeys.choiceScalaxbPackageName
    val choiceScalaxbPackageNames  = ChoiceScalaxbKeys.choiceScalaxbPackageNames
  }
  import autoImport._

  override lazy val projectSettings: Seq[Def.Setting[_]] =
    inConfig(Compile)(baseChoiceScalaxbSettings)

  lazy val baseChoiceScalaxbSettings: Seq[Def.Setting[_]] = Seq(
    choiceScalaxbXsdSource    := sourceDirectory.value / "xsd",
    choiceScalaxbPackageName  := "generated",
    choiceScalaxbPackageNames := Map.empty,
    choiceScalaxb / sourceManaged := sourceManaged.value / "sbt-scalaxb-choice-adt",
    choiceScalaxb := choiceScalaxbTask.value,
    sourceGenerators += (choiceScalaxb).taskValue
  )

  private def collectXsdFiles(dir: File): Seq[File] =
    if (!dir.exists) Nil
    else
      IO.listFiles(dir).toSeq.flatMap { f =>
        if (f.isDirectory) collectXsdFiles(f)
        else if (f.getName.endsWith(".xsd")) Seq(f)
        else Nil
      }

  private def choiceScalaxbTask: Def.Initialize[Task[Seq[File]]] = Def.task {
    val xsdDir       = choiceScalaxbXsdSource.value
    val outDir       = (choiceScalaxb / sourceManaged).value
    val pkg          = choiceScalaxbPackageName.value
    val pkgOverrides = choiceScalaxbPackageNames.value
    val targetScala  = scalaVersion.value
    val cacheDir     = streams.value.cacheDirectory / "choice-scalaxb-inputs"
    val log          = streams.value.log

    val allXsds = collectXsdFiles(xsdDir).sortBy(_.getName)

    if (allXsds.isEmpty) {
      log.info(s"choiceScalaxb: no .xsd files found under $xsdDir, skipping.")
      Seq.empty[File]
    } else {
      import sbt.util.CacheImplicits._
      val cachedGenerate = Tracked.inputChanged(cacheDir) { (changed: Boolean, in: FilesInfo[ModifiedFileInfo]) =>
        if (!changed && outDir.exists && IO.listFiles(outDir).nonEmpty) {
          log.debug("choiceScalaxb: inputs unchanged, skipping regeneration.")
          (outDir ** "*.scala").get()
        } else {
          log.info(s"choiceScalaxb: generating from ${allXsds.size} schema file(s) under $xsdDir")
          IO.delete(outDir)
          outDir.mkdirs()

          val namespacePackages: Map[Option[String], Option[String]] =
            pkgOverrides.map { case (ns, p) => (Some(ns): Option[String]) -> Some(p) } + (None -> Some(pkg))

          val config = sc.Config.default
            .update(PackageNames(namespacePackages))
            .update(Outdir(outDir))
            .update(GeneratePackageDir)
            .update(TargetScalaVersion(targetScala))

          val driver = new ChoiceAwareDriver
          val generated = driver.processFiles(allXsds.toVector, config)
          generated.foreach(f => log.info("  generated " + f))
          generated
        }
      }
      cachedGenerate(FilesInfo.lastModified(allXsds.toSet).asInstanceOf[FilesInfo[ModifiedFileInfo]]).toSeq
    }
  }
}
