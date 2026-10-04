"""
Filmow profile scraper using cloudscraper to bypass Cloudflare.
Called by the Kotlin backend as a subprocess.

Based on https://github.com/yanari/filmow_to_letterboxd

Usage:
    python filmow_scraper.py <username> [cookies]

Output:
    JSON to stdout with the scraped profile data.
"""

import json
import re
import sys
import time
import cloudscraper
from bs4 import BeautifulSoup

BASE_URL = "https://filmow.com"
REQUEST_DELAY = 2.0
MAX_RETRIES = 5


def create_session(cookies_str=""):
    session = cloudscraper.create_scraper()
    if cookies_str:
        for pair in cookies_str.split(";"):
            pair = pair.strip()
            if "=" in pair:
                key, value = pair.split("=", 1)
                session.cookies.set(key.strip(), value.strip())
        log(f"[SESSION] Cookies set: {[k for k in session.cookies.keys()]}")
    else:
        log("[SESSION] No cookies provided — scraping as anonymous user")
    return session


def check_authenticated(soup):
    """Returns True if the page indicates an authenticated session."""
    # Filmow shows a logout link or user menu when logged in
    if soup.select_one("a[href*='logout'], a[href*='sair'], .user-menu, #user-menu"):
        return True
    # No sign-in form on the page is also a good indicator
    if soup.select_one("form[action*='login'], input[name='password']"):
        return False
    return None  # inconclusive


def get_page(session, url):
    last_response = None
    for attempt in range(MAX_RETRIES):
        time.sleep(REQUEST_DELAY)
        t0 = time.time()
        log(f"[HTTP] GET {url} (attempt {attempt + 1}/{MAX_RETRIES})...")
        last_response = session.get(url, timeout=30)
        elapsed = time.time() - t0
        log(f"[HTTP] {last_response.status_code} in {elapsed:.1f}s ({len(last_response.text)} chars)")
        if last_response.status_code == 429:
            retry_after = last_response.headers.get("Retry-After")
            wait = (
                float(retry_after)
                if retry_after and retry_after.isdigit()
                else min(60, 5 * (2 ** attempt))
            )
            log(f"[HTTP] Rate limited, waiting {wait:.0f}s...")
            time.sleep(wait)
            continue
        last_response.raise_for_status()
        return BeautifulSoup(last_response.text, "html.parser")
    last_response.raise_for_status()


def get_last_page(soup):
    pages = []
    for link in soup.select(".pagination a[href]"):
        match = re.search(r"pagina=(\d+)", link.get("href", ""))
        if match:
            pages.append(int(match.group(1)))
    return max(pages, default=1)


def scrape_profile_page(session, username):
    """Scrapes the profile page and returns display name, stats, and recently watched movies."""
    display_name = username
    recent = []
    stats = {}

    try:
        soup = get_page(session, f"{BASE_URL}/@{username}")

        # Display name
        el = soup.select_one("span[itemprop=name] a") or soup.select_one("span[itemprop=name]")
        if el:
            display_name = el.get_text(strip=True)

        # Stats from profile header (e.g. "4 Já Vi", "0 Comentários")
        for a in soup.select(".profile__stats a"):
            text = a.get_text(strip=True)
            count_match = re.match(r"(\d+)", text)
            if count_match:
                count = int(count_match.group(1))
                if "Vi" in text:
                    stats["watchedCount"] = count

        authenticated = check_authenticated(soup)
        log(f"[SESSION] Authenticated: {authenticated}")
        log(f"Stats: {stats}")

        # Recently watched from .last-seen section
        last_seen = soup.select_one(".last-seen")
        if last_seen:
            items = last_seen.select(".recent-movies-list div.movie_list_item")
            for item in items:
                mi = item.select_one("div.movie-item")
                if not mi:
                    continue

                # Skip series (same check as _collect_list_movies)
                a_el = mi.select_one("a[href]")
                item_href = a_el.get("href", "") if a_el else ""
                if re.search(r"-(temporada|season)-|\d+a-temporada|/serie/", item_href):
                    log(f"    skipping series in recently watched: {item_href}")
                    continue

                parsed = parse_movie_item_from_div(mi, "Assisti Recentemente", container=item)
                if not parsed:
                    continue

                # Override user rating from stars above the movie-item
                stars = item.select_one(".user-extras__item[title]")
                if stars:
                    rating_match = re.search(r"Nota:\s*([0-5](?:[.,]\d)?)", stars.get("title", ""))
                    if rating_match:
                        parsed["userRating"] = float(rating_match.group(1).replace(",", "."))

                recent.append(parsed)

            log(f"Recently watched: {len(recent)} items")

    except Exception as e:
        log(f"Failed to scrape profile page for @{username}: {e}")

    return display_name, stats, recent


