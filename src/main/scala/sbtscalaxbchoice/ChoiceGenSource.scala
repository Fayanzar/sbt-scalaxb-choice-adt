package com.example.scalaxbchoice

import scalaxb.compiler.Config
import scalaxb.compiler.xsd._

/**
 * Overrides scalaxb's xsd.GenSource so that an "eligible" top-level <xsd:choice>
 * is rendered as a real sealed-trait ADT instead of scalaxb.DataRecord[Any].
 *
 *  1. buildChoiceTypeName - the field's declared type (library's own
 *     extension point for this, previously always "scalaxb.DataRecord[X]").
 *  2. buildChoiceParser - reads XML into AdtName.Branch(value) instead of
 *     scalaxb.DataRecord(ns, key, value).
 *  3. buildXMLString - writes each branch back out using its statically
 *     known (from the XSD) element name instead of DataRecord's runtime key.
 *  4. run - emits the sealed trait + case classes themselves.
 *
 * Eligibility (checked identically in all three overrides, so a choice is
 * upgraded consistently or not at all): every branch must be a plain
 * <xsd:element> or element ref - no nested compositors, no mixed content.
 * Anything else falls back to normal DataRecord[Any] behavior.
 */
trait ChoiceAdtOverrides extends GenSource {

  // A choice qualifies when every branch is a plain element (no nested compositors).
  //
  // NOTE: context.compositorParents is NOT a marker for "abstract/polymorphic
  // type" - tracing ContextProcessor.makeCompositorName confirms scalaxb
  // registers *every* choice/sequence/all compositor belonging to *every*
  // complex type there unconditionally (it's just the index GenSource itself
  // uses to find "which compositors belong to this decl"). So eligibility
  // must NOT be gated on compositorParents - doing so excludes nearly every
  // choice in a real multi-hundred-type schema bundle. The actual duplicate-
  // declaration hazard this could cause is handled below instead, by
  // suppressing scalaxb's own makeCompositor emission for eligible choices.
  protected def isAdtEligible(choice: ChoiceDecl): Boolean =
    choice.particles.nonEmpty &&
      context.compositorNames.contains(choice) &&
      choice.particles.forall {
        case _: ElemDecl => true
        case _: ElemRef  => true
        case _           => false
      }

  protected def choiceBranches(choice: ChoiceDecl): List[ElemDecl] =
    choice.particles.collect {
      case elem: ElemDecl => elem
      case ref: ElemRef   => buildElement(ref)
    }

  // context.compositorNames is only unique enough for scalaxb's own inline
  // text usage - it was never meant to back standalone top-level
  // declarations, which is what we're doing. Two unrelated compositors can
  // legitimately compute the identical name string. Every compositor in
  // context.compositorParents that we are NOT suppressing (i.e. every
  // sequence/all/non-eligible-choice - see the makeCompositor override
  // below, which suppresses only ADT-eligible choices) still gets its own
  // stock `trait <name>` from scalaxb's own makeCompositor, so we reserve
  // those names and allocate our own guaranteed-unique name per eligible
  // ChoiceDecl: start from scalaxb's own name, and if that's already taken
  // (by us, or reserved for one of those stock declarations), suffix with
  // an increasing number until it isn't.
  protected lazy val reservedCompositorNames: Set[String] =
    context.compositorParents.keysIterator.collect {
      case c: HasParticle if context.compositorNames.contains(c) &&
          !(c match { case ch: ChoiceDecl => isAdtEligible(ch); case _ => false }) =>
        makeTypeName(context.compositorNames(c))
    }.toSet

  private val adtNameCache = scala.collection.mutable.Map.empty[ChoiceDecl, String]
  private val usedAdtNames = scala.collection.mutable.Set.empty[String]

  protected def adtName(choice: ChoiceDecl): String =
    adtNameCache.getOrElseUpdate(choice, {
      val base = makeTypeName(context.compositorNames(choice))
      def taken(n: String) = usedAdtNames(n) || reservedCompositorNames(n)
      var candidate = base
      var i = 2
      while (taken(candidate)) { candidate = base + i; i += 1 }
      usedAdtNames += candidate
      candidate
    })

