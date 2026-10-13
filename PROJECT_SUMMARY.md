# Simon Gray's Indie Blog — Project Summary

Orientation for AI/LLM agents working in this codebase. Human-facing documentation
lives in [doc/](doc/) and is indexed from the [README](README.md); this file is the
map, not the manual, and points there rather than repeating it.

## Overview

A personal blog served by a Clojure web service, and a full participant in the
[IndieWeb](https://indieweb.org/). Posts are Markdown files with YAML frontmatter,
watched on disk and synced into an in-memory database that exists only as a query index.
HTML is rendered server-side from Hiccup with Replicant, annotated with microformats2
so other sites can read it.

**Key features:**
- File-based content management with directory watching; no build step, no restart
- Markdown with YAML frontmatter; permalinks derived from title and date
- An in-memory db, rebuilt from the files on every start, with full-text search
- RSS feeds (main + per-tag) and a sitemap
- Server-side rendering with Replicant
- The full IndieWeb stack: microformats2, Webmention (send + receive), WebSub,
  IndieAuth, Micropub, POSSE/backfeed, Bridgy Fed federation, native comments
- REPL-driven development

## The architectural rule

**Disk is the source of truth; the database is a derived index.**

```
posts/*.md          ─┐
indieweb/**.edn     ─┴─→ watcher ─→ in-memory db ─→ Replicant ─→ HTML + RSS
```

Data flows one way. Nothing writes to the db except the sync layer watching those
directories; when a Webmention arrives, an EDN file is written and the watcher syncs it
in. The db is rebuilt in memory on every start, so there is no schema to migrate, and
moderation is editing a file.

**This is the single most important thing to know before changing anything.** See
[doc/architecture.md](doc/architecture.md).

## Namespaces

### Core
- **`service.clj`** — entry point. The conf maps, the route table, the Pedestal
  connector, `start!`/`stop!`/`restart!`. Start reading here.
- **`interceptors.clj`** — one handler per route.
- **`db.clj`** — the in-memory db and its search index, the file watchers, sync, and
  every query.
- **`content.clj`** — Markdown + frontmatter → post entity maps.
- **`component.cljc`** — all HTML we emit, as Hiccup. The microformats live here.
- **`shared.cljc`** — helpers used by both `component.cljc` and the server namespaces.
- **`feed.clj`** — RSS (`xml`), sitemap (`sitemap-xml`) and OpenSearch
  (`opensearch-xml`) generation.
  `absolutize` fully qualifies every url on the way into a feed.
- **`http.clj`** — the one HTTP client we reach other sites with, on wary-fetch:
  every request goes to public addresses only, redirects included (the SSRF guard).
  `opts` makes the libraries' requests go through it too.

### IndieWeb
- **`indieweb.clj`** — what we learn about the outside world, persisted as EDN files.
  Aggregates the namespaces below.
- **`indieweb/webmention.clj`** — sending, receiving, verifying, on webmention-clj;
  reply contexts; avatar caching; the WebSub ping; the debounced publish hook.
- **`indieweb/micropub.clj`** — the Micropub endpoint and media endpoint, on
  micropub-clj: the mapping between Micropub entries and post files, slugs, and
  the storing of uploads.
- **`indieweb/auth.clj`** — our own IndieAuth server, on indieauth-clj, which
  Micropub clients and our own comment sign-in both ask; and the ways a person
  signs in to it (their own IndieAuth server, or GitHub or Mastodon rel=me via
  relmeauth-clj, with a provider chooser). Tokens are stored hashed in `tokens.edn`.
- **`indieweb/signin.clj`** — the sign-in flow's client half, on indieauth-clj.
  Signed tokens, no sessions, one secret, and a cookie for each callback: the
  signed sign-in itself, so only the browser that started it can finish it.
- **`indieweb/comments.clj`** — native comments, on the same file conventions.
- **`indieweb/store.clj`** — the EDN-file conventions all of the above follow: atomic
  writes, entry keying, path derivation.

`.cljc` marks code written to be platform-agnostic so components could run in a
browser. There is **no ClojureScript build in the repo today** — all rendering is
server-side.

## Dependencies

| Dependency | Version | Role |
|---|---|---|
| `org.clojure/clojure` | 1.12.5 | |
| `io.pedestal/pedestal.service` + `.jetty` | 0.8.2-beta-10 | Interceptor-based web framework and HTTP server |
| `dk.simongray/drop-in-search` | 0.1.1 | Full-text search over the posts |
| `dk.simongray/wary-fetch` | 0.6.1 | Every request to another site: public addresses only, redirects included, with time and size limits |
| `dk.simongray/whatwg-mime` | 0.1.0 | Reading the Content-Type of the images we cache, as a browser reads it |
| `dk.simongray/websub-clj` | 0.5.0 | WebSub: the hub ping on publish and the feed's `Link` header |
| `no.cjohansen/replicant` | 2026.06.2 | Hiccup → HTML string, server-side |
| `io.github.nextjournal/markdown` | 0.7.225 | Markdown → Hiccup |
| `dk.cst/hiccup-tools` | git | Hiccup manipulation |
| `com.github.rawleyfowler/sluj` | 1.0.2 | Slugifier (`sluj`), used for post and tag slugs. **Not** a YAML parser — frontmatter is parsed by `content/yaml->map` |
| `com.nextjournal/beholder` | 1.0.3 | Filesystem watching |
| `clj-rss/clj-rss` | 0.4.0 | RSS generation |
| `tick/tick` | 1.0.1 | Dates and times |
| `com.taoensso/telemere` + `-slf4j` | 1.2.1 | Logging; the SLF4J backend routes Jetty/Pedestal logs into Telemere |
| `dk.simongray/microformats-clj` | 0.3.0 | The microformats2 and rels of other people's pages |
| `dk.simongray/indieauth-clj` | 0.2.1 | IndieAuth: our server, the client half of the comment sign-in, and signed texts |
| `dk.simongray/relmeauth-clj` | 0.2.2 | Signing in through a GitHub or Mastodon account that a homepage links with rel=me |
| `dk.simongray/html-pieces` | 0.6.0 | Parsing other people's pages for microformats-clj |
| `dk.simongray/webmention-clj` | 0.2.1 | Webmention: sending, the endpoint, verifying, and what a mention shows (kind, author, content) |
| `dk.simongray/micropub-clj` | git | Micropub: the endpoint and the media endpoint, which take posts and files from clients |
| `metosin/jsonista` | 1.0.0 | JSON of the API catalog |
| `nrepl/nrepl` | 1.7.0 | `:nrepl` alias only |

## Data model

The db is one atom, `db/db`, holding plain maps. Two naming conventions coexist,
deliberately:

- **Post attributes are unnamespaced**: `:file` (the key, an absolute path),
  `:slug`, `:year`, `:date`, `:title`, `:content`, `:hiccup`, `:tags` (a set),
  `:derived` (the set of attributes that were computed rather than authored),
  `:language`, `:location`, `:length`, `:ext`, plus the response verbs `:reply-to`,
  `:like-of`, `:repost-of`, `:bookmark-of` and `:rsvp`, `:syndication`.
- **Everything learned from outside is namespaced**: `:mention/*`, `:delivery/*`,
  `:context/*`, `:comment/*`. These have no key of their own — they are only ever
  replaced wholesale by `sync-indieweb!`, and their files already index them.

The title, tags and visible text of each post are indexed for full-text search
(`db/post->document`).

Frontmatter keys are documented in
[doc/reference-frontmatter.md](doc/reference-frontmatter.md); the EDN file shapes in
[doc/reference-files.md](doc/reference-files.md).

## Frequently needed functions

```clojure
(require '[blog.grays.web.db :as db]
         '[blog.grays.web.content :as content]
         '[blog.grays.web.component :as c])

;; Database
db/db                                 ; the db, an atom
(db/posts db/db)                      ; all posts, pages excluded
(db/find-post db/db year slug)        ; one post
(db/tagged-posts db/db tag)
(db/find-page db/db slug)             ; standalone page (/about, /now)
(db/verified-mentions db/db path)     ; webmentions for a permalink
(db/approved-comments db/db path)
(db/search-posts db/db q)             ; full-text, best first (/search)
(swap! db/db db/with-post post)       ; with-post/without-post take the value
(swap! db/db db/without-post file)
(db/start! conf)                      ; sync from the files, then watch them

;; Content
(content/read-posts dir)              ; every .md in dir → post entity maps
(content/md->post path)               ; one file
(content/expand-post post)            ; derive title/slug/year/…
(content/check-posts posts)           ; throws on duplicate slugs

;; Rendering
(c/page title main conf & opts)       ; complete HTML page
(c/article post colour conf & opts)   ; one h-entry
(c/articles posts conf)               ; a feed of snippets
```

Operational recipes (sending Webmentions, blocking a mention, re-fetching a context)
are in [doc/how-to-operate.md](doc/how-to-operate.md).

## Development workflow

```bash
clojure -M:nrepl        # external nREPL on 7888; connect an editor and share with the LLM
```

```clojure
(blog.grays.web.service/restart!)   ; (re)start the dev server on :4567 with dev-conf
```

`restart!` uses `dev-conf`; `start!` merges its argument onto `prod-conf` and blocks
unless the result is `:development`.

**Test new code in the REPL with a few relevant function calls rather than by
restarting the service** — most of this codebase is pure functions over maps, and
`content/md->post` and `component/article` can be exercised directly. Restarting the web service requires explicit permission from the user.

Most namespaces end in a rich `(comment …)` block of worked examples; read it before
writing new exploratory code.

### Tests

```bash
clojure -T:build ci :uber-file '"blog.jar"'   # clean, uberjar; no test step
```

There is no test alias: run the tests from the REPL with `clojure.test`. Four test
namespaces, all guarding places where a mistake fails **silently and somewhere
else**, which is exactly what the REPL is bad at catching:

- `indieweb/webmention_test.clj` — what we keep of a verified Webmention, and
  that one whose source is gone or no longer links us fails.
- `content_test.clj` — the `assets/…` → `/assets/…` rewrite, which keeps a
  markdown editor and a browser agreeing about where an image is, and the plain
  text of a post, which search and descriptions are made from.
- `feed_test.clj` — that no relative url survives into a feed item, since the
  same HTML gets re-served from a reader's own domain.
- `indieweb/auth_test.clj` — the sign-in checks (the ways in a homepage offers,
  the sign-in cookies, the iss check, the comment token), a Mastodon sign-in
  against a fake server, and our IndieAuth server
  end to end, where a mistake lets a sign-in succeed for the wrong person.

## Configuration and deployment

Configuration is three maps in `service.clj`: `conf` (shared), `prod-conf` and
`dev-conf`. There is no config file. **An absent key turns its feature off**, which is
how features are toggled. Every key is documented in
[doc/reference-conf.md](doc/reference-conf.md).

| | Development | Production |
|---|---|---|
| Content root | `~/Code/simon.grays.blog/` | `/opt/blog/simon.grays.blog/` |
| CSP | permissive, CORS open | `default-src 'self'` |
| Webmentions sent | no | yes (`:send-webmentions?`) |

Deployment is an uberjar run by a systemd unit (`system/blog.service`); see
[doc/how-to-deploy.md](doc/how-to-deploy.md).

## Notable design decisions

- **Plain maps over a database** — a disposable index rather than a system of record,
  rebuilt in memory on every start; drop-in-search does the full-text search.
- **Pedestal over Ring** — the interceptor model composes; conf and the db are
  attached to every request before routing.
- **Replicant over Rum/Reagent** — pure functions from data to Hiccup, no React.
- **File-based over a CMS** — posts are edited in an editor and versioned in Git. The
  Micropub endpoint writes the same files, so it adds a door rather than a second
  system.
- **No admin UI** — moderation is a text editor, by design.
- **A preference for less** — one HTTP client, one name per concept.

What was deliberately *not* built is listed in
[doc/indieweb.md](doc/indieweb.md) §13 and [doc/comments.md](doc/comments.md) §7, so
absences read as decisions rather than oversights. Check those lists before proposing
a feature.

## Conventions

Coding style is specified in [LLM_CODE_STYLE.md](LLM_CODE_STYLE.md), and project rules
in [CLAUDE.md](CLAUDE.md). In short: Clojure is a functional language, edits should be
surgical and diffs lean, and anything unexpected gets reported rather than
investigated on a tangent.