_PT_MONTHS = {
    "janeiro": 1, "fevereiro": 2, "março": 3, "abril": 4,
    "maio": 5, "junho": 6, "julho": 7, "agosto": 8,
    "setembro": 9, "outubro": 10, "novembro": 11, "dezembro": 12,
}


def parse_date_str(text):
    """Convert a Filmow date string to ISO-8601 (YYYY-MM-DDT00:00:00Z). Returns None on failure."""
    if not text:
        return None
    text = text.strip().lower()
    # ISO or partial ISO: 2023-11-04 / 2023-11-04T...
    m = re.match(r"(\d{4})-(\d{2})-(\d{2})", text)
    if m:
        return f"{m.group(1)}-{m.group(2)}-{m.group(3)}T00:00:00Z"
    # DD/MM/YYYY or DD/MM/YY
    m = re.match(r"(\d{1,2})/(\d{1,2})/(\d{2,4})", text)
    if m:
        year = m.group(3)
        if len(year) == 2:
            year = "20" + year
        return f"{year}-{m.group(2).zfill(2)}-{m.group(1).zfill(2)}T00:00:00Z"
    # "4 de novembro de 2023" / "em 4 de novembro de 2023"
    m = re.search(r"(\d{1,2})\s+de\s+(\w+)\s+de\s+(\d{4})", text)
    if m:
        month_num = _PT_MONTHS.get(m.group(2))
        if month_num:
            return f"{m.group(3)}-{str(month_num).zfill(2)}-{m.group(1).zfill(2)}T00:00:00Z"
    return None


def parse_watched_at(container):
    """Extract the watched date from a movie list item container element."""
    el = container.select_one('[id="watched-in"]')
    if el:
        # <time datetime="...">, <input value="...">, or plain text
        raw = el.get("datetime") or el.get("value") or el.get_text(strip=True)
        return parse_date_str(raw)
    return None


def extract_titles_from_alt(alt_text):
    """Extract localTitle (PT) and originalTitle from alt text like 'Eternos (Eternals)'."""
    orig_match = re.search(r"\(([^)]+)\)$", alt_text)
    if orig_match:
        local_title = alt_text[:orig_match.start()].strip()
        original_title = orig_match.group(1)
        return local_title, original_title
    return alt_text.strip(), None


def parse_movie_item_from_list(item, status):
    """Extract movie data from a li.movie_list_item element (classic layout)."""
    link = item.select_one("a.tip-movie[href]") or item.select_one("a[href]")
    if not link:
        return None

    filmow_id = item.get("data-movie-pk", "") or link.get("data-movie-pk", "")

    img = item.select_one("img.lazyload") or item.select_one("img")
    alt_text = img.get("alt", "") if img else ""
    title = link.get("title", "").strip() or alt_text or link.get_text(strip=True)

    local_title, original_title = extract_titles_from_alt(alt_text)
    if original_title:
        title = original_title

    poster_url = None
    if img:
        poster_url = img.get("data-src") or img.get("data-original") or img.get("src")
        if poster_url and "placeholder" in poster_url:
            poster_url = None

    year_match = re.search(r"\((\d{4})\)", alt_text)
    year = year_match.group(1) if year_match else None

    rating_el = item.select_one(".star-rating[title]") or item.select_one("span.star-rating-small[title]")
    user_rating = None
    if rating_el:
        rating_match = re.search(r"Nota:\s*([0-5](?:[.,]\d)?)", rating_el.get("title", ""))
        if rating_match:
            user_rating = float(rating_match.group(1).replace(",", "."))

    vote_average = 0.0
    avg_el = item.select_one("span.movie-rating-average")
    if avg_el:
        try:
            vote_average = float(avg_el.get_text(strip=True))
        except ValueError:
            pass

    return {
        "filmowId": filmow_id or None,
        "title": title,
        "localTitle": local_title if original_title else None,
        "originalTitle": original_title,
        "year": year,
        "posterUrl": poster_url,
        "voteAverage": vote_average,
        "userRating": user_rating,
        "watchedAt": None,
        "href": link.get("href", "") or "",
        "status": status,
    }


