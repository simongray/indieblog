# Simon Gray's Indie Blog — Project Summary

Orientation for AI/LLM agents working in this codebase. Human-facing documentation
lives in [doc/](doc/) and is indexed from the [README](README.md); this file is the
map, not the manual, and points there rather than repeating it.

## Overview

A personal blog served by a Clojure web service, and a full participant in the
[IndieWeb](https://indieweb.org/). Posts are Markdown files with YAML frontmatter,
watched on disk and synced into a Datalevin database that exists only as a query index.
HTML is rendered server-side from Hiccup with Replicant, annotated with microformats2
so other sites can read it.

**Key features:**
- File-based content management with directory watching; no build step, no restart
- Markdown with YAML frontmatter; permalinks derived from title and date
- Datalevin (LMDB) storage, treated as disposable
- RSS feeds (main + per-tag) and a sitemap
- Server-side rendering with Replicant
- The full IndieWeb stack: microformats2, Webmention (send + receive), WebSub,
  IndieAuth, Micropub, POSSE/backfeed, Bridgy Fed federation, native comments
- REPL-driven development

## The architectural rule

**Disk is the source of truth; the database is a derived index.**

```
posts/*.md          ─┐
indieweb/**.edn     ─┴─→ watcher ─→ Datalevin db ─→ Replicant ─→ HTML + RSS
```

Data flows one way. Nothing writes to the db except the sync layer watching those
directories; when a Webmention arrives, an EDN file is written and the watcher syncs it
in. The db can be deleted and rebuilt at any moment (`db/rebuild!`), a schema change is
a rebuild rather than a migration, and moderation is editing a file.

**This is the single most important thing to know before changing anything.** See
[doc/architecture.md](doc/architecture.md).

## Namespaces

### Core
- **`service.clj`** — entry point. The conf maps, the route table, the Pedestal
  connector, `start!`/`stop!`/`restart!`. Start reading here.
- **`interceptors.clj`** — one handler per route.
- **`db.clj`** — the Datalevin schema, the file watchers, sync, and every query.
- **`content.clj`** — Markdown + frontmatter → post entity maps.
- **`component.cljc`** — all HTML we emit, as Hiccup. The microformats live here.
- **`shared.cljc`** — helpers used by both `component.cljc` and the server namespaces.
- **`feed.clj`** — RSS (`xml`) and sitemap (`sitemap-xml`) generation.
- **`http.clj`** — the one HTTP client we reach other sites with, plus `valid-url?`
  (the SSRF guard applied to every visitor-supplied URL).

### IndieWeb
- **`indieweb.clj`** — what we learn about the outside world, persisted as EDN files.
  Aggregates the namespaces below.
- **`indieweb/webmention.clj`** — sending, receiving, verifying; reply contexts;
  avatar caching; the WebSub ping; the debounced publish hook.
- **`indieweb/webmention/html.clj`** — reading other people's HTML (jsoup +
  microformats2). The only namespace with a test suite.
- **`indieweb/micropub.clj`** — the Micropub endpoint: create, update, delete, queries,
  media uploads.
- **`indieweb/signin.clj`** — Web sign-in for visitors, delegated to IndieLogin.com.
  HMAC-signed tokens; no sessions, no cookies.
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
| `datalevin/datalevin` | 0.10.18 | LMDB-backed Datalog store. **Needs `--add-opens` JVM flags on JDK 17+** (see the aliases in `deps.edn`) |
| `no.cjohansen/replicant` | 2026.06.2 | Hiccup → HTML string, server-side |
| `io.github.nextjournal/markdown` | 0.7.225 | Markdown → Hiccup |
| `dk.cst/hiccup-tools` | git | Hiccup manipulation |
| `com.github.rawleyfowler/sluj` | 1.0.2 | Slugifier (`sluj`), used for post and tag slugs. **Not** a YAML parser — frontmatter is parsed by `content/yaml->map` |
| `com.nextjournal/beholder` | 1.0.3 | Filesystem watching |
| `clj-rss/clj-rss` | 0.4.0 | RSS generation |
| `tick/tick` | 1.0.1 | Dates and times |
| `com.taoensso/telemere` + `-slf4j` | 1.2.1 | Logging; the SLF4J backend routes Datalevin/Jetty/Pedestal logs into Telemere |
| `org.jsoup/jsoup` | 1.15.2 | HTML parsing for Webmention endpoint discovery |
| `metosin/jsonista` | 1.0.0 | JSON in/out for Micropub |
| `nrepl/nrepl` | 1.7.0 | `:nrepl` alias only |

## Data model

Schema lives in `db/schema`. Two naming conventions coexist, deliberately:

- **Post attributes are unnamespaced**: `:file` (unique identity, an absolute path),
  `:slug`, `:year`, `:date`, `:title`, `:content`, `:hiccup`, `:tags`
  (cardinality-many), `:derived` (cardinality-many; which attributes were computed
  rather than authored), `:language`, `:location`, `:length`, `:ext`, plus the response
  verbs `:reply-to`, `:like-of`, `:repost-of`, `:bookmark-of` and `:rsvp`,
  `:syndication`.
- **Everything learned from outside is namespaced**: `:mention/*`, `:delivery/*`,
  `:context/*`, `:comment/*`. These need no identity attribute — they are never
  upserted, only replaced wholesale by `sync-indieweb!`, and their files already index
  them.

`:title` and `:content` are `:db/fulltext`. `:hiccup` has no `:db/valueType` on purpose,
so Datalevin stores the nested vector as one opaque value.

Frontmatter keys are documented in
[doc/reference-frontmatter.md](doc/reference-frontmatter.md); the EDN file shapes in
[doc/reference-files.md](doc/reference-files.md).

## Frequently needed functions

```clojure
(require '[blog.grays.web.db :as db]
         '[blog.grays.web.content :as content]
         '[blog.grays.web.component :as c])

;; Database
(db/get-conn db-dir)                  ; connection
(db/get-posts conn)                   ; all posts, pages and responses excluded
(db/get-post conn year slug)          ; one post
(db/get-posts-by-tag conn tag)
(db/get-page conn slug)               ; standalone page (/about, /now)
(db/get-mentions conn path)           ; webmentions for a permalink
(db/get-comments conn path)
(db/search-posts conn q)              ; full-text; no UI route yet
(db/put-post! conn post)
(db/retract-post! conn file)
(db/rebuild! conf)                    ; wipe and rebuild from files

;; Content
(content/read-posts dir)              ; every .md in dir → post entity maps
(content/md->post path)               ; one file
(content/expand-post post)            ; derive title/slug/year/…
(content/check! posts)                ; throws on duplicate slugs

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
`content/md->post`, `component/article` and the `html.clj` parsers can all be exercised
directly. Restarting the web service requires explicit permission from the user.

Every namespace ends in a rich `(comment …)` block of worked examples; read it before
writing new exploratory code.

### Tests

```bash
clojure -T:build ci :uber-file '"blog.jar"'   # clean, test, uberjar
```

There is one test namespace,
`test/blog/grays/web/indieweb/webmention/html_test.clj`, covering microformats2
parsing — the code most exposed to other people's malformed HTML.

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

- **Datalevin over SQL** — a disposable, rebuildable index rather than a system of
  record. Schema applies on a fresh connection, so schema changes are rebuilds.
- **Pedestal over Ring** — the interceptor model composes; conf and the db connection
  are attached to every request before routing.
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
