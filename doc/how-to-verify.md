# How to verify the IndieWeb features against production

A prod test protocol for the features from issue #2, in dependency order —
each section builds on the previous ones. Bullets under each step are what to
**expect**; where a step can fail, the section ends with where in the code to look.
Log ids referenced below (`::verified`, `::sent`, etc.) can be grepped in the
journal on the server.

Prerequisites: the site is deployed with the current code, `db-dir` points at
persistent storage, and the server was restarted after deploy (the Datalevin
schema additions — `:syndication`, `:context/*`,
`:like-of`/`:repost-of`/`:bookmark-of`, `:tags`, `:mention/author-photo-cache`,
`:rsvp`, `:comment/*` — only apply on a fresh connection). Mentions that predate the avatar cache also
need `(webmention/cache-avatars! conf)` once, to fetch the faces a `db/rebuild!`
alone cannot. See [how-to-deploy.md](how-to-deploy.md).

## 1. Discovery links

All IndieWeb endpoints are advertised via `<link>` elements in `<head>`.

```sh
curl -s https://simon.grays.blog/ | grep -oE '<link rel="[^"]+"[^>]*>'
```

- `rel="webmention"` → `https://simon.grays.blog/webmention`
- `rel="authorization_endpoint"` → `https://indieauth.com/auth`
- `rel="token_endpoint"` → `https://tokens.indieauth.com/token`
- `rel="micropub"` → `https://simon.grays.blog/micropub`
- `rel="alternate"` (RSS) and the `rel="me"` identity links

On failure: `component.cljc/head` and the conf keys in `service.clj`
(`:webmention-endpoint`, `:indieauth`, `:micropub-endpoint`).

## 2. Microformats2

Machine-readable markup is what every other feature parses.

1. Run https://indiewebify.me/ level 2 checks (h-card, h-entry) against the
   frontpage and a post URL.
2. Inspect the parsed tree with https://pin13.net/mf2/ — paste a post URL.

- Frontpage/footer: a representative `h-card` with `p-name`, `u-url`,
  `u-photo`, `p-locality`, `p-country-name`, `u-email`, and the `rel="me"` links
- Post page: `h-entry` with `p-name`, `dt-published`, `p-location`,
  hidden `p-author h-card`, `e-content`, `u-url`
- `rel="me"` verification passes (your GitHub/Mastodon profiles must link
  back to `simon.grays.blog` for section 5 to work later)

On failure: `component.cljc/article`, `footer`, `rel=me-links`.

### Frontpage response strip

Response posts (like/repost/bookmark/reply) are pulled out of the article feed
into a strip above `<main>`.

```sh
curl -s https://simon.grays.blog/ | grep -o 'class="responses"'
```

- The strip renders above the article feed, up to 3 cards, one per latest
  response; 3 columns on a wide screen, 1 below 600px
- Response posts do **not** appear in the article `.h-feed`, and their
  permalink still renders full h-entry markup
- `curl -s https://simon.grays.blog/feed` excludes response posts

On failure: `interceptors.clj/frontpage` (the split), `component.cljc/responses`
+ `page` (the strip and its slot), `db/response-post?` (the predicate),
`main.css` (`ul.responses` grid); RSS filtering in `interceptors.clj/rss-feed`.

### Tags / categories

A post's `tags:` frontmatter becomes `p-category` markup, tag pages, and
per-tag feeds.

```sh
curl -s https://simon.grays.blog/posts/2026/SOME-SLUG | grep -oE 'class="[^"]*p-category[^"]*"'
curl -sI https://simon.grays.blog/tags/SOME-TAG
curl -s  https://simon.grays.blog/tags/SOME-TAG/feed | grep -oE '<title>[^<]*</title>' | head -1
```

- A tagged post renders one `a.p-category` per tag; the mf2 value is the bare
  slug, the `#` being CSS (confirm via pin13.net/mf2)
- `/tags/<slug>` is an `.h-feed` listing that tag's posts; an unused tag 404s
- `/tags/<slug>/feed` is valid RSS with a tag-specific `<title>` and **no**
  WebSub `Link` header (that is main-feed only)

On failure: `content.clj/parse-tags` (frontmatter → slugs), `db.clj` (`:tags`
schema + `get-posts-by-tag`), `component.cljc/article` + `tagged` + `page`
(markup + h-feed), `interceptors.clj/tag-index`/`tag-feed`, `feed.clj/xml`
(per-tag channel), routes in `service.clj`. Remember a `db/rebuild!` after deploy
so existing posts pick up their tags.

### Standalone pages (/about, /now)

