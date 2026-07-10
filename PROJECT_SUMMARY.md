# Simon Gray's Indie Blog - Project Summary

## Overview

A personal blog application built with Clojure and modern web technologies. The system automatically processes Markdown files with YAML frontmatter into a graph database, serves HTML content via Pedestal web framework, and provides RSS feeds. The architecture emphasizes file-based content management with automatic synchronization and real-time updates during development.

**Key Features:**
- File-based content management with automatic directory watching
- Markdown processing with YAML frontmatter support
- Graph database storage using Asami
- RSS/Atom feed generation
- Server-side rendering with Replicant
- Cross-platform code sharing between CLJ/CLJS
- Development-friendly REPL workflow

## Architecture Overview

```
Posts Directory (Markdown files)
    ↓ (File watcher + Content processor)
Asami Graph Database
    ↓ (Query layer)
Pedestal Web Service
    ↓ (Replicant)
HTML Pages + RSS Feeds
```

The system follows a unidirectional data flow: file changes trigger database updates, which are then served through web endpoints using Replicant to render hiccup to HTML.

## Key File Paths

### Core Application Files
- **`src/blog/grays/web/service.clj`** - Main web service entry point with Pedestal configuration, routing, and server lifecycle management
- **`src/blog/grays/web/db.clj`** - Database layer using Asami graph database with file watching and entity management
- **`src/blog/grays/web/content.clj`** - Content processing pipeline for Markdown files with YAML frontmatter
- **`src/blog/grays/web/interceptors.clj`** - Pedestal interceptors for request handling (frontpage, single posts, feeds)
- **`src/blog/grays/web/component.cljc`** - Functions for HTML generation (shared CLJ/CLJS code)
- **`src/blog/grays/web/shared.cljc`** - Utility functions shared between client and server
- **`src/blog/grays/web/feed.clj`** - RSS/Atom feed generation using clj-rss

### Configuration Files
- **`deps.edn`** - Project dependencies and development aliases
- **`LLM_CODE_STYLE.md`** - Coding style guide for AI-assisted development

## Dependencies and Their Roles

### Core Web Stack
- **`io.pedestal/pedestal.service` v0.7.2** - Web framework providing interceptor-based request handling
- **`io.pedestal/pedestal.jetty` v0.7.2** - Jetty adapter for HTTP server
- **`no.cjohansen/replicant` v2026.06.2** - Data-driven hiccup rendering library, used for server-side HTML generation

### Content Processing
- **`io.github.nextjournal/markdown` v0.6.157** - Markdown parsing with extensible transformations
- **`dk.cst/hiccup-tools`** - Hiccup manipulation utilities for HTML processing
- **`com.github.rawleyfowler/sluj` v1.0.2** - YAML frontmatter parsing

### Database and Storage
- **`org.clojars.quoll/asami` v2.3.4** - Graph database for content storage and querying
- **`com.nextjournal/beholder` v1.0.2** - File system watching for automatic content updates

### Feed Generation and Utilities
- **`clj-rss/clj-rss` v0.4.0** - RSS/Atom feed generation
- **`tick/tick` v1.0** - Date/time handling library

### Development Tools
- **`org.slf4j/slf4j-simple` v2.0.17** - Logging implementation
- **`nrepl/nrepl` v1.3.1** - REPL server for development (alias :nrepl)

## Available Tools and APIs

### Development Workflow
```clojure
;; Start development server with REPL
clojure -M:nrepl                    ; Start REPL on port 7888
(blog.grays.web.service/restart!)   ; Restart web server

;; Production deployment
clojure -M:server                   ; Start production server
```

### Content Source Configuration
- **Development Posts**: Sourced from `/Users/simongray/Code/simon.grays.blog/posts/`
- **Development Database**: Located at `/Users/simongray/Code/simon.grays.blog/db/`
- **Production Posts**: Located at `/opt/blog/simon.grays.blog/posts/`
- **Production Database**: Located at `/opt/blog/simon.grays.blog/db/`

### Database Operations
```clojure
(require '[blog.grays.web.db :as db])

;; Core database functions
(db/pconn db-dir)                   ; Get persistent connection
(db/latest-posts conn)              ; Query recent posts
(db/single-post conn year slug)     ; Get specific post
(db/retract-entity! conn ident)     ; Remove entity from database
```

### Content Processing
```clojure
(require '[blog.grays.web.content :as content])

;; File processing pipeline
(content/md-dossier dir)            ; Process all Markdown files in directory
(content/expand-post post)          ; Expand post with derived metadata
(content/sort-posts posts)          ; Sort posts by date
```

### Component Rendering
```clojure
(require '[blog.grays.web.component :as c])

;; HTML generation
(c/html-page main conf)             ; Generate complete HTML page
(c/article-elem post conf)          ; Render single article
(c/article-elems posts conf)        ; Render multiple articles
```

## Implementation Patterns

### File-Based Content Management
- **Markdown + YAML Frontmatter**: Posts stored as `.md` files with metadata in YAML headers
- **Automatic Synchronization**: File watcher monitors posts directory for changes
- **Entity Lifecycle**: Files map to database entities with create/update/delete operations

