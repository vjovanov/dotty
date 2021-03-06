package dotty.tools.dotc
package core

import Contexts._, Types._, Symbols._, Names._, Flags._, Scopes._
import SymDenotations._, Denotations.Denotation
import config.Printers._
import Decorators._
import StdNames._
import util.SimpleMap
import collection.mutable
import ast.tpd._

trait TypeOps { this: Context => // TODO: Make standalone object.

  final def asSeenFrom(tp: Type, pre: Type, cls: Symbol, theMap: AsSeenFromMap): Type = {

    def toPrefix(pre: Type, cls: Symbol, thiscls: ClassSymbol): Type = /*>|>*/ ctx.conditionalTraceIndented(TypeOps.track, s"toPrefix($pre, $cls, $thiscls)") /*<|<*/ {
      if ((pre eq NoType) || (pre eq NoPrefix) || (cls is PackageClass))
        tp
      else if (thiscls.derivesFrom(cls) && pre.baseTypeRef(thiscls).exists)
        pre match {
          case SuperType(thispre, _) => thispre
          case _ => pre
        }
      else if ((pre.termSymbol is Package) && !(thiscls is Package))
        toPrefix(pre.select(nme.PACKAGE), cls, thiscls)
      else
        toPrefix(pre.baseTypeRef(cls).normalizedPrefix, cls.owner, thiscls)
    }

    /*>|>*/ ctx.conditionalTraceIndented(TypeOps.track, s"asSeen ${tp.show} from (${pre.show}, ${cls.show})", show = true) /*<|<*/ { // !!! DEBUG
      tp match {
        case tp: NamedType =>
          val sym = tp.symbol
          if (sym.isStatic) tp
          else tp.derivedSelect(asSeenFrom(tp.prefix, pre, cls, theMap))
        case tp: ThisType =>
          toPrefix(pre, cls, tp.cls)
        case _: BoundType | NoPrefix =>
          tp
        case tp: RefinedType =>
          tp.derivedRefinedType(
            asSeenFrom(tp.parent, pre, cls, theMap),
            tp.refinedName,
            asSeenFrom(tp.refinedInfo, pre, cls, theMap))
        case tp: TypeAlias  =>
          tp.derivedTypeAlias(asSeenFrom(tp.alias, pre, cls, theMap))
        case _ =>
          (if (theMap != null) theMap else new AsSeenFromMap(pre, cls))
            .mapOver(tp)
      }
    }
  }

  class AsSeenFromMap(pre: Type, cls: Symbol) extends TypeMap {
    def apply(tp: Type) = asSeenFrom(tp, pre, cls, this)
  }

  /** Implementation of Types#simplified */
  final def simplify(tp: Type, theMap: SimplifyMap): Type = tp match {
    case tp: NamedType =>
      if (tp.symbol.isStatic) tp
      else tp.derivedSelect(simplify(tp.prefix, theMap)) match {
        case tp1: NamedType if tp1.denotationIsCurrent =>
          val tp2 = tp1.reduceProjection
          //if (tp2 ne tp1) println(i"simplified $tp1 -> $tp2")
          tp2
        case tp1 => tp1
      }
    case tp: PolyParam =>
      typerState.constraint.typeVarOfParam(tp) orElse tp
    case  _: ThisType | _: BoundType | NoPrefix =>
      tp
    case tp: RefinedType =>
      tp.derivedRefinedType(simplify(tp.parent, theMap), tp.refinedName, simplify(tp.refinedInfo, theMap))
    case tp: TypeAlias =>
      tp.derivedTypeAlias(simplify(tp.alias, theMap))
    case AndType(l, r) =>
      simplify(l, theMap) & simplify(r, theMap)
    case OrType(l, r) =>
      simplify(l, theMap) | simplify(r, theMap)
    case _ =>
      (if (theMap != null) theMap else new SimplifyMap).mapOver(tp)
  }

  class SimplifyMap extends TypeMap {
    def apply(tp: Type) = simplify(tp, this)
  }

