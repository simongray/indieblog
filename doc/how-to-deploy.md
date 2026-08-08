# How to deploy

## Build the jar

```bash
clojure -T:build ci :uber-file '"blog.jar"'
```

`build/ci` cleans, runs the tests and builds the uberjar. The result carries everything
including the `resources/public` assets.
Content is *not* in the jar — it lives in the directories named by `prod-conf`.

## Install as a systemd service

Copy the jar to `/opt/blog/` on the server, then:

```bash
cp system/blog.service /etc/systemd/system/blog.service
systemctl enable blog
systemctl start blog
```

The unit runs `java -jar blog.jar` from `/opt/blog`, which starts with `prod-conf`:
content under `/opt/blog/simon.grays.blog/`, and `:send-webmentions?` on. See
[reference-conf.md](reference-conf.md) for what differs from dev.

Logs go to the journal. Every log id mentioned in [how-to-verify.md](how-to-verify.md)
(`::verified`, `::sent`, `::hub-pinged`, …) is greppable there.

## After deploying a schema change

Datalevin applies schema on a fresh connection only, so **any** addition to `db/schema`
reaches existing content only through a rebuild:

```clj
(db/rebuild! conf)
```

Mentions that predate the avatar cache also need `(wm/cache-avatars! conf)` once — a
rebuild cannot fetch faces it never had. Both are in
[how-to-operate.md](how-to-operate.md).

## Enable Bridgy Fed (once, by hand)

The federation code does nothing until the bridge is enabled by entering the domain at
<https://fed.brid.gy/web-site>.

**This is a commitment rather than an experiment.** From then on every post is public on
Mastodon and Bluesky under this domain, and disabling the bridge later deletes the
bridged account and disconnects its followers *for good*. Note also that merely
connecting the site is enough for Bridgy Fed to start bridging posts from the RSS feed
on its own; the Webmentions do not opt you *in*, they only make it immediate and make
edits and deletions propagate.

Once connected, the fediverse handle is `@simon.grays.blog@simon.grays.blog`, because
`/.well-known/webfinger` and `host-meta` redirect to fed.brid.gy. Those must stay
*redirects*: Bridgy Fed refuses a site that serves WebFinger itself.

See [indieweb.md](indieweb.md) §10a for what the bridge actually does.

## Connect Bridgy (also once, also by hand)

POSSE backfeed — pulling likes and replies back from a silo account you hold — is a
separate thing from the above. Connect the account at <https://brid.gy/> and let it
poll. Syndication URLs themselves are pasted into frontmatter by hand; see
[how-to-publish.md](how-to-publish.md).

## Verify the deploy

[how-to-verify.md](how-to-verify.md) is the full protocol. The two-minute version:

```bash
curl -s  https://simon.grays.blog/ | grep -oE '<link rel="[^"]+"[^>]*>'
curl -sI https://simon.grays.blog/feed | grep -i link
```

Expect a `<link>` per configured endpoint, and `rel="hub"` + `rel="self"` on the feed.