  override def buildChoiceTypeName(decl: ComplexTypeDecl, choice: ChoiceDecl, shortLocal: Boolean): String =
    if (isAdtEligible(choice)) adtName(choice)
    else super.buildChoiceTypeName(decl, choice, shortLocal)

  // Args.buildArg(selector, typeSymbol, stackItem) - the 3-arg overload stock
  // buildConverter uses for its DataRecord[Any] wrapping - resolves a named
  // SimpleTypeDecl branch to its *base* type (e.g. plain "String"), even
  // when that simple type is an enumeration with its own dedicated
  // generated type name. That's invisible in stock output because it all
  // gets erased into DataRecord[Any] anyway. It is NOT invisible for us:
  // our case class field type comes from buildTypeName(elem.typeSymbol),
  // which (via buildTypeName(decl: SimpleTypeDecl, ...)) *does* resolve
  // enumerations to their own named type - so the two diverge and the
  // compiler rejects the mismatch (e.g. "Found: String, Required:
  // FooEnumType8"). This replicates that same 3-arg overload but keeps the
  // SimpleTypeDecl case consistent with buildTypeName(elem.typeSymbol) by
  // calling buildTypeName(decl, false) instead of buildTypeName(baseType(decl)).
  // Deliberately still ignores elem.minOccurs/maxOccurs (always "Single"),
  // matching stock buildConverter's intent: cardinality is already handled
  // by the outer occurrence-based parser combinator, and this only ever
  // converts the single already-matched node "x".
  protected def buildChoiceValueArg(selector: String, typeSymbol: XsTypeSymbol, stackItem: Option[String]): String =
    typeSymbol match {
      case AnyType(_) => selector
      case symbol: BuiltInSimpleTypeSymbol =>
        buildArg(buildTypeName(symbol), selector, Single, stackItem)
      case ReferenceTypeSymbol(decl: SimpleTypeDecl) =>
        buildArg(buildTypeName(decl, false), selector, Single, stackItem)
      case ReferenceTypeSymbol(_: ComplexTypeDecl) =>
        buildFromXML(buildTypeName(typeSymbol), selector, stackItem, None)
      case _ =>
        buildFromXML(buildTypeName(typeSymbol), selector, stackItem, None)
    }

  override def buildChoiceParser(choice: ChoiceDecl, occurrence: Occurrence,
      mixed: Boolean, ignoreSubGroup: Boolean): String =
    if (mixed || !isAdtEligible(choice)) super.buildChoiceParser(choice, occurrence, mixed, ignoreSubGroup)
    else {
      val name = adtName(choice)
      val singleOccurrence = occurrence.copy(minOccurs = 1, maxOccurs = 1)

      val perBranch = choiceBranches(choice) map { elem =>
        val rawMatcher = buildParser(elem, singleOccurrence, mixed, false, true)
        val valueExpr = buildChoiceValueArg("x", elem.typeSymbol, Some("node"))
        "(" + rawMatcher + " ^^ (x => " + name + "." + elem.name + "(" + valueExpr + ")))"
      }

      val base = perBranch.mkString(" | " + newline + indent(3))
      buildParserString(base, occurrence)
    }

  override def buildXMLString(param: Param): String = {
    val choiceOpt: Option[ChoiceDecl] = param.typeSymbol match {
      case XsDataRecord(ReferenceTypeSymbol(wrapperDecl: ComplexTypeDecl))
          if compositorWrapper.contains(wrapperDecl) =>
        compositorWrapper(wrapperDecl) match {
          case choice: ChoiceDecl if isAdtEligible(choice) => Some(choice)
          case _ => None
        }
      case _ => None
    }

    choiceOpt match {
      case None => super.buildXMLString(param)
      case Some(choice) =>
        val name = "__obj." + makeParamName(param.name, param.typeSymbol != XsAnyAttribute && param.attribute)
        val trait_ = adtName(choice)

        def matchCode(selector: String): String = {
          val cases = choiceBranches(choice) map { elem =>
            val branchNs = elementNamespaceString(elem.global, elem.namespace, elem.qualified)
            val typeAttribute = elem.typeSymbol match {
              case AnyType(_) => "true"
              case _          => "false"
            }
            "case b: " + trait_ + "." + elem.name + " => " +
              buildToXML(buildTypeName(elem.typeSymbol),
                "b.value, " + branchNs + ", " + quote(Some(elem.name)) + ", __scope, " + typeAttribute)
          }
          "(" + selector + " match {" + newline +
            cases.map(indent(3) + _).mkString(newline) + newline +
            indent(2) + "})"
        }

        val branchToXML = "x => " + matchCode("x")

        (param.cardinality, param.nillable) match {
          case (Multiple, true)  => name + " flatMap { x => x map { " + branchToXML + " } }"
          case (Multiple, false) => name + " flatMap { " + branchToXML + " }"
          case (Optional, true)  => name + " map { x => x map { " + branchToXML + " } } getOrElse {Nil}"
          case (Optional, false) => name + " map { " + branchToXML + " } getOrElse {Nil}"
          case (Single, _)       => matchCode(name)
        }
    }
  }

