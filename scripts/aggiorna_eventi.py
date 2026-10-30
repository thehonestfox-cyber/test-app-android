#!/usr/bin/env python3
"""
Legge i siti delle leghe e scrive eventi.json nella radice del repo.
Gira su GitHub Actions (.github/workflows/eventi.yml); l'app scarica solo il JSON.

  - Serie A: sport.sky.it/calcio/serie-a/calendario-risultati
  - Serie B: legab.it/seriebkt
  - Serie C: seriec.com (partite) + sport.sky.it (canale Sky di ogni partita)

E' la stessa logica che prima stava in FixtureParsers.kt, riportata in Python. Solo libreria standard.
"""
import json
import re
import sys
import time
import unicodedata
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime
from pathlib import Path
from zoneinfo import ZoneInfo

ROME = ZoneInfo("Europe/Rome")
OUT = Path(__file__).resolve().parent.parent / "eventi.json"

URL_A = "https://sport.sky.it/calcio/serie-a/calendario-risultati"
URL_B = "https://www.legab.it/seriebkt"
URL_C = "https://www.seriec.com/"
SKY_INDEX = ["https://sport.sky.it/calcio/serie-c", "https://sport.sky.it/argomenti/guida-tv"]
SKY_ARTICLES = 3
KEEP_MS = 36 * 3_600_000          # una partita iniziata da meno di 36 ore resta nell'elenco

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")

IMG = "§IMG§"

script_re = re.compile(r"<(script|style)\b.*?</\1>", re.I | re.S)
comment_re = re.compile(r"<!--.*?-->", re.S)
tag_re = re.compile(r"<[^>]*>", re.S)
img_re = re.compile(r"<img\b[^>]*>", re.I | re.S)
num_ent_re = re.compile(r"&#(x?)([0-9a-fA-F]+);")
space_re = re.compile(r"\s+")

ENTITIES = {
    "&nbsp;": " ", "&amp;": "&", "&quot;": '"', "&apos;": "'", "&ordf;": "ª", "&deg;": "°",
    "&agrave;": "à", "&egrave;": "è", "&eacute;": "é", "&igrave;": "ì", "&ograve;": "ò",
    "&ugrave;": "ù", "&ouml;": "ö", "&uuml;": "ü", "&auml;": "ä",
}


@dataclass(frozen=True)
class Fixture:
    league: str
    round: int
    home: str
    away: str
    start: int   # millisecondi


# ---------------------------------------------------------------- utilita'

def fold(s):
    n = unicodedata.normalize("NFD", s.lower())
    return "".join(c for c in n if not unicodedata.category(c).startswith("M"))


def decode(s):
    def rep(m):
        try:
            return chr(int(m.group(2), 16 if m.group(1) else 10))
        except (ValueError, OverflowError):
            return " "
    t = num_ent_re.sub(rep, s)
    for k, v in ENTITIES.items():
        t = t.replace(k, v)
    return t


def visible_words(html, keep_images):
    t = script_re.sub(" ", html)
    t = comment_re.sub(" ", t)
    t = img_re.sub("\n" + IMG + "\n" if keep_images else "\n", t)
    t = tag_re.sub("\n", t)
    return [w for w in space_re.split(decode(t)) if w]


def blank_tags(html):
    """Come l'HTML ma con tag e script sostituiti da spazi: stessa lunghezza, posizioni uguali."""
    def blank(m):
        return " " * len(m.group(0))
    t = script_re.sub(blank, html)
    t = comment_re.sub(blank, t)
    return tag_re.sub(blank, t)


def pretty(s):
    out = []
    for w in space_re.split(s.strip()):
        if w:
            l = w.lower()
            out.append(l[:1].upper() + l[1:])
    return " ".join(out)


def to_ms(d, h, m):
    return int(datetime(d.year, d.month, d.day, h, m, tzinfo=ROME).timestamp() * 1000)


def distinct(items):
    return list(dict.fromkeys(items))


# ---------------------------------------------------------------- Serie C

def is_code(w):
    return 2 <= len(w) <= 4 and all(c.isupper() or c.isdigit() for c in w) and any(c.isalpha() for c in w)


C_MARKERS = {"Risultati", "GIRONE", "Prossime", "Classifiche", "Completa", "Caricamento..."}


