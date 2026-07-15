# IndieWeb feature testing guide

A prod test protocol for the features from issue #2, in dependency order —
each section builds on the previous ones. Every test lists the expected
outcome and, on failure, where in the code to look. Log ids referenced below
(`::verified`, `::sent`, etc.) can be grepped in the journal on the server.

Prerequisites: the site is deployed with the current code, `db-dir` points at
persistent storage, and the server was restarted after deploy (the Datalevin
schema additions — `:syndication`, `:context/*`,
`:like-of`/`:repost-of`/`:bookmark-of` — only apply on a fresh connection).

## 1. Discovery links

All IndieWeb endpoints are advertised via `<link>` elements in `<head>`.

```sh
curl -s https://simon.grays.blog/ | grep -oE '<link rel="[^"]+"[^>]*>'
```

- [x] `rel="webmention"` → `https://simon.grays.blog/webmention`
- [x] `rel="authorization_endpoint"` → `https://indieauth.com/auth`
- [x] `rel="token_endpoint"` → `https://tokens.indieauth.com/token`
- [x] `rel="micropub"` → `https://simon.grays.blog/micropub`
- [x] `rel="alternate"` (RSS) and the `rel="me"` identity links

On failure: `component.cljc/head` and the conf keys in `service.clj`
(`:webmention-endpoint`, `:indieauth`, `:micropub-endpoint`).

## 2. Microformats2

Machine-readable markup is what every other feature parses.

1. Run https://indiewebify.me/ level 2 checks (h-card, h-entry) against the
   frontpage and a post URL.
2. Inspect the parsed tree with https://pin13.net/mf2/ — paste a post URL.

- [x] Frontpage/footer: a representative `h-card` with `p-name`, `u-url`,
      `u-email`, and the `rel="me"` links
- [x] Post page: `h-entry` with `p-name`, `dt-published`, `p-location`,
      hidden `p-author h-card`, `e-content`, `u-url`
- [ ] `rel="me"` verification passes (your GitHub/Mastodon profiles must link
      back to `simon.grays.blog` for section 5 to work later)

On failure: `component.cljc/article`, `footer`, `rel=me-links`.

### Frontpage response strip

Response posts (like/repost/bookmark/reply) are pulled out of the article feed
into a strip above `<main>`.

```sh
curl -s https://simon.grays.blog/ | grep -o '<ul class="responses"'
```

- [ ] The strip renders above the article feed, up to 3 cards, one per latest
      response; 3 columns on a wide screen, 1 below 600px
- [ ] Response posts do **not** appear in the article `.h-feed`, and their
      permalink still renders full h-entry markup
- [ ] `curl -s https://simon.grays.blog/feed` excludes response posts

On failure: `interceptors.clj/frontpage` (the split), `component.cljc/responses`
+ `page` (the strip and its slot), `db/response-post?` (the predicate),
`main.css` (`ul.responses` grid); RSS filtering in `interceptors.clj/rss-feed`.

## 3. Webmention receiving

### Endpoint validation (synchronous checks)

```sh
# malformed → 400
curl -si -d source=x -d target=y https://simon.grays.blog/webmention
# source == target → 400
curl -si -d source=https://simon.grays.blog/ -d target=https://simon.grays.blog/ \
  https://simon.grays.blog/webmention
# target is not a post → 400
curl -si -d source=https://example.com/ -d target=https://simon.grays.blog/nope \
  https://simon.grays.blog/webmention
```

- [x] All three return `400` with body `Invalid Webmention`

### End-to-end mention

1. Go to https://commentpara.de/, write a comment linking to one of your
   post URLs, and submit — it publishes a page and sends the webmention.
2. Watch the journal for `::verified` (status `verified`).
3. Reload the post page.

