# Configuration reference

Every key of the conf map in `service.clj`. There is no config file: `conf` is the
shared base, and `prod-conf`/`dev-conf` are it with a few keys assoc'd on.

The governing convention: **an absent key turns its feature off**, silently and
completely. Nothing else in the code hardcodes an endpoint URL, so swapping the native
Webmention endpoint for a hosted one (webmention.io) is a one-line change here.

## Identity and presentation

| Key | Value | Effect |
|---|---|---|
| `:url` | string | The site's canonical URL. The h-card's `u-url`/`u-uid` — this is what makes the domain an identity. |
| `:name` | string | Site name; the RSS channel title. |
| `:author` | string | The h-card's `p-name`. |
| `:email` | string | The h-card's `u-email`, and the `Contact` in `security.txt`. |
| `:language` | BCP 47 tag | Default post language and the feed's. |
| `:tagline` | hiccup | The masthead blurb. Hiccup, not a string, so it can carry markup. |
| `:photo` | path | The representative h-card's photo, shown in the footer colophon. Bridgy Fed refuses to bridge a profile without one. |
| `:locality`, `:country` | string | The h-card's `p-locality`/`p-country-name`, shown in the footer byline. |
| `:identity` | `{url {:label …}}` | The `rel=me` links: hidden in `<head>` on every page, visible in the footer colophon. Combined with links back from those profiles, this is what IndieAuth authenticates against. |

## IndieWeb endpoints

Each of these is advertised as a `<link rel>` in `<head>` by `component/head`, and each
disappears from the markup when its key is absent.

| Key | `<link rel>` | Absent ⇒ |
|---|---|---|
| `:webmention-endpoint` | `webmention` | Nobody discovers where to send us mentions. |
| `:indieauth` | `indieauth-metadata`, `authorization_endpoint`, `token_endpoint` | No Micropub client can discover where to authenticate. A map of `:metadata`, `:authorization-endpoint` and `:token-endpoint`, all served by the auth namespace — the site is its own IndieAuth server ([indieweb.md](indieweb.md) §8). |
| `:micropub-endpoint` | `micropub` | No Micropub client can find the endpoint. |
| `:media-endpoint` | — | Advertised in Micropub's `q=config` rather than in `<head>`. |
| `:websub-hub` | — | Sent as a `Link` header on `/feed`; no hub is pinged on publish. |
| `:bridgy-fed` | `me`, `alternate` (ActivityPub) | The site is not discoverable as a fediverse account. **The bridge must also be enabled once by hand**; see [how-to-deploy.md](how-to-deploy.md). |
| `:sign-in` | — | A map of `:endpoint` — our own `/auth` (the auth namespace). It speaks the same contract IndieLogin.com once did, so one conf key can swap a hosted endpoint back in. Absent ⇒ the whole native-comment flow turns off: no form renders and every flow route answers 400. |

## Environment

The three directories have no default — they are the only keys `dev-conf` and
`prod-conf` must set, and they are what you point at your own content.

| Key | `dev-conf` | `prod-conf` |
|---|---|---|
| `:posts-dir` | `~/Code/simon.grays.blog/posts/` | `/opt/blog/simon.grays.blog/posts/` |
| `:indieweb-dir` | `~/Code/simon.grays.blog/indieweb/` | `/opt/blog/simon.grays.blog/indieweb/` |
| `:db-dir` | `~/Code/simon.grays.blog/db/` | `/opt/blog/simon.grays.blog/db/` |
| `:secrets-file` | `~/Code/simon.grays.blog/secrets.edn` | `/opt/blog/simon.grays.blog/secrets.edn` |
| `:port` | `4567` | `4567` |
| `:development` | `true` | absent |
| `:send-webmentions?` | `false` | `true` |

`:development` loosens the Content-Security-Policy to allow the shadow-cljs websocket
and enables permissive CORS. In production the CSP is `default-src 'self'`, which is
why mention avatars are cached and re-served from `/avatars/` rather than hotlinked.

`:send-webmentions?` makes the watcher notify linked sites and ping the WebSub hub when
a post syncs. It is prod-only on purpose: a source URL on `localhost` is not something
anyone else can fetch. `dev-conf` sets it to `false` explicitly: `start!` merges
dev-conf over prod-conf, and without the override the prod value leaks through.

`:secrets-file` names an EDN map merged onto conf by `start!` — today the `:github`
OAuth app credentials the auth namespace signs visitors in with. It is kept outside the
repo, and an absent file merges nothing. Its shape is in
[reference-files.md](reference-files.md).

See [reference-files.md](reference-files.md) for what lives in each of the three
directories.
