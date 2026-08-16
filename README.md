indieblog
=========
The Clojure web service behind [simon.grays.blog](https://simon.grays.blog): a personal
blog that is also a first-class citizen of the [IndieWeb](https://indieweb.org/).

Posts are Markdown files in a directory. Disk is the source of truth and the database is
merely a derived index, so the db can be deleted and rebuilt at any moment, a schema
change stops being a migration, and moderating a Webmention or a comment is editing a
file.

Documentation
-------------
### Start here
- [Getting started](doc/tutorial-getting-started.md) — from a clean checkout to your
  first post, in eight steps

### How do I…
- [Publish](doc/how-to-publish.md) — articles, notes, images, tags, replies, pages
- [Operate](doc/how-to-operate.md) — the REPL recipes: rebuild, re-send, moderate
- [Deploy](doc/how-to-deploy.md) — build the jar, install the service, enable the bridge
- [Verify](doc/how-to-verify.md) — the full prod test protocol

### Look it up
- [Frontmatter](doc/reference-frontmatter.md) — every key a post may carry
- [Configuration](doc/reference-conf.md) — every conf key, and what turns off without it
- [Endpoints](doc/reference-endpoints.md) — every route and what it answers
- [Files on disk](doc/reference-files.md) — the content tree and the EDN shapes

### Understand
- [Architecture](doc/architecture.md) — the one rule, and what falls out of it
- [IndieWeb](doc/indieweb.md) — what each protocol is for, and where it lives in the code
- [Native comments](doc/comments.md) — Web sign-in, and commenting without a website

IndieWeb status
---------------
- [x] microformats2: h-card, h-entry, h-feed, rel=me
- [x] Webmention sending (REPL: `webmention/send-webmentions!`)
- [x] Reply posts (`reply-to:` frontmatter → u-in-reply-to)
- [x] Like/repost/bookmark posts (`like-of:`/`repost-of:`/`bookmark-of:` → u-*-of)
- [x] Frontpage response strip (latest likes/reposts/bookmarks/replies; out of the article feed and RSS)
- [x] Notes (untitled posts: no headline, no p-name)
- [x] WebSub (Link header on /feed; REPL: `webmention/ping-hub!`)
- [x] Native webmention receiving + display (POST /webmention)
- [x] On-page mention form (paste the URL of a reply → POST /webmention; browsers get a redirect back, see doc/indieweb.md §5f)
- [x] Automatic sending/pinging on publish (debounced watcher hook)
- [x] IndieAuth, self-hosted (authorization + token + metadata endpoints, consent page, local token verification; see doc/indieweb.md §8)
- [x] Micropub (create/update/delete notes/articles/replies/likes/reposts/bookmarks as markdown files)
- [x] Micropub media endpoint (POST /media; image uploads to posts assets/, advertised in q=config)
- [x] POSSE/backfeed via Bridgy (u-syndication; connecting Bridgy is manual)
- [x] Federation via Bridgy Fed (the site *is* the fediverse/Bluesky account)
- [x] Full reply contexts (fetched title/author)
- [x] Tags/categories (`tags:` frontmatter → p-category; `/tags/<slug>` pages + per-tag RSS)
- [x] Standalone pages (/about, /now; see doc/indieweb.md §4b)
- [x] RSVP posts (`rsvp:` + `reply-to:` frontmatter → p-rsvp)
- [x] 410 Gone for deleted posts (read from the deliveries bookkeeping)
- [x] Micropub q=category (tag autocompletion for clients)
- [x] Fediverse handle under this domain (/.well-known/webfinger + host-meta → fed.brid.gy; see doc/indieweb.md §10a)
- [x] Native comments with Web sign-in (own /auth endpoint: the visitor's IndieAuth server, GitHub or Mastodon, with a provider chooser; see [doc/comments.md](doc/comments.md))

Validate a running deployment with [indiewebify.me](https://indiewebify.me/),
[webmention.rocks](https://webmention.rocks/) and the
[W3C feed validator](https://validator.w3.org/feed/check.cgi?url=https%3A%2F%2Fsimon.grays.blog%2Ffeed).

Clojure-mcp
-----------
I've added experimental support for [clojure-mcp](https://github.com/bhauman/clojure-mcp) through Claude, which is an MCP server for AI-assisted Clojure development. The project hasn't been developed with AI at all, but future changes may be AI-assisted.

See my personal [mcp-stuff repo](https://github.com/simongray/mcp-stuff) for documentation. The current versions of `LLM_CODE_STYLE.md` should also be located in that repo as well as my most recent personal `config.edn` for clojure-mcp projects.

### REPL
When integrating with clojure-mcp, an external nREPL on localhost:7888 should be used:

```shell
clojure -M:nrepl
```

You can then use e.g. IntelliJ IDEA to connect to this REPL and share it with the LLM.
