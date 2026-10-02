## Agent Skills (SkillsJars)

This project declares Agent Skills as build-only dependencies in the sbt plugin's `Skills` configuration. Before working, extract them with:

```bash
./sbt extractSkillsJars
```

The generated skills are written under `.kiro/skills/`. Read relevant `SKILL.md` files there and follow their guidance. The generated directory is ignored because it can be reproduced from the pinned dependency in `build.sbt`.

## Build, Test & Dev Workflow
- Use the MCP server named `sbt-mcp-jev-word-stream` for ALL sbt interactions. Run
  commands/tasks through its `sbt-task` tool; use `list-tasks` to discover
  tasks/settings or get per-task help. Do not invoke `sbt`, a shell, or a
  separate sbt client when the MCP tool is available. If the MCP server is
  unavailable, state that clearly and use a direct CLI fallback only when
  necessary. Separate multiple sbt commands with `;`.
- Use `sbt-mcp-jev-word-stream` for Scala/classpath symbol work: `glob-search` to
  find/list symbols, `inspect` for members/signatures, and `symbol-location`
  for source locations. Prefer these over text search, dependency-jar
  inspection, or guessed APIs whenever the question is about Scala symbols.
- JavaDoc/ScalaDoc lookups are available through the same server via its
  proxied javadocs.dev tools.
- sbt 2.x uses a persistent daemon. After changing environment variables or JVM `-D` properties such as `-Dlocal`, run `./sbt shutdown` before the next build so the new values take effect.

## Agent tooling

- Follow the `zen-of-projects` Skill (extract with `./sbt extractSkillsJars` into the gitignored `.kiro/skills/`); this file records only project-specific facts and exceptions.
- MCP server `sbt-mcp-jev-word-stream` (sbt-mcp) listens on `http://127.0.0.1:5015/`. Kiro uses the HTTP entry in `.kiro/settings/mcp.json`; start sbt first. Claude Code uses `.mcp.json`, which runs `.claude/sbt-mcp-stdio.sh` (approved in `.claude/settings.json`). That stdio bridge starts a foreground sbt in cloud sessions (`CLAUDE_CODE_REMOTE=true`), and locally only connects to an sbt you already started. Its tools are deferred: load them with ToolSearch (search `sbt-mcp-jev-word-stream`). Diagnostics go to `/tmp/sbt-mcp-stdio.log` and `/tmp/sbt-mcp-server.log`.
- Maintenance routine: `.factory/MAINTENANCE.md` (weekly), following the `zen-of-projects` Skill.
