package dotc

import test._
import dotty.partest._
import org.junit.Test
import org.junit.experimental.categories._

class inlinetest extends CompilerTest {
  val noCheckOptions = List("-pagewidth", "160")

  val defaultOutputDir = "./out/"

  implicit val defaultOptions = noCheckOptions ++ List(
      "-Yno-deep-subtypes", "-Yno-double-bindings",
      "-Ycheck:tailrec,resolveSuper,mixin,restoreScopes",
      "-d", defaultOutputDir
  )
  val testPickling = List("-Xprint-types", "-Ytest-pickler", "-Ystop-after:pickler")

  val twice = List("#runs", "2")
  val staleSymbolError: List[String] = List()

  val allowDeepSubtypes = defaultOptions diff List("-Yno-deep-subtypes")
  val allowDoubleBindings = defaultOptions diff List("-Yno-double-bindings")

  val testsDir      = "./tests/"
  val posDir        = testsDir + "pos/"
  val posSpecialDir = testsDir + "pos-special/"
  val negDir        = testsDir + "neg/"
  val newDir        = testsDir + "new/"

  val sourceDir = "./src/"
  val dottyDir  = sourceDir + "dotty/"
  val toolsDir  = dottyDir + "tools/"
  val dotcDir   = toolsDir + "dotc/"
  val coreDir   = dotcDir + "core/"

  @Test def dotc_inline = compileDir("./tests/pos/", "inline")
}