def split_c(seg):
    groups, cur = [], []
    for w in seg:
        if w == IMG:
            if cur:
                groups.append(cur)
            cur = []
        else:
            cur.append(w)
    if cur:
        groups.append(cur)
    if len(groups) == 2:
        home = groups[0][:-1] if len(groups[0]) > 1 and is_code(groups[0][-1]) else groups[0]
        away = groups[1][1:] if len(groups[1]) > 1 and is_code(groups[1][0]) else groups[1]
        if home and away:
            return " ".join(home), " ".join(away)
    s = [w for w in seg if w != IMG]
    for i in range(1, len(s) - 2):
        if is_code(s[i]) and is_code(s[i + 1]) and not is_code(s[i + 2]):
            return " ".join(s[:i]), " ".join(s[i + 2:])
    return None


def parse_serie_c(html, today):
    words = visible_words(html, True)
    date_re = re.compile(r"(\d{1,2})/(\d{1,2})")
    time_re = re.compile(r"(\d{1,2}):(\d{2})")
    round_re = re.compile(r"(\d{1,2})[ªº°]")
    idx = [i for i in range(len(words) - 1)
           if date_re.fullmatch(words[i]) and time_re.fullmatch(words[i + 1])]
    out = []
    for k, i in enumerate(idx):
        rnd = 0
        for b in range(1, 4):
            j = i - b
            if j < 0:
                break
            m = round_re.fullmatch(words[j])
            if m:
                rnd = int(m.group(1))
            if rnd > 0:
                break
        dm = date_re.fullmatch(words[i])
        tm = time_re.fullmatch(words[i + 1])
        day, mon = int(dm.group(1)), int(dm.group(2))
        h, mi = int(tm.group(1)), int(tm.group(2))
        if not (1 <= mon <= 12 and 1 <= day <= 31 and h <= 23 and mi <= 59):
            continue
        cands = []
        for dy in (-1, 0, 1):
            try:
                cands.append(date(today.year + dy, mon, day))
            except ValueError:
                pass
        if not cands:
            continue
        d = min(cands, key=lambda x: abs((x - today).days))
        end = idx[k + 1] if k + 1 < len(idx) else len(words)
        seg = words[i + 2:end]
        while seg and (seg[-1] == "|" or round_re.fullmatch(seg[-1])):
            seg.pop()
        cut = next((n for n, w in enumerate(seg) if w in C_MARKERS), -1)
        if cut >= 0:
            seg = seg[:cut]
        teams = split_c(seg)
        if not teams:
            continue
        out.append(Fixture("C", rnd, pretty(teams[0]), pretty(teams[1]), to_ms(d, h, mi)))
    return distinct(out)


# ---------------------------------------------------------------- Serie B

MONTHS = ["gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno",
          "luglio", "agosto", "settembre", "ottobre", "novembre", "dicembre"]


def slug_team(s):
    parts = []
    for w in s.split("-"):
        parts.append("L.R." if w == "lr" else "Südtirol" if w == "sudtirol" else w)
    return pretty(" ".join(parts)).replace("L.r.", "L.R.")


def parse_serie_b(html):
    flat = blank_tags(html)
    link_re = re.compile(r"""/seriebkt/partita/[^"'\s/<>]+/[^"'\s/<>]+/([a-z0-9\-]+)""")
    links = [(m.start(), m.group(1)) for m in link_re.finditer(html)]
    date_re = re.compile(r"(\d{1,2})\s+(" + "|".join(MONTHS) + r")\s+(20\d\d)", re.I)
    dates = list(date_re.finditer(flat))
    round_re = re.compile(r"(\d{1,2})\s*(?:a|ª|º|°|&ordf;)\s+giornata", re.I)
    rounds = [(m.start(), int(m.group(1))) for m in round_re.finditer(flat)]
    if not links or not dates:
        return []

    inf = 1 << 60
    after, before = [], []
    for i in range(len(dates)):
        a = dates[i].start()
        b = dates[i + 1].start() if i + 1 < len(dates) else inf
        after.append([l for l in links if a <= l[0] < b])
        a2 = dates[i - 1].start() if i > 0 else -1
        b2 = dates[i].start()
        before.append([l for l in links if a2 + 1 <= l[0] < b2])
    if all(len(x) == 1 for x in after):
        chosen = after
    elif all(len(x) == 1 for x in before):
        chosen = before
    else:
        chosen = after

    tz_time_re = re.compile(r"\bOre\s+(\d{1,2})[:.](\d{2})")
    sentence_time_re = re.compile(r"ore:?\s*(\d{1,2})[:.](\d{2})", re.I)
    out = []
    for i, d in enumerate(dates):
        if not chosen[i]:
            continue
        link = chosen[i][0]
        end = dates[i + 1].start() if i + 1 < len(dates) else min(len(flat), d.start() + 900)
        win = space_re.sub(" ", flat[d.start():min(end, len(flat))])
        tm = tz_time_re.search(win) or sentence_time_re.search(win)
        if not tm:
            continue
        h, mi = int(tm.group(1)), int(tm.group(2))
        if h > 23 or mi > 59:
            continue
        mon = MONTHS.index(d.group(2).lower()) + 1
        try:
            dd = date(int(d.group(3)), mon, int(d.group(1)))
        except ValueError:
            continue
        slug = link[1]
        cut = slug.find("-vs-")
        if cut <= 0:
            continue
        rnd = 0
        for p, r in rounds:
            if p < d.start():
                rnd = r
        out.append(Fixture("B", rnd, slug_team(slug[:cut]), slug_team(slug[cut + 4:]), to_ms(dd, h, mi)))
    return distinct(out)


