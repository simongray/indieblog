# Plan: self-hosted Web sign-in and IndieAuth

The blog delegates two kinds of authentication to third parties, and both
delegations have run out of road (see [indieweb.md](indieweb.md) §8a):

- **Web sign-in for visitors** was delegated to IndieLogin.com, which no
  longer takes new users, so visitors cannot sign in to comment.
- **IndieAuth for Micropub** is delegated to indieauth.com, which is
  deprecated with no successor.

The end state is *your domain is your identity provider*: one auth endpoint on
the blog that authenticates anyone as their URL — via their own IndieAuth
server if they run one, via RelMeAuth (GitHub, the fediverse) if not. Visitors
use it to sign in and comment. Micropub clients use it to authenticate the
author, who signs in through the same flow. No passwords anywhere, and both
delegations retired.

## Integrated, not a separate service

IndieAuth discovery is per-domain: the authorization server for this blog is
whatever this blog's own HTML says it is, so the endpoint lives on the blog.
Two disciplines keep that honest:

- It is a real HTTP endpoint speaking the same contract IndieLogin spoke, so
  the signin namespace (its client) is unchanged; only `:sign-in :endpoint`
  in conf moves. External Micropub clients must speak HTTP to it in phase 3
  anyway.
- It gets its own namespace under `indieweb/` with plain-data boundaries,
  like signin and micropub, so it could be extracted if that ever mattered.

## Phase 1 — endpoint shell + GitHub RelMeAuth *(done)*

The `auth` namespace serves:

    GET  /auth?me=&client_id=&redirect_uri=&state=   begin authentication
    GET  /auth/callback/github?code=&state=          complete the GitHub half
    POST /auth {code, client_id, redirect_uri}       redeem code -> {"me": ...}

The visitor's claimed `me` is canonicalized and fetched (behind the SSRF
guard), their homepage's rel=me links are read (`html/rel-hrefs`), and a GitHub
profile among them starts the OAuth round trip. Verification is bidirectional:
the homepage links the profile *and* the profile's website field links back.
Continuity is the signin namespace's HMAC tokens — the provider state and the
authorization code are both signed maps, so the endpoint keeps no sessions.
Codes are single-use per boot. Only the blog itself is accepted as a client;
spec client verification arrives with external clients in phase 3.

Secrets (the GitHub OAuth app's credentials) live in an EDN file outside the
repo, named by `:secrets-file` in conf and merged in `service/start!`:

    {:github {:client-id "..." :client-secret "..."}}

Operational prerequisites: register the GitHub OAuth app (callback URL
`https://simon.grays.blog/auth/callback/github`) and write the secrets file
on the server. GitHub pins the callback URL per app, so dev testing needs a
second throwaway app or happens against prod.

## Phase 2 — IndieAuth delegation *(done)*

In `/auth` discovery, the visitor's own IndieAuth server wins over rel=me.
Discovery reads `rel=indieauth-metadata`, falls back to the legacy
`rel=authorization_endpoint`, and checks the Link header before the HTML
either way. A visitor who runs a server authenticates there, with us as a
spec-current client: PKCE (S256), the RFC 9207 `iss` check, code exchange
with the verifier, and the spec's rules for the returned `me` (a URL other
than the claimed one is accepted only if its page advertises the same
authorization endpoint). The endpoint keeps no state for this either:
the PKCE verifier is re-derived as the HMAC signature of the state token
(`signin/signature`) instead of being stored. With this the endpoint is a
full IndieLogin replacement.

## Phase 3 — self-hosted IndieAuth server (§8a's exit) *(done)*

The endpoint grows the server role for external clients:

- Serve the IndieAuth metadata document (its `issuer` must be a URL prefix of
  the metadata URL — trivially true self-hosted, which is exactly why
  delegation could not do it), and swap the deprecated
  `rel=authorization_endpoint`/`token_endpoint` links in `component/head` for
  `rel=indieauth-metadata`, keeping the legacy pair for older clients.
- Authorization: an external client (e.g. Quill) sends the author here to
  authenticate as the site's own URL — which routes through the same phase-1
  flow — then a consent page (client + requested scopes) and a code, now
  verifying the client's PKCE challenge and validating `redirect_uri` against
  the fetched `client_id` page per spec.
- A token endpoint issuing bearer tokens recorded in a `tokens.edn` in the
  indieweb-dir: revocation is editing the file, like moderation. Scopes are
  only granted when `me` is the site's own URL; a visitor authenticates as an
  identity, full stop.
- `micropub/authorize` verifies tokens with a local lookup
  (`indieweb/find-token`; tokens are stored hashed), and conf's `:indieauth`
  keys now name our own endpoints. No introspection endpoint is served: the
  authorization server and the only resource server share a process, so
  verification never crosses HTTP. The sign-in role gained a guard for the
  new situation: the owner's own homepage now advertises this very endpoint,
  which must not be delegated back to, so their sign-in falls through to the
  rel=me providers.

## Phase 4 — broaden RelMeAuth *(Mastodon done; email open)*

- **Fediverse/Mastodon** *(done)*: per-instance dynamic client registration
  (`POST /api/v1/apps`), credentials cached in the indieweb-dir's
  `oauth-clients.edn`, verification bidirectional as with GitHub — the
  account must be the profile the homepage's rel=me named, and its bio or
  profile fields must link back. The rel=me providers are tried in the order
  the homepage lists them. A non-Mastodon site with an `/@user` URL fails
  registration, and the next rel=me is tried. Verified end to end in
  production via the provider chooser.
- **Provider chooser** *(done)*: a homepage that offers several ways to
  authenticate gets a choice page, so the rel=me order does not decide
  silently — the visitor's own IndieAuth server first, then each provider as
  the page lists them. A single option skips the page, so the common case
  keeps its zero-click feel. The choice links restart `/auth` with a
  `provider=` pin, stateless as ever. The owner sees the chooser too, both
  when signing in to comment and on the consent leg for external clients.
- **Email magic links**: optional, and the only piece that needs new
  infrastructure (outbound mail). Decide whether it is worth the dependency.

## Cross-cutting

Each phase updates the docs it touches (indieweb.md §8/8a, comments.md, the
reference docs). Discovery and canonicalization are pure functions pinned by
tests from strings, the html_test way; the OAuth round trips are verified by
REPL-driven walkthroughs against the real providers. SSRF guards already wrap
every fetch; open redirects are closed by binding `redirect_uri` into the
signed code (phase 1) and by spec client verification (phase 3). The html
namespace under webmention/ now also serves sign-in discovery; if it grows, a
rename out of webmention/ can follow.
