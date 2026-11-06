#!/usr/bin/env python3
"""Search a small live candidate sample in the three enabled sources.

Only public titles, match counts and sanitized errors are reported. A missing exact
or containment match is not asserted to prove franchise-level uniqueness.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import time
import unicodedata
from pathlib import Path
from typing import Any
from urllib.parse import urlencode

import requests
from bs4 import BeautifulSoup
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
TIMEOUT = 35
MANGACIX_KEY = b"i4C7R2fXGocdYgFLzCbDlsJjukf8G58b"


class SearchFailure(Exception):
    pass


def normalize_title(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).casefold().replace("ı", "i")
    return "".join(character for character in value if character.isalnum())


def title_matches(query: str, result: dict[str, Any]) -> bool:
    needle = normalize_title(query)
    if len(needle) < 3:
        return False
    names = [result.get("title", ""), *result.get("aliases", [])]
    for name in names:
        if not isinstance(name, str):
            continue
        candidate = normalize_title(name)
        if candidate == needle:
            return True
        if min(len(candidate), len(needle)) >= 8 and (needle in candidate or candidate in needle):
            return True
    return False


def response_for(session: requests.Session, url: str, **kwargs: Any) -> requests.Response:
    try:
        response = session.get(url, timeout=TIMEOUT, **kwargs)
    except requests.RequestException as exc:
        raise SearchFailure(f"network={type(exc).__name__}") from None
    if response.status_code != 200:
        raise SearchFailure(f"http={response.status_code}")
    return response


def search_mangatr(session: requests.Session, title: str) -> list[dict[str, Any]]:
    query = urlencode({"view": "sayfalama", "icerik": title, "page": 1})
    response = response_for(
        session, f"https://manga-tr.com/arama.html?{query}",
        headers={"Referer": "https://manga-tr.com/", "Origin": "https://manga-tr.com"},
    )
    document = BeautifulSoup(response.text, "html.parser")
    links = document.select("a.arama-card__title")
    if not links:
        links = document.select("div.row a[data-toggle]")
    if not links:
        links = document.select("a[href*='/manga-'][href$='.html']")
    results = []
    seen = set()
    for link in links:
        name = link.get_text(" ", strip=True) or link.get("title", "").strip()
        normalized = normalize_title(name)
        if len(normalized) < 2 or normalized in seen:
            continue
        seen.add(normalized)
        results.append({"title": name, "aliases": []})
    return results[:20]


def signed_mangacix_url(path: str, parameters: list[tuple[str, Any]]) -> tuple[str, dict[str, str]]:
    query = urlencode(parameters)
    iv = os.urandom(12)
    encrypted = AESGCM(MANGACIX_KEY).encrypt(iv, b"{version}" + query.encode(), None)
    token = f"{base64.b64encode(encrypted).decode()}.{base64.b64encode(iv).decode()}"
    return (
        f"https://mangacix.net{path}?{query}",
        {
            "X-E-H": token,
            "Accept": "application/json, text/plain, */*",
            "Referer": "https://mangacix.net/",
            "Origin": "https://mangacix.net",
        },
    )


def search_mangacix(session: requests.Session, title: str) -> list[dict[str, Any]]:
    url, headers = signed_mangacix_url(
        "/secure/titles",
        [("onlyStreamable", "true"), ("page", 1), ("perPage", 16), ("query", title)],
    )
    response = response_for(session, url, headers=headers)
    try:
        items = response.json()["pagination"]["data"]
        return [{"title": item["name"], "aliases": []} for item in items[:20]]
    except (ValueError, KeyError, TypeError) as exc:
        raise SearchFailure(f"response={type(exc).__name__}") from None


def search_mangadiyari(session: requests.Session, title: str) -> list[dict[str, Any]]:
    query = urlencode({"search": title, "sort": "latest", "limit": 24, "page": 1})
    response = response_for(
        session, f"https://mangadiyari.com/api/series?{query}",
        headers={
            "Accept": "application/json, text/plain, */*",
            "Referer": "https://mangadiyari.com/",
            "Origin": "https://mangadiyari.com",
        },
    )
    try:
        items = response.json()["series"]
    except (ValueError, KeyError, TypeError) as exc:
        raise SearchFailure(f"response={type(exc).__name__}") from None
    results = []
    for item in items[:20]:
        aliases = item.get("alt_names") or ""
        results.append({
            "title": item.get("title", ""),
            "aliases": [part.strip() for part in re.split(r"[,;/]", aliases) if part.strip()],
        })
    return results


SEARCHERS = {
    "mangatr": search_mangatr,
    "mangacix": search_mangacix,
    "mangadiyari": search_mangadiyari,
}


def compare_title(session: requests.Session, title: str) -> dict[str, Any]:
    result: dict[str, Any] = {"candidateTitle": title, "sources": {}}
    for source, searcher in SEARCHERS.items():
        try:
            found = searcher(session, title)
            matches = [item["title"] for item in found if title_matches(title, item)]
            result["sources"][source] = {
                "ok": True,
                "resultsReturned": len(found),
                "matchedTitles": matches[:5],
                "exactOrContainmentMatch": bool(matches),
            }
        except Exception as exc:
            message = str(exc) if isinstance(exc, SearchFailure) else f"unexpected={type(exc).__name__}"
            result["sources"][source] = {"ok": False, "error": message}
        time.sleep(0.25)
    successful = [item for item in result["sources"].values() if item["ok"]]
    result["allActiveSearchesSucceeded"] = len(successful) == len(SEARCHERS)
    result["matchedInSuccessfulSources"] = sum(
        bool(item.get("exactOrContainmentMatch")) for item in successful
    )
    result["noMatchInAnySuccessfulSource"] = bool(successful) and result["matchedInSuccessfulSources"] == 0
    return result


def selected_titles(candidate: dict[str, Any]) -> list[str]:
    if not candidate.get("ok"):
        return []
    titles = []
    for title in candidate.get("latestTitles", [])[:3] + candidate.get("sampledPopularTitles", [])[:2]:
        if isinstance(title, str) and title and title not in titles:
            titles.append(title)
    return titles


def run(input_path: Path) -> dict[str, Any]:
    candidates = json.loads(input_path.read_text(encoding="utf-8"))["sources"]
    session = requests.Session()
    session.headers.update({"User-Agent": USER_AGENT, "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7"})
    report: dict[str, Any] = {"sources": {}}
    try:
        for key, candidate in candidates.items():
            titles = selected_titles(candidate)
            if not titles:
                report["sources"][key] = {"compared": 0, "reason": "candidate-not-live-verified"}
                continue
            comparisons = [compare_title(session, title) for title in titles]
            report["sources"][key] = {
                "compared": len(comparisons),
                "allActiveSearchesSucceeded": all(item["allActiveSearchesSucceeded"] for item in comparisons),
                "titlesWithoutMatchInSuccessfulSources": [
                    item["candidateTitle"] for item in comparisons if item["noMatchInAnySuccessfulSource"]
                ],
                "comparisons": comparisons,
            }
    finally:
        session.close()
    report["ok"] = any(item.get("compared", 0) > 0 for item in report["sources"].values())
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    report = run(args.input)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