def parse_movie_item_from_div(item, status, container=None):
    """Extract movie data from a div.movie-item element (newer layout).

    container: parent element that may hold the watched-in date field.
    """
    a = item.select_one("a[href][data-movie-pk]")
    if not a:
        return None

    title_el = item.select_one("h3.movie-item__title")
    title = title_el.get_text(strip=True) if title_el else ""

    poster = item.select_one("img.movie-item__poster")
    poster_url = poster.get("src") if poster else None

    # Extract original title from poster alt: "Título PT (Original Title)"
    alt_text = poster.get("alt", "") if poster else ""
    local_title, original_title = extract_titles_from_alt(alt_text)
    if original_title:
        title = original_title

    gr_el = item.select_one("span.movie-item__rating")
    vote_average = 0.0
    if gr_el:
        try:
            vote_average = float(re.sub(r"[^0-9.]", "", gr_el.get_text()))
        except ValueError:
            pass

    ur_el = item.select_one("span.movie-item__user-rating")
    user_rating = None
    if ur_el:
        try:
            user_rating = float(re.sub(r"[^0-9.]", "", ur_el.get_text()))
        except ValueError:
            pass

    year_match = re.search(r"\((\d{4})\)", title)
    year = year_match.group(1) if year_match else None

    return {
        "filmowId": a.get("data-movie-pk", "") or None,
        "title": title,
        "localTitle": local_title if original_title else None,
        "originalTitle": original_title,
        "year": year,
        "posterUrl": poster_url,
        "voteAverage": vote_average,
        "userRating": user_rating,
        "watchedAt": None,
        "href": a.get("href", "") or "",
        "status": status,
    }


def scrape_section(session, username, content_type, status_key, errors):
    """
    Scrape a section (e.g. filmes/ja-vi, series/quero-ver).
    content_type: "filmes" or "series"
    status_key: "ja-vi", "quero-ver", or "favoritos"
    """
    status_labels = {
        "ja-vi": "Já Vi",
        "quero-ver": "Quero Ver",
        "favoritos": "Favorito",
    }
    status = status_labels.get(status_key, status_key)
    base_path = f"/usuario/{username}/{content_type}/{status_key}/"
    url = f"{BASE_URL}{base_path}"

    movies = []

    try:
        first_page = get_page(session, url)
    except Exception as e:
        errors.append(f"{base_path} failed: {e}")
        return movies

    # Check if page has content
    movie_list = first_page.select_one("#movies-list")
    if not movie_list:
        # Try newer layout
        items = first_page.select("div.movie-item")
        if not items:
            return movies

    total_pages = get_last_page(first_page)
    log(f"{base_path} -> {total_pages} pages")

    for page_num in range(1, total_pages + 1):
        soup = first_page if page_num == 1 else None
        if soup is None:
            try:
                soup = get_page(session, f"{url}?pagina={page_num}")
            except Exception as e:
                errors.append(f"{base_path} page {page_num} failed: {e}")
                continue

        # Classic layout: li.movie_list_item
        items = soup.select("li.movie_list_item")
        if items:
            for item in items:
                parsed = parse_movie_item_from_list(item, status)
                if parsed:
                    movies.append(parsed)
        else:
            # Newer layout: div.movie-item
            div_items = soup.select("div.movie-item")
            for item in div_items:
                parsed = parse_movie_item_from_div(item, status, container=item.parent)
                if parsed:
                    movies.append(parsed)

        count = len(items) or len(soup.select("div.movie-item"))
        log(f"  page {page_num}/{total_pages} -> {count} items")

    return movies