Pages live at `/<slug>` (see `db/page-slugs`), each backed by a frontmatter-less
markdown file of the same name in `posts-dir`. They render as plain markdown;
the h-card lives in the footer of every page.

```sh
curl -s https://simon.grays.blog/about | grep -oE '<(h1|section)[^>]*>'
curl -sio /dev/null -w '%{http_code}\n' https://simon.grays.blog/posts/2026/about
```

- `/about` and `/now` both render as plain pages: an `h1.page-title` and a
  `section.text`, no h-entry and no h-card of their own
- Pages are absent from the frontpage `.h-feed`, `/feed`, and the response
  strip; `/posts/<year>/about` 404s (`/about` is the only URL); both pages
  are listed in `/sitemap.xml`

On failure: `db.clj` (`page-slugs`, `page?`, `get-page` + the exclusions in
`get-posts`/`get-post`), `component.cljc/plain`,
`interceptors.clj/standalone-page`, and the generated routes in `service.clj`.

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

- All three return `400` with body `Invalid Webmention`

### The on-page mention form

1. Open a post page in a browser and scroll to "Responses".
2. Paste the URL of a page that links to the post and submit.

- The browser is answered with a 303 back to the post's `#comments`
  section; the mention itself appears on a later reload, once verified
- Submitting an invalid URL renders the styled "Invalid Webmention" page
  rather than the plain-text 400
- The `curl` requests above (no `text/html` in Accept) still get the plain
  `202`/`400` bodies

On failure: `component.cljc/mention-form` (markup) and the Accept negotiation
in `interceptors.clj/webmention`.

### End-to-end mention

1. Go to https://commentpara.de/, write a comment linking to one of your
   post URLs, and submit — it publishes a page and sends the webmention.
2. Watch the journal for `::verified` (status `verified`).
3. Reload the post page.

- A reply or plain mention appears under "Responses" as a full comment with
  author name and date; a like/repost/bookmark instead joins the **facepile**
  above them as an avatar (or a monogram of the author's initial when no
  photo was cached)
- The avatar is served from our own origin under `/avatars/…` rather than
  hotlinked (CSP is `default-src 'self'`); the cache file exists under
  `indieweb-dir/avatars/`
- Re-submitting an edited comment re-verifies (spec's update mechanism)
- `webmention.rocks` Receiver Tests pass (follow the instructions on
  https://webmention.rocks/ — they exercise verification edge cases)

On failure: `indieweb/webmention.clj/receive-mention!` (synchronous validation),
`verify-mention!` (fetch, microformat parsing, avatar caching via `cache-avatar!`),
`component.cljc/comments` + `mention`/`face` (rendering), `indieweb.clj/put-avatar!`
and the `/avatars` route in `service.clj` (serving). Moderation escape hatch:
`webmention/block-mention!` via REPL.

## 4. Webmention sending + WebSub (publish automation)

### Debounced notification

1. `ssh` in and `touch`/edit a post file in `posts-dir` (or save it several
   times within a few seconds).
2. Watch the journal.

- ~10s after the *first* save: one `::sent` per external link and one
  `::hub-pinged` — saves within that window join the same single flush
- A server *restart* triggers no notifications (only live watcher events
  should; the startup `sync-posts!` must stay silent)

### Endpoint discovery correctness

1. Create a temporary post whose body links to a handful of
   https://webmention.rocks/test/1 … /test/23 URLs (they cover header links,
   relative URLs, `<link>` vs `<a>`, etc.).
2. Publish and wait for the flush.

- Each linked test page displays your mention (the page itself reports
  success/failure)
- Discovery alone (no post, no send) can be checked from a REPL — all 23
  pass, incl. #15 (empty `href` = the page itself) and #23 (relative URL
  resolved against the *post-redirect* URL; its target is `/test/23/page`):
  ```clojure
  (map (comp discover-endpoint! #(str "https://webmention.rocks/test/" %))
       (range 1 23))
  (discover-endpoint! "https://webmention.rocks/test/23/page")
  ```

### Update/delete propagation

1. Remove one of those links from the post and save.
2. Delete the whole test post file.

- Both times, the previously notified targets are re-notified (`::sent`
  for the *old* targets — the union with the `indieweb/deliveries/` bookkeeping at work)
- The deleted post's permalink answers **410 Gone** (read from those same
  delivery records), not 404:
  ```sh
  curl -sio /dev/null -w '%{http_code}\n' https://simon.grays.blog/posts/2026/DELETED-SLUG
  ```

### Response-verb posts (like/repost/bookmark)

