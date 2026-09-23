# sbt-scalaxb-choice-adt

A minimal `sbt-scalaxb`-style plugin: same idea as upstream (find XSDs, run
scalaxb, wire the output in as a source-generator task), but uses the
`ChoiceAwareDriver` from `scalaxb-choice-plugin` instead of
`scalaxb.compiler.Module.moduleByFileName`, so `<xsd:choice>` groups of
plain elements come out as `sealed trait` ADTs instead of
`scalaxb.DataRecord[Any]`.

## What's exposed

Deliberately smaller than upstream `sbt-scalaxb`'s ~30 settings (most of
those are for WSDL/HTTP-client codegen this project doesn't use):

- `choiceScalaxbXsdSource` (default: `src/main/xsd`) - searched recursively
- `choiceScalaxbPackageName` (default: `"generated"`) - fallback package
- `choiceScalaxbPackageNames` (default: empty) - `Map[namespace URI, package]`
  overrides, same shape as upstream's `scalaxbPackageNames`
- `choiceScalaxb` - the task itself, wired into `Compile / sourceGenerators`
  so it reruns automatically on `compile`, with basic input-change caching
  (regenerates only when an `.xsd` file's mtime changes, or the output
  directory is missing/empty)

## Build and install locally

```
cd sbt-scalaxb-choice-adt
sbt publishLocal
```

That puts it in your local `~/.ivy2/local` (or `~/.cache` for Coverage/`sbt
1.10+` depending on resolver config - `publishLocal`'s own output tells you
where). No Maven Central publish needed for local use.

## Example build.sbt

TBD
