import zio.json.*
import zio.test.*

object JevPricingSpec extends ZIOSpecDefault:
  def spec = suite("JevPricing")(
    test("charges $0.042 per million input tokens") {
      assertTrue(
        JevPricing.pricing.estimateUsd(1_000_000, 0) == BigDecimal("0.042"),
        JevPricing.pricing.estimateUsd(500_000, 0) == BigDecimal("0.021"),
      )
    },
    test("does not charge for output tokens") {
      assertTrue(
        JevPricing.pricing.estimateUsd(0, 1_000_000) == BigDecimal("0"),
        JevPricing.pricing.estimateUsd(100, 1_000_000) == BigDecimal("0.0000042"),
      )
    },
    test("serializes estimated response cost in completion metadata") {
      val summary = WordGenerator.Summary(
        turns = 10,
        inputTokens = 1_000_000,
        outputTokens = 25_000,
        jevLatencyMs = 250,
        estimatedCostUsd = JevPricing.pricing.estimateUsd(1_000_000, 25_000),
      )
      assertTrue(summary.toJson.contains("\"estimatedCostUsd\":0.042"))
    },
  )