- [ ] The comment appears under "Mentions" with author name and date
- [ ] Re-submitting an edited comment re-verifies (spec's update mechanism)
- [ ] `webmention.rocks` Receiver Tests pass (follow the instructions on
      https://webmention.rocks/ — they exercise verification edge cases)

On failure: `webmention.clj/receive-mention!` (synchronous validation),
`verify-mention!` (fetch + microformat parsing), `component.cljc/mention`
(rendering). Moderation escape hatch: `webmention/block-mention!` via REPL.

## 4. Webmention sending + WebSub (publish automation)

### Debounced notification

1. `ssh` in and `touch`/edit a post file in `posts-dir` (or save it several
   times within a few seconds).
2. Watch the journal.

- [ ] ~10s after the last save: one `::sent` per external link and one
      `::hub-pinged` — a burst of saves collapses into a single flush
- [ ] A server *restart* triggers no notifications (only live watcher events
      should; the startup `sync-posts!` must stay silent)

### Endpoint discovery correctness

1. Create a temporary post whose body links to a handful of
   https://webmention.rocks/test/1 … /test/23 URLs (they cover header links,
   relative URLs, `<link>` vs `<a>`, etc.).
2. Publish and wait for the flush.

- [ ] Each linked test page displays your mention (the page itself reports
      success/failure)
- [x] Discovery alone (no post, no send) can be checked from a REPL — all 23
      pass, incl. #15 (empty `href` = the page itself) and #23 (relative URL
      resolved against the *post-redirect* URL; its target is `/test/23/page`):
      ```clojure
      (map (comp discover-endpoint #(str "https://webmention.rocks/test/" %))
           (range 1 23))
      ```

### Update/delete propagation

1. Remove one of those links from the post and save.
2. Delete the whole test post file.

- [ ] Both times, the previously notified targets are re-notified (`::sent`
      for the *old* targets — the union with the `indieweb/deliveries/` bookkeeping at work)

### Response-verb posts (like/repost/bookmark)

1. Create a post with a `like-of:` (or `repost-of:`/`bookmark-of:`) frontmatter
   key pointing at an external URL that advertises a Webmention endpoint.
2. Publish and wait for the flush.

```sh
curl -s https://simon.grays.blog/posts/2026/SOME-SLUG | grep -oE 'u-(like|repost|bookmark)-of'
```

- [ ] The post renders a labelled `u-like-of`/`u-repost-of`/`u-bookmark-of`
      link (confirm via pin13.net/mf2), the bare target URL as its text
- [ ] ~10s later, one `::sent` for that target: a response verb is a send
      target exactly like an external link (`db/response-verb-attrs`)

### WebSub

```sh
curl -sI https://simon.grays.blog/feed | grep -i link
```

- [x] `Link` headers with `rel="hub"` (Superfeedr) and `rel="self"`
- [ ] Optionally run a publisher test at https://websub.rocks/

On failure: `webmention.clj` — `schedule-notify!`/`notify!` (debounce),
`send-webmentions!` (union logic), `discover-endpoint` (discovery),
`ping-hub!`; the watcher hook wiring is in `service.clj/start!` and
`db.clj/->watcher-callback` (prod-only via `:send-webmentions?`).

## 5. IndieAuth (delegated)

Tested implicitly by signing in somewhere that speaks IndieAuth:

1. Go to https://quill.p3k.io/ and sign in as `simon.grays.blog`.
2. Complete the indieauth.com flow (it authenticates you via your `rel="me"`
   providers from section 2).

- [ ] Sign-in succeeds and Quill obtains a token (this is the exact
      prerequisite Micropub needs)

