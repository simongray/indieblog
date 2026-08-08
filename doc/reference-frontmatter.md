# Frontmatter reference

Every key a post's YAML frontmatter may carry. For *why* these exist, see
[indieweb.md](indieweb.md); for how to use them, see
[how-to-publish.md](how-to-publish.md).

Frontmatter is the block between two `---` lines at the very top of the file:

```markdown
---
title: A post about something
date: 2026-08-08
tags: clojure, indieweb
---

The body, in Markdown.
```

Everything is optional. A file with no frontmatter at all is still a valid post —
and is exactly what a [standalone page](#standalone-pages) is.

## Keys

| Key | Value | Effect |
|---|---|---|
| `title` | string | The headline, and the `p-name` of the h-entry. Derived from the body's first `<h1>` when absent. With neither, the post is a **note**: no headline, no `p-name`. |
| `date` | `YYYY-MM-DD` | The `dt-published` date, and the `<year>` in the permalink. Absent ⇒ the year falls back to the current one and the post has no date. |
| `slug` | string | The `<slug>` in the permalink. Derived from a slugified `title`, falling back to the filename. |
| `tags` | comma-separated | One `p-category` per tag, each linking to `/tags/<slug>`. Authored already slug-shaped. |
| `location` | string | The `p-location`. Defaults to `Copenhagen` (hardcoded in `content/expand-post`, not conf). |
| `language` | BCP 47 tag | Defaults to `en`. |
| `reply-to` | one URL | Makes the post a **reply**: `u-in-reply-to`. |
| `like-of` | one URL | Makes the post a **like**: `u-like-of`. |
| `repost-of` | one URL | Makes the post a **repost**: `u-repost-of`. |
| `bookmark-of` | one URL | Makes the post a **bookmark**: `u-bookmark-of`. |
| `rsvp` | `yes`/`no`/`maybe`/`interested` | A `p-rsvp` beside the reply context. **Renders nothing without `reply-to`.** |
| `syndication` | space-separated URLs | One hidden `u-syndication` per URL — where the POSSE copies live. |

## Response verbs

`reply-to`, `like-of`, `repost-of` and `bookmark-of` are the four *response verbs*
(`db/response-verb-attrs`). Carrying any one of them makes a post a **response**
(`db/response-post?`), which changes three things:

- it is pulled **out** of the frontpage article feed and out of `/feed`, and rendered
  in the response strip above `<main>` instead;
- its own permalink still renders the full h-entry markup;
- the URL it names becomes a Webmention target, sent on publish exactly like a link in
  the body.

They are not additive: a post names one thing it responds to.

## Derived, not authored

`content/expand-post` fills in `title`, `slug`, `year`, `location`, `length` and
`language` when the frontmatter omits them, and records which of them it derived under
`:derived`. Writing the key yourself always wins.

## Gotchas

- **`: ` truncates a value.** The frontmatter parser splits each line on the first
  colon-plus-whitespace and keeps two fields, so `title: Foo: a subtitle` stores the
  title `Foo` and silently drops the rest. A colon *not* followed by whitespace is
  fine, which is why URLs (`https://…`) parse correctly.
- **Slugs must be unique across the whole site.** `content/check!` throws on boot
  rather than let two posts collide — including with a standalone page's slug.
- **`tags` reached existing posts only through a rebuild.** `:tags` was a schema
  addition; see [how-to-operate.md](how-to-operate.md).

## Standalone pages

`/about` and `/now` (`shared/pages`) are markdown files of the same name in the posts
dir, and take **no frontmatter at all**: they have no date, and the slug derives from
the title, so `about.md` titled "About" lands on `about` by itself. They are not posts
— no feed membership, no `/posts/<year>/<slug>` permalink.
