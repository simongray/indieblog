# Files on disk reference

Where everything lives, and the shape of what is in it. Disk is the source of truth;
the database is a derived index — see [architecture.md](architecture.md) for why.

## The tree

```
simon.grays.blog/
├── posts/                  the source of truth for content
│   ├── some-post.md
│   ├── about.md            a standalone page: no frontmatter
│   └── assets/             images, served at /assets/
├── indieweb/               the source of truth for everything IndieWeb
│   ├── mentions/2020/some-post.edn
│   ├── deliveries/2020/some-post.edn
│   ├── comments/2026/some-post.edn
│   ├── contexts.edn
│   └── avatars/            cached mention-author photos, served at /avatars/
└── db/                     derived; delete at will
```

The three roots are `:posts-dir`, `:indieweb-dir` and `:db-dir` in
[reference-conf.md](reference-conf.md). `db/start!` creates the indieweb
subdirectories itself.

## The file conventions

Entries are EDN maps **keyed by the remote URL**, in a file whose **name carries the
local permalink**. Local half in the filename, remote half in the key — so neither
needs an identity attribute in the db. The file *is* the index.

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
  :content      "Couldn't agree more, though I'd add that…"}

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

## Moderation

**Moderation is editing a file.** Flip `:status` and save; the watcher picks it up
within seconds. There is no admin UI because there does not need to be one. See
[how-to-operate.md](how-to-operate.md) for the one case that needs more than an editor
(refusing future re-sends of a blocked mention).
