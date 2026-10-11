# Files on disk reference

Where everything lives, and the shape of what is in it. Disk is the source of truth;
the database is a derived index — see [architecture.md](architecture.md) for why.

## The tree

```
simon.grays.blog/
├── posts/                  the source of truth for content
│   ├── some-post.md
│   ├── about.md            a standalone page: no frontmatter
│   └── assets/             images, served at /assets/; referenced relatively
│                           from the posts so editors resolve them too
├── indieweb/               the source of truth for everything IndieWeb
│   ├── mentions/2020/some-post.edn
│   ├── deliveries/2020/some-post.edn
│   ├── comments/2026/some-post.edn
│   ├── contexts.edn
│   ├── tokens.edn          Micropub bearer tokens, hashed; not synced to the db
│   ├── oauth-clients.edn   our app registrations at Mastodon instances
│   └── avatars/            cached mention-author photos, served at /avatars/
└── secrets.edn             merged onto conf at start!; not in the repo
```

The two roots are `:posts-dir` and `:indieweb-dir` in
[reference-conf.md](reference-conf.md). `db/start!` creates the indieweb
subdirectories itself.

## The file conventions

Entries are EDN maps **keyed by the remote URL**, in a file whose **name carries the
local permalink**. Local half in the filename, remote half in the key — so neither
needs a key of its own in the db. The file *is* the index.

Keys are bare on disk and namespaced (`:mention/source`, …) on the way in, exactly as
post frontmatter is.

Writes are serialised and atomic (temp file + `ATOMIC_MOVE`), so the watcher can never
read a half-written file. `indieweb/store.clj` holds these conventions.

## A mention

```clj
;; indieweb/mentions/2020/some-post.edn
{"https://example.com/a-page"
 {:status       :verified                      ; :pending :verified :failed :blocked
  :kind         :reply                         ; :reply :like :repost :bookmark :mention
  :url          "https://example.com/a-page"   ; the source's own u-url
  :received     "2026-07-14T09:12:03Z"
  :published    "2026-07-13"
  :author-name  "Jane Doe"
  :author-url   "https://example.com/"
  :author-photo "https://example.com/jane.jpg"
  :author-photo-cache "/avatars/…"             ; the local copy that renders
  :content      "Couldn't agree more, though I'd add that…"
  :in-reply-to  #{"https://example.org/x"}}    ; replies only, minus our permalink

 "https://spam.example/x"
 {:status :blocked}}
```

The key is the URL that was POSTed to us; `:url` is the permalink that page claims for
itself. Usually the same — but a bridge (Bridgy, Bridgy Fed) POSTs a proxy page on its
own domain, so we verify against the key and display the `:url`. Otherwise every reply
from the fediverse would read as having come from brid.gy.

## A comment

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

A native comment has no remote URL to be keyed by, so the key is a generated id — a UTC
timestamp plus a short random suffix — which doubles as the comment's `#comment-<id>`
anchor on the post page.

`:auth` records *how the author was authenticated*, so a future anonymous or
email-verified comment is just another value here rather than a restructuring. Only
`:approved` comments render.

## A token

```clj
;; indieweb/tokens.edn
{"VWK0FWk0…"                                   ; the hash of the bearer token
 {:type      :access
  :grant     "PUnUqzAQ…"
  :me        "https://simon.grays.blog/"
  :client-id "https://quill.p3k.io/"
  :scope     "create update"
  :issued-at #inst "2026-10-11T11:46:54.152-00:00"
  :expires   nil}
 "grant:PUnUqzAQ…"                             ; what the owner granted the client
 {:me        "https://simon.grays.blog/"
  :client-id "https://quill.p3k.io/"
  :scope     "create update"
  :tokens    {"VWK0FWk0…" nil}
  :expires   nil}}
```

The store of the Micropub bearer tokens our IndieAuth server issued
([indieweb.md](indieweb.md) §8), in indieauth-clj's shape. The key is a hash
of the token, so the file leaks nothing. The `:client-id` and `:issued-at`
fields tell the rows apart when you revoke. **Revocation is deleting the
token's row**, and it takes effect at the very next request: tokens are
deliberately *not* synced into the db, and the server reads the file
directly. For the same reason, a token works the moment it is issued. Rows
in the older shape, keyed by a SHA-256 in hex, are no longer read and can be
deleted.

## An OAuth client registration

```clj
;; indieweb/oauth-clients.edn
{"https://indieweb.social"
 {:client-id     "…"
  :client-secret "…"
  :registered    "2026-08-16T09:03:11Z"}}
```

The apps the blog registered as at Mastodon instances, one row per instance.
A visitor from a known instance skips the registration round trip. This file
is not synced into the db either. Deleting a row causes a fresh registration
on the next sign-in from that instance.

## The secrets file

```clj
;; secrets.edn — outside the repo, next to the two directories
{:github {:client-id "…" :client-secret "…"}}
```

Merged onto conf by `service/start!` (see `:secrets-file` in
[reference-conf.md](reference-conf.md)). Today it holds the GitHub OAuth app
the auth namespace signs visitors in with. An absent file merges nothing,
which turns GitHub sign-in off and breaks nothing else.

## Moderation

**Moderation is editing a file.** Flip `:status` and save; the watcher picks it up
within seconds. There is no admin UI because there does not need to be one. A blocked
mention stays blocked when it is sent again. [how-to-operate.md](how-to-operate.md)
has the REPL equivalent, which can also block a source before it has sent anything.