def scrape_list_detail(session, href, errors):
    """Scrape a single list detail page. Returns description and list of movies (non-movie items ignored)."""
    url = f"{BASE_URL}{href}"
    description = None
    movies = []

    try:
        soup = get_page(session, url)
    except Exception as e:
        errors.append(f"List detail {href} failed: {e}")
        return description, movies

    # Description from meta tag (Filmow doesn't have a dedicated description field in the UI)
    meta_desc = soup.select_one('meta[name=description]')
    if meta_desc:
        description = meta_desc.get("content", "").strip() or None

    # Collect all movie items across all pages.
    # Lists use two pagination styles:
    #   1. Classic .pagination with numbered links (handled by get_last_page)
    #   2. "Carregar mais" button with data-next-page (infinite scroll)
    total_pages = get_last_page(soup)

    seen_movie_ids = set()

    if total_pages > 1:
        # Classic pagination — iterate known page range
        log(f"  list detail {href} -> {total_pages} pages (classic pagination)")
        for page_num in range(1, total_pages + 1):
            page_soup = soup if page_num == 1 else None
            if page_soup is None:
                try:
                    page_soup = get_page(session, f"{url}?pagina={page_num}")
                except Exception as e:
                    errors.append(f"List {href} page {page_num} failed: {e}")
                    continue
            _collect_list_movies(page_soup, movies, seen_movie_ids, page_num, total_pages)
    else:
        # "Load more" pagination — follow data-next-page until exhausted
        log(f"  list detail {href} -> load-more pagination")
        max_pages = 50  # safety limit
        page_num = 1
        page_soup = soup
        seen_movie_ids = set()
        while page_soup and page_num <= max_pages:
            new_count = _collect_list_movies(page_soup, movies, seen_movie_ids, page_num, "?")
            # Stop if page yielded no new movies (server returning same page)
            if new_count == 0:
                log(f"  list detail {href} -> page {page_num} added 0 new movies, stopping")
                break
            load_more = page_soup.select_one("a.btn-lists-infinite-scroll[data-next-page]")
            if not load_more or "disabled" in load_more.get("class", []):
                break
            next_page = load_more.get("data-next-page")
            if not next_page:
                break
            page_num += 1
            try:
                page_soup = get_page(session, f"{url}?pagina={next_page}")
            except Exception as e:
                errors.append(f"List {href} page {next_page} failed: {e}")
                break

    return description, movies


def _collect_list_movies(soup, movies, seen_ids, page_num, total_label):
    """Extract movie items from a single list page. Returns count of new (non-duplicate) movies added."""
    items = soup.select("div.movie-item")
    new_count = 0
    for item in items:
        a = item.select_one("a[href][data-movie-pk]")
        if not a:
            continue

        movie_pk = a.get("data-movie-pk", "")
        if movie_pk in seen_ids:
            continue

        item_href = a.get("href", "")

        # Skip series
        is_series = bool(re.search(r"-(temporada|season)-|\d+a-temporada", item_href))
        if is_series:
            log(f"    skipping series: {item_href}")
            continue

        parsed = parse_movie_item_from_div(item, "Lista")
        if parsed:
            seen_ids.add(movie_pk)
            movies.append(parsed)
            new_count += 1

    log(f"    page {page_num}/{total_label} -> {len(items)} items ({new_count} new)")
    return new_count


def _collect_list_cards(soup):
    """Extract list card references from a lists index page."""
    refs = []
    for card in soup.select("div.list-card"):
        link = card.select_one("a.list-card__covers[href]") or card.select_one("a[href*='/listas/']")
        if not link:
            continue

        href = link.get("href", "")
        id_match = re.search(r"-l(\d+)/?$", href)
        filmow_id = id_match.group(1) if id_match else ""

        title_el = card.select_one("a.list-card__title")
        title = title_el.get_text(strip=True) if title_el else ""

        cover_el = card.select_one("img.list-card__cover")
        cover_url = cover_el.get("src") if cover_el else None

        if title:
            refs.append({
                "filmowId": filmow_id,
                "title": title,
                "href": href,
                "coverUrl": cover_url,
            })
    return refs


