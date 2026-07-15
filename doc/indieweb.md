# IndieWeb: what it is, and how this blog implements it

A guide to the IndieWeb features on simon.grays.blog: what each protocol is
*for*, and where it lives in the code. For a step-by-step protocol to **verify**
these features against production, see [testing.md](testing.md) instead.

---

## 1. The idea

The IndieWeb is a set of small, independent protocols for owning your own
content while still being able to talk to other sites. The premise: you publish
on your own domain, and the social features people expect — replies, likes,
subscriptions, cross-posting — are added afterwards as protocols on top of plain
HTML, rather than being the property of a platform.

Four ideas do most of the work:

| Idea | Meaning |
|---|---|
| **Your domain is your identity** | `simon.grays.blog` is the account. `rel=me` links prove other profiles belong to it. |
| **Your posts are your data** | Content lives in your files, on your server, at your permalinks. |
| **Markup is the API** | There is no JSON API to publish against. Sites read each other's *HTML*, annotated with microformats2 class names. |
| **POSSE** | *Publish (on your) Own Site, Syndicate Elsewhere*: post here first, copy to silos, pull responses back. |

Nothing here requires anyone else's permission or an account anywhere. That is
the entire point, and it explains most of the design decisions below — including
why so much of this codebase is concerned with reading *other people's*
malformed HTML.

## 2. Map of the code

```
service.clj              routes, and the conf that turns features on
component.cljc           all HTML we emit — this is where the microformats live
interceptors.clj         request handlers behind the routes
webmention.clj           sending, receiving, verifying; WebSub ping
webmention/html.clj      reading other people's HTML (jsoup + microformats2)
micropub.clj             the Micropub endpoint (post by API)
indieweb.clj             what we learn, persisted as EDN files
http.clj                 the one HTTP client we reach other sites with
db.clj                   the derived index, and the file watchers that fill it
```

### The one architectural rule

**Disk is the source of truth; the database is a derived index.**

```
posts/*.md          ─┐
                     ├─→ watcher ─→ Datalevin db ─→ rendered HTML
indieweb/**.edn     ─┘
```

Data flows in one direction only. Nothing writes to the db except the sync layer
watching those two directories. When a Webmention arrives, `verify-mention!`
writes an **EDN file**; the watcher notices and syncs it in. Reads go to the db,
writes go to the files, never the reverse.

Three things fall out of this, and they are worth stating because they are the
payoff:

- The db can be deleted at any moment and rebuilt from the files (`db/rebuild!`).
  A schema change is not a migration; it is a rebuild.
- **Moderation is editing a file.** There is no admin UI because there does not
  need to be one.
- Everything is inspectable and diffable with the tools you already have.

---

## 3. Discovery

**What it is.** Other sites need to find your endpoints without being told. The
convention is `<link rel="...">` in `<head>`, which any client can fetch and
parse.

**How it works here.** `component/head` emits a link per configured endpoint, and
each is driven by a key in `service/conf` — omit the key and the feature is
simply not advertised:

| `<link rel>` | conf key |
|---|---|
| `webmention` | `:webmention-endpoint` |
| `authorization_endpoint`, `token_endpoint` | `:indieauth` |
| `micropub` | `:micropub-endpoint` |
| `me` (one per profile) | `:identity` |
| `me` (the bridged copy of this site) | `:bridgy-fed` |
| `alternate` (ActivityPub; posts only) | `:bridgy-fed` |
| `alternate` (RSS) | always |

Swapping the native Webmention endpoint for a hosted one (webmention.io) is a
one-line conf change, precisely because nothing else in the code knows the URL.

---

## 4. Microformats2

**What it is.** mf2 is a vocabulary of HTML class names that makes a page
machine-readable *without* a parallel API. An `h-entry` is a post; `p-name` is
its title; `u-url` its permalink; `dt-published` its date; `h-card` a person. A
parser reads the same HTML a browser does and recovers structured data from it.

This is load-bearing: **every other feature below depends on it.** A Webmention
tells another site *that* you mentioned them; the microformats on your page tell
them *who you are, what you said, and whether it was a reply or a like.*

