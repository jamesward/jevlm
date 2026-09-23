# Jev Word Stream

A Jev-only generative text experiment. The app uses the recursive loop in [`zio-typesafe-ai`](https://github.com/jamesward/zio-typesafe-ai) to traverse a contextual word hierarchy. Internal turns select either a structured lexical branch or a leaf word; only committed leaf words and sentence endings are streamed to the browser.

Every model round includes a structured view of the original user message, accumulated response, current sentence tail, factual constraints, traversal frontier, and separate Jev-round/committed-word budgets.

## Hierarchical word ranking

`RankedWordSource` retrieves up to 512 context-ranked candidates from:

1. phrase and bigram successors
2. explanatory topic vocabulary
3. normalized terms from the original prompt
4. broad response vocabulary and a frequency-ranked 48,000-word English fallback

`LexicalTree` groups that induced candidate pool into contextual, topical, discourse, prompt-term, and partitioned common-word branches. Root options contain structured subtree data: purpose, direct children, sample leaves, candidate count, and host rank. The probability-aware loop retains the top three root branches, presents at most 255 structured leaves from that beam, then commits the leaf maximizing the geometric mean of its root and leaf probabilities.

Duplicates and recently selected words are removed, and any candidate that would recreate an earlier trigram is excluded. Ending actions appear only at the branch phase and only when valid for the accumulated response. External-data limitations retain a finite truthful response path; ordinary prose uses hierarchical traversal.

Every structured state view reports a 68-round Jev budget and a 30-word commitment budget. Branch traversal is silent in the browser, each committed leaf resets traversal to the root, and the host reserves enough rounds to close the sentence and response before the hard loop limit.

The packaged fallback is the English 2018 frequency list from [`hermitdave/FrequencyWords`](https://github.com/hermitdave/FrequencyWords) at commit `525f9b560de45753a5ea01069454e72e9aa541c6`, filtered to 48,261 ASCII English word tokens. It is distributed under the MIT license included at `src/main/resources/dictionaries/FrequencyWords-LICENSE.txt`.

## Response cost

The completion summary estimates Jev cost from aggregate usage reported by `zio-typesafe-ai`. TypeSafe AI's [published Jev pricing](https://typesafe.ai/blog/introducing-system-one-models-and-jev) is **$0.042 per million input tokens** ($42 per billion); output tokens are free. The browser displays this estimate beside response time when generation completes.

## Configure

Set a TypeSafe AI key:

```bash
export TYPESAFE_API_KEY=YOUR_TYPESAFE_TOKEN
```

Startup fails before server initialization if it is missing or blank. `TYPESAFE_DEFAULT_MODEL` may optionally override the library's `jev-latest` default.

## Sensitive Jev HTTP logging

Every TypeSafe AI exchange logs its complete JSON request and complete decoded response re-encoded as canonical JSON at `INFO`. This includes the original user message, accumulated response state, candidate descriptions, probabilities, and usage. Logging is implemented with zio-typesafe-ai's `ExchangeObserver`, below the library's per-turn retry boundary, so every physical success or failure is observed. HTTP headers are never supplied to the observer, so the `Authorization` header and API key cannot be logged through this path. Treat these body logs as sensitive and apply appropriate production retention and access controls.

## Run

The current source uses the sibling `../zio-typesafe-ai` checkout for the unreleased `loopWithTurn` API. Enable the local project reference with `-Dlocal`:

```bash
./sbt -Dlocal dev
```

After a zio-typesafe-ai release containing `loopWithTurn`, ordinary builds can use the published dependency again.

Open <http://localhost:8080>.

## Test and package

```bash
./sbt -Dlocal "test; stage"
```

## Paid Jev evaluation suite

The test dependency `com.jamesward:zio-evals_3:0.1.0` comes from Maven Central. The complete unpaid catalog still contains 100 cases across external-data truthfulness, numeric/counting, timeless facts, completion/anti-repetition, code concepts, ambiguity/calibrated limitations, and safety.

To control token usage, `JevlmIntegrationSpec` runs only two paid high-value regressions:

- `001` — current Denver weather must produce a truthful external-data limitation
- `067` — `say hello` must terminate cleanly without unrelated trailing words

The focused suite is disabled unless `TYPESAFE_API_KEY` is set because every generated word, the native prompt preflight, and every Jev judge verdict are paid API calls:

```bash
export TYPESAFE_API_KEY=YOUR_TYPESAFE_TOKEN
./sbt -Dlocal "testOnly JevlmIntegrationSpec"
```

Each case runs Jevlm once and uses `JevJudge` with a 0.55 pass threshold against that case's rubric. The two cases may execute in parallel. To run one case:

```bash
./sbt -Dlocal 'testOnly JevlmIntegrationSpec -- -t "067 "' # say hello
./sbt -Dlocal 'testOnly JevlmIntegrationSpec -- -t "001 "' # Denver weather
```

A passing suite means Jev judged each sampled answer acceptable; it is not proof that Jevlm is more accurate than an LLM. A comparative accuracy claim requires running the same case catalog, sample count, and judge against explicitly named LLM baselines and reporting pass rates, uncertainty, failures, token usage, and cost.

The fast, unpaid `JevlmEvalCasesSpec` verifies the full catalog has exactly 100 unique cases with the intended category counts and that the paid subset remains exactly cases 001 and 067.

## Deploy to Heroku

The app uses `heroku/jvm` followed by `https://github.com/jamesward/buildpack-scala`. The buildpack stages the native-packager app and runs the generated `bin/jev-word-stream` launcher; no `Procfile` is needed.

The server binds to all interfaces and uses `PORT` when present:

```bash
heroku config:set --app YOUR_APP TYPESAFE_API_KEY=YOUR_TYPESAFE_TOKEN
heroku ps:scale --app YOUR_APP web=1
```
