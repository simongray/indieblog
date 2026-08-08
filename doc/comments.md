# Native comments

How visitors write comments directly on a post page, and how Web sign-in tells
us who they are. Companion to [indieweb.md](indieweb.md), which covers the other
way of responding to a post: Webmentions (its §5).

## 1. What it is

A Webmention is a great comment mechanism for people who have a website and a
page to link. Everyone else was stuck. Native comments close that gap: a
visitor signs in *as their website* (the IndieWeb's
[Web sign-in](https://indieweb.org/Web_sign-in)), writes their comment in a
form on our page, and it is stored and rendered by us, first-party.

The two mechanisms meet in the post's Responses section, interleaved by date.

## 2. IndieWeb content, generically stored

A comment is IndieWeb content: the author is authenticated as *their own
website*, via IndieLogin. So comments live in the `indieweb-dir` alongside the
mentions, and `indieweb/comments.clj` sits in the same namespace group as
`webmention.clj` and `signin.clj`.

The storage itself stays generic, built to also hold comments with other kinds
of identity later (anonymous, email-verified, and so on) without restructuring.
Each entry records *how its author was authenticated* in `:auth` (`:indieauth`
today). A future anonymous comment is just another `:auth` value, presumably
defaulting to `:status :pending` where an authenticated one defaults to
`:approved`.

## 3. The data on disk

The store follows the file conventions of the store namespace: filename carries
the permalink, entries are keyed inside the file, writes are atomic, and the
watcher (with no publish hook, as ever) syncs changes into the db.

```clj
;; indieweb/comments/2026/some-post.edn
{"20260717T091203-4f2a"
 {:status       :approved      ; :approved :pending :blocked
  :auth         :indieauth
  :received     "2026-07-17T09:12:03Z"
  :published    "2026-07-17"
  :author-url   "https://their.site/"
  :author-name  "Jane Doe"
  :author-photo "https://their.site/jane.jpg"
  :author-photo-cache "/avatars/…"
  :content      "Great post, but…"}}
```

A native comment has no remote URL to be keyed by, so the key is a generated id
(a UTC timestamp plus a short random suffix); the id doubles as the comment's
`#comment-<id>` anchor on the post page. **Moderation is your editor**, exactly
as for mentions: flip `:status` and save. Only `:approved` comments render.

## 4. The sign-in flow

Authentication is delegated to the `:sign-in :endpoint` of conf, in practice
[IndieLogin.com](https://indielogin.com/api). Unlike the deprecated *server*
half of IndieAuth.com (indieweb.md §8a), signing visitors in to apps is exactly
the problem IndieLogin.com is maintained to solve, so this delegation carries
none of that bit-rot. Remove the `:sign-in` conf key and the feature turns off
whole: no form is rendered and every flow request answers 400.

The flow, across three routes:

1. **`POST /sign-in`** (the form in the Responses section): the claimed `me`
   URL must pass the same public-http(s) guard as a Webmention source
   (`http/valid-url?`; sign-in later fetches a visitor-chosen URL, so it has
   the same SSRF exposure), and the `path` must be an existing post. The
   visitor is then redirected to the endpoint with
   `me`/`client_id`/`redirect_uri`/`state`.
2. **`GET /sign-in/callback`**: the returned `state` is verified, the `code`
   is POSTed back to the endpoint, and the JSON response names the visitor's
   authenticated site. They land on the "Write a comment" page.
3. **`POST /comments`**: the comment is trimmed and capped
   (`shared/comment-max-length`), the visitor's homepage is fetched once to
   read its h-card (`html/card`) for a display name and photo (the photo goes
   through the same avatar cache as mention authors, keeping CSP untouched),
   and the entry is written. The browser is redirected to `#comments`; the
   watcher syncs the file, so the comment appears on a reload moments later.

**There are no sessions and no cookies.** Continuity is carried by short-lived
HMAC-signed tokens minted in `indieweb/signin.clj`: the `state` (10 minutes) proves the
callback belongs to a sign-in we started, and a fresh token on the comment form
(30 minutes) proves the poster was authenticated; it doubles as the CSRF
token. The secret is random per boot, so a server restart merely voids
in-flight sign-ins. Comments default to `:approved` because their author just
proved control of a domain; moderation stays available after the fact.

## 5. Rendering

`component/comments` renders the Responses section: the mentions facepile,
then replies, plain mentions and native comments interleaved by date, then the
two forms. A native comment (`component/native-comment`) mirrors a mention
except for what it honestly lacks: it cites no external page, so there is no
`h-cite`, and its `u-url` is its own `#comment-<id>` anchor. The author link
goes to the *verified* `me`, never to what the h-card claims; a nameless
author falls back to their domain. Content is plain text, escaped on render;
no markdown, no HTML.

## 6. Operations

```clj
(require '[blog.grays.web.service :as service]
         '[blog.grays.web.indieweb.comments :as comments])

(def dir (:indieweb-dir service/dev-conf))

;; A post's comments, keyed by id; nil when there are none.
(comments/comments dir "/posts/2020/some-post")
```

There is no `block-mention!` analogue: unlike a mention, a blocked comment has
no re-send machinery to refuse, so flipping `:status` in the file is the whole
story.

The `:comment/*` schema attributes apply on a fresh connection only, so the
first deploy needs a `db/rebuild!` (indieweb.md §12). `db/start!` creates the
comments subdirectory itself.

## 7. Deliberately not implemented

- **Commenter-side edit and delete.** Write to the author, who edits the file.
- **Threading.** Comments respond to the post, not to each other.
- **Markdown in comments.** Plain text renders safely and reads fine.
- **Notifications.** The files are on disk; look at them.
- **Anonymous comments.** Not yet; the `:auth` mechanism is the accommodation
  that keeps them possible without restructuring.
