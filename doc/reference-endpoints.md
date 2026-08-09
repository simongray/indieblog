# HTTP endpoints reference

Every route the service answers, as declared in `service.clj`. Handlers live in
`interceptors.clj` under the name given here.

Every page route also answers `HEAD`. Only the status codes that carry meaning are
listed; anything unlisted is an ordinary 200 or 404.

## Content

| Path | Methods | Handler | Notes |
|---|---|---|---|
| `/` | GET, HEAD | `frontpage` | The `.h-feed` of article snippets, with the response strip above it. |
| `/posts/:year/:slug` | GET, HEAD | `single-post` | `:year` is constrained to four digits. Content-negotiated: `text/html` first, `text/markdown` second, so the raw source is available to anyone who asks for it. **410 Gone** for a deleted post, read from the delivery bookkeeping. |
| `/about`, `/now` | GET, HEAD | `standalone-page` | One route generated per `db/page-slugs` entry. Every page renders as plain markdown; the h-card lives in the footer. |
| `/feed` | GET, HEAD | `rss-feed` | RSS of articles only — responses and pages are excluded. Carries the WebSub `Link` headers (`rel="hub"`, `rel="self"`). Every url in an item's HTML is fully qualified (`feed/absolutize`); site-root paths resolve against the site, bare fragments against the item. |
| `/tags` | GET, HEAD | `tag-index` | Every tag in use. |
| `/tags/:tag` | GET, HEAD | `tagged` | An `.h-feed` of that tag's posts. An unused tag **404s**. |
| `/tags/:tag/feed` | GET, HEAD | `tag-feed` | Per-tag RSS, with its own channel title and self URL, and **no** WebSub hub — that is main-feed only. |
| `/sitemap.xml` | GET, HEAD | `sitemap` | Posts and standalone pages. |
| `/sitemap.xsl` | GET, HEAD | `sitemap-xsl` | Its stylesheet; a route of its own only to get the content type right. |

## IndieWeb

| Path | Methods | Handler | Statuses |
|---|---|---|---|
| `/webmention` | POST | `webmention` | **202** accepted (verification is async), **400** `Invalid Webmention` — malformed, `source` == `target`, or a target that is not a post. Content-negotiated: a browser gets a styled page and a 303 back to `#comments`, a `curl` gets the plain body. |
| `/micropub` | GET | `micropub` | Queries `q=config`, `q=source`, `q=category`. **401** no/invalid token, **400** bogus `url`. |
| `/micropub` | POST | `micropub` | **202** + `Location` on create, **204** on update/delete, **401** unauthorized, **403** `insufficient_scope`, **400** `invalid_request`. |
| `/media` | POST | `media` | **201** + `Location`. Multipart parsing is scoped to this route rather than the global stack. Uploads land in the posts `assets/` dir. **400** non-image or missing `file`, **403** missing `media`/`create` scope. |
| `/sign-in` | POST | `sign-in` | **303** to the sign-in endpoint, **400** on a private `me` URL or an unknown post. |
| `/sign-in/callback` | GET | `sign-in-callback` | **400** on a bad or expired `state` (10 minutes). |
| `/comments` | POST | `post-comment` | **303** back to the post's `#comments`, **400** on a bad or expired token (30 minutes, and voided by a restart). |

All six answer 400 whole when the `:sign-in` / Micropub conf keys are absent — see
[reference-conf.md](reference-conf.md).

## Well-known URIs (RFC 8615)

| Path | Methods | Handler | Notes |
|---|---|---|---|
| `/.well-known/webfinger` | GET | `bridgy-fed-redirect` | **302** to `fed.brid.gy`, query string intact. This is what gives the site its `@domain@domain` fediverse handle. |
| `/.well-known/host-meta` | GET | `bridgy-fed-redirect` | As above. |
| `/.well-known/api-catalog` | GET, HEAD | `api-catalog` | `application/linkset+json`, listing the webmention, micropub, media and feed endpoints. |
| `/.well-known/security.txt` | GET, HEAD | `security-txt` | `Contact`, `Expires`, `Preferred-Languages`, `Canonical`. |

## Static files

| Prefix | Served from | Notes |
|---|---|---|
| `/assets/` | `<posts-dir>/assets/` | Post images and any other static asset, straight off disk — no copying step, nothing in the db. Uncached: the watcher changes files under a running server, and a frozen `Content-Length` means truncated responses. |
| `/avatars/` | `<indieweb-dir>/avatars/` | Cached avatars of people who mention us. Rooted at exactly this subdirectory, so the rest of the indieweb dir stays unreachable. Serving them ourselves is what keeps the CSP at `default-src 'self'`. |
| `/` | classpath `public/` | CSS and other app resources. Cached except in `:development`. |