# ---------------------------------------------------------------- Serie A (sport.sky.it)

IT_MONTHS = {"gen": 1, "feb": 2, "mar": 3, "apr": 4, "mag": 5, "giu": 6,
             "lug": 7, "ago": 8, "set": 9, "ott": 10, "nov": 11, "dic": 12}


def undouble(w):
    n = len(w)
    if n >= 2 and n % 2 == 0 and w[:n // 2] == w[n // 2:]:
        return w[:n // 2]
    return w


def parse_serie_a(html):
    flat = blank_tags(html)
    link_re = re.compile(r"/calcio/serie-a/partite/\d{4}/giornata-(\d+)/([a-z0-9\-]+)")
    links = list(link_re.finditer(html))
    if not links:
        return []
    head_re = re.compile(r"(?:[^\W\d_]|[&;]){6,14}\s+(\d{1,2})\s+([A-Za-z]{3})\s+(20\d\d)")
    heads = [m for m in head_re.finditer(flat) if m.group(2).lower() in IT_MONTHS]
    time_re = re.compile(r"(\d{1,2}):(\d{2})")
    out = {}
    for k, m in enumerate(links):
        start = m.start()
        rnd = int(m.group(1))
        key = f"{rnd}|{m.group(2)}"
        if key in out:
            continue
        head = None
        for h in heads:
            if h.start() < start:
                head = h
        if head is None:
            continue
        end = links[k + 1].start() if k + 1 < len(links) else len(flat)
        nxt = next((h for h in heads if h.start() > start), None)
        if nxt is not None and nxt.start() < end:
            end = nxt.start()
        words = [w for w in space_re.split(decode(flat[start:min(end, len(flat))])) if w]
        cut = next((n for n, w in enumerate(words) if w in ("Giornata", "Link")), -1)
        w = words[:cut] if cut >= 0 else words
        ti = next((n for n, x in enumerate(w) if time_re.fullmatch(x)), -1)
        if ti <= 0:
            continue
        home = " ".join(undouble(w[:ti]))
        away = " ".join(undouble(w[ti + 1:]))
        if not home or not away:
            continue
        tm = time_re.fullmatch(w[ti])
        h, mi = int(tm.group(1)), int(tm.group(2))
        if h > 23 or mi > 59:
            continue
        try:
            d = date(int(head.group(3)), IT_MONTHS[head.group(2).lower()], int(head.group(1)))
        except ValueError:
            continue
        out[key] = Fixture("A", rnd, home, away, to_ms(d, h, mi))
    return list(out.values())


# ---------------------------------------------------------------- guida Sky alla Serie C

sky_link_re = re.compile(r"/calcio/serie-c/(\d{4})/(\d{2})/(\d{2})/serie-c-partite-sky-[a-z0-9\-]*giornata-(\d+)")
block_end_re = re.compile(r"</(?:li|p|h[1-6]|div|tr|ul|ol)\s*>|<br\s*/?>", re.I | re.S)
sky_line_re = re.compile(r"([^\n,]{3,70}?)\s*,\s*in diretta su\s+([^\n]+)", re.I)
sky_chan_re = re.compile(r"\bSky\s+Sp(?:ort|ot)\s+([A-Za-zÀ-ÿ]+|\d{1,3})\b", re.I)
bullet_re = re.compile(r"[*•]")


def sky_guide_links(html):
    return distinct("https://sport.sky.it" + m.group(0) for m in sky_link_re.finditer(html))


def sky_link_stamp(url):
    m = sky_link_re.search(url)
    if not m:
        return 0
    return int(m.group(1) + m.group(2) + m.group(3)) * 100 + int(m.group(4))


def parse_sky_guide(html):
    t = script_re.sub(" ", html)
    t = comment_re.sub(" ", t)
    t = block_end_re.sub("\n", t)
    t = tag_re.sub("", t)
    t = decode(t)
    out = {}
    for line in re.split(r"\r\n|\r|\n", t):
        m = sky_line_re.search(line)
        if not m:
            continue
        teams = space_re.sub(" ", bullet_re.sub("", m.group(1))).strip().strip("- ")
        if "-" not in teams and "\u2013" not in teams:
            continue
        chans = distinct("Sky Sport " + pretty(c) for c in sky_chan_re.findall(m.group(2)))
        if not chans:
            continue
        out.setdefault(fold(teams), {"t": teams, "c": chans})
    return list(out.values())


# ---------------------------------------------------------------- rete e file

def http(url):
    for attempt in (1, 2):
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": UA, "Accept-Language": "it-IT,it;q=0.9",
                "Accept": "text/html,application/xhtml+xml,*/*;q=0.8"})
            with urllib.request.urlopen(req, timeout=25) as r:
                return r.read().decode("utf-8", errors="replace")
        except Exception as e:
            print(f"GET {url} ERRORE {type(e).__name__}: {e} (tentativo {attempt})", file=sys.stderr)
            time.sleep(2)
    return None