1. Create a post with a `like-of:` (or `repost-of:`/`bookmark-of:`) frontmatter
   key pointing at an external URL that advertises a Webmention endpoint.
2. Publish and wait for the flush.

```sh
curl -s https://simon.grays.blog/posts/2026/SOME-SLUG | grep -oE 'u-(like|repost|bookmark)-of'
```

- The post renders a labelled `u-like-of`/`u-repost-of`/`u-bookmark-of`
  link (confirm via pin13.net/mf2), the bare target URL as its text
- ~10s later, one `::sent` for that target: a response verb is a send
  target exactly like an external link (`db/response-verb-attrs`)

### RSVP posts

1. Create a post with both `reply-to:` (an event URL) and `rsvp: yes` in its
   frontmatter, and publish.

```sh
curl -s https://simon.grays.blog/posts/2026/SOME-SLUG | grep -oE '<data[^>]*p-rsvp[^>]*>[^<]*</data>'
```

- The post renders `RSVP: <data class="p-rsvp" value="yes">` under its
  reply context (confirm via pin13.net/mf2); an `rsvp:` without `reply-to:`
  renders nothing
- The rest is a reply: out of the article feed, into the strip, and the
  event URL gets the Webmention (`::sent`)

On failure: the `:rsvp` schema in `db.clj` (remember the rebuild), rendering in
`component.cljc/article`, mapping in `indieweb/micropub.clj/params->post`.

### WebSub

```sh
curl -sI https://simon.grays.blog/feed | grep -i link
```

- `Link` headers with `rel="hub"` (Superfeedr) and `rel="self"`
- Optionally run a publisher test at https://websub.rocks/

On failure: `indieweb/webmention.clj` — `schedule-notify!`/`notify!` (debounce),
`send-webmentions!` (union logic), `discover-endpoint!` (discovery),
`ping-hub!`; the watcher hook wiring is in `service.clj/start!` and
`db.clj/->watcher-callback` (prod-only via `:send-webmentions?`).

## 5. IndieAuth (delegated)

Tested implicitly by signing in somewhere that speaks IndieAuth:

1. Go to https://quill.p3k.io/ and sign in as `simon.grays.blog`.
2. Complete the indieauth.com flow (it authenticates you via your `rel="me"`
   providers from section 2).

