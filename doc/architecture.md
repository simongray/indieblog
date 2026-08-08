# Architecture

## The one rule

**Disk is the source of truth; the database is a derived index.**

```
posts/*.md          ─┐
indieweb/**.edn     ─┴─→ watcher ─→ Datalevin db ─→ rendered HTML
```

Data flows in one direction only. Nothing writes to the db except the sync layer
watching those directories. When a Webmention arrives, `verify-mention!` writes an
**EDN file**; the watcher notices and syncs it in. Reads go to the db, writes go to the
files, never the reverse.

Three things fall out of this, and they are worth stating because they are the payoff:

- The db can be deleted at any moment and rebuilt from the files (`db/rebuild!`). A
  schema change is not a migration; it is a rebuild.
- **Moderation is editing a file.** There is no admin UI because there does not need to
  be one.
- Everything is inspectable and diffable with the tools you already have.

What is on disk, and in what shape, is [reference-files.md](reference-files.md).

## What is where

Posts are Markdown files in the posts dir. The IndieWeb data that *cannot* be
regenerated — Webmentions received, Webmentions delivered, reply contexts fetched — is
EDN in the indieweb dir; native comments are EDN there too.

The distinction that decides where something lives is not what it is *about* but
whether losing it would lose anything. A parsed post can be re-derived from its file. A
mention someone sent you cannot be re-derived from anything.

## Technology choices

- **Datalevin over SQL.** A schema that is a rebuild rather than a migration is only
  cheap if the store makes it cheap. Datalevin applies schema on a fresh connection,
  which suits a db that is thrown away and refilled on demand.
- **Pedestal over Ring.** The interceptor model composes: conf and the db connection are
  attached to every request before routing, and cross-cutting concerns (CSP,
  cache-control, the styled 404, content negotiation) are interceptors rather than
  middleware wrappers.
- **Replicant over Rum/Reagent.** Pure functions from data to hiccup, rendered to an
  HTML string on the server. No React, and the same `.cljc` components would run in a
  browser.
- **File-based over a CMS.** Posts are edited in an editor and versioned in Git. The
  Micropub endpoint writes the same files a hand-written post uses, so publishing from
  a client adds a door rather than a second system.

## A preference for less

The rest is a preference for less: one HTTP client, one name per concept, and
(paraphrasing Saint-Exupéry) perfection reached not when there is nothing more to add,
but when there is nothing left to take away.

[indieweb.md](indieweb.md) §13 and [comments.md](comments.md) §7 list what was
deliberately *not* built, so their absence reads as a decision rather than an oversight.
