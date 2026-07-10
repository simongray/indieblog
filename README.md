````bash
cp system/blog.service /etc/systemd/system/blog.service
systemctl enable blog
systemctl start blog
````

[Validate my RSS feed](https://validator.w3.org/feed/check.cgi?url=https%3A%2F%2Fsimon.grays.blog%2Ffeed).

Local development
-----------------
The blog posts are sourced from /Users/simongray/Code/simon.gSUrays.blog and the db is located there too.

Images and assets
-----------------
Image files (and any other static assets) for posts live in the `assets`
subdirectory of the posts directory, e.g. `.../simon.grays.blog/posts/assets/`.
They are served directly from disk under the `/assets/` URL prefix — there is no
copying step and they are not stored in the database.

To embed an image in a post, reference it with an **absolute** `/assets/` path:

````markdown
![Alt text](/assets/my-photo.png)
````

A bare or relative reference such as `![…](my-photo.png)` will **not** work: on a
post page (`/posts/YYYY/slug`) the browser resolves it against the post URL as
`/posts/YYYY/my-photo.png`, not `/assets/`. Always start the path with
`/assets/`.

Clojure-mcp
-----------
I've added experimental support for [clojure-mcp](https://github.com/bhauman/clojure-mcp) through Claude, which is an MCP server for AI-assisted Clojure development. The project hasn't been developed with AI at all, but future changes may be AI-assisted.

See my personal [mcp-stuff repo](https://github.com/simongray/mcp-stuff) for documentation. The current versions of `LLM_CODE_STYLE.md` should also be located in that repo as well as my most recent personal `config.edn` for clojure-mcp projects.

### REPL
When integrating with clojure-mcp, an external nREPL on localhost:7888 should be used:

```shell
clojure -M:nrepl
```

You can then use e.g. IntelliJ IDEA to connect to this REPL and share it with the LLM.