On failure: the two `<link>`s from section 1, or the `rel="me"` backlinks
from section 2 (indieauth.com can't verify you without them).

## 6. Micropub

### Auth failures (no token needed)

```sh
# 401 missing token
curl -si -d h=entry -d content=x https://simon.grays.blog/micropub
# 401 invalid token
curl -si -H "Authorization: Bearer garbage" -d h=entry -d content=x \
  https://simon.grays.blog/micropub
```

- [x] Both return JSON errors (`unauthorized`) with status 401

### Queries (needs a token — copy one from Quill, or mint via gimme-a-token)

```sh
TOKEN=...
curl -si -H "Authorization: Bearer $TOKEN" \
  'https://simon.grays.blog/micropub?q=config'
curl -si -H "Authorization: Bearer $TOKEN" \
  'https://simon.grays.blog/micropub?q=source&url=https://simon.grays.blog/posts/2026/SOME-SLUG'
```

- [ ] `q=config` → 200 with `syndicate-to` and a `post-types` array
      (note/article/reply/like/repost/bookmark)
- [ ] `q=source` → 200 with `type`/`properties` JSON for a real post; 400 for
      a bogus URL

### Creation

1. Publish a short **note** (no title) from Quill.
2. Publish an **article** (with title) from Quill.
3. JSON syntax via curl:

```sh
curl -si -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"type":["h-entry"],"properties":{"content":["A JSON note."],"mp-slug":["json-note"]}}' \
  https://simon.grays.blog/micropub

# a like: a verb property, no content
curl -si -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"type":["h-entry"],"properties":{"like-of":["https://example.com/a-post"]}}' \
  https://simon.grays.blog/micropub
```

- [ ] Each returns `202` with a `Location` header; the post is live at that
      URL once the watcher syncs (seconds)
- [ ] The note derives its slug from the first words of content; the article
      from its title; `mp-slug` is honoured; a repeated `mp-slug` gets a
      `-2` suffix
- [ ] A like/repost/bookmark (a verb property, no `content`) is created: it
      returns `202`, its slug falls back to the target URL, and its file carries
      the `like-of:` (etc.) frontmatter with an empty body
- [ ] Inspect the written file on the server: frontmatter has `date`, `slug`
      (+ `title` and any response verb — `reply-to`/`like-of`/… — when given),
      body below
- [ ] ~10s later: the publish automation fires for the new post (`::sent`
      for any external links, `::hub-pinged`) — Micropub posts ride the
      watcher for free
- [ ] `content` missing / `h=event` → 400 `invalid_request`
- [ ] Optional deep-dive: the server test suite at https://micropub.rocks/
      (only the create + query tests apply; update/delete/media are
      unimplemented by design)

Journal ids: `::post-created`, `::token-verification-error`.
On failure: `micropub.clj` — `authorize`/`verify-token` (401/403 issues),
`params->post` (mapping issues), `create!`/`derive-slug`/`unique-slug` (file
issues), `handle-query` (queries); routes/body-params in `service.clj`.

## 7. POSSE / backfeed (u-syndication + Bridgy)

1. Post something to Mastodon linking a blog post (manual POSSE), then add
   its URL to that post's frontmatter: `syndication: https://mastodon.social/@you/123`
   (multiple URLs separated by spaces).
2. Verify the markup:

```sh
curl -s https://simon.grays.blog/posts/2026/SOME-SLUG | grep u-syndication
```

- [ ] One hidden `<a class="u-syndication">` per URL, visible to parsers
      (confirm via pin13.net/mf2)

3. Connect https://brid.gy/ to your Mastodon account and let it poll (or use
   its "resend" button). Get someone to like/boost/reply to the toot.

- [ ] Likes/boosts/replies arrive as ordinary webmentions and render under
      "Mentions" with the right verb (`liked`, `reposted`, …)

On failure: markup in `component.cljc/article`; verbs in
`webmention.clj/mention-kind` + `component.cljc/kind->verb`; everything else
is section 3's receiver.

## 8. Reply contexts

1. Add `reply-to: https://some.blog/interesting-post` to a post's
   frontmatter (pick a target with an h-entry or at least a `<title>`).
2. Load the post page **twice**.

- [ ] First load: bare URL ("In reply to https://…") — the fetch is async
- [ ] Second load (a few seconds later): "In reply to *Post Title* by
      Author" (author only when the target marks one up)
- [ ] The frontpage snippet shows the same enrichment
- [ ] A dead `reply-to` URL stays a bare link forever (failures are cached;
      `::context-error` in the journal) — retry manually via
      `(webmention/fetch-context! conn url)` in a REPL

On failure: `webmention.clj/fetch-context!`/`entry-title` (extraction),
`reply-context` (cache/scheduling), `db.clj/get-context`, rendering in
`component.cljc/article`; handler wiring in `interceptors.clj/frontpage` and
`single-post`.

## Debugging cheat sheet

| Symptom | First place to look |
|---|---|
| 4xx/5xx from an endpoint | journal around the request; `interceptors.clj` |
| Mention accepted but never appears | `::verified` w/ status `failed` → `verify-mention!` (does the source really link to the target?) |
| No `::sent` after editing a post | `:send-webmentions?` conf (prod only) → `service.clj/start!` hook wiring |
| Notifications on server restart | watcher vs `sync-posts!` separation in `db.clj/start!` |
| Micropub 401 with a valid token | `verify-token` response parsing; 403 → `me` host mismatch or missing `create` scope |
| Micropub 202 but no post | watcher didn't pick the file up → file location/extension; journal from `db.clj` |
| Reply context never enriches | `::context-error`; cached failure entity (see section 8) |
