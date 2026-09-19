# Jev Word Stream

A Jev-only generative text experiment. The app repeatedly asks the recursive loop in [`zio-typesafe-ai`](https://github.com/jamesward/zio-typesafe-ai) to choose one action:

- one ranked next-word candidate
- `END_OF_SENTENCE`
- `END_OF_RESPONSE`

Every iteration includes the original user message and the complete response accumulated so far. Each selected word is sent to the browser immediately with server-sent events.

## Word ranking

The app does not load a large language model or an exhaustive vocabulary tree. `RankedWordSource` builds a compact autocomplete-style candidate list using:

1. ranked bigram successors for the previous word
2. explanatory vocabulary for recognized topics
3. normalized topic terms extracted from the original prompt
4. broad response vocabulary and a frequency-ranked 48,000-word English fallback

Duplicates and recently selected words are removed while preserving rank, and any candidate that would recreate an earlier trigram is removed. Jev receives at most 96 words plus only the ending actions valid for the current state, well below its 255-choice limit. `END_OF_SENTENCE` is unavailable at the start of a sentence, and `END_OF_RESPONSE` is unavailable until the response contains enough words to be meaningful.

Every state view tells Jev its 48-turn total budget, turns used, and turns remaining. The host begins forced completion on a repeated trigram, at 36 response words, or with two turns remaining; it reserves a sentence-ending turn when needed and then supplies only `END_OF_RESPONSE`. This keeps the library's hard iteration limit as a safety net rather than a normal termination path.

The packaged fallback is the English 2018 frequency list from [`hermitdave/FrequencyWords`](https://github.com/hermitdave/FrequencyWords) at commit `525f9b560de45753a5ea01069454e72e9aa541c6`, filtered to 48,261 ASCII English word tokens. It is distributed under the MIT license included at `src/main/resources/dictionaries/FrequencyWords-LICENSE.txt`.

## Response cost

The completion summary estimates Jev cost from aggregate usage reported by `zio-typesafe-ai`. TypeSafe AI's [published Jev pricing](https://typesafe.ai/blog/introducing-system-one-models-and-jev) is **$0.042 per million input tokens** ($42 per billion); output tokens are free. The browser displays this estimate beside response time when generation completes.

## Configure

Set a TypeSafe AI key:

```bash
export TYPESAFE_API_KEY=YOUR_TYPESAFE_TOKEN
```

Startup fails before server initialization if it is missing or blank. `TYPESAFE_DEFAULT_MODEL` may optionally override the library's `jev-latest` default.

## Run

```bash
./sbt dev
```

Open <http://localhost:8080>.

## Test and package

```bash
./sbt "test; stage"
```

## Paid Jev evaluation suite

The test dependency `com.jamesward:zio-evals_3:0.1.0` comes from Maven Central. `JevlmIntegrationSpec` contains 100 individually reported integration tests: 20 external-data truthfulness, 20 exact numeric/counting, 20 timeless facts, 15 completion/anti-repetition, 10 code concepts, 10 ambiguity/calibrated limitations, and 5 safety cases.

The suite is disabled unless `TYPESAFE_API_KEY` is set because every generated word, the native prompt preflight, and every Jev judge verdict are paid API calls:

```bash
export TYPESAFE_API_KEY=YOUR_TYPESAFE_TOKEN
./sbt "testOnly JevlmIntegrationSpec"
```

Each case runs Jevlm once and uses `JevJudge` with a 0.55 pass threshold against that case's rubric. A passing suite means Jev judged each sampled answer acceptable; it is not proof that Jevlm is more accurate than an LLM. A comparative accuracy claim requires running the same case catalog, sample count, and judge against explicitly named LLM baselines and reporting pass rates, uncertainty, failures, token usage, and cost. One sample per case is useful for regression detection but insufficient for a strong statistical claim.

The fast, unpaid `JevlmEvalCasesSpec` always verifies that the catalog has exactly 100 unique cases with the intended category counts and required prompts.

## Deploy to Heroku

The app uses `heroku/jvm` followed by `https://github.com/jamesward/buildpack-scala`. The buildpack stages the native-packager app and runs the generated `bin/jev-word-stream` launcher; no `Procfile` is needed.

The server binds to all interfaces and uses `PORT` when present:

```bash
heroku config:set --app YOUR_APP TYPESAFE_API_KEY=YOUR_TYPESAFE_TOKEN
heroku ps:scale --app YOUR_APP web=1
```