  /** Approximate union type by intersection of its dominators.
   *  See Type#approximateUnion for an explanation.
   */
  def approximateUnion(tp: Type): Type = {
    /** a faster version of cs1 intersect cs2 */
    def intersect(cs1: List[ClassSymbol], cs2: List[ClassSymbol]): List[ClassSymbol] = {
      val cs2AsSet = new util.HashSet[ClassSymbol](100)
      cs2.foreach(cs2AsSet.addEntry)
      cs1.filter(cs2AsSet.contains)
    }
    /** The minimal set of classes in `cs` which derive all other classes in `cs` */
    def dominators(cs: List[ClassSymbol], accu: List[ClassSymbol]): List[ClassSymbol] = (cs: @unchecked) match {
      case c :: rest =>
        val accu1 = if (accu exists (_ derivesFrom c)) accu else c :: accu
        if (cs == c.baseClasses) accu1 else dominators(rest, accu1)
    }
    if (ctx.featureEnabled(defn.LanguageModuleClass, nme.keepUnions)) tp
    else tp match {
      case tp: OrType =>
        val commonBaseClasses = tp.mapReduceOr(_.baseClasses)(intersect)
        val doms = dominators(commonBaseClasses, Nil)
        doms.map(tp.baseTypeWithArgs).reduceLeft(AndType.apply)
      case tp @ AndType(tp1, tp2) =>
        tp derived_& (approximateUnion(tp1), approximateUnion(tp2))
      case tp: RefinedType =>
        tp.derivedRefinedType(approximateUnion(tp.parent), tp.refinedName, tp.refinedInfo)
      case _ =>
        tp
    }
  }

  /** A type is volatile if its DNF contains an alternative of the form
   *  {P1, ..., Pn}, {N1, ..., Nk}, where the Pi are parent typerefs and the
   *  Nj are refinement names, and one the 4 following conditions is met:
   *
   *  1. At least two of the parents Pi are abstract types.
   *  2. One of the parents Pi is an abstract type, and one other type Pj,
   *     j != i has an abstract member which has the same name as an
   *     abstract member of the whole type.
   *  3. One of the parents Pi is an abstract type, and one of the refinement
   *     names Nj refers to an abstract member of the whole type.
   *  4. One of the parents Pi is an an alias type with a volatile alias
   *     or an abstract type with a volatile upper bound.
   *
   *  Lazy values are not allowed to have volatile type, as otherwise
   *  unsoundness can result.
   */
  final def isVolatile(tp: Type): Boolean = {

    /** Pre-filter to avoid expensive DNF computation
     *  If needsChecking returns false it is guaranteed that
     *  DNF does not contain intersections, or abstract types with upper
     *  bounds that themselves need checking.
     */
    def needsChecking(tp: Type, isPart: Boolean): Boolean = tp match {
      case tp: TypeRef =>
        tp.info match {
          case TypeAlias(alias) =>
            needsChecking(alias, isPart)
          case TypeBounds(lo, hi) =>
            isPart || tp.controlled(isVolatile(hi))
          case _ => false
        }
      case tp: RefinedType =>
        needsChecking(tp.parent, true)
      case tp: TypeProxy =>
        needsChecking(tp.underlying, isPart)
      case tp: AndType =>
        true
      case tp: OrType =>
        isPart || needsChecking(tp.tp1, isPart) && needsChecking(tp.tp2, isPart)
      case _ =>
        false
    }

    needsChecking(tp, false) && {
      DNF(tp) forall { case (parents, refinedNames) =>
        val absParents = parents filter (_.symbol is Deferred)
        absParents.nonEmpty && {
          absParents.lengthCompare(2) >= 0 || {
            val ap = absParents.head
            ((parents exists (p =>
              (p ne ap)
              || p.memberNames(abstractTypeNameFilter, tp).nonEmpty
              || p.memberNames(abstractTermNameFilter, tp).nonEmpty))
            || (refinedNames & tp.memberNames(abstractTypeNameFilter, tp)).nonEmpty
            || (refinedNames & tp.memberNames(abstractTermNameFilter, tp)).nonEmpty
            || isVolatile(ap))
          }
        }
      }
    }
  }

  /** The disjunctive normal form of this type.
   *  This collects a set of alternatives, each alternative consisting
   *  of a set of typerefs and a set of refinement names. Both sets are represented
   *  as lists, to obtain a deterministic order. Collected are
   *  all type refs reachable by following aliases and type proxies, and
   *  collecting the elements of conjunctions (&) and disjunctions (|).
   *  The set of refinement names in each alternative
   *  are the set of names in refinement types encountered during the collection.
   */
  final def DNF(tp: Type): List[(List[TypeRef], Set[Name])] = ctx.traceIndented(s"DNF($this)", checks) {
    tp.dealias match {
      case tp: TypeRef =>
        (tp :: Nil, Set[Name]()) :: Nil
      case RefinedType(parent, name) =>
        for ((ps, rs) <- DNF(parent)) yield (ps, rs + name)
      case tp: TypeProxy =>
        DNF(tp.underlying)
      case AndType(l, r) =>
        for ((lps, lrs) <- DNF(l); (rps, rrs) <- DNF(r))
          yield (lps | rps, lrs | rrs)
      case OrType(l, r) =>
        DNF(l) | DNF(r)
      case tp =>
        TypeOps.emptyDNF
    }
  }