- Sign-in succeeds and Quill obtains a token (this is the exact
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

- Both return JSON errors (`unauthorized`) with status 401

### Queries (needs a token — copy one from Quill, or mint via gimme-a-token)

```sh
TOKEN=...
curl -si -H "Authorization: Bearer $TOKEN" \
  'https://simon.grays.blog/micropub?q=config'
curl -si -H "Authorization: Bearer $TOKEN" \
  'https://simon.grays.blog/micropub?q=source&url=https://simon.grays.blog/posts/2026/SOME-SLUG'
curl -si -H "Authorization: Bearer $TOKEN" \
  'https://simon.grays.blog/micropub?q=category'
```

- `q=config` → 200 with `syndicate-to`, a `post-types` array
  (note/article/reply/rsvp/like/repost/bookmark), the supported `q` values,
  and a `media-endpoint`
- `q=source` → 200 with `type`/`properties` JSON for a real post; 400 for
  a bogus URL
- `q=category` → 200 with a sorted `categories` array of every tag slug
  in use

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

- Each returns `202` with a `Location` header; the post is live at that
  URL once the watcher syncs (seconds)
- The note derives its slug from the first words of content; the article
  from its title; `mp-slug` is honoured; a repeated `mp-slug` gets a
  `-2` suffix
- A like/repost/bookmark (a verb property, no `content`) is created: it
  returns `202`, its slug falls back to the target URL, and its file carries
  the `like-of:` (etc.) frontmatter with an empty body
- A post sent with `category` (JSON array, or form-encoded `category[]`) is
  written with a comma-separated `tags:` line and shows the tags as
  `p-category` once synced
- Inspect the written file on the server: frontmatter has `date`, `slug`
  (+ `title` and any response verb — `reply-to`/`like-of`/… — when given),
  body below
- ~10s later: the publish automation fires for the new post (`::sent`
  for any external links, `::hub-pinged`) — Micropub posts ride the
  watcher for free
- `content` missing / `h=event` → 400 `invalid_request`
- Optional deep-dive: the server test suite at https://micropub.rocks/
  (the create, query, update, delete and media tests all apply)

### Update / delete

Update and delete address a post by its `url` and both answer `204`. Use a real
post URL from the creation step above.

```sh
# update: replace the body, add a tag (JSON only)
curl -si -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"action":"update","url":"https://simon.grays.blog/posts/2026/json-note",
       "replace":{"content":["Edited body."]},"add":{"category":["indieweb"]}}' \
  https://simon.grays.blog/micropub

# delete (form-encoded is fine)
curl -si -H "Authorization: Bearer $TOKEN" \
  -d action=delete -d url=https://simon.grays.blog/posts/2026/json-note \
  https://simon.grays.blog/micropub
```

- Update → `204`; seconds later the body and tags change in place, the
  permalink is unchanged, and the publish automation re-fires
- The rewritten file keeps every frontmatter key it had: a `replace` of one
  property does not drop the others, and tags stay comma-joined
- `published`/`mp-slug` in an update are ignored; the date and permalink
  do not move
- Delete → `204`; the post 404s within seconds, and its previously notified
  targets get a re-send (`::sent`) so the federated copies withdraw
- Bogus `url` (no such post) → 400 `invalid_request`; a token missing the
  `update`/`delete` scope → 403 `insufficient_scope`
- Undelete is unsupported: `action=undelete` → 400 `invalid_request`

### Media endpoint

Upload a file to `/media`, then reference the returned URL in a post; `q=config`
above advertises the endpoint.

```sh
# upload an image; the URL comes back in the Location header
curl -si -H "Authorization: Bearer $TOKEN" -F "file=@some-photo.jpg" \
  https://simon.grays.blog/media
```

- `201` with a `Location` like
  `https://simon.grays.blog/assets/YYYY-MM-DD-some-photo.jpg`, immediately
  fetchable (served straight from `assets/`, no watcher wait)
- The stored filename is the date plus a slug of the upload's name; a second
  upload of the same name gets a `-2` suffix
- A non-image, or a missing `file` part → 400 `invalid_request`
- A token missing both `media` and `create` scope → 403 `insufficient_scope`

Journal ids: `::post-created`, `::post-updated`, `::post-deleted`,
`::media-uploaded`, `::token-verification-error`.
On failure: `indieweb/micropub.clj` — `authorize`/`verify-token!` (401/403 issues),
`params->post`/`apply-update` (mapping issues), `create!`/`derive-slug`/
`unique-slug` (create file issues), `parse-file`/`write-post!` (update/delete
file issues), `handle-media` (media upload issues), `handle-query` (queries);
routes/body-params in `service.clj`.

## 7. POSSE / backfeed (u-syndication + Bridgy)

1. Post something to Mastodon linking a blog post (manual POSSE), then add
   its URL to that post's frontmatter: `syndication: https://mastodon.social/@you/123`
   (multiple URLs separated by spaces).
2. Verify the markup:

```sh
curl -s https://simon.grays.blog/posts/2026/SOME-SLUG | grep u-syndication
```

- One hidden `<a class="u-syndication">` per URL, visible to parsers
  (confirm via pin13.net/mf2)

3. Connect https://brid.gy/ to your Mastodon account and let it poll (or use
   its "resend" button). Get someone to like/boost/reply to the toot.

- Likes/boosts/replies arrive as ordinary webmentions and render under
  "Responses" with the right verb (`liked`, `reposted`, …)

On failure: markup in `component.cljc/article`; verbs in
`indieweb/webmention.clj/mention-kind` + `component.cljc/kind->phrase`; everything else
is section 3's receiver.

## 8. Reply contexts

1. Add `reply-to: https://some.blog/interesting-post` to a post's
   frontmatter (pick a target with an h-entry or at least a `<title>`).
2. Load the post page **twice**.

- First load: bare URL ("In reply to https://…") — the fetch is async
- Second load (a few seconds later): "In reply to *Post Title* by
  Author" (author only when the target marks one up)
- The frontpage strip card shows the verb and the bare target URL —
  contexts enrich the permalink page only
- A dead `reply-to` URL stays a bare link forever (failures are cached;
  `::context-error` in the journal) — retry manually via
  `(webmention/fetch-context! conn url)` in a REPL

On failure: `indieweb/webmention.clj/fetch-context!`/`entry-title` (extraction),
`reply-context` (cache/scheduling), `db.clj/get-context`, rendering in
`component.cljc/article`; handler wiring in `interceptors.clj/frontpage` and
`single-post`.

## 9. Well-known URIs

Not IndieWeb per se, but served by the same routes; all live under
`/.well-known/` (RFC 8615).

```sh
curl -sI 'https://simon.grays.blog/.well-known/webfinger?resource=acct:simon.grays.blog@simon.grays.blog' | grep -i location
curl -s https://simon.grays.blog/.well-known/security.txt
curl -si https://simon.grays.blog/.well-known/api-catalog | grep -i content-type
```

