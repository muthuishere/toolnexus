---
name: marketing
description: Draft and track the launch posts (LinkedIn, X, Hacker News, Reddit) for a toolnexus release in ~/.marketing/toolnexus/<version>/, built only from shipped facts. Runs as the last step of a release. Trigger: marketing for this release, write the launch posts, LinkedIn/X/HN post for vX, what did we post about toolnexus.
---

# Marketing a toolnexus release

Runs as the final step of the `release` skill, after every registry is verified from outside.
It produces **drafts only**. The owner posts.

Everything lives outside the repo in `~/.marketing/toolnexus/`:

| path | holds |
|---|---|
| `HISTORY.md` | Every post ever made (date, channel, hook, file) plus the voice that worked. **Read first, every time.** |
| `history/` | Full text of past posts. |
| `<version>/facts.md` | THE source. Every claim with its source (CHANGELOG section, standing page, issue/PR), plus honest NOT-claims and "coming next (not shipped)". |
| `<version>/linkedin.md`, `x.md`, `hackernews.md`, `reddit.md` | One draft per channel, header `status: DRAFT / STAGED / POSTED <url>`. |
| `<version>/assets/` | Video, poster, screenshots (the docs-site explainer video lives in `site/public/`). |
| `<version>/README.md` | Status log: `date · channel · drafted/staged/posted · URL`. |

## Steps

1. **Read what was already said.** Read `~/.marketing/toolnexus/HISTORY.md` and the previous
   version's folder (`ls ~/.marketing/toolnexus/`, take the latest version before this one: its
   `facts.md`, drafts and README log). Keep the voice, **do not reuse a hook already in HISTORY.md**,
   and build on it ("last release X, now Y").
2. **Create `~/.marketing/toolnexus/<version>/` and build `facts.md` first**, from:
   - the `## <version> — <date>` section of `CHANGELOG.md` (the release body is the same text),
   - the current standing/benchmark page (`site/src/content/docs/standing-*.md`, latest month),
   - merged PRs and issues it cites.
   Nothing unreleased goes in as shipped; put it under "coming next". Copy each entry's "Not done"
   gaps into the NOT-claims.
3. **Write the drafts from `facts.md` only.** Honest numbers, losses included ("fastest in some
   languages, level in some, behind in a couple"). Compare product to product (features, workflow,
   latency, memory), never company stats. No vendor bashing. No vendor names in claims about the
   library's API (vendors appear only as benchmark competitors or examples).
4. **Channel shapes:**
   - **LinkedIn:** a hook line, a short what-it-is, 3 arrows of what's new, an honest benchmark line,
     links (repo, docs, standing page), ≤5 hashtags, the video attached.
   - **X:** a 3–5 tweet thread, each tweet ≤280 chars.
   - **HN:** a `Show HN:` title ≤80 chars, the repo URL, and an author first comment that says what it
     is NOT (no graph orchestration, no hosted platform, no RAG/vector store — that's citenexus).
   - **Reddit:** one post per relevant sub, after checking the sub's self-promotion rules.
5. **Never post without the owner's explicit go in this conversation.** Posting is outward-facing.
   toolnexus is a personal (github.com/muthuishere) project: post from the owner's personal accounts only, never the Deemwar page or account. When the owner says go, post through the `chrome-agent` skill (it stages without `--confirm`;
   confirm only after the owner approves the staged post). Never hand-roll a browser.
6. **Record it.** After each post: set the draft's header to `POSTED <url>`, append
   `date · channel · posted · URL` to `<version>/README.md`, and **add a row to
   `~/.marketing/toolnexus/HISTORY.md`** (date, channel, hook, file) so the next release does not
   reuse the hook.
7. Report back: the folder path, which drafts exist, and what is waiting on the owner.