def scrape_lists(session, username, errors):
    """Scrape user lists from /listas/usuario/{username}/ and each list's detail."""
    url = f"{BASE_URL}/listas/usuario/{username}/"
    lists = []

    try:
        soup = get_page(session, url)
    except Exception as e:
        errors.append(f"Lists page failed: {e}")
        return lists

    # Collect list cards across all index pages.
    # Uses classic pagination or "load more" button, same as list detail.
    list_refs = []
    seen_list_ids = set()
    total_pages = get_last_page(soup)

    def _extend_list_refs(page_soup):
        """Add new list cards, skip duplicates. Returns count of new cards."""
        cards = _collect_list_cards(page_soup)
        new_count = 0
        for card in cards:
            fid = card["filmowId"] or card["title"]
            if fid not in seen_list_ids:
                seen_list_ids.add(fid)
                list_refs.append(card)
                new_count += 1
        return new_count

    if total_pages > 1:
        log(f"Lists index -> {total_pages} pages (classic pagination)")
        for page_num in range(1, total_pages + 1):
            page_soup = soup if page_num == 1 else None
            if page_soup is None:
                try:
                    page_soup = get_page(session, f"{url}?pagina={page_num}")
                except Exception as e:
                    errors.append(f"Lists index page {page_num} failed: {e}")
                    continue
            _extend_list_refs(page_soup)
            log(f"  lists index page {page_num}/{total_pages} -> {len(list_refs)} lists so far")
    else:
        max_pages = 50  # safety limit
        page_soup = soup
        page_num = 1
        while page_soup and page_num <= max_pages:
            new_count = _extend_list_refs(page_soup)
            log(f"  lists index page {page_num} -> {len(list_refs)} lists so far ({new_count} new)")
            if new_count == 0:
                log(f"  lists index page {page_num} added 0 new lists, stopping")
                break
            load_more = page_soup.select_one("a.btn-lists-infinite-scroll[data-next-page]")
            if not load_more or "disabled" in load_more.get("class", []):
                break
            next_page = load_more.get("data-next-page")
            if not next_page:
                break
            page_num += 1
            try:
                page_soup = get_page(session, f"{url}?pagina={next_page}")
            except Exception as e:
                errors.append(f"Lists index page {next_page} failed: {e}")
                break

    log(f"Lists: found {len(list_refs)}, fetching details...")

    for ref in list_refs:
        description, movies = scrape_list_detail(session, ref["href"], errors)
        lists.append({
            "filmowId": ref["filmowId"],
            "title": ref["title"],
            "description": description,
            "filmowUrl": f"{BASE_URL}{ref['href']}",
            "coverUrl": ref["coverUrl"],
            "movies": movies,
        })
        log(f"  list '{ref['title']}': {len(movies)} movies")

    return lists


def fetch_detail_data(session, href, errors):
    """Fetch the movie detail page and extract all available movie data.

    Returns a dict with any of the following keys (only non-null values included):
        watchedAt   – ISO-8601 date the user watched the movie
        posterUrl   – full-resolution poster URL
        imdbUrl     – IMDB URL from sameAs in JSON-LD
        overview    – plot description (Portuguese)
        releaseDate – ISO date "YYYY-MM-DD" from datePublished
        runtime     – int, duration in minutes
        director    – str, first director name
        genres      – list[str], genre names
    """
    if not href:
        return {}
    url = f"{BASE_URL}{href}"
    try:
        soup = get_page(session, url)

        result = {}

        el = soup.select_one('[id="watched-in"]')
        if el:
            raw = el.get("datetime") or el.get("value") or el.get_text(strip=True)
            watched_at = parse_date_str(raw)
            if watched_at:
                result["watchedAt"] = watched_at

        poster_wrapper = soup.select_one(".movie__poster-wrapper")
        if poster_wrapper:
            first_link = poster_wrapper.select_one("a[href]")
            if first_link:
                result["posterUrl"] = first_link.get("href")

        for script in soup.select('script[type="application/ld+json"]'):
            try:
                data = json.loads(script.string or "")
                if data.get("@type") != "Movie":
                    continue

                same_as = data.get("sameAs", [])
                if isinstance(same_as, list):
                    imdb_url = next((u for u in same_as if "imdb.com" in u), None)
                elif isinstance(same_as, str) and "imdb.com" in same_as:
                    imdb_url = same_as
                else:
                    imdb_url = None
                if imdb_url:
                    result["imdbUrl"] = imdb_url

                description = (data.get("description") or "").strip()
                if description:
                    result["overview"] = description

                date_published = (data.get("datePublished") or "").strip()
                if date_published:
                    result["releaseDate"] = date_published  # already ISO: YYYY-MM-DD

                duration = data.get("duration") or ""
                if duration:
                    m = re.match(r"PT(?:(\d+)H)?(?:(\d+)M)?", duration)
                    if m and (m.group(1) or m.group(2)):
                        result["runtime"] = int(m.group(1) or 0) * 60 + int(m.group(2) or 0)

                directors = data.get("director", [])
                if isinstance(directors, dict):
                    directors = [directors]
                director_names = [d["name"].strip() for d in directors if d.get("name")]
                if director_names:
                    result["director"] = director_names[0]

                genres = data.get("genre", [])
                if isinstance(genres, str):
                    genres = [genres]
                genres = [g.strip() for g in genres if g.strip()]
                if genres:
                    result["genres"] = genres

                break
            except Exception:
                pass

        return result
    except Exception as e:
        errors.append(f"fetch_detail_data {href} failed: {e}")
        return {}


