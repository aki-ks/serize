package serize
package test

import java.nio.{ByteBuffer, ByteOrder}

import boopickle._
import org.scalatest._
import org.scalatest.prop.PropertyChecks

object AnnotatedPickler extends Default {
  val container = Container
    .withCaseObject(CaseObject)
    .withCaseClass[CaseClass0]
    .withCaseClass[CaseClass1]
    .withCaseClass[CaseClass2]
    .withCaseClass[CaseClass2WithDefaults]
}

object ContainerPickler extends Default {
  val container = Container
    .withCaseObject(CaseObject, "CaseObject")
    .withCaseClass[CaseClass0]("CaseClass0")()
    .withCaseClass[CaseClass1]("CaseClass1")(F(0).a)
    .withCaseClass[CaseClass2]("CaseClass2")(F(-93).a, F(954).b)
    .withCaseClass[CaseClass2WithDefaults]("CaseClass2WithDefaults")(F(-93).a = 10, F(954).b = 20)
}

class SerizeTest extends FunSuite with Matchers with PropertyChecks {
  case class PickleStateFactory(f: () => PickleState)
  case class UnpickleStateFactory(f: ByteBuffer => UnpickleState)

  val containers = AnnotatedPickler :: ContainerPickler :: Nil
  val unpicklerFactories = UnpickleStateFactory(UnpickleState.unpickleStateSize) :: UnpickleStateFactory(SpeedOriented.unpickleStateSpeed) :: Nil
  val picklerFactories = PickleStateFactory(() => PickleState.pickleStateSize) :: PickleStateFactory(() => SpeedOriented.pickleStateSpeed) :: Nil

  /** Serialize a value into a bytearray */
  def pickle[A](a: A)(implicit pickler: Pickler[A], factory: PickleStateFactory): Array[Byte] = {
    val state = factory.f()
    pickler.pickle(a)(state)

    val buffer = state.toByteBuffer
    val array = new Array[Byte](buffer.remaining())
    buffer.get(array)
    array
  }

  /** Deserialize a bytearray into a value */
  def unpickle[A](array: Array[Byte])(implicit pickler: Pickler[A], factory: UnpickleStateFactory): A = {
    val state = factory.f(ByteBuffer.wrap(array).order(ByteOrder.LITTLE_ENDIAN))
    pickler.unpickle(state)
  }

  def pickleField[A](id: Int, value: A)(implicit intPickler: Pickler[Int], pickler: Pickler[A], factory: PickleStateFactory): Array[Byte] = {
    val fieldData = pickle[A](value)
    pickle(id) ++ pickle(fieldData.length) ++ fieldData
  }

  def doTest[A](value: A)(getArray: PickleStateFactory => Array[Byte])(implicit pickler: Pickler[A]): Unit = {
    for((picklerFactory, unpicklerFactory) ← picklerFactories zip unpicklerFactories) {
      implicit val _picklerFactory = picklerFactory
      implicit val _unpicklerFactory = unpicklerFactory

      val array = getArray(picklerFactory)
      unpickle[A](array) shouldEqual value
      pickle[A](value) shouldEqual array
    }
  }

  test("Serialize case object") {
    for(container ← containers) {
      import container._
      doTest(CaseObject) { implicit factory =>
        pickle("CaseObject") ++ // id of class
          pickle(0) // no fields
      }
    }
  }

  test("Serialize case classes") {
    for(container ← containers) {
      import container._

      doTest(CaseClass0()) { implicit factory =>
        pickle("CaseClass0") ++ // id of class
          pickle(0) // no fields
      }

      forAll { a: Int =>
        doTest(CaseClass1(a)) { implicit factory =>
          pickle("CaseClass1") ++ // id of class
            pickle(1) ++ // 1 field
            pickleField(0, a) // first field
        }
      }

      forAll { (a: Int, b: Int) =>
        doTest(CaseClass2(a, b)) { implicit factory =>
          pickle("CaseClass2") ++ // id of class
            pickle(2) ++ // 2 fields
            pickleField(-93, a) ++ // first field
            pickleField(954, b) // second field
        }
      }
    }
  }

  test("Deserialize case class with fields in different order") {
    for {
      container ← containers
      (picklerFactory, unpicklerFactory) ← picklerFactories zip unpicklerFactories
    } {
      import container._
      implicit val _picklerFactory = picklerFactory
      implicit val _unpicklerFactory = unpicklerFactory

      forAll { (a: Int, b: Int) =>
        unpickle[CaseClass2](
          pickle("CaseClass2") ++ // id of class
            pickle(2) ++ // 2 fields
            pickleField(954, b) ++ // second field
            pickleField(-93, a) // first field
        ) shouldEqual CaseClass2(a, b)
      }
    }
  }

  test("Deserialize case class with no longer existing fields") {
    for {
      container ← containers
      (picklerFactory, unpicklerFactory) ← picklerFactories zip unpicklerFactories
    } {
      import container._
      implicit val _picklerFactory = picklerFactory
      implicit val _unpicklerFactory = unpicklerFactory

      forAll { (a: Int, b: Int, any1: Int, any2: Int) =>
        unpickle[CaseClass2](
          pickle("CaseClass2") ++ // id of class
            pickle(4) ++ // 4 fields
            pickleField(9, any1) ++ // non existent field
            pickleField(4, any2) ++ // non existent field
            pickleField(-93, a) ++ // first field
            pickleField(954, b) // second field
        ) shouldEqual CaseClass2(a, b)
      }
    }
  }

  test("Deserialize case class from default values") {
    for {
      container ← containers
      (picklerFactory, unpicklerFactory) ← picklerFactories zip unpicklerFactories
    } {
      import container._
      implicit val _picklerFactory = picklerFactory
      implicit val _unpicklerFactory = unpicklerFactory

      unpickle[CaseClass2WithDefaults](
        pickle("CaseClass2WithDefaults") ++ // id of class
          pickle(0) // 0 fields
      ) shouldEqual CaseClass2WithDefaults()

      forAll { a: Int =>
        unpickle[CaseClass2WithDefaults](
          pickle("CaseClass2WithDefaults") ++ // id of class
            pickle(1) ++ // 1 field
            pickleField(-93, a) // first field
        ) shouldEqual CaseClass2WithDefaults(a = a)
      }

      forAll { b: Int =>
        unpickle[CaseClass2WithDefaults](
          pickle("CaseClass2WithDefaults") ++ // id of class
            pickle(1) ++ // 1 field
            pickleField(954, b) // second field
        ) shouldEqual CaseClass2WithDefaults(b = b)
      }

      forAll { (b: Int, any: Int) =>
        unpickle[CaseClass2WithDefaults](
          pickle("CaseClass2WithDefaults") ++ // id of class
            pickle(1) ++ // 2 field
            pickleField(954, b) ++ // second field
            pickleField(487853, any) // non existent field
        ) shouldEqual CaseClass2WithDefaults(b = b)
      }
    }
  }

}
