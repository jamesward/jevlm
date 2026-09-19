import webjars.generated.WebJars as Gen
import zio.http.template2.*

object UI:
  private val tailwindUrl = Gen.url(Gen.Artifact.`tailwindcss__browser`, "dist/index.global.js")

  val index: Dom =
    html(
      lang := "en",
      `class` := "bg-[#080b12]",
      head(
        meta(charset := "UTF-8"),
        meta(name := "viewport", Dom.attr("content", "width=device-width, initial-scale=1.0")),
        title("Jev Word Stream"),
        script(src := tailwindUrl),
        Dom.element("style")(
          Dom.attr("type", "text/tailwindcss"),
          Dom.raw(UIAssets.tailwindCss),
        ),
      ),
      body(
        `class` := "min-h-screen text-slate-100 antialiased selection:bg-cyan-300/25",
        main(
          `class` := "mx-auto max-w-6xl px-4 py-8 sm:px-6 sm:py-12",
          header(
            `class` := "mb-8 flex flex-col gap-5 border-b border-white/10 pb-7 sm:flex-row sm:items-end sm:justify-between",
            div(
              div(
                `class` := "mb-3 inline-flex items-center gap-2 rounded-full border border-cyan-300/20 bg-cyan-300/10 px-3 py-1 text-[11px] font-bold uppercase tracking-[.22em] text-cyan-200",
                span(`class` := "h-1.5 w-1.5 rounded-full bg-cyan-300"),
                "Jev recursive choice",
              ),
              h1(
                `class` := "text-4xl font-black tracking-[-.04em] text-white sm:text-6xl",
                "Words, chosen ", span(`class` := "text-cyan-300", "one by one."),
              ),
              p(
                `class` := "mt-3 max-w-2xl text-sm leading-6 text-slate-400 sm:text-base",
                "Jev sees your original message and the response so far, then ranks the next word—or decides to end the sentence or response.",
              ),
            ),
            div(
              id := "connection",
              `class` := "flex shrink-0 items-center gap-2 text-xs font-semibold text-slate-500",
              span(`class` := "status-dot h-2 w-2 rounded-full bg-slate-600"),
              span(`class` := "status-label", "Ready"),
            ),
          ),
          section(
            `class` := "grid gap-6 lg:grid-cols-[minmax(0,1fr)_19rem]",
            div(
              `class` := "space-y-6",
              form(
                id := "prompt-form",
                `class` := "panel rounded-3xl border border-white/10 p-5 sm:p-7",
                label(
                  `class` := "block text-sm font-bold text-slate-200",
                  "Your message",
                  textarea(
                    id := "prompt",
                    name := "prompt",
                    rows := "6",
                    maxlength := WordGeneration.Prompt.MaxLength.toString,
                    placeholder := "Ask a question, describe a problem, or give Jev something to respond to…",
                    `class` := "mt-3 w-full resize-y rounded-2xl border border-white/10 bg-black/25 px-4 py-3.5 text-base leading-7 text-white outline-none transition placeholder:text-slate-600 focus:border-cyan-300/40 focus:ring-4 focus:ring-cyan-300/10",
                  ),
                ),
                div(
                  `class` := "mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between",
                  p(`class` := "text-xs text-slate-500", "Ctrl/⌘ + Enter to generate · up to 2,000 characters"),
                  div(
                    `class` := "flex gap-2",
                    button(
                      id := "cancel",
                      `type` := "button",
                      `class` := "hidden rounded-xl border border-white/10 px-4 py-2.5 text-sm font-bold text-slate-300 transition hover:bg-white/5",
                      "Stop",
                    ),
                    button(
                      id := "generate",
                      `type` := "submit",
                      `class` := "rounded-xl bg-cyan-300 px-5 py-2.5 text-sm font-black text-slate-950 shadow-lg shadow-cyan-950/40 transition hover:bg-cyan-200 disabled:cursor-wait disabled:opacity-50",
                      "Generate response",
                    ),
                  ),
                ),
                div(
                  id := "error",
                  `class` := "mt-4 hidden rounded-xl border border-rose-400/25 bg-rose-400/10 px-4 py-3 text-sm text-rose-200",
                ),
              ),
              article(
                `class` := "panel min-h-64 rounded-3xl border border-white/10 p-5 sm:p-7",
                div(
                  `class` := "mb-5 flex items-center justify-between gap-3",
                  div(
                    p(`class` := "text-[10px] font-black uppercase tracking-[.2em] text-slate-500", "Streaming response"),
                    h2(id := "response-status", `class` := "mt-1 text-lg font-bold text-white", "Waiting for a message"),
                  ),
                  div(
                    `class` := "flex items-center gap-2",
                    div(
                      id := "elapsed",
                      Dom.attr("title", "Response time"),
                      `class` := "hidden rounded-lg border border-cyan-300/15 bg-cyan-300/5 px-2.5 py-1.5 font-mono text-xs font-bold text-cyan-200",
                      "0.0s",
                    ),
                    div(
                      id := "response-cost",
                      Dom.attr("title", s"Estimated Jev cost at $$${JevPricing.pricing.inputUsdPerMillion}/1M input tokens; output free"),
                      `class` := "hidden rounded-lg border border-emerald-300/15 bg-emerald-300/5 px-2.5 py-1.5 font-mono text-xs font-bold text-emerald-200",
                      "$0.000000",
                    ),
                  ),
                ),
                div(
                  id := "response",
                  Dom.attr("aria-live", "polite"),
                  `class` := "response min-h-32 text-lg leading-9 text-slate-200 sm:text-xl sm:leading-10",
                  span(`class` := "text-slate-600", "The generated words will appear here as Jev selects them."),
                ),
              ),
            ),
            aside(
              `class` := "space-y-5",
              div(
                `class` := "panel rounded-3xl border border-white/10 p-5",
                h2(`class` := "text-sm font-black text-white", "Live run"),
                dl(
                  `class` := "mt-4 grid grid-cols-2 gap-3",
                  metric("choices", "Choices", "0"),
                  metric("latency", "Jev latency", "—"),
                  metric("input-tokens", "Input tokens", "0"),
                  metric("output-tokens", "Output tokens", "0"),
                ),
              ),
              div(
                `class` := "panel rounded-3xl border border-white/10 p-5",
                h2(`class` := "text-sm font-black text-white", "Option ranking"),
                ol(
                  `class` := "mt-4 space-y-3 text-xs leading-5 text-slate-400",
                  ranking("01", "Bigram successors", "Words that commonly follow the last selected word."),
                  ranking("02", "Topic expansion", "Relevant explanatory terms inferred from recognized prompt topics."),
                  ranking("03", "Prompt + response words", "Normalized prompt terms and broad response vocabulary."),
                  ranking("04", "Common backoff", "A frequency-ranked English fallback vocabulary."),
                ),
                p(
                  `class` := "mt-4 border-t border-white/10 pt-4 text-[11px] leading-5 text-slate-500",
                  s"The host ranks ${RankedWordSource.EnglishDictionarySize} packaged English words, then supplies at most ${RankedWordSource.MaxWordOptions} candidates plus only valid ending actions each turn.",
                ),
              ),
            ),
          ),
        ),
        script.inlineJs(UIAssets.clientScript),
      ),
    )

  private def metric(idValue: String, label: String, initial: String): Dom =
    div(
      `class` := "rounded-xl border border-white/[.07] bg-black/20 p-3",
      dt(`class` := "text-[9px] font-bold uppercase tracking-wider text-slate-500", label),
      dd(id := idValue, `class` := "mt-1 font-mono text-sm font-bold text-slate-200", initial),
    )

  private def ranking(number: String, label: String, description: String): Dom =
    li(
      `class` := "flex gap-3",
      span(`class` := "font-mono text-cyan-300/70", number),
      div(strong(`class` := "block text-slate-300", label), span(description)),
    )
