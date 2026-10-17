p = 'app/src/main/java/com/example/helper/service/SolverService.kt'
with open(p, encoding='utf-8') as f:
    lines = f.readlines()
n = 0
for i, ln in enumerate(lines):
    if 'X 보정' in ln and '\\"' in ln:
        lines[i] = ln.replace('\\"', '"')
        n += 1
with open(p, 'w', encoding='utf-8') as f:
    f.writelines(lines)
print(f'fixed {n} line(s)')
