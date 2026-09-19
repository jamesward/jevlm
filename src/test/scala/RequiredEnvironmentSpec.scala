import zio.test.*

object RequiredEnvironmentSpec extends ZIOSpecDefault:
  private val typeSafe = RequiredEnvironment.Variable.TypeSafeApiKey

  def spec = suite("RequiredEnvironment")(
    test("reports the missing TypeSafe AI key") {
      val result = RequiredEnvironment.validate(Map.empty)
      assertTrue(
        result.left.exists(_.variables == List(typeSafe)),
        result.left.exists(_.getMessage.contains("TYPESAFE_API_KEY")),
      )
    },
    test("treats a blank key as missing") {
      val result = RequiredEnvironment.validate(Map(typeSafe.name -> "  "))
      assertTrue(result.left.exists(_.variables == List(typeSafe)))
    },
    test("accepts a nonblank TypeSafe AI key") {
      val result = RequiredEnvironment.validate(Map(typeSafe.name -> "typesafe-token"))
      assertTrue(result == Right(()))
    },
  )
