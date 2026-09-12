#!/usr/bin/env python3
"""
websearch — High-Performance, Zero-Failure Web Search CLI for Colab & Next AI
=============================================================================
Provides real-time web search with titles, URLs, and snippet extraction.
Bypasses datacenter bot blocks by using clean lightweight search endpoints with fallbacks.

Usage:
  websearch "search query"
  websearch "search query" --limit 5
  websearch "search query" --json
  websearch "search query" --fetch 1   (fetches text content of result #1)
"""

import sys
import os
import re
import json
import html
import argparse
import urllib.request
import urllib.parse

USER_AGENT = "Lynx/2.8.9rel.1 libwww-FM/2.14"

def search_ddg_lite(query: str, limit: int = 5):
    """Primary web search using DuckDuckGo Lite endpoint."""
    try:
        data = urllib.parse.urlencode({"q": query}).encode("utf-8")
        req = urllib.request.Request(
            "https://lite.duckduckgo.com/lite/",
            data=data,
            headers={"User-Agent": USER_AGENT}
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            page = resp.read().decode("utf-8", errors="ignore")

        links = re.findall(r'<a[^>]*href=[\'\"]([^\'\"]+)[\'\"][^>]*class=[\'\"]result-link[\'\"][^>]*>([\s\S]*?)</a>', page)
        snippets = re.findall(r'<td[^>]*class=[\'\"]result-snippet[\'\"][^>]*>([\s\S]*?)</td>', page)

        results = []
        for i in range(min(limit, len(links))):
            raw_url = links[i][0]
            if "uddg=" in raw_url:
                url = urllib.parse.unquote(raw_url.split("uddg=")[1].split("&")[0])
            else:
                url = raw_url
            title = html.unescape(re.sub(r'<[^>]+>', '', links[i][1]).strip())
            snip = html.unescape(re.sub(r'<[^>]+>', '', snippets[i]).strip()) if i < len(snippets) else ""
            if title and url:
                results.append({"title": title, "url": url, "snippet": snip})
        return results
    except Exception:
        return []

def search_google_news(query: str, limit: int = 5):
    """Fallback search using Google News RSS."""
    try:
        url = f"https://news.google.com/rss/search?q={urllib.parse.quote(query)}&hl=en-US&gl=US&ceid=US:en"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=8) as resp:
            xml = resp.read().decode("utf-8", errors="ignore")

        items = re.findall(r'<item>([\s\S]*?)</item>', xml)
        results = []
        for item in items[:limit]:
            title_m = re.search(r'<title>([\s\S]*?)</title>', item)
            link_m = re.search(r'<link>([\s\S]*?)</link>', item)
            desc_m = re.search(r'<description>([\s\S]*?)</description>', item)
            if title_m and link_m:
                title = html.unescape(re.sub(r'<[^>]+>', '', title_m.group(1)).strip())
                link = link_m.group(1).strip()
                desc = html.unescape(re.sub(r'<[^>]+>', '', desc_m.group(1)).strip()) if desc_m else ""
                results.append({"title": title, "url": link, "snippet": desc})
        return results
    except Exception:
        return []

def search_hn_algolia(query: str, limit: int = 5):
    """Fallback tech search using Hacker News Algolia API."""
    try:
        url = f"https://hn.algolia.com/api/v1/search?query={urllib.parse.quote(query)}&hitsPerPage={limit}"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=8) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        results = []
        for h in data.get("hits", [])[:limit]:
            title = h.get("title") or h.get("story_title") or ""
            link = h.get("url") or f"https://news.ycombinator.com/item?id={h.get('objectID')}"
            snippet = h.get("comment_text") or ""
            if title:
                results.append({"title": title, "url": link, "snippet": html.unescape(re.sub(r'<[^>]+>', '', snippet)[:200])})
        return results
    except Exception:
        return []

def fetch_page_content(url: str, max_chars: int = 4000) -> str:
    """Fetch URL and convert HTML to readable text/markdown."""
    try:
        headers = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=10) as resp:
            content_type = resp.headers.get("Content-Type", "")
            raw = resp.read().decode("utf-8", errors="ignore")
        # Strip script, style, head
        clean = re.sub(r'<(script|style|header|footer|nav)[^>]*>[\s\S]*?</\1>', '', raw, flags=re.IGNORECASE)
        # Convert links to markdown
        clean = re.sub(r'<a[^>]*href=[\'"]([^\'"]+)[\'"][^>]*>([\s\S]*?)</a>', r'[\2](\1)', clean)
        # Strip other HTML tags
        text = re.sub(r'<[^>]+>', ' ', clean)
        text = html.unescape(text)
        text = re.sub(r'\s+', ' ', text).strip()
        return text[:max_chars]
    except Exception as e:
        return f"[Failed to fetch content from {url}: {e}]"

def execute_search(query: str, limit: int = 5):
    """Execute search with automatic fallbacks."""
    # 1. Try DuckDuckGo Lite
    res = search_ddg_lite(query, limit)
    if res:
        return res
    # 2. Try Google News RSS
    res = search_google_news(query, limit)
    if res:
        return res
    # 3. Try HN Algolia
    res = search_hn_algolia(query, limit)
    return res

def main():
    parser = argparse.ArgumentParser(description="Next AI Fast Web Search CLI")
    parser.add_argument("query", help="Search query string")
    parser.add_argument("--limit", "-n", type=int, default=5, help="Maximum number of results")
    parser.add_argument("--json", action="store_true", help="Output results as JSON")
    parser.add_argument("--fetch", type=int, default=0, help="Fetch page content of Nth result (1-indexed)")

    args = parser.parse_args()

    results = execute_search(args.query, args.limit)

    if not results:
        if args.json:
            print(json.dumps({"query": args.query, "results": []}))
        else:
            print(f"No results found for: {args.query}")
        sys.exit(0)

    if args.json:
        if args.fetch and 1 <= args.fetch <= len(results):
            target = results[args.fetch - 1]
            target["page_content"] = fetch_page_content(target["url"])
        print(json.dumps({"query": args.query, "count": len(results), "results": results}, indent=2))
        return

    print(f"\n🌐 Web Search Results for: \"{args.query}\" ({len(results)} found)")
    print("=" * 65)
    for i, r in enumerate(results, 1):
        print(f"\033[1;36m{i}. {r['title']}\033[0m")
        print(f"   \033[4;34m{r['url']}\033[0m")
        if r['snippet']:
            print(f"   {r['snippet']}")
        print()

    if args.fetch and 1 <= args.fetch <= len(results):
        target = results[args.fetch - 1]
        print("=" * 65)
        print(f"📄 Full Page Content: {target['title']} ({target['url']})")
        print("=" * 65)
        content = fetch_page_content(target["url"])
        print(content)
        print("=" * 65)

if __name__ == "__main__":
    main()
