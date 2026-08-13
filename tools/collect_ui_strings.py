"""
Collect every user-facing string in the app into one corpus.

Runtime translation walks the rendered views, but that only ever sees the
screen in front of it — so switching language would trickle in a screen at a
time, each needing the network. This gathers the whole UI up front, at build
time, from the three places text actually lives:

  * res/values/strings.xml
  * android:text / android:hint literals in res/layout (not @string refs)
  * user-facing Kotlin literals — notifyUser(), setTitle/setMessage, .text =

The result is res/raw/ui_strings.txt, which the app translates in batches the
moment a language is chosen and then caches, so no screen ever waits for the
network afterwards.

Deliberately conservative: anything that looks like an identifier, a format
specifier on its own, a number, or a protected term is left out — translating
those produces nonsense, not a translation.

    python3 tools/collect_ui_strings.py
"""
import os, re, sys, html

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES  = os.path.join(HERE, "app/src/main/res")
JAVA = os.path.join(HERE, "app/src/main/java")
OUT  = os.path.join(RES, "raw/ui_strings.txt")

# Terms that must never be sent for translation: units, protocols, product
# names. A translator will happily turn "MOA" into a word.
PROTECTED = {
    "BAS","MOA","MRAD","mil","RH","ASL","hPa","Pa","m/s","km/h","fps","FPS",
    "Kestrel","GoPro","TACTACAM","ShotKam","Vortex","Leica","SIG","KILO","Vectronix",
    "Terrapin","FIRE4000","Tangoinnos","Ruger","Anschütz","Walther","Feinwerkbau",
    "RTSP","MJPEG","Wi-Fi","BLE","Bluetooth","GATT","UUID","HTTP","JSON","CSV","API",
    "ISSF","NRA","IPSC","IDPA","AI","OpenAI","Claude","Open-Meteo","OpenWeatherMap",
    "Windy","Netatmo","Android","R8","AAB","APK","dp","sp",
}

def keep(s):
    s = s.strip()
    if len(s) < 3 or len(s) > 400:            return False
    if s in PROTECTED:                        return False
    if not re.search(r"[A-Za-z]{3}", s):      return False   # numbers/symbols only
    if re.fullmatch(r"[A-Za-z0-9_.]+", s) and " " not in s:
        # a bare identifier or a single token like "btnSave" / "1.20.45"
        if not re.fullmatch(r"[A-Z][a-z]+", s):  return False
    if re.fullmatch(r"[%\d\s.,:+\-/()°]*", s):   return False
    if s.startswith("http"):                  return False
    return True

found = []
seen = set()
def add(s):
    s = html.unescape(s).replace("\\'", "'").replace('\\"', '"').strip()
    if keep(s) and s not in seen:
        seen.add(s); found.append(s)

# 1. strings.xml
sx = os.path.join(RES, "values/strings.xml")
if os.path.exists(sx):
    for m in re.finditer(r"<string[^>]*>(.*?)</string>", open(sx, encoding="utf-8").read(), re.S):
        add(re.sub(r"\s+", " ", m.group(1)))

# 2. layout literals
for root, _, files in os.walk(os.path.join(RES, "layout")):
    for f in files:
        if not f.endswith(".xml"): continue
        src = open(os.path.join(root, f), encoding="utf-8").read()
        for attr in ("text", "hint", "contentDescription"):
            for m in re.finditer(r'android:%s="([^@"][^"]*)"' % attr, src):
                add(m.group(1))

# 3. Kotlin user-facing literals
# Anything a screen can put in front of the shooter. Much of the interface is
# assembled at runtime — a status line rebuilt on refresh, a row label in the
# conditions table, a button relabelled with a count — and none of that appears
# in a layout. Log lines are skipped: they are never shown and translating them
# would only cost time.
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.){3,300})"')
SKIP_LINE = re.compile(r'Logger\.[iwe]\(|const val TAG|import |package |"\s*\+\s*$')
for root, _, files in os.walk(JAVA):
    for f in files:
        if not f.endswith(".kt"): continue
        for line in open(os.path.join(root, f), encoding="utf-8"):
            if SKIP_LINE.search(line): continue
            # a line that only builds a URL, a UUID or a preference key is noise
            if "http" in line or "UUID.fromString" in line: continue
            for m in LITERAL.finditer(line):
                add(m.group(1))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8") as fh:
    fh.write("\n".join(found))
print("%d strings -> %s" % (len(found), os.path.relpath(OUT, HERE)))