  private def enterArgBinding(formal: Symbol, info: Type, cls: ClassSymbol, decls: Scope) = {
    val lazyInfo = new LazyType { // needed so we do not force `formal`.
      def complete(denot: SymDenotation)(implicit ctx: Context): Unit = {
        denot setFlag formal.flags & RetainedTypeArgFlags
        denot.info = info
      }
    }
    val typeArgFlag = if (formal is Local) TypeArgument else EmptyFlags
    val sym = ctx.newSymbol(
      cls, formal.name,
      formal.flagsUNSAFE & RetainedTypeArgFlags | typeArgFlag | Override,
      lazyInfo,
      coord = cls.coord)
    cls.enter(sym, decls)
  }

  /** If `tpe` is of the form `p.x` where `p` refers to a package
   *  but `x` is not owned by a package, expand it to
   *
   *      p.package.x
   */
  def makePackageObjPrefixExplicit(tpe: NamedType): Type = {
    def tryInsert(pkgClass: SymDenotation): Type = pkgClass match {
      case pkgCls: PackageClassDenotation if !(tpe.symbol.maybeOwner is Package) =>
        tpe.derivedSelect(pkgCls.packageObj.valRef)
      case _ =>
        tpe
    }
    tpe.prefix match {
      case pre: ThisType if pre.cls is Package => tryInsert(pre.cls)
      case pre: TermRef if pre.symbol is Package => tryInsert(pre.symbol.moduleClass)
      case _ => tpe
    }
  }

  /** If we have member definitions
   *
   *     type argSym v= from
   *     type from v= to
   *
   *  where the variances of both alias are the same, then enter a new definition
   *
   *     type argSym v= to
   *
   *  unless a definition for `argSym` already exists in the current scope.
   */
  def forwardRef(argSym: Symbol, from: Symbol, to: TypeBounds, cls: ClassSymbol, decls: Scope) =
    argSym.info match {
      case info @ TypeBounds(lo2 @ TypeRef(_: ThisType, name), hi2) =>
        if (name == from.name &&
            (lo2 eq hi2) &&
            info.variance == to.variance &&
            !decls.lookup(argSym.name).exists) {
              // println(s"short-circuit ${argSym.name} was: ${argSym.info}, now: $to")
              enterArgBinding(argSym, to, cls, decls)
            }
      case _ =>
    }


  /** Normalize a list of parent types of class `cls` that may contain refinements
   *  to a list of typerefs referring to classes, by converting all refinements to member
   *  definitions in scope `decls`. Can add members to `decls` as a side-effect.
   */
  def normalizeToClassRefs(parents: List[Type], cls: ClassSymbol, decls: Scope): List[TypeRef] = {

    /** If we just entered the type argument binding
     *
     *    type From = To
     *
     *  and there is a type argument binding in a parent in `prefs` of the form
     *
     *    type X = From
     *
     *  then also add the binding
     *
     *    type X = To
     *
     *  to the current scope, provided (1) variances of both aliases are the same, and
     *  (2) X is not yet defined in current scope. This "short-circuiting" prevents
     *  long chains of aliases which would have to be traversed in type comparers.
     */
    def forwardRefs(from: Symbol, to: Type, prefs: List[TypeRef]) = to match {
      case to @ TypeBounds(lo1, hi1) if lo1 eq hi1 =>
        for (pref <- prefs)
          for (argSym <- pref.decls)
            if (argSym is TypeArgument)
              forwardRef(argSym, from, to, cls, decls)
      case _ =>
    }

    // println(s"normalizing $parents of $cls in ${cls.owner}") // !!! DEBUG
    var refinements: SimpleMap[TypeName, Type] = SimpleMap.Empty
    var formals: SimpleMap[TypeName, Symbol] = SimpleMap.Empty
    def normalizeToRef(tp: Type): TypeRef = tp match {
      case tp @ RefinedType(tp1, name: TypeName) =>
        val prevInfo = refinements(name)
        refinements = refinements.updated(name,
            if (prevInfo == null) tp.refinedInfo else prevInfo & tp.refinedInfo)
        formals = formals.updated(name, tp1.typeParamNamed(name))
        normalizeToRef(tp1)
      case tp: TypeRef =>
        if (tp.symbol.info.isAlias) normalizeToRef(tp.info.bounds.hi)
        else tp
      case ErrorType =>
        defn.AnyClass.typeRef
      case _ =>
        throw new TypeError(s"unexpected parent type: $tp")
    }
    val parentRefs = parents map normalizeToRef
    refinements foreachBinding { (name, refinedInfo) =>
      assert(decls.lookup(name) == NoSymbol, // DEBUG
        s"redefinition of ${decls.lookup(name).debugString} in ${cls.showLocated}")
      enterArgBinding(formals(name), refinedInfo, cls, decls)
    }
    // These two loops cannot be fused because second loop assumes that
    // all arguments have been entered in `decls`.
    refinements foreachBinding { (name, refinedInfo) =>
      forwardRefs(formals(name), refinedInfo, parentRefs)
    }
    parentRefs
  }

