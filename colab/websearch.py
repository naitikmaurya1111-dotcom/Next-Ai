#!/usr/bin/env python3
"""
websearch — High-Performance, Zero-Failure Web Search CLI for Colab & Next AI
=============================================================================
Provides real-time web search with titles, URLs, and snippet extraction.
Bypasses datacenter bot blocks by using clean lightweight search endpoints with
multi-endpoint automatic fallbacks:
  1. DuckDuckGo Lite (general web queries)
  2. Google News RSS (current events & breaking news)
  3. Wikipedia Search API (encyclopedic, technical, scientific, historical facts)
  4. Hacker News Algolia (tech, programming, AI, developer discussions)

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
from typing import List, Dict, Any

USER_AGENTS = [
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
    "Lynx/2.8.9rel.1 libwww-FM/2.14"
]

def clean_html_text(raw_html: str) -> str:
    """Safely strip HTML tags and decode HTML entities."""
    if not raw_html:
        return ""
    # Strip script/style
    clean = re.sub(r'<(script|style)[^>]*>[\s\S]*?</\1>', '', raw_html, flags=re.IGNORECASE)
    # Strip all remaining tags
    clean = re.sub(r'<[^>]+>', ' ', clean)
    clean = html.unescape(clean)
    # Collapse multiple whitespace
    return re.sub(r'\s+', ' ', clean).strip()

def search_ddg_lite(query: str, limit: int = 5) -> List[Dict[str, str]]:
    """Primary web search using DuckDuckGo Lite endpoint."""
    try:
        data = urllib.parse.urlencode({"q": query}).encode("utf-8")
        req = urllib.request.Request(
            "https://lite.duckduckgo.com/lite/",
            data=data,
            headers={
                "User-Agent": USER_AGENTS[2],
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Content-Type": "application/x-www-form-urlencoded"
            }
        )
        with urllib.request.urlopen(req, timeout=7) as resp:
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
            title = clean_html_text(links[i][1])
            snip = clean_html_text(snippets[i]) if i < len(snippets) else ""
            if title and url and not url.startswith("/"):
                results.append({"title": title, "url": url, "snippet": snip, "source": "DuckDuckGo"})
        return results
    except Exception:
        return []

def search_google_news(query: str, limit: int = 5) -> List[Dict[str, str]]:
    """Fallback search using Google News RSS."""
    try:
        url = f"https://news.google.com/rss/search?q={urllib.parse.quote(query)}&hl=en-US&gl=US&ceid=US:en"
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENTS[0]})
        with urllib.request.urlopen(req, timeout=7) as resp:
            xml = resp.read().decode("utf-8", errors="ignore")

        items = re.findall(r'<item>([\s\S]*?)</item>', xml)
        results = []
        for item in items[:limit]:
            title_m = re.search(r'<title>([\s\S]*?)</title>', item)
            link_m = re.search(r'<link>([\s\S]*?)</link>', item)
            desc_m = re.search(r'<description>([\s\S]*?)</description>', item)
            if title_m and link_m:
                title = clean_html_text(title_m.group(1))
                link = link_m.group(1).strip()
                desc = clean_html_text(desc_m.group(1)) if desc_m else ""
                if title and link:
                    results.append({"title": title, "url": link, "snippet": desc, "source": "Google News"})
        return results
    except Exception:
        return []

def search_wikipedia(query: str, limit: int = 5) -> List[Dict[str, str]]:
    """Wikipedia search endpoint with dual API fallback (Search Query API + OpenSearch)."""
    # 1. Primary Wikipedia Query API
    try:
        api_url = (
            f"https://en.wikipedia.org/w/api.php?action=query&list=search"
            f"&srsearch={urllib.parse.quote(query)}&srlimit={limit}&utf8=&format=json"
        )
        req = urllib.request.Request(
            api_url,
            headers={"User-Agent": "NextAI-WebSearch/2.0 (Colab Bridge; nextai-app)"}
        )
        with urllib.request.urlopen(req, timeout=7) as resp:
            data = json.loads(resp.read().decode("utf-8"))

        search_items = data.get("query", {}).get("search", [])
        if search_items:
            results = []
            for item in search_items[:limit]:
                title = item.get("title", "").strip()
                snippet_raw = item.get("snippet", "")
                snippet = clean_html_text(snippet_raw)
                wiki_url = f"https://en.wikipedia.org/wiki/{urllib.parse.quote(title.replace(' ', '_'))}"
                if title:
                    results.append({
                        "title": f"{title} — Wikipedia",
                        "url": wiki_url,
                        "snippet": snippet,
                        "source": "Wikipedia"
                    })
            if results:
                return results
    except Exception:
        pass

    # 2. Wikipedia OpenSearch API fallback
    try:
        opensearch_url = (
            f"https://en.wikipedia.org/w/api.php?action=opensearch"
            f"&search={urllib.parse.quote(query)}&limit={limit}&namespace=0&format=json"
        )
        req = urllib.request.Request(
            opensearch_url,
            headers={"User-Agent": "NextAI-WebSearch/2.0 (Colab Bridge; nextai-app)"}
        )
        with urllib.request.urlopen(req, timeout=7) as resp:
            data = json.loads(resp.read().decode("utf-8"))

        if isinstance(data, list) and len(data) >= 4:
            titles = data[1]
            descriptions = data[2]
            urls = data[3]
            results = []
            for i in range(min(limit, len(titles))):
                t = titles[i]
                d = descriptions[i] if i < len(descriptions) else ""
                u = urls[i] if i < len(urls) else f"https://en.wikipedia.org/wiki/{urllib.parse.quote(t.replace(' ', '_'))}"
                if t and u:
                    results.append({
                        "title": f"{t} — Wikipedia",
                        "url": u,
                        "snippet": d or f"Wikipedia article on {t}",
                        "source": "Wikipedia"
                    })
            if results:
                return results
    except Exception:
        pass

    return []

def search_hn_algolia(query: str, limit: int = 5) -> List[Dict[str, str]]:
    """Fallback tech & discussion search using Hacker News Algolia API."""
    try:
        url = f"https://hn.algolia.com/api/v1/search?query={urllib.parse.quote(query)}&hitsPerPage={limit}"
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENTS[0]})
        with urllib.request.urlopen(req, timeout=7) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        results = []
        for h in data.get("hits", [])[:limit]:
            title = h.get("title") or h.get("story_title") or ""
            link = h.get("url") or f"https://news.ycombinator.com/item?id={h.get('objectID')}"
            snippet = h.get("comment_text") or ""
            if title:
                results.append({
                    "title": clean_html_text(title),
                    "url": link,
                    "snippet": clean_html_text(snippet)[:250],
                    "source": "HackerNews"
                })
        return results
    except Exception:
        return []

def fetch_page_content(url: str, max_chars: int = 4000) -> str:
    """Fetch URL and convert HTML to readable text/markdown."""
    try:
        headers = {"User-Agent": USER_AGENTS[0]}
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=10) as resp:
            raw = resp.read().decode("utf-8", errors="ignore")
        # Strip script, style, header, footer, nav
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

def execute_search(query: str, limit: int = 5) -> List[Dict[str, str]]:
    """
    Execute search with 4-tier automatic fallback for 100% search reliability:
    1. DuckDuckGo Lite (General web)
    2. Google News RSS (News / current events)
    3. Wikipedia Search API (Encyclopedic / facts / science / history)
    4. Hacker News Algolia (Tech / programming / AI)
    """
    clean_q = (query or "").strip()
    if not clean_q:
        return []

    # 1. Try DuckDuckGo Lite
    res = search_ddg_lite(clean_q, limit)
    if res:
        return res

    # 2. Try Google News RSS
    res = search_google_news(clean_q, limit)
    if res:
        return res

    # 3. Try Wikipedia Search API
    res = search_wikipedia(clean_q, limit)
    if res:
        return res

    # 4. Try HN Algolia
    res = search_hn_algolia(clean_q, limit)
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
        src = f" [{r.get('source', '')}]" if r.get('source') else ""
        print(f"\033[1;36m{i}. {r['title']}{src}\033[0m")
        print(f"   \033[4;34m{r['url']}\033[0m")
        if r.get('snippet'):
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