  // makeCompositor is what actually emits the bare, unsealed `trait <name>`
  // scalaxb generates for every compositor in context.compositorParents
  // (called unconditionally from makeTrait/makeCaseClassWithType/makeGroup
  // for the compositors belonging to a given complex type - see
  // ContextProcessor.makeCompositorName, which registers every choice
  // there, not just "polymorphic" ones). Since that would otherwise always
  // collide with our own sealed trait of the same name for an eligible
  // choice, we return an empty Snippet for those and let super handle
  // everything else (plain sequences, "all"s, and non-eligible choices)
  // exactly as scalaxb normally would.
  override def makeCompositor(compositor: HasParticle): scalaxb.compiler.Snippet = compositor match {
    case choice: ChoiceDecl if isAdtEligible(choice) => scalaxb.compiler.Snippet()
    case other => super.makeCompositor(other)
  }

  // Dedupe by ChoiceDecl equality (.distinct), not by name: two entries in
  // schema.choices that are .distinct-equal are the literal same choice
  // content encountered twice by the parser, so skipping the repeat is
  // correct. Uniqueness of the *emitted name* across genuinely different
  // choices is now guaranteed by adtName's allocator above instead, so we
  // no longer need (or want) to collapse different choices that happened to
  // share scalaxb's own name string - that used to silently merge unrelated
  // types under one shared trait, which was a correctness bug, not just a
  // cosmetic one.
  override def run: scalaxb.compiler.Snippet = {
    val base = super.run
    val extraAdts = schema.choices.distinct.collect {
      case choice: ChoiceDecl if isAdtEligible(choice) => makeChoiceAdt(choice)
    }
    scalaxb.compiler.Snippet((base +: extraAdts): _*)
  }

  // NOT sealed: GenSource.buildOptions(decl: ComplexTypeDecl) independently
  // scans every choice in every schema, and whenever a complex type's name
  // matches a choice branch's element type anywhere in the bundle, it adds
  // "extends <thisTrait>" to THAT complex type's own case class - in
  // whichever file it happens to be declared in.
  // That's a legitimate, independent extension point scalaxb always
  // provides, entirely outside our control - sealing would only be safe if
  // every implementor were guaranteed to live in this same file, which
  // isn't true here. We never pattern-match on anything but our own
  // generated wrapper case classes below, so losing exhaustiveness checking
  // costs us nothing correctness-wise.
  protected def makeChoiceAdt(choice: ChoiceDecl): scalaxb.compiler.Snippet = {
    val name = adtName(choice)
    val caseClasses = choiceBranches(choice) map { elem =>
      "case class " + elem.name + "(value: " + buildTypeName(elem.typeSymbol) + ") extends " + name
    }
    val src =
      <source>trait {name}
object {name} {{
  {caseClasses.mkString(newline + indent(1))}
}}</source>
    scalaxb.compiler.Snippet(src)
  }
}

class ChoiceGenSource(schema: SchemaDecl, context: XsdContext, config: Config)
  extends GenSource(schema, context, config) with ChoiceAdtOverrides

/** Drop-in replacement for scalaxb.compiler.xsd.Driver that uses ChoiceGenSource. */
class ChoiceAwareDriver extends Driver {
  override def generate(xsd: Schema, part: String, context: Context, cnfg: Config) = {
    val pkg = packageName(xsd.targetNamespace, context)
    Seq((pkg, scalaxb.compiler.Snippet(headerSnippet(pkg),
      (new ChoiceGenSource(xsd, context, cnfg)).run), part))
  }
}