def scrape_profile(username, cookies_str=""):
    session = create_session(cookies_str)
    errors = []
    t_start = time.time()

    log(f"[PHASE] scrape_profile_page...")
    display_name, stats, recently_watched = scrape_profile_page(session, username)
    log(f"[PHASE] scrape_profile_page done in {time.time() - t_start:.1f}s")

    t = time.time()
    log(f"[PHASE] fetching detail data for {len(recently_watched)} recently watched movies...")
    for movie in recently_watched:
        detail = fetch_detail_data(session, movie.get("href", ""), errors)
        movie.update({k: v for k, v in detail.items() if v is not None})
    log(f"[PHASE] recently watched detail data done in {time.time() - t:.1f}s")

    t = time.time()
    log(f"[PHASE] scrape_section filmes/ja-vi...")
    watched = scrape_section(session, username, "filmes", "ja-vi", errors)
    log(f"[PHASE] filmes/ja-vi done: {len(watched)} movies in {time.time() - t:.1f}s")

    t = time.time()
    log(f"[PHASE] fetching detail data for {len(watched)} movies...")
    for movie in watched:
        detail = fetch_detail_data(session, movie.get("href", ""), errors)
        movie.update({k: v for k, v in detail.items() if v is not None})
    log(f"[PHASE] detail data done in {time.time() - t:.1f}s")

    t = time.time()
    log(f"[PHASE] scrape_section filmes/quero-ver...")
    watchlist = scrape_section(session, username, "filmes", "quero-ver", errors)
    log(f"[PHASE] filmes/quero-ver done: {len(watchlist)} movies in {time.time() - t:.1f}s")

    t = time.time()
    log(f"[PHASE] fetching detail data for {len(watchlist)} watchlist movies...")
    for movie in watchlist:
        detail = fetch_detail_data(session, movie.get("href", ""), errors)
        movie.update({k: v for k, v in detail.items() if v is not None})
    log(f"[PHASE] watchlist detail data done in {time.time() - t:.1f}s")

    t = time.time()
    log(f"[PHASE] scrape_section filmes/favoritos...")
    favorites = scrape_section(session, username, "filmes", "favoritos", errors)
    log(f"[PHASE] filmes/favoritos done: {len(favorites)} movies in {time.time() - t:.1f}s")

    t = time.time()
    log(f"[PHASE] fetching detail data for {len(favorites)} favorites movies...")
    for movie in favorites:
        detail = fetch_detail_data(session, movie.get("href", ""), errors)
        movie.update({k: v for k, v in detail.items() if v is not None})
    log(f"[PHASE] favorites detail data done in {time.time() - t:.1f}s")

    t = time.time()
    log(f"[PHASE] scrape_lists...")
    user_lists = scrape_lists(session, username, errors)
    log(f"[PHASE] scrape_lists done: {len(user_lists)} lists in {time.time() - t:.1f}s")

    t = time.time()
    list_movie_count = sum(len(lst.get("movies", [])) for lst in user_lists)
    log(f"[PHASE] fetching detail data for {list_movie_count} list movies...")
    for lst in user_lists:
        for movie in lst.get("movies", []):
            detail = fetch_detail_data(session, movie.get("href", ""), errors)
            movie.update({k: v for k, v in detail.items() if v is not None})
    log(f"[PHASE] list movies detail data done in {time.time() - t:.1f}s")

    log(f"[PHASE] Total scrape time: {time.time() - t_start:.1f}s")

    return {
        "username": username,
        "displayName": display_name,
        "watchedCount": stats.get("watchedCount", 0),
        "recentlyWatched": recently_watched,
        "watched": watched,
        "watchlist": watchlist,
        "favorites": favorites,
        "lists": user_lists,
        "errors": errors,
    }


def log(msg):
    print(msg, file=sys.stderr, flush=True)


def main():
    # Force UTF-8 output on Windows
    if sys.platform == "win32":
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")

    if len(sys.argv) < 2:
        print(json.dumps({"error": "Usage: filmow_scraper.py <username> [cookies]"}))
        sys.exit(1)

    username = sys.argv[1]
    cookies = sys.argv[2] if len(sys.argv) > 2 else ""

    try:
        result = scrape_profile(username, cookies)
        print(json.dumps(result, ensure_ascii=False))
    except Exception as e:
        print(json.dumps({"error": str(e), "username": username}))
        sys.exit(1)


if __name__ == "__main__":
    main()