**How it works here.** All of it is in `component.cljc`, woven into the markup we
were emitting anyway:

- `article` → `article.h-entry`, with `h2/h1.headline.p-name` (the headline is
  demoted to `h2` in frontpage snippets so each page keeps one `h1`; the
  microformat is unaffected), `time.dt-published`, `.p-location`, and the
  permalink as `a.u-url` wrapped around the headline text by `link-headline`.
- A **note** (a post with no title) has **no `p-name`**, because a name is what
  an article has and a note has not. `split-headline-content` returns a nil
  headline, and `article` states the permalink as a *hidden* `a.u-url` in the
  metadata aside instead, since every h-entry owes one. (A note's slug comes
  from its filename, there being no title to derive it from.)
- Post body → `section.text.e-content`; frontpage snippets → `.p-summary`
  instead, since a snippet is not the full content and must not claim to be.
- Authorship → a **hidden** `a.p-author.h-card`. The byline is implied for a
  human reader, but a parser needs it stated.
- `footer` → the representative `address.h-card`, with `a.p-name.u-url.u-uid`
  pointing at the site's canonical URL. This is what makes the domain an
  identity. It also carries a **hidden** `img.u-photo` (the `:photo` conf key),
  which the page has no room to show but Bridgy Fed refuses to bridge without;
  see section 10a.
- Frontpage `<main>` → `.h-feed`, holding the article snippets only. The latest
  likes/reposts/bookmarks/replies render in a separate strip *above* `<main>`
  (`component/responses`), kept outside the feed so a parser does not read each
  response twice; its canonical h-entry lives on its own permalink. See section
  6a.
- `rel=me` links (`rel=me-links`) → cross-link GitHub, Mastodon, LinkedIn, email.
  Combined with a link back from those profiles, they establish that the same
  person controls all of them — which is what IndieAuth then authenticates
  against.

### 4a. Categories

**What it is.** `p-category` is the mf2 property for a post's tags: a parser
collects each one as a category of the h-entry, exactly as it collects the
`p-name` or `dt-published`. It is also the property a Micropub client sends as
`category`, which `params->post` maps onto the same `tags:` frontmatter a
hand-written post uses (section 9).

**How it works here.** A post names its tags in a comma-separated `tags:`
frontmatter line. `content/parse-tags` turns that into a set of slugs at ingest,
stored as the cardinality-many `:tags` (see its schema note). Tags are authored
already slug-shaped, so the slug is both the stored value and the `/tags/<slug>`
URL, with no separate display form. The slug is settled at ingest because the
slugifier is clj-only and `component.cljc` is cross-platform.

`article` renders each tag as an `a.p-category` in the metadata aside, linking to
its tag page. The `#` is added in CSS, so the mf2 value stays the bare slug.

**Tag pages.** `/tags/<slug>` (`interceptors/tag-index`) lists the tag's posts as
an `.h-feed`, reusing the frontpage feed markup; an unused tag matches nothing
and 404s. `/tags/<slug>/feed` (`interceptors/tag-feed`) is the RSS of that tag's
articles, with its own channel title and self URL and no WebSub hub, which is
pinged only for the main feed. A `/tags` index root is a TODO.

Adding `:tags` was a schema change, so it reaches existing posts only through a
`db/rebuild!`.

---

## 5. Webmention

The core protocol, and the largest part of the implementation. A Webmention is a
notification: *"a page at `source` links to your page at `target`."* That is the
whole spec. Everything else — replies, likes, reposts, comment threads — is that
one notification plus microformats on the source page.

The flow, in both directions:

```
        we publish                              someone replies to us
             │                                            │
   send-webmentions!                              POST /webmention
             │                                            │
   discover-endpoint (their site)                 receive-mention!  ─→ :pending
             │                                            │
   POST source+target ────────────────────→       verify-mention!  (fetch source)
             │                                            │
   record delivery                             :verified / :failed → EDN file
             │                                            │
   indieweb/deliveries/…                          indieweb/mentions/…
```

### 5a. Sending

