# How to operate the blog

REPL recipes for running the thing. Everything here assumes:

```clj
(require '[blog.grays.web.service :as service]
         '[blog.grays.web.db :as db]
         '[blog.grays.web.indieweb.webmention :as wm]
         '[blog.grays.web.indieweb.comments :as comments])

(def conf service/dev-conf)                    ; or prod-conf, on the server
(def conn (db/get-conn (:db-dir conf)))
```

## Rebuild the database after a schema change

Always safe, and the only migration mechanism there is: the db is derived, so a schema
change is a rebuild rather than a migration.

```clj
(db/rebuild! conf)
```

Do this after **any** addition to `db/schema` — Datalevin applies schema on a fresh
connection only, so an existing db keeps ignoring the new attribute until it is rebuilt.
Attributes that have needed it so far: `:syndication`, `:context/*`,
`:like-of`/`:repost-of`/`:bookmark-of`, `:tags`, `:mention/author-photo-cache`, `:rsvp`,
`:comment/*`.

## Re-send a post's Webmentions

Only meaningful once deployed — the source URL has to be publicly reachable for the
receiving site to verify it.

```clj
(wm/send-webmentions! conn conf "2026" "some-post")
```

In production this happens by itself: the watcher debounces about 10 seconds after the
first save and flushes once, sending to every external link and every response verb.
See `:send-webmentions?` in [reference-conf.md](reference-conf.md).

## Tell the WebSub hub the feed changed

```clj
(wm/ping-hub! conf)
```

Also automatic on publish, alongside the above.

## Hide a spam mention

Editing the file is usually enough — set `:status :blocked` in
`indieweb/mentions/<year>/<slug>.edn` and save. Use this instead when you also want
future re-sends of the same mention refused:

```clj
(wm/block-mention! conf "https://spam.example/x" "/posts/2020/some-post")
```

## Moderate a comment

Comments have no `block-mention!` analogue: a blocked comment has no re-send machinery
to refuse, so flipping `:status` in the file is the whole story. To find the file:

```clj
;; A post's comments, keyed by id; nil when there are none.
(comments/comments (:indieweb-dir conf) "/posts/2020/some-post")
```

Then edit `indieweb/comments/<year>/<slug>.edn`, set `:status` to `:blocked` (or
`:pending`), and save. Only `:approved` comments render; the change shows within seconds.

## Re-fetch a failed reply context

Context fetches are cached including their failures, so a `reply-to` URL that was down
once stays a bare link forever. Retry it by hand:

```clj
(wm/fetch-context! conf "https://example.com/a-page")
```

`::context-error` in the journal is the symptom.

## Cache avatars for mentions that predate the cache

A `db/rebuild!` cannot fetch faces it never had. This can:

```clj
(wm/cache-avatars! conf)
```

## Restart the server

```clj
(service/restart!)
```

---

For verifying that all of this actually works against production, see
[how-to-verify.md](how-to-verify.md).
