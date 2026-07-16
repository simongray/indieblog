<?xml version="1.0" encoding="UTF-8"?>
<!-- Renders /sitemap.xml as a readable HTML page for humans; crawlers ignore
     the stylesheet and parse the underlying XML. No inline styles: the prod
     CSP is default-src 'self', so the page links the site's own main.css. -->
<xsl:stylesheet version="1.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:s="http://www.sitemaps.org/schemas/sitemap/0.9">
  <xsl:output method="html" encoding="UTF-8" indent="yes"/>
  <xsl:template match="/">
    <html lang="en">
      <head>
        <title>Sitemap</title>
        <meta name="viewport" content="width=device-width, initial-scale=1"/>
        <link rel="stylesheet" href="/css/main.css"/>
      </head>
      <body>
        <main>
          <h1>Sitemap</h1>
          <p>
            Every canonical URL of this site
            (<xsl:value-of select="count(s:urlset/s:url)"/> in total), as told
            to search engines; you are seeing the machine-readable
            <code>/sitemap.xml</code> rendered by <code>/sitemap.xsl</code>.
          </p>
          <table>
            <tr>
              <th>URL</th>
              <th>Last modified</th>
            </tr>
            <xsl:for-each select="s:urlset/s:url">
              <tr>
                <td>
                  <a href="{s:loc}"><xsl:value-of select="s:loc"/></a>
                </td>
                <td>
                  <xsl:value-of select="s:lastmod"/>
                </td>
              </tr>
            </xsl:for-each>
          </table>
        </main>
      </body>
    </html>
  </xsl:template>
</xsl:stylesheet>
