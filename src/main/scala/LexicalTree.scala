import com.jamesward.zio_typesafe_ai.TypeSafeAI
import com.jamesward.zio_typesafe_ai.TypeSafeAI.*
import WordGeneration.*
import zio.*
import zio.schema.{Schema, derived}

object LexicalTree:
  val BeamWidth = 3
  val MaxLeavesPerBranch = 80

  final case class BranchId(value: String)
  final case class RetainedBranch(id: BranchId, probability: Double)

  enum SelectionPhase derives CanEqual:
    case Branches
    case Leaves(retained: Vector[RetainedBranch])

  enum BranchKind derives CanEqual:
    case Contextual
    case Topical
    case Discourse
    case PromptTerms
    case Common(initial: Char)

    def id: BranchId = this match
      case Contextual     => BranchId("contextual")
      case Topical        => BranchId("topical")
      case Discourse      => BranchId("discourse")
      case PromptTerms    => BranchId("prompt_terms")
      case Common(initial) => BranchId(s"common_$initial")

    def label: String = this match
      case Contextual      => "Contextual continuation"
      case Topical         => "Topic vocabulary"
      case Discourse       => "General response language"
      case PromptTerms     => "Terms from the user message"
      case Common(initial) => s"English words beginning with '$initial'"

    def purpose: String = this match
      case Contextual   => "Words strongly supported by the immediately preceding phrase or word."
      case Topical      => "Words related to the recognized subject and nearby prompt vocabulary."
      case Discourse    => "Common grammatical and explanatory words used to construct a response."
      case PromptTerms  => "Words copied from the user's message when repeating a named subject is useful."
      case Common(_)    => "Frequency-ranked English fallback words, partitioned only after contextual sources are exhausted."

  final case class Branch(
    id: BranchId,
    kind: BranchKind,
    candidates: Vector[RankedWordSource.Candidate],
  )

  final case class BranchDescription(
    decision: String,
    branch: String,
    purpose: String,
    candidateCount: Int,
    directChildren: Vector[String],
    sampleLeaves: Vector[String],
    bestHostRank: Int,
  ) derives Schema

  final case class LeafDescription(
    decision: String,
    word: String,
    resultingTail: String,
    source: String,
    branch: String,
    branchProbability: Double,
    hostRank: Int,
  ) derives Schema

  private final case class Leaf(
    id: String,
    word: String,
    branch: RetainedBranch,
    candidate: RankedWordSource.Candidate,
  )

  def branches(state: State): Vector[Branch] =
    RankedWordSource.candidates(state, RankedWordSource.MaxTreeCandidates)
      .groupBy(candidate => kind(candidate.source, candidate.word))
      .toVector
      .map: (branchKind, candidates) =>
        Branch(
          branchKind.id,
          branchKind,
          candidates.sortBy(_.rank).take(MaxLeavesPerBranch),
        )
      .sortBy(_.candidates.headOption.map(_.rank).getOrElse(Int.MaxValue))

  def branchOptions(state: State): Vector[LoopOption[Action]] =
    branches(state).map: branch =>
      val words = branch.candidates.map(_.word)
      LoopOption(
        branch.id.value,
        Action.SelectBranch(branch.id),
        Content(BranchDescription(
          decision = "Choose this branch when it contains the best grammatical and semantic continuation.",
          branch = branch.kind.label,
          purpose = branch.kind.purpose,
          candidateCount = words.size,
          directChildren = words,
          sampleLeaves = words.take(8),
          bestHostRank = branch.candidates.map(_.rank).min,
        )),
      )

  def retainBranches(state: State, turn: LoopTurn): Vector[RetainedBranch] =
    retainBranches(
      state,
      turn.answer.probabilities.view.mapValues(_.unwrap).toMap,
      turn.choice,
    )

  def retainBranches(
    state: State,
    probabilities: Map[String, Double],
    selectedId: String,
  ): Vector[RetainedBranch] =
    val available = branches(state).map(branch => branch.id.value -> branch.id).toMap
    val retained = probabilities.toVector.flatMap: (id, probability) =>
      available.get(id).map(RetainedBranch(_, probability))
    retained.sortBy(branch => -branch.probability).take(BeamWidth) match
      case empty if empty.isEmpty =>
        available.get(selectedId).toVector.map(RetainedBranch(_, 1.0))
      case values => values

  def leafOptions(state: State): Vector[LoopOption[Action]] =
    leaves(state).map: leaf =>
      val resultingTail = (state.responseWords.takeRight(7) :+ leaf.word).mkString(" ")
      LoopOption(
        leaf.id,
        Action.SelectWord(leaf.word),
        Content(LeafDescription(
          decision = "Choose this exact next word only if the complete resulting tail is grammatical, relevant, and non-redundant.",
          word = leaf.word,
          resultingTail = resultingTail,
          source = leaf.candidate.source.description,
          branch = leaf.branch.id.value,
          branchProbability = leaf.branch.probability,
          hostRank = leaf.candidate.rank,
        )),
      )

  def bestWord(state: State, turn: LoopTurn): Option[String] =
    bestWord(state, turn.answer.probabilities.view.mapValues(_.unwrap).toMap)

  def bestWord(state: State, probabilities: Map[String, Double]): Option[String] =
    leaves(state).flatMap: leaf =>
      probabilities.get(leaf.id).map: leafProbability =>
        val logScore = (
          math.log(math.max(leaf.branch.probability, 1e-12)) +
          math.log(math.max(leafProbability, 1e-12))
        ) / 2.0
        leaf.word -> math.exp(logScore)
    .maxByOption(_._2)
    .map(_._1)

  private def leaves(state: State): Vector[Leaf] =
    state.selectionPhase match
      case SelectionPhase.Branches => Vector.empty
      case SelectionPhase.Leaves(retained) =>
        val byId = branches(state).map(branch => branch.id -> branch).toMap
        retained.flatMap: retainedBranch =>
          byId.get(retainedBranch.id).toVector.flatMap: branch =>
            branch.candidates.map: candidate =>
              Leaf(
                id = s"leaf_${candidate.rank}",
                word = candidate.word,
                branch = retainedBranch,
                candidate = candidate,
              )
        .take(ChoiceCriteria.MaxOptions)

  private def kind(source: RankedWordSource.Source, word: String): BranchKind =
    source match
      case RankedWordSource.Source.JevPreflight      => BranchKind.Contextual
      case RankedWordSource.Source.Phrase(_)         => BranchKind.Contextual
      case RankedWordSource.Source.Bigram(_)         => BranchKind.Contextual
      case RankedWordSource.Source.Topic             => BranchKind.Topical
      case RankedWordSource.Source.DictionaryRelated => BranchKind.Topical
      case RankedWordSource.Source.ResponseVocabulary => BranchKind.Discourse
      case RankedWordSource.Source.Prompt             => BranchKind.PromptTerms
      case RankedWordSource.Source.Common             =>
        BranchKind.Common(word.headOption.map(_.toLower).getOrElse('#'))