- `webfinger` and `host-meta` 302 to `https://fed.brid.gy/.well-known/…`
  with the query string intact and no double slash after the host;
  searching `@simon.grays.blog@simon.grays.blog` on a Mastodon instance
  then finds the site ([indieweb.md](indieweb.md) §10a)
- `security.txt` has `Contact`, an `Expires` about half a year out,
  `Preferred-Languages` and `Canonical`
- `api-catalog` is `application/linkset+json` listing the webmention,
  micropub, media and feed endpoints

On failure: `interceptors.clj/bridgy-fed-redirect`/`security-txt`/`api-catalog`
and their routes in `service.clj`.

## 10. Native comments (Web sign-in)

IndieWeb content, stored generically (see [comments.md](comments.md)): the visitor is
authenticated as their website via IndieLogin.com. The full flow only works
deployed, since the `redirect_uri` sent along points at the production domain.

### Failure paths (no IndieLogin needed)

```sh
# private me → 400
curl -si -d me=http://localhost/x -d path=/posts/2020/SOME-SLUG \
  https://simon.grays.blog/sign-in
# unknown post → 400
curl -si -d me=https://example.com/ -d path=/posts/2020/nope \
  https://simon.grays.blog/sign-in
# garbage state → 400
curl -si 'https://simon.grays.blog/sign-in/callback?code=x&state=garbage'
# garbage token → 400
curl -si -d token=garbage -d content=hi https://simon.grays.blog/comments
```

- All four answer 400 with the styled "Sign-in failed" page

### End-to-end comment

1. Open a post page, scroll to "Responses", and enter your website URL in the
   sign-in form ("No reply to link? …").
2. Complete authentication at IndieLogin.com (it uses your site's `rel="me"`
   providers, or your own IndieAuth endpoint if you advertise one).
3. Write something on the "Write a comment" page and post it.

- The sign-in form 303s to indielogin.com carrying
  `me`/`client_id`/`redirect_uri`/`state`
- The callback lands on "Write a comment", naming the post and your domain
- Posting 303s back to the post's `#comments`; the comment appears on a
  reload once the watcher syncs (seconds; `::indieweb-synced` in the journal)
- The comment shows your h-card name (or your bare domain when your
  homepage marks none up) linking to your site, a dated link to its own
  `#comment-<id>` anchor, and the text as a blockquote; it is interleaved
  with any webmentions by date
- When your homepage h-card has a `u-photo`, the avatar is cached and
  served from `/avatars/…` like a mention author's
- The entry exists in `indieweb-dir/comments/<year>/<slug>.edn` with
  `:status :approved` and `:auth :indieauth`; flipping the status to
  `:blocked` in an editor hides it within seconds
- Waiting over 30 minutes between signing in and posting gets the styled
  400 (token expiry); a server restart between the two does the same
  (the signing secret is per-boot)

On failure: routes in `service.clj`;
`interceptors.clj/sign-in`/`sign-in-callback`/`post-comment` (the handlers);
`indieweb/signin.clj` (tokens and the IndieLogin exchange; `::exchange-error` in the
journal); `indieweb/comments.clj` (storage); `db.clj/sync-indieweb!` + `get-comments`;
`component.cljc/sign-in-form`/`comment-form`/`native-comment` (markup).
Remember: `:comment/*` needs the post-deploy `db/rebuild!` from the
prerequisites, and removing the `:sign-in` conf key turns the feature off.

## Debugging cheat sheet

| Symptom | First place to look |
|---|---|
| 4xx/5xx from an endpoint | journal around the request; `interceptors.clj` |
| Mention accepted but never appears | `::verified` w/ status `failed` → `verify-mention!` (does the source really link to the target?) |
| No `::sent` after editing a post | `:send-webmentions?` conf (prod only) → `service.clj/start!` hook wiring |
| Notifications on server restart | watcher vs `sync-posts!` separation in `db.clj/start!` |
| Micropub 401 with a valid token | `verify-token!` response parsing; 403 → `me` host mismatch or missing `create` scope |
| Micropub 202 but no post | watcher didn't pick the file up → file location/extension; journal from `db.clj` |
| Comment posted but never appears | `::indieweb-synced` in the journal; the `indieweb-dir` conf and the file's `:status` |
| Sign-in loops back to "Sign-in failed" | state/token expiry or a restart in between (`indieweb/signin.clj`); `::exchange-error` means IndieLogin rejected the code |
| Reply context never enriches | `::context-error`; cached failure entity (see section 8) |
