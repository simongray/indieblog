````bash
cp system/blog.service /etc/systemd/system/blog.service
systemctl enable blog
systemctl start blog
````

[Validate my RSS feed](https://validator.w3.org/feed/check.cgi?url=https%3A%2F%2Fsimon.grays.blog%2Ffeed).

Architecture
------------
Disk is the source of truth and the database is merely a derived index. Posts are
markdown files in the posts dir; the IndieWeb data that *cannot* be regenerated
(Webmentions received, Webmentions delivered, reply contexts fetched) is EDN in
the indieweb dir. Nothing writes to the db but the sync layer watching those two
directories, so it can be deleted and rebuilt from the files at any time
(`db/rebuild!`), and a schema change stops being a migration. Moderating a
Webmention is editing a file.

The rest is a preference for less: one HTTP client, one name per concept, and
(paraphrasing Saint-Exupéry) perfection reached not when there is nothing more
to add, but when there is nothing left to take away.

Local development
-----------------
The blog posts are sourced from /Users/simongray/Code/simon.grays.blog and the db is located there too.

Images and assets
-----------------
Image files (and any other static assets) for posts live in the `assets`
subdirectory of the posts directory, e.g. `.../simon.grays.blog/posts/assets/`.
They are served directly from disk under the `/assets/` URL prefix — there is no
copying step and they are not stored in the database.

To embed an image in a post, reference it with an **absolute** `/assets/` path:

````markdown
![Alt text](/assets/my-photo.png)
````

A bare or relative reference such as `![…](my-photo.png)` will **not** work: on a
post page (`/posts/YYYY/slug`) the browser resolves it against the post URL as
`/posts/YYYY/my-photo.png`, not `/assets/`. Always start the path with
`/assets/`.

IndieWeb
--------
Implementation status ([full plan](doc/indieweb.md)):

- [x] microformats2: h-card, h-entry, h-feed, rel=me
- [x] Webmention sending (REPL: `webmention/send-webmentions!`)
- [x] Reply posts (`reply-to:` frontmatter → u-in-reply-to)
- [x] Like/repost/bookmark posts (`like-of:`/`repost-of:`/`bookmark-of:` → u-*-of)
- [x] Frontpage response strip (latest likes/reposts/bookmarks/replies; out of the article feed and RSS)
- [x] Notes (untitled posts: no headline, no p-name)
- [x] WebSub (Link header on /feed; REPL: `webmention/ping-hub!`)
- [x] Native webmention receiving + display (POST /webmention)
- [x] Automatic sending/pinging on publish (debounced watcher hook)
- [x] IndieAuth (delegated endpoints; deprecated, see doc/indieweb.md §8a)
- [x] Micropub (create notes/articles/replies/likes/reposts/bookmarks as markdown files)
- [x] POSSE/backfeed via Bridgy (u-syndication; connecting Bridgy is manual)
- [x] Federation via Bridgy Fed (the site *is* the fediverse/Bluesky account)
- [x] Full reply contexts (fetched title/author)

Received/delivered Webmentions and reply contexts are persisted as EDN under the
indieweb dir, e.g. `.../simon.grays.blog/indieweb/`; see `blog.grays.web.indieweb`.

Bridgy Fed does nothing until the bridge is enabled once, by hand, at
<https://fed.brid.gy/web-site>. After that every post is public on Mastodon and
Bluesky under this domain, and turning it back off deletes the bridged account
and its followers for good. See [doc/indieweb.md](doc/indieweb.md) §10a.

Validate with [indiewebify.me](https://indiewebify.me/) and [webmention.rocks](https://webmention.rocks/).
See [doc/testing.md](doc/testing.md) for the full prod test protocol.

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
