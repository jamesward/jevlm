object JevPricing:
  final case class TokenPricing(
    inputUsdPerMillion: BigDecimal,
    outputUsdPerMillion: BigDecimal,
  ):
    def estimateUsd(inputTokens: Int, outputTokens: Int): BigDecimal =
      val inputCost = inputUsdPerMillion * BigDecimal(inputTokens)
      val outputCost = outputUsdPerMillion * BigDecimal(outputTokens)
      (inputCost + outputCost) / BigDecimal(1_000_000)

  val PricingPublishedOn = "2026-09-15"
  val pricing: TokenPricing = TokenPricing(
    inputUsdPerMillion = BigDecimal("0.042"),
    outputUsdPerMillion = BigDecimal("0"),
  )
