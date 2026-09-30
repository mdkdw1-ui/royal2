FILE = "/data/data/com.termux/files/home/royal2/app/src/main/java/com/example/helper/util/GridSeedDB.kt"
with open(FILE, "r", encoding="utf-8") as f:
    content = f.read()
old = "< 0.03f &&"
new = "< 0.01f &&"
if old in content:
    content = content.replace(old, new, 1)
    with open(FILE, "w", encoding="utf-8") as f:
        f.write(content)
    print("OK filter")
else:
    print("SKIP")
print("DONE")
