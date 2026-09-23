import WordGeneration.*
import zio.test.*

object LexicalTreeSpec extends ZIOSpecDefault:
  private def state: State =
    val prompt = Prompt.parse("Explain how large language models work").fold(
      error => throw IllegalArgumentException(error),
      identity,
    )
    State.initial(prompt)

  def spec = suite("LexicalTree")(
    test("builds bounded branches with structured subtree descriptions") {
      val branches = LexicalTree.branches(state)
      val options = LexicalTree.branchOptions(state)
      val descriptions = options.map(_.description.as[LexicalTree.BranchDescription])

      assertTrue(
        branches.nonEmpty,
        branches.size <= 255,
        branches.forall(_.candidates.nonEmpty),
        branches.forall(_.candidates.size <= LexicalTree.MaxLeavesPerBranch),
        options.map(_.id).distinct.size == options.size,
        descriptions.forall(_.exists(_.directChildren.nonEmpty)),
        descriptions.forall(_.exists(_.sampleLeaves.size <= 8)),
      )
    },
    test("retains three likely branches and chooses the best normalized leaf path") {
      val branches = LexicalTree.branches(state)
      val rootProbabilities = branches.zipWithIndex.map: (branch, index) =>
        branch.id.value -> (1.0 / (index + 1).toDouble)
      .toMap
      val retained = LexicalTree.retainBranches(state, rootProbabilities, branches.head.id.value)
      val leafState = descend(state, retained) match
        case Transition.Continue(next, Emission.BranchSelected) => next
        case other => throw IllegalStateException(s"Unexpected descent: $other")
      val leaves = LexicalTree.leafOptions(leafState)
      val leafProbabilities = leaves.zipWithIndex.map: (leaf, index) =>
        leaf.id -> (if index == leaves.size - 1 then 0.9 else 0.01)
      .toMap
      val expected = leaves.flatMap: leaf =>
        leaf.description.as[LexicalTree.LeafDescription].toOption.map: description =>
          description.word -> math.sqrt(description.branchProbability * leafProbabilities(leaf.id))
      .maxByOption(_._2).map(_._1)

      assertTrue(
        retained.size == math.min(LexicalTree.BeamWidth, branches.size),
        retained.map(_.probability) == retained.map(_.probability).sorted.reverse,
        leaves.nonEmpty,
        leaves.size <= 255,
        leaves.map(_.id).distinct.size == leaves.size,
        LexicalTree.bestWord(leafState, leafProbabilities) == expected,
      )
    },
  )