### Graph Database Schema
- **Post Entities**: Core content with `:post/title`, `:post/slug`, `:post/date`, `:post/content`
- **Derived Attributes**: Computed fields like `:post/year`, `:post/hiccup`, `:post/derived`
- **File Metadata**: Tracking file paths and modification times for sync

### Web Service Architecture
- **Interceptor Pipeline**: Pedestal interceptors for request processing
- **Route Structure**: RESTful URLs with year/slug pattern for posts
- **Content Security Policy**: Configurable CSP headers for development vs production

### Component System
- **Server-Side Rendering**: Replicant renders hiccup to HTML strings
- **Cross-Platform Components**: Shared `.cljc` files work in both CLJ and CLJS
- **Hiccup Integration**: Seamless conversion between Markdown and Hiccup data structures

## Development Workflow

### REPL-Driven Development
1. **Start REPL**: `clojure -M:nrepl` (connects on port 7888)
2. **Connect IDE**: Use IntelliJ IDEA or other editor to connect to REPL
3. **Live Reloading**: Use `(restart!)` for server changes
4. **Content Testing**: Rich comment blocks in each namespace for interactive development

### Content Creation Workflow
1. **Create Markdown File**: Add `.md` file to `/Users/simongray/Code/simon.grays.blog/posts/` directory with YAML frontmatter
2. **Automatic Processing**: File watcher detects changes and updates database
3. **Live Preview**: Changes appear immediately in development server
4. **Validation**: Built-in checks for required frontmatter fields

### AI-Assisted Development
- **Clojure-MCP Integration**: Experimental support for AI-assisted development via [clojure-mcp](https://github.com/bhauman/clojure-mcp)
- **Style Guide**: Comprehensive coding standards in `LLM_CODE_STYLE.md`
- **External REPL**: Shared REPL connection between IDE and AI tools on localhost:7888
- **Documentation**: See [mcp-stuff repo](https://github.com/simongray/mcp-stuff) for configuration details

### Local Development Setup
- **Posts Directory**: `/Users/simongray/Code/simon.grays.blog/posts/`
- **Database Directory**: `/Users/simongray/Code/simon.grays.blog/db/`
- **RSS Feed Validation**: [W3C Feed Validator](https://validator.w3.org/feed/check.cgi?url=https%3A%2F%2Fsimon.grays.blog%2Ffeed)

## Extension Points

### Content Types
- **Current**: Markdown posts with YAML frontmatter
- **Extension**: Add support for other content types (images, videos, external links)
- **Location**: Extend `blog.grays.web.content` namespace

### Database Schema
- **Current**: Simple post entities with basic metadata
- **Extension**: Add tags, categories, related posts, comments
- **Location**: Modify entity structure in `blog.grays.web.db`

### Web Features
- **Current**: Basic blog with RSS feeds
- **Extension**: Search, pagination, tag browsing, comments
- **Location**: Add new interceptors and routes in respective namespaces

### Templating and Themes
- **Current**: Single theme rendered with Replicant
- **Extension**: Multiple themes, customizable styling, dark mode
- **Location**: Extend `blog.grays.web.component` with theme system

### Feed Formats
- **Current**: RSS/Atom feeds
- **Extension**: JSON Feed, webhook notifications, social media integration
- **Location**: Extend `blog.grays.web.feed` namespace

## Configuration and Deployment

### Environment Configuration
- **Development**: Local paths, CORS enabled, unsafe CSP for live reloading
- **Production**: Remote paths, strict CSP, systemd service integration
- **Configuration**: Centralized in `blog.grays.web.service/conf`

### Deployment Options
- **Systemd Service**: Includes service file for Linux deployment
- **Standalone JAR**: Can be built with `:build` alias
- **Development Server**: Hot-reloading server for local development

### External Dependencies
- **Posts Directory**: 
  - Development: `/Users/simongray/Code/simon.grays.blog/posts/`
  - Production: `/opt/blog/simon.grays.blog/posts/`
- **Database Directory**: 
  - Development: `/Users/simongray/Code/simon.grays.blog/db/`
  - Production: `/opt/blog/simon.grays.blog/db/`
- **Static Assets**: App resources (CSS, etc.) served from `resources/public`; post
  images/assets served directly from `<posts-dir>/assets/` under the `/assets/` URL
  prefix. Embed images in Markdown with an absolute path, e.g. `![alt](/assets/foo.png)`.

## Notable Design Decisions

### Technology Choices
- **Asami over SQL**: Graph database provides flexible schema evolution
- **Pedestal over Ring**: Interceptor model enables composable request processing
- **Replicant over Rum/Reagent**: Data-driven rendering, pure functions from data to hiccup, no React dependency
- **File-based over CMS**: Direct file editing with Git version control

### Code Organization
- **Cross-platform Components**: Maximizes code reuse between CLJ/CLJS
- **Namespace Separation**: Clear boundaries between concerns (db, content, web, feed)
- **Configuration Injection**: Runtime configuration passed through interceptor chain

This summary provides the foundation for understanding and extending the indie blog system. The codebase follows modern Clojure practices with emphasis on simplicity, composability, and developer experience.