  /** An argument bounds violation is a triple consisting of
   *   - the argument tree
   *   - a string "upper" or "lower" indicating which bound is violated
   *   - the violated bound
   */
  type BoundsViolation = (Tree, String, Type)

  /** The list of violations where arguments are not within bounds.
   *  @param  args          The arguments
   *  @param  boundss       The list of type bounds
   *  @param  instantiate   A function that maps a bound type and the list of argument types to a resulting type.
   *                        Needed to handle bounds that refer to other bounds.
   */
  def boundsViolations(args: List[Tree], boundss: List[TypeBounds], instantiate: (Type, List[Type]) => Type)(implicit ctx: Context): List[BoundsViolation] = {
    val argTypes = args.tpes
    val violations = new mutable.ListBuffer[BoundsViolation]
    for ((arg, bounds) <- args zip boundss) {
      def checkOverlapsBounds(lo: Type, hi: Type): Unit = {
        //println(i"instantiating ${bounds.hi} with $argTypes")
        //println(i" = ${instantiate(bounds.hi, argTypes)}")
        val hiBound = instantiate(bounds.hi, argTypes.mapConserve(_.bounds.hi))
        val loBound = instantiate(bounds.lo, argTypes.mapConserve(_.bounds.lo))
          // Note that argTypes can contain a TypeBounds type for arguments that are
          // not fully determined. In that case we need to check against the hi bound of the argument.
        if (!(lo <:< hiBound)) violations += ((arg, "upper", hiBound))
        if (!(loBound <:< hi)) violations += ((arg, "lower", bounds.lo))
      }
      arg.tpe match {
        case TypeBounds(lo, hi) => checkOverlapsBounds(lo, hi)
        case tp => checkOverlapsBounds(tp, tp)
      }
    }
    violations.toList
  }

  /** Is `feature` enabled in class `owner`?
   *  This is the case if one of the following two alternatives holds:
   *
   *  1. The feature is imported by a named import
   *
   *       import owner.feature
   *
   *  (the feature may be bunched with others, or renamed, but wildcard imports
   *  don't count).
   *
   *  2. The feature is enabled by a compiler option
   *
   *       - language:<prefix>feature
   *
   *  where <prefix> is the full name of the owner followed by a "." minus
   *  the prefix "dotty.language.".
   */
  def featureEnabled(owner: ClassSymbol, feature: TermName): Boolean = {
    def toPrefix(sym: Symbol): String =
      if (sym eq defn.LanguageModuleClass) "" else toPrefix(sym.owner) + sym.name + "."
    def featureName = toPrefix(owner) + feature
    def hasImport(implicit ctx: Context): Boolean = (
         ctx.importInfo != null
      && (   (ctx.importInfo.site.widen.typeSymbol eq owner)
          && ctx.importInfo.originals.contains(feature)
          ||
          { var c = ctx.outer
            while (c.importInfo eq ctx.importInfo) c = c.outer
            hasImport(c)
          }))
    def hasOption = ctx.base.settings.language.value exists (s => s == featureName || s == "_")
    hasImport || hasOption
  }

  /** Is auto-tupling enabled? */
  def canAutoTuple =
    !featureEnabled(defn.LanguageModuleClass, nme.noAutoTupling)
}

object TypeOps {
  val emptyDNF = (Nil, Set[Name]()) :: Nil
  var track = false // !!!DEBUG
}
