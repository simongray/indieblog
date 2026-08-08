# How to publish

Every post is a Markdown file in the posts dir. Save the file and the watcher syncs it
within seconds — there is no build step and no restart. Deleting the file unpublishes
the post.

For the exact meaning of every frontmatter key below, see
[reference-frontmatter.md](reference-frontmatter.md).

## Write an article

```markdown
---
title: On the merits of doing less
date: 2026-08-08
---

The body, in Markdown.
```

The permalink is `/posts/2026/on-the-merits-of-doing-less` — the year from `date`, the
slug from a slugified `title`. Set `slug:` yourself to override it.

Slugs must be unique across the whole site; a collision refuses to boot rather than let
one post shadow another.

## Write a note

A note is a post with **no title** — leave `title:` out and start no `<h1>`:

```markdown
---
date: 2026-08-08
---

Short thought, no headline.
```

The slug falls back to the filename, so name the file deliberately. This is not a
cosmetic distinction: the fediverse tells a note from an article by whether it has a
name, and Mastodon shows a note in full but an article as a title plus a link.

## Add tags

```markdown
tags: clojure, indieweb
```

Comma-separated, and authored already slug-shaped — the slug is both the stored value
and the `/tags/<slug>` URL. Each becomes a `p-category` linking to its tag page, and
each tag gets its own RSS feed at `/tags/<slug>/feed`.

## Embed an image

Put the file in the `assets` subdirectory of the posts dir
(`.../simon.grays.blog/posts/assets/`) and reference it with an **absolute** path:

```markdown
![Alt text](/assets/my-photo.png)
```

A bare or relative reference such as `![…](my-photo.png)` will **not** work: on a post
page (`/posts/YYYY/slug`) the browser resolves it against the post URL as
`/posts/YYYY/my-photo.png`. Always start the path with `/assets/`.

Assets are served straight off disk. There is no copying step and nothing is stored in
the database.

## Respond to something

Add exactly one response verb naming the URL you are responding to:

```markdown
---
date: 2026-08-08
reply-to: https://some.blog/interesting-post
---

Couldn't agree more, though I'd add that…
```

`like-of:`, `repost-of:` and `bookmark-of:` work the same way and usually carry an empty
body. All four:

- keep the post out of the frontpage article feed and out of `/feed`, showing it in the
  response strip instead;
- render the target as `u-in-reply-to` / `u-like-of` / …;
- make the target a Webmention recipient, so the other site is told.

A reply to an event can also carry an RSVP, which needs `reply-to:` to mean anything:

```markdown
reply-to: https://some.site/an-event
rsvp: yes
```

Reply contexts are fetched asynchronously, so the first load of a new reply shows the
bare URL and a load a few seconds later shows the target's title and author.

## Record a POSSE copy

After posting a copy somewhere manually, paste its URL back into the post:

```markdown
syndication: https://mastodon.social/@you/123 https://bsky.app/…
```

Space-separated. Each renders as a hidden `u-syndication` link, which is how Bridgy
finds the copy and brings its likes and replies back as Webmentions.

Nothing syndicates automatically. Federation via Bridgy Fed is a different mechanism
that needs none of this — see [how-to-deploy.md](how-to-deploy.md).

## Add a standalone page

Drop a frontmatter-less Markdown file in the posts dir, named for its slug
(`about.md`, `now.md`), and add the slug to `shared/pages`. It is served at `/<slug>`,
listed in the masthead and the sitemap, and kept out of every feed.

`/about` is special-cased to render as the site's full visible h-card, using the
`:portrait`, `:locality` and `:country` conf keys.

## Publish from a Micropub client

Sign in to a client like [Quill](https://quill.p3k.io/) as your domain. It writes the
same Markdown files with the same frontmatter, so everything above still applies, and
the new post rides the watcher exactly like a hand-written one. Images can be uploaded
to the media endpoint first; the URL it returns is an `/assets/` path.
