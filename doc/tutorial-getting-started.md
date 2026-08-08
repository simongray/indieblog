# Getting started

By the end of this you will have the blog running on your own machine, with a post you
wrote showing up on the frontpage, in a tag page and in the RSS feed — without ever
restarting the server.

You need Java and the `clojure` CLI. Nothing else.

## 1. Make a place for your content

Content lives *outside* this repository: the code is one thing, the blog is another.
Create a directory for it, with three subdirectories:

```bash
mkdir -p ~/blog-content/{posts,indieweb,db}
```

Mine is `~/Code/simon.grays.blog`. Yours can be anywhere — you will name it in the next
step.

## 2. Point the code at it

Open `src/blog/grays/web/service.clj`, find `dev-conf` near the top, and change the
three directory paths to yours:

```clojure
(def dev-conf
  (assoc conf
    :development true
    :db-dir "/Users/you/blog-content/db/"
    :posts-dir "/Users/you/blog-content/posts/"
    :indieweb-dir "/Users/you/blog-content/indieweb/"))
```

Use absolute paths — `~` will not be expanded.

## 3. Start a REPL

```bash
clojure -M:nrepl
```

This starts an nREPL server on port 7888 and drops you at a prompt. Leave it running;
everything from here happens in this REPL, and you can also connect your editor to
port 7888 instead of typing at the prompt.

## 4. Start the server

```clojure
(require '[blog.grays.web.service :as service])
(service/restart!)
```

You should see a `Starting blog server on port 4567` log line. Open
<http://localhost:4567> — an empty blog, with a masthead and no posts.

Leave the server running too. You will not need to restart it again.

## 5. Write a post

In a *different* terminal, create `~/blog-content/posts/hello.md`:

```markdown
---
title: Hello, world
date: 2026-08-08
---

My first post. It has **Markdown** in it.
```

Save it, then reload <http://localhost:4567>.

The post is there. Nothing was rebuilt and nothing was restarted: a file watcher noticed
the new file and synced it into the database, which is the only way anything ever gets
in there.

Click the headline. The permalink is `/posts/2026/hello-world` — the year came from
`date`, the slug from the title.

## 6. Edit it while it is running

Change the body of `hello.md`, save, and reload the post page. The change is already
live. This is the whole authoring loop: edit a file, save it, look at it.

Now delete the file, reload the frontpage, and the post is gone. Put it back before
carrying on.

## 7. Add a tag

Add a `tags:` line to the frontmatter:

```markdown
---
title: Hello, world
date: 2026-08-08
tags: beginnings
---
```

Save, reload the post, and the tag appears in its metadata. Follow it to
<http://localhost:4567/tags/beginnings> — a page listing every post with that tag, which
has its own RSS feed at `/tags/beginnings/feed`.

## 8. Look at the feed

Open <http://localhost:4567/feed>. Your post is in it, as RSS.

Have a look at the post's HTML source too, and find `class="h-entry"`,
`class="p-name"`, `class="dt-published"`. Those class names are
[microformats2](indieweb.md), and they are what makes the page readable by other
sites — a plain HTML page that is also an API. That is what the rest of the docs are
mostly about.

---

## Where to go next

You have a working blog. From here:

- [how-to-publish.md](how-to-publish.md) — notes, images, replies, tags, pages
- [reference-frontmatter.md](reference-frontmatter.md) — every frontmatter key
- [architecture.md](architecture.md) — why files are the source of truth and the
  database is disposable
- [indieweb.md](indieweb.md) — what all the microformats are for
