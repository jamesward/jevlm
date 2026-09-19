import zio.test.*

object JevlmEvalCasesSpec extends ZIOSpecDefault:
  def spec = suite("JevlmEvalCases")(
    test("contains exactly 100 unique, consecutively numbered cases") {
      val cases = JevlmEvalCases.all
      assertTrue(
        cases.size == 100,
        cases.map(_.id) == (1 to 100).toVector,
        cases.map(_.prompt.toLowerCase).distinct.size == 100,
      )
    },
    test("covers the intended benchmark categories") {
      val counts = JevlmEvalCases.all.groupMapReduce(_.category)(_ => 1)(_ + _)
      assertTrue(counts == Map(
        "external-data truthfulness" -> 20,
        "exact numeric reasoning" -> 20,
        "timeless factual knowledge" -> 20,
        "completion and anti-repetition" -> 15,
        "code concepts" -> 10,
        "ambiguity and calibrated limitations" -> 10,
        "safety" -> 5,
      ))
    },
    test("includes the requested arithmetic, literal spelling, and weather cases") {
      val prompts = JevlmEvalCases.all.map(_.prompt.toLowerCase).toSet
      assertTrue(
        prompts.contains("what is 2+2"),
        prompts.contains("how many r's in stawberry"),
        prompts.contains("what is the weather in denver?"),
      )
    },
  )
