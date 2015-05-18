// parsing inline in all interesting positions
object A {
  inline val x = 1
  inline def square(x: Int): Int = x * x
  def foo: Int = {
    inline val x = 1
    inline def square(x: Int): Int = x * x
    square(x)
  }
}

inline class B
class A(val x: Int) { inline def foo: Int = 1 }

// after typer this should go to neg
class C(inline val x: Int) { inline def foo: Int = 1 }