**What it is.** When you publish a post linking to someone, you tell them. You
discover their endpoint from their page, then POST `source` and `target` to it.
Crucially, you must **re-send on update and deletion too** — that is the spec's
only mechanism for propagating edits and removals. If you delete a post, the
other site only finds out because you send the same Webmention again and it
re-fetches the now-missing page.

**How it works here.**

- `external-links` pulls every absolute off-site `<a href>` out of the post's
  hiccup.
- `discover-endpoint` fetches the target and looks for `rel=webmention`: the
  `Link` **header** first, then the first `<link>`/`<a>` in the body. Two
  edge cases the spec demands and the code special-cases: relative hrefs resolve
  against the **final, post-redirect URL**, and an *empty* href means the page
  itself (Java's `URI.resolve` deviates from RFC 3986 here).
- `send-webmentions!` computes its target set as the union of: the post's
  external links, the URLs it responds to (`reply-to`, `like-of`, `repost-of`,
  and `bookmark-of`; see `db/response-verb-attrs`), **and every target
  previously delivered to**. That last one is what makes updates and deletions
  propagate — it is why we keep delivery records at all.
- Each successful delivery is recorded in `indieweb/deliveries/YYYY/slug.edn`.

**Automation.** In production (`:send-webmentions? true`), `db/watch!` calls
`schedule-notify!` on each synced post. It debounces by `notify-delay` (10s),
because a single file save emits several filesystem events and we do not want to
spam anyone's endpoint. After the delay, `notify!` sends the Webmentions and
pings the WebSub hub.

The `on-sync` hook is passed **only** to the posts watcher, never the `indieweb/`
one. This is deliberate and slightly load-bearing: if an incoming Webmention
(which writes a file) went through the publish hook, receiving a mention would
trigger *sending* mentions.

### 5b. Receiving

**What it is.** You accept a POST of `source` + `target`, then *independently
verify* it by fetching the source and confirming it really does link to you.
Verification is mandatory — the POST itself is unauthenticated and anyone can
claim anything.

**How it works here.** `POST /webmention` → `interceptors/webmention` →
`receive-mention!`.

Validated **synchronously**, per spec (a 400 otherwise):

- both URLs are absolute, public `http(s)` — `valid-url?`, which rejects
  loopback and private-range hosts via `private-host?`. That is an SSRF guard:
  without it, a stranger could make our server fetch our own internal network.
- `source ≠ target`
- the target resolves to an **existing post** on this site (`target-path`)

If it passes, the mention is written as `:pending` and verification is handed to
a **2-thread pool** (`fetcher`). The small fixed size doubles as backpressure
against a verification flood. We answer **202 Accepted**, not 201 — we have
accepted the notification, not yet believed it.

`verify-mention!` then fetches the source and settles it:

- **`:verified`** — the source really does link to us. We parse its microformats
  for author (name/url/photo), publication date, and the *kind* of mention.
- **`:failed`** — unreachable, or the link is gone. Note that a previously
  verified mention whose link has since **disappeared** correctly flips to
  `:failed`, and thus vanishes from the page. That is the spec's deletion
  mechanism, and it works because re-received mentions are always re-verified.

Only `:verified` mentions are ever displayed.

### 5c. Kinds, and display

A source doesn't just link to you — it links *in a particular way*, marked up
with an mf2 class:

| mf2 class on the source | our kind | rendered as |
|---|---|---|
| `u-in-reply-to` | `:reply` | "replied" |
| `u-like-of` | `:like` | "liked this" |
| `u-repost-of` | `:repost` | "reposted this" |
| `u-bookmark-of` | `:bookmark` | "bookmarked this" |
| *(a plain link)* | `:mention` | "mentioned this" |

One vocabulary, three places: `html/kind->class` (reading), `:mention/kind`
(storing), `component/kind->phrase` (rendering).

`component/comments` renders them below the post as `li.p-comment.h-cite`
entries — which means our comments are *themselves* microformatted, and can be
read by anyone parsing our page. A reply also carries an **excerpt** of its
content, so the section reads as a conversation rather than as a list of links.
Only a reply does: a like has none, and the `e-content` of a plain mention is
somebody's entire post.

**We link to `:mention/url`, not `:mention/source`.** `source` is the URL that
was POSTed to us; `url` is the permalink that page claims for itself via
`u-url`. For a hand-written blog they are the same. For a bridge they are not:
Bridgy and Bridgy Fed POST a *proxy page on their own domain*. Verify against
the source, display the url. Otherwise every reply from the fediverse reads as
having come from brid.gy: a documented Bridgy footgun, and one here.

The author's `u-photo` is parsed and stored but not yet shown: see the TODO on
`component/mention`.

### 5d. Reading other people's HTML

`webmention/html.clj` deserves its own note, because it is the only place in the
codebase where a DOM exists. Everywhere else HTML is something we *emit*
(hiccup, never read back). Here we must *read* markup written by software we do
not control, fetched over the network, and routinely malformed — hence **jsoup**,
a tolerant HTML5 tree builder, which also resolves relative hrefs against the
document base.

We implement a deliberate *subset* of mf2 — `p-name`, `u-url`, `p-author`,
`dt-published`, `e-content` (or `p-summary`), and the three verbs — and not a
real parser: no value-class-pattern, no implied properties, no nested `h-cite`,
and `e-content` is read as text rather than as markup.

Two rules of the spec are nonetheless respected, because a naïve CSS selector
gets them wrong and **both were bugs here once**:

1. A property nested inside *another* microformat root belongs to that root. A
   plain `.h-entry .p-name` will happily return the *author's* name as the post's
   title. Handled in `property`.
2. The value-attribute forms — `<data value>`, `<abbr title>`, `<img alt>` —
   carry their value in an attribute and have **no text at all**. Handled in
   `property-value`.

jsoup types never escape this namespace: `parse` yields a document, and
`endpoint-href`/`entry` reduce it to plain Clojure data. Which is why the whole
thing is testable from a string, with no HTTP fetch in sight — see
`test/…/html_test.clj`.

### 5e. Moderation

`:blocked` is a fourth status. A blocked mention is hidden *and* refuses future
re-sends (`receive-mention!` checks the file before accepting).

```clj
(webmention/block-mention! conf "https://spam.example/x" "/posts/2020/some-post")
```

…or, equivalently and more usefully, open
`indieweb/mentions/2020/some-post.edn` in your editor, change `:status
:verified` to `:status :blocked`, and save. The watcher does the rest. Delete the
entry to unblock.

---

## 6. Reply contexts

**What it is.** When a post *is* a reply, the reader deserves to see what it
replies to — otherwise the post is half a conversation. The convention is to
fetch the target page and show its title and author.

**How it works here.** Add `reply-to:` to a post's frontmatter. This does two
things at once: it renders `a.u-in-reply-to` (so the world knows this is a
reply), and it becomes a Webmention target (so the person being replied to
finds out).

For display, `reply-context` looks the URL up in the db. On a **miss** it returns
nil and schedules an async fetch — so the first render shows the bare URL and
subsequent ones show "In reply to *Their Title* by *Their Name*".

Two details worth knowing:

- **Failures are cached too**, as an entry with no title/author. A dead link is
  therefore attempted once, not on every render. Call `fetch-context!` directly
  to force a retry.
- A fetch takes *seconds*, and only reaches the db once the watcher has synced
  the file it writes. Without a guard, every render in that window would schedule
  another fetch. The `attempted` set ensures **at most one fetch per URL per
  session**.

Contexts are cached in `indieweb/contexts.edn`. Persisting them is partly
architectural consistency and partly an archive: the title you fetched in 2026
may be unfetchable in 2030.

### 6a. Likes, reposts, bookmarks

**What it is.** The same move as a reply, three more verbs. A post can *like*,
*repost*, or *bookmark* another page instead of replying to it.

**How it works here.** Add a `like-of:`, `repost-of:`, or `bookmark-of:` key to
the frontmatter. Each renders a labelled `a.u-like-of` / `a.u-repost-of` /
`a.u-bookmark-of` link (the class and label come from `component/response-verbs`)
and, exactly like `reply-to`, becomes a Webmention target. `db/response-verb-attrs`
is the single list naming all four verbs, read by both `component/article` (to
render) and `send-webmentions!` (to notify), so the two never drift.

Unlike a reply, these fetch no context: a like or a bookmark is not half a
conversation, so the visible link text is just the target URL. The matching
*inbound* kinds are section 5c's business; `:bookmark` was the one verb that side
was missing, and it is there now.

**Where they show.** A response is not an article, so it is kept out of the
article feed: `db/response-post?` splits the frontpage posts, the articles fill
the `.h-feed`, and the latest few responses render in the strip above it
(`component/responses`, section 4). The same split filters them out of RSS. Each
still has its own permalink page with full h-entry markup, which is where a parser
(and Bridgy Fed) reads it, so a like or repost still *federates* — via the
per-post Webmention to the bridge rather than via feed discovery. Older responses
drop off the strip as newer ones arrive; an archive page for them is a TODO.

**Posting them.** Either hand-write the frontmatter, or post from a Micropub
client: `micropub/params->post` maps the `like-of`/`repost-of`/`bookmark-of`
properties (and `in-reply-to` → `reply-to`), `create!` no longer requires content
for a post that carries a verb, and `q=config` advertises the post types so a
client offers the buttons.

---

## 7. WebSub

**What it is.** RSS is *pull* — readers poll your feed on a schedule, so news is
always a little stale. WebSub adds *push*: you tell a hub the feed changed, the
hub tells every subscriber immediately.

**How it works here.** Two halves:

- **Advertise.** The `/feed` response carries a `Link` header with `rel="hub"`
  (the `:websub-hub`, currently Superfeedr) and `rel="self"` (the canonical feed
  URL). Both are required for a subscriber to discover the hub.
- **Notify.** `ping-hub!` POSTs `hub.mode=publish` + `hub.url` to the hub.
  Superfeedr answers **204**. It is called by `notify!`, i.e. debounced alongside
  Webmention sending on publish.

No hub configured → `ping-hub!` returns nil and does nothing. That is the whole
feature.

---

## 8. IndieAuth

**What it is.** OAuth where **your domain is the client ID and the user ID**. You
sign in to third-party apps *as your website*. It is what makes Micropub usable:
an app needs to prove it is allowed to post as you.

**How it works here — by not implementing it.** We advertise *someone else's*
endpoints:

```clj
:indieauth {:authorization-endpoint "https://indieauth.com/auth"
            :token-endpoint         "https://tokens.indieauth.com/token"}
```

Authentication is delegated to indieauth.com, which authenticates you by
following the `rel=me` links in section 4 (you prove you own the domain by
proving you own a profile that links back to it). We never see a password, never
store a token, and never implement an OAuth server.

Our only job is verification, in `micropub/verify-token`: hand the bearer token
to the token endpoint and see whether it comes back valid. Writing an OAuth
implementation would be strictly more code and strictly less secure.

### 8a. …and why that will not last

This is the one part of the implementation with an expiry date on it, and the
argument above no longer quite holds. Two things have happened:

- **There is nothing left to delegate to.** indieauth.com carries a deprecation
  notice. Its intended successor, MyIndieAuth.com, has never been started.
  IndieLogin.com replaces the *other* half of the old service (signing users in
  to *apps*) and is not an authorization server for a domain.
- **The discovery mechanism we advertise is itself deprecated.**
  `rel=authorization_endpoint` and `rel=token_endpoint` have been superseded by
  `rel=indieauth-metadata`, pointing at a metadata document served *by the
  authorization server* (its `issuer` must be a prefix of the metadata URL, so
  we cannot serve one on indieauth.com's behalf without lying about who we are).
  indieauth.com also predates both PKCE and RFC 9207's `iss` parameter, so newer
  clients will increasingly refuse it.

The exit is to **self-host**: at one user, an authorization + token endpoint is
a consent page, a signed authorization code, a JWT, and an introspection route,
and `verify-token` collapses into verifying a signature locally, with no HTTP
call to a stranger on every Micropub request. See
[the IndieAuth spec on discovery](https://indieauth.spec.indieweb.org/#discovery)
and [indieauth.com](https://indieauth.com/) itself, which says so in a banner.

---

## 9. Micropub

**What it is.** A standard publishing API. Any Micropub client — Quill,
Indigenous, a shortcut on your phone — can post to any Micropub server. Write the
server once, and every client works.

**How it works here.** `POST /micropub` → `micropub/handle-post`, which
dispatches on the request's `action`: create (the default), update, or delete
(the last two in section 9a). The create path:

1. **Authorize** (`authorize`). Bearer token from the `Authorization` header or
   an `access_token` param → verified against the delegated token endpoint. Then
   two checks: the token's `me` must be **this domain** (a valid token for
   someone *else's* site is not a valid token for ours), and its scope must
   include `create` or `post`.
2. **Normalize** (`params->post`). Micropub has two syntaxes — form-encoded and
   JSON — and in JSON every value is an array. `params->post` flattens both into
   one post map. Content may arrive as `{"html": …}`; markdown tolerates raw
   HTML, so it passes straight through. The `in-reply-to`/`like-of`/`repost-of`/
   `bookmark-of` properties become the response verbs of section 6a, and
   `category` becomes the comma-separated `tags:` line (section 4a).
3. **Create** (`create!`). Derive a slug (from `mp-slug`, else the title, else
   the first few words of the content, else the target of a like/repost/bookmark
   — untitled notes and contentless responses are both first-class cases), make
   it unique within the year, and **write a markdown file to the posts dir**. A
   post that carries a response verb needs no content of its own.

And then it stops. It does not touch the db.

This is the architecture paying for itself: a Micropub post becomes a file, the
watcher syncs it exactly like a hand-written one, and it picks up Webmention
sending and the WebSub ping for free. There is no second code path to keep in
step with the first. It is also why we answer **202 Accepted** rather than 201 —
the post is not live until the watcher has synced it — with the eventual
permalink in the `Location` header.

Queries (`handle-query`, GET): `q=config` (which advertises the supported
`post-types`, so a client offers a Like/Reply/… composer, and the
`media-endpoint` of section 9b), `q=syndicate-to`, `q=source`.

### 9a. Update and delete

Both address an existing post by its `url` and, like create, come down to a file
operation the watcher then syncs; neither touches the db directly.

**Delete** (`handle-delete`, scope `delete`) removes the post's file. The watcher
retracts the entity, and because a deletion still fires the publish hook (with
the post's pre-retraction year and slug), it re-sends the post's Webmentions: the
previously notified targets learn the source is gone, which is also how the
federated copies are withdrawn. This is a hard delete. Undelete would need the
file to survive somewhere the watcher ignores plus a read path that hides it; at
one user that is more machinery than the feature earns, so it is not supported.

**Update** (`handle-update`, scope `update`) applies the request's `replace`,
`add` and `delete` operations to the post's file. The file, not the db, is the
source of truth: `parse-file` reads it back into a frontmatter map and a body,
`apply-update` applies the operations, and the *whole* frontmatter is rewritten
(every key, not just the create-time subset, so tags and syndication survive).
The mf2 properties map to post attributes through `update-property->attr`: `name`
to the title, `content` to the body, `category` to the tags, the response verbs
to themselves. `category` and `syndication` are the multi-valued ones, so `add`
and value-specific `delete` are meaningful there; on a scalar they set and clear.
`published` and `mp-slug` are deliberately absent from that map: they fix the
date and the permalink, and an update that moved the file would 404 the old URL,
so the permalink stays put from create time. Success is 204, no Location.

### 9b. The media endpoint

**What it is.** Posting a photo is not posting text. The file is uploaded
separately, as `multipart/form-data`, to a **media endpoint** whose URL a client
learns from `q=config`. The client uploads the file, gets a URL back, then
creates a post that references it. This is what lets a phone post a picture.

**How it works here.** `POST /media` → `interceptors/media` →
`micropub/handle-media`.

1. **Authorize**, exactly as create does, accepting a `media` or `create` scope.
2. **Parse.** The upload arrives in the `file` part. Multipart parsing is not in
   the global interceptor stack, since nothing else needs it; it is scoped to
   this one route, where `(middlewares/multipart-params)` sits in front of the
   handler and adds `:multipart-params`. That parser reads the request body
   directly (via commons-fileupload), so no Jetty servlet configuration is
   involved.
3. **Store.** The file is written into the posts `assets/` dir (section 11) under
   a `YYYY-MM-DD-<slug>` name derived from its original filename
   (`content/file-slug`), made unique the way `unique-slug` numbers a post within
   a year. Only the extensions in `content/img-ext` are accepted: the same set a
   hand-written post may embed, and the one canonical place saying what this blog
   serves.

The response is **201**, not create's 202, with the file's URL in `Location`.
That difference is the architecture paying off once more: `assets/` is already
served as static files (section 11), so an upload is live the instant it lands,
with no watcher sync to wait on and nothing written to the db.

---

## 10. POSSE and backfeed

**What it is.** *Publish on your Own Site, Syndicate Elsewhere.* The canonical
copy lives here; copies go to Mastodon and friends. **Backfeed** is the return
path: replies and likes on those copies are pulled back and displayed here, as
Webmentions.

**How it works here.** A post's frontmatter carries `syndication:`
(space-separated URLs of the copies). `component/article` renders each as a
**hidden** `a.u-syndication` link.

That hidden link is the entire mechanism. [Bridgy](https://brid.gy/) watches the
silo copy, sees a like or reply, matches it back to the canonical post via the
`u-syndication` link — and **sends us a Webmention**. Which we then receive,
verify, and display through the machinery of section 5, with no silo-specific
code anywhere in this codebase.

Connecting Bridgy is a manual, one-off step; syndicating a post is currently
manual too (paste the URL into the frontmatter).

### 10a. Federation, which is not POSSE

**What it is.** [Bridgy Fed](https://fed.brid.gy/) is the other thing entirely,
and the confusingly similar name is not our fault. POSSE *copies* a post to an
account you hold on Mastodon. Bridgy Fed *federates* the site itself: this
domain becomes a first-class fediverse and Bluesky account, people there follow
`@simon.grays.blog` directly, and there is no silo account anywhere, no copy,
and no `syndication:` URL to paste. What they follow is this server.

**How it works here.** It is the same protocols we already speak, pointed at a
translator. Bridgy Fed reads h-entry and Webmention on one side and talks
ActivityPub and AT Protocol on the other, so the whole feature is four
conf-driven lines of markup and one extra Webmention target:

| what | where |
|---|---|
| `a.u-bridgy-fed` (hidden) on every post | `component/article` |
| `link rel=me` to the bridged home page | `component/head` |
| `link rel=alternate` (ActivityPub) per post | `component/page` |
| `img.u-photo` (hidden) in the h-card | `component/footer` |
| the bridge as a Webmention target | `webmention/send-webmentions!` |

Publishing then federates a post for free, because `notify!` already sends the
Webmentions. Editing one federates the edit. **Deleting one withdraws it**, and
that falls out of the delivery records: a deleted post is never *newly* announced
to the bridge, but it is re-sent to every target it previously reached, Bridgy
Fed refetches it, gets a 404, and deletes the federated copy. Replies and likes
come back as Webmentions from `brid.gy` proxy pages, which is what section 5c's
`u-url` handling is for.

Four details that are each load-bearing and none of them obvious:

- **The bridge link must sit outside `e-content`.** It lives in the metadata
  aside with the other hidden machine-readable links. Inside the content,
  Mastodon renders a link preview of fed.brid.gy in the middle of the post.
- **The `u-bridgy-fed` class is not decoration.** Without it, an mf2 parser
  reads an empty `<a>` as an implied `u-url` and the post claims fed.brid.gy as
  its own permalink.
- **The h-card needs a photo.** Bridgy Fed refuses to bridge a profile without
  one, as a spam filter. Ours is hidden, since the page has no room for a
  portrait, but a parser still sees it. This is why `:photo` exists in the conf.
- **Notes matter here.** The fediverse distinguishes a note from an article by
  whether it has a name, and Mastodon shows a note in full but an article as a
  title plus a link. That is the payoff for section 4's insistence that a
  titleless post carry no `p-name`.

**Turning it on.** The code does nothing until the bridge is enabled by hand,
once, by entering the domain at <https://fed.brid.gy/web-site>. Be aware that
this is a commitment rather than an experiment: from then on every post is
public on Mastodon and Bluesky under this domain, and disabling the bridge later
deletes the bridged account and disconnects its followers for good. Note also
that merely connecting the site is enough for Bridgy Fed to start bridging posts
from the RSS feed on its own; the Webmentions do not opt us *in*, they only make
it immediate and make edits and deletions propagate.

---

## 11. The data on disk

```
simon.grays.blog/
├── posts/                  the source of truth for content
│   ├── some-post.md
│   └── assets/
├── indieweb/               the source of truth for everything IndieWeb
│   ├── mentions/2020/some-post.edn
│   ├── deliveries/2020/some-post.edn
│   └── contexts.edn
└── db/                     derived; delete at will
```

Entries are EDN maps **keyed by the remote URL**, in a file whose **name carries
the local permalink**:

```clj
;; indieweb/mentions/2020/some-post.edn
{"https://example.com/a-page"
 {:status       :verified
  :kind         :reply
  :url          "https://example.com/a-page"  ; the source's own u-url
  :received     "2026-07-14T09:12:03Z"
  :published    "2026-07-13"
  :author-name  "Jane Doe"
  :author-url   "https://example.com/"
  :author-photo "https://example.com/jane.jpg"
  :content      "Couldn't agree more, though I'd add that…"}

 "https://spam.example/x"
 {:status :blocked}}
```

Local half in the filename, remote half in the key — so **neither needs an
identity attribute in the db**. The file *is* the index. Keys are bare on disk
and namespaced (`:mention/source`, …) on the way in, exactly as post frontmatter
is.

Writes are serialised and atomic (temp file + `ATOMIC_MOVE`), so the watcher can
never read a half-written file.

---

## 12. Operations

```clj
(require '[blog.grays.web.service :as service]
         '[blog.grays.web.db :as db]
         '[blog.grays.web.webmention :as wm])

(def conf service/dev-conf)
(def conn (db/get-conn (:db-dir conf)))

;; Wipe the db and rebuild from the files. Always safe. This is how a schema
;; change is applied.
(db/rebuild! conf)

;; Send Webmentions for a post (only meaningful once deployed — the source URL
;; must be publicly reachable).
(wm/send-webmentions! conn conf "2026" "some-post")

;; Tell the WebSub hub the feed changed.
(wm/ping-hub! conf)

;; Hide a mention, and refuse future re-sends of it.
(wm/block-mention! conf "https://spam.example/x" "/posts/2020/some-post")

;; Re-fetch a reply context that failed.
(wm/fetch-context! conf "https://example.com/a-page")
```

## 13. Deliberately not implemented

Worth stating, so their absence reads as a decision rather than an oversight:

- **A full mf2 parser.** We read the subset the three features consume.
- **Our own IndieAuth server.** Delegated; see section 8. But on borrowed time:
  section 8a explains why this one *is* now an oversight rather than a decision.
- **Micropub undelete.** Delete is a hard delete; see section 9a for why bringing
  a post back is not worth the machinery at one user.
- **Automatic syndication.** POSSE copies are pasted into frontmatter by hand.
- **An admin UI.** Moderation is a text editor, by design.
- **Vouch, private Webmentions, Salmention.** Not needed at this scale.

## 14. See also

- [testing.md](testing.md) — the prod verification protocol
- [webmention.rocks](https://webmention.rocks/) — the sending/receiving conformance suite
- [indiewebify.me](https://indiewebify.me/) — checks the microformats on a live page
- [fed.brid.gy/docs](https://fed.brid.gy/docs) — the Bridgy Fed manual, and the source of every gotcha in section 10a
- [indieweb.org](https://indieweb.org/) — the wiki