def fx_json(f):
    return {"r": f.round, "h": f.home, "a": f.away, "s": f.start}


def fx_key(f):
    return f"{fold(f['h'])}|{fold(f['a'])}|{f['r']}"


def load_old():
    try:
        return json.loads(OUT.read_text(encoding="utf-8"))
    except Exception:
        return {}


def main():
    old = load_old()
    now = datetime.now(ROME)
    now_ms = int(now.timestamp() * 1000)
    today = now.date()
    midnight_ms = to_ms(today, 0, 0)

    sources = [("A", URL_A, parse_serie_a), ("B", URL_B, parse_serie_b),
               ("C", URL_C, lambda h: parse_serie_c(h, today))]
    fixtures, stato = {}, {}
    for league, url, parse in sources:
        html = http(url)
        parsed = []
        if html is None:
            stato[league] = "errore di rete"
        else:
            try:
                parsed = parse(html)
                stato[league] = "ok" if parsed else "nessuna partita letta"
            except Exception as e:
                print(f"Serie {league}: errore di lettura {e!r}", file=sys.stderr)
                stato[league] = "errore di lettura"
        old_list = old.get("fixtures", {}).get(league, [])
        if parsed:
            # come faceva l'app: le partite gia' iniziate (da meno di 36 ore) restano anche se il sito non le elenca piu'
            merged = {fx_key(f): f for f in old_list if now_ms - KEEP_MS < f["s"] <= now_ms}
            for f in parsed:
                merged[fx_key(fx_json(f))] = fx_json(f)
            lst = [f for f in merged.values() if f["s"] > now_ms - KEEP_MS]
        else:
            lst = [f for f in old_list if f["s"] > now_ms - KEEP_MS]     # sito non leggibile: resta il vecchio
        fixtures[league] = sorted(lst, key=lambda f: (f["s"], f["r"], f["h"]))
        print(f"Serie {league}: {stato[league]}, {len(parsed)} partite lette, {len(fixtures[league])} nel file")

    # guida Sky (canali della Serie C)
    sky, sky_at = old.get("sky", []), old.get("sky_at", 0)
    pages = [http(u) for u in SKY_INDEX]
    if any(p is not None for p in pages):
        links = distinct(l for p in pages if p for l in sky_guide_links(p))
        newest = sorted(links, key=sky_link_stamp, reverse=True)[:SKY_ARTICLES]
        found = {}
        for u in newest:
            h = http(u)
            if h is None:
                continue
            try:
                for s in parse_sky_guide(h):
                    found.setdefault(fold(s["t"]), s)
            except Exception as e:
                print(f"Sky: errore di lettura {e!r}", file=sys.stderr)
        print(f"Sky Serie C: {len(newest)} articoli, {len(found)} partite con canale")
        if found:
            sky, sky_at = list(found.values()), midnight_ms   # sky_at cambia una volta al giorno: pochi commit

    data = {"fixtures": fixtures, "stato": stato, "sky": sky, "sky_at": sky_at}
    text = json.dumps(data, ensure_ascii=False, indent=1) + "\n"
    try:
        same = OUT.read_text(encoding="utf-8") == text
    except FileNotFoundError:
        same = False
    if not same:
        OUT.write_text(text, encoding="utf-8")
        print("eventi.json aggiornato")
    else:
        print("eventi.json invariato")


if __name__ == "__main__":
    main()
