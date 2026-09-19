# Project context for Claude

See [README.md](README.md) for what this project is and [ROADMAP.md](ROADMAP.md) for
its milestones, decisions and current status.

## Tooling

The TypeSafe AI plugin (`typesafe-ai`, System One models including **Jev**) is
installed and enabled in this environment. It gives typed judgments and
probabilities as a programming primitive — routing, ranking, extraction,
verification — usable wherever a feature would otherwise need an ad-hoc LLM
prompt-and-parse step. It was installed to help Claude move faster on this
project, not (yet) as a dependency of the satellite-pass-predictor app itself.

Invoke the `typesafe:typesafe-ai` skill when a task's shape fits — it reads
TypeSafe's live docs for current API/SDK details rather than relying on
memorized specifics.
