import json
import math
import os
import threading
import time
from typing import Dict, List, Optional, Tuple

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import HTMLResponse, Response

cv2.setNumThreads(1)

app = FastAPI(title="Royal2 Zero-Mistake Vision Server")

DATA_DIR = os.path.join(os.path.dirname(__file__), "templates_db")
os.makedirs(DATA_DIR, exist_ok=True)
META_PATH = os.path.join(DATA_DIR, "meta.json")

COLOR_NAMES = {1: "빨강", 2: "노랑", 3: "초록", 4: "파랑", 5: "보라"}
COLOR_BGR = {
    1: (60, 60, 255),
    2: (0, 220, 255),
    3: (60, 220, 60),
    4: (255, 140, 40),
    5: (220, 70, 200),
}


class ZeroMistakeServerEngine:
    def __init__(self):
        self.lock = threading.Lock()
        self.templates: Dict[int, dict] = {}
        self.pitch_ratio: float = 0.105
        self.prev_gray: Optional[np.ndarray] = None
        self.prev_moves: List[dict] = []
        self.latest_debug_jpg: Optional[bytes] = None
        self.latest_stats: dict = {"status": "waiting"}
        self.load_templates()

    def _prepare_template(self, bgr64: np.ndarray) -> dict:
        gray = cv2.cvtColor(bgr64, cv2.COLOR_BGR2GRAY)
        gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
        edge = cv2.magnitude(gx, gy)
        return {"bgr64": bgr64, "gray64": gray, "edge64": edge}

    def load_templates(self):
        with self.lock:
            self.templates.clear()
            if os.path.exists(META_PATH):
                try:
                    with open(META_PATH, "r", encoding="utf-8") as f:
                        meta = json.load(f)
                        self.pitch_ratio = float(meta.get("pitch_ratio", 0.105))
                except Exception:
                    pass
            for c in range(1, 6):
                p = os.path.join(DATA_DIR, f"gem_{c}.png")
                if os.path.exists(p):
                    img = cv2.imread(p, cv2.IMREAD_COLOR)
                    if img is not None:
                        img64 = cv2.resize(img, (64, 64), interpolation=cv2.INTER_AREA)
                        self.templates[c] = self._prepare_template(img64)

    def save_template(self, color: int, img_bytes: bytes, pitch_ratio: float):
        arr = np.frombuffer(img_bytes, np.uint8)
        img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if img is None:
            raise ValueError("Invalid image")
        img64 = cv2.resize(img, (64, 64), interpolation=cv2.INTER_AREA)
        with self.lock:
            cv2.imwrite(os.path.join(DATA_DIR, f"gem_{color}.png"), img64)
            self.pitch_ratio = pitch_ratio
            with open(META_PATH, "w", encoding="utf-8") as f:
                json.dump({"pitch_ratio": self.pitch_ratio}, f)
            self.templates[color] = self._prepare_template(img64)
            self.prev_moves = []

    def clear_templates(self):
        with self.lock:
            for c in range(1, 6):
                p = os.path.join(DATA_DIR, f"gem_{c}.png")
                if os.path.exists(p):
                    os.remove(p)
            self.templates.clear()
            self.prev_moves = []

    @staticmethod
    def _pearson_zero_norm(patch: np.ndarray, tpl_norm: np.ndarray, n: int) -> float:
        p = patch.astype(np.float32).ravel()
        mean_p = float(np.mean(p))
        sum_p2 = float(np.dot(p, p))
        var_p = sum_p2 - n * mean_p * mean_p
        if var_p <= 1e-4:
            return 0.0
        dot = float(np.dot(p, tpl_norm))
        return dot / math.sqrt(var_p)

    @staticmethod
    def _norm_vec(arr: np.ndarray) -> np.ndarray:
        v = arr.astype(np.float32).ravel()
        v = v - np.mean(v)
        norm = float(np.linalg.norm(v))
        if norm > 1e-6:
            v /= norm
        return v

    def _nms_peaks(self, heatmap: np.ndarray, thr: float, min_dist: int) -> List[Tuple[int, int, float]]:
        ys, xs = np.where(heatmap >= thr)
        if len(xs) == 0:
            return []
        vals = heatmap[ys, xs]
        order = np.argsort(-vals)
        peaks = []
        suppressed = np.zeros(heatmap.shape, dtype=bool)
        h, w = heatmap.shape
        for idx in order:
            y, x, val = int(ys[idx]), int(xs[idx]), float(vals[idx])
            if suppressed[y, x]:
                continue
            peaks.append((x, y, val))
            y0, y1 = max(0, y - min_dist), min(h, y + min_dist + 1)
            x0, x1 = max(0, x - min_dist), min(w, x + min_dist + 1)
            suppressed[y0:y1, x0:x1] = True
            if len(peaks) >= 120:
                break
        return peaks

    @staticmethod
    def _circular_phase(vals: List[float], pitch: float) -> float:
        if not vals or pitch <= 1e-3:
            return 0.0
        ang = [2.0 * math.pi * (v % pitch) / pitch for v in vals]
        sx = sum(math.cos(a) for a in ang)
        sy = sum(math.sin(a) for a in ang)
        theta = math.atan2(sy, sx)
        if theta < 0:
            theta += 2.0 * math.pi
        return theta * pitch / (2.0 * math.pi)

    @staticmethod
    def _has_existing_match3(grid: np.ndarray) -> bool:
        rows, cols = grid.shape
        for r in range(rows):
            run = 1
            for c in range(1, cols):
                if grid[r, c] > 0 and grid[r, c] == grid[r, c - 1]:
                    run += 1
                    if run >= 3:
                        return True
                else:
                    run = 1
        for c in range(cols):
            run = 1
            for r in range(1, rows):
                if grid[r, c] > 0 and grid[r, c] == grid[r - 1, c]:
                    run += 1
                    if run >= 3:
                        return True
                else:
                    run = 1
        return False

    @staticmethod
    def _five_line(grid: np.ndarray, r: int, c: int) -> List[Tuple[int, int]]:
        color = int(grid[r, c])
        if color <= 0:
            return []
        rows, cols = grid.shape
        cc = c
        while cc > 0 and grid[r, cc - 1] == color:
            cc -= 1
        hline = []
        while cc < cols and grid[r, cc] == color:
            hline.append((r, cc))
            cc += 1
        if len(hline) >= 5:
            return hline
        rr = r
        while rr > 0 and grid[rr - 1, c] == color:
            rr -= 1
        vline = []
        while rr < rows and grid[rr, c] == color:
            vline.append((rr, c))
            rr += 1
        if len(vline) >= 5:
            return vline
        return []

    def analyze(self, img_bytes: bytes, screen_w: int, screen_h: int, roi_top: int) -> dict:
        t0 = time.time()
        arr = np.frombuffer(img_bytes, np.uint8)
        work_bgr = cv2.imdecode(arr, cv2.IMREAD_COLOR)
        if work_bgr is None:
            return {"moving": False, "moves": [], "gems": [], "error": "decode_failed"}

        work_h, work_w = work_bgr.shape[:2]
        scale = work_w / float(max(screen_w, 1))
        work_gray = cv2.cvtColor(work_bgr, cv2.COLOR_BGR2GRAY)

        with self.lock:
            templates = dict(self.templates)
            pitch_ratio = self.pitch_ratio

        if len(templates) < 4:
            self._render_debug(work_bgr, [], [], None, "Need >= 4 gems")
            return {"moving": False, "moves": [], "gems": [], "need_gems": True}

        with self.lock:
            prev = self.prev_gray
            self.prev_gray = work_gray.copy()
            if prev is not None and prev.shape == work_gray.shape:
                mad = float(np.mean(cv2.absdiff(work_gray, prev)))
            else:
                mad = 0.0

        if mad > 3.5:
            with self.lock:
                self.prev_moves = []
            self._render_debug(work_bgr, [], [], None, f"MOVING mad={mad:.1f}")
            return {"moving": True, "mad": round(mad, 2), "moves": [], "gems": []}

        half_w = max(120, work_w // 2)
        half_h = max(120, work_h // 2)
        half_gray = cv2.resize(work_gray, (half_w, half_h), interpolation=cv2.INTER_AREA)
        base_pitch_work = max(24.0, pitch_ratio * work_w)

        best_scale = 1.0
        best_scale_score = -1.0
        best_coarse_hits = 0

        for s in (0.76, 0.84, 0.92, 1.00, 1.10, 1.20, 1.30):
            in_sz_h = max(10, int(round(base_pitch_work * s * 0.5 * (40.0 / 64.0))))
            if in_sz_h >= min(half_w, half_h) - 4:
                continue
            sc_score = 0.0
            hits = 0
            min_d = max(6, int(round(in_sz_h * 1.1)))
            for c, tpl in templates.items():
                t_g = cv2.resize(tpl["gray64"][12:52, 12:52], (in_sz_h, in_sz_h), interpolation=cv2.INTER_AREA)
                res = cv2.matchTemplate(half_gray, t_g, cv2.TM_CCOEFF_NORMED)
                peaks = self._nms_peaks(res, 0.70, min_d)
                hits += len(peaks)
                sc_score += sum(v - 0.65 for _, _, v in peaks)
            if sc_score > best_scale_score:
                best_scale_score = sc_score
                best_scale = s
                best_coarse_hits = hits

        if best_coarse_hits < 6:
            with self.lock:
                self.prev_moves = []
            self._render_debug(work_bgr, [], [], None, f"NO BOARD hits={best_coarse_hits}")
            return {"moving": False, "moves": [], "gems": [], "rows": 0, "cols": 0}

        cell_w = int(round(base_pitch_work * best_scale))
        in_sz = max(16, int(round(cell_w * (40.0 / 64.0))))
        out_sz = max(in_sz + 4, int(round(cell_w * (54.0 / 64.0))))
        gx = cv2.Sobel(work_gray, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(work_gray, cv2.CV_32F, 0, 1, ksize=3)
        work_edge = cv2.magnitude(gx, gy)

        scaled_tpls = {}
        for c, tpl in templates.items():
            ig = cv2.resize(tpl["gray64"][12:52, 12:52], (in_sz, in_sz), interpolation=cv2.INTER_AREA)
            ib = cv2.resize(tpl["bgr64"][12:52, 12:52], (in_sz, in_sz), interpolation=cv2.INTER_AREA)
            ie = cv2.resize(tpl["edge64"][12:52, 12:52], (in_sz, in_sz), interpolation=cv2.INTER_AREA)
            ob = cv2.resize(tpl["bgr64"][5:59, 5:59], (out_sz, out_sz), interpolation=cv2.INTER_AREA)
            scaled_tpls[c] = {
                "ig": ig, "ib": ib,
                "ib_norm": [self._norm_vec(ib[:, :, ch]) for ch in range(3)],
                "ie_norm": self._norm_vec(ie),
                "ob": ob,
                "ob_norm": [self._norm_vec(ob[:, :, ch]) for ch in range(3)],
            }

        raw_cands = []
        min_d_work = max(10, int(round(cell_w * 0.58)))
        for c, st in scaled_tpls.items():
            res = cv2.matchTemplate(work_gray, st["ig"], cv2.TM_CCOEFF_NORMED)
            for px, py, g_score in self._nms_peaks(res, 0.72, min_d_work):
                raw_cands.append((px, py, c, g_score))

        raw_cands.sort(key=lambda x: -x[3])
        dedup_cands = []
        for cand in raw_cands:
            cx, cy = cand[0] + in_sz / 2.0, cand[1] + in_sz / 2.0
            if any(math.hypot(cx - (d[0] + in_sz / 2.0), cy - (d[1] + in_sz / 2.0)) < cell_w * 0.55 for d in dedup_cands):
                continue
            dedup_cands.append(cand)

        n_in = in_sz * in_sz
        n_out = out_sz * out_sz
        margin = max(2, (out_sz - in_sz) // 2)
        ring_mask = np.ones((out_sz, out_sz), dtype=bool)
        ring_mask[margin:out_sz - margin, margin:out_sz - margin] = False

        verified_gems = []
        rejected_debug = []

        for px, py, _, g_score in dedup_cands:
            if px < 0 or py < 0 or px + in_sz > work_w or py + in_sz > work_h:
                continue
            patch_bgr = work_bgr[py:py + in_sz, px:px + in_sz]
            patch_edge = work_edge[py:py + in_sz, px:px + in_sz]

            color_scores = {}
            color_diffs = {}
            for c, st in scaled_tpls.items():
                r_corr = min(
                    self._pearson_zero_norm(patch_bgr[:, :, ch], st["ib_norm"][ch], n_in)
                    for ch in range(3)
                )
                rgb_diff = float(np.mean(cv2.absdiff(patch_bgr, st["ib"])))
                color_scores[c] = r_corr
                color_diffs[c] = rgb_diff

            sorted_cols = sorted(color_scores.items(), key=lambda kv: -kv[1])
            best_c, best_rgb_corr = sorted_cols[0]
            second_rgb_corr = sorted_cols[1][1] if len(sorted_cols) > 1 else 0.0
            best_rgb_diff = color_diffs[best_c]

            cx = px + in_sz / 2.0
            cy = py + in_sz / 2.0

            if best_rgb_corr < 0.72 or best_rgb_diff > 40.0:
                rejected_debug.append((cx, cy, f"rgb({best_rgb_corr:.2f},{best_rgb_diff:.0f})"))
                continue
            if (best_rgb_corr - second_rgb_corr) < 0.05:
                rejected_debug.append((cx, cy, "margin"))
                continue

            edge_corr = self._pearson_zero_norm(patch_edge, scaled_tpls[best_c]["ie_norm"], n_in)
            if edge_corr < 0.45:
                rejected_debug.append((cx, cy, f"edge({edge_corr:.2f})"))
                continue

            ox0 = int(round(cx - out_sz / 2.0))
            oy0 = int(round(cy - out_sz / 2.0))
            movable = True
            if ox0 >= 0 and oy0 >= 0 and ox0 + out_sz <= work_w and oy0 + out_sz <= work_h:
                opatch = work_bgr[oy0:oy0 + out_sz, ox0:ox0 + out_sz]
                ob = scaled_tpls[best_c]["ob"]
                ob_norm = scaled_tpls[best_c]["ob_norm"]
                o_corr = min(self._pearson_zero_norm(opatch[:, :, ch], ob_norm[ch], n_out) for ch in range(3))
                ring_diff = float(np.mean(np.abs(opatch.astype(np.float32) - ob.astype(np.float32))[ring_mask]))
                if o_corr < 0.58 or ring_diff > 46.0:
                    movable = False
            else:
                movable = False

            verified_gems.append({"cx": cx, "cy": cy, "color": best_c, "movable": movable, "score": best_rgb_corr})

        if len(verified_gems) < 8:
            with self.lock:
                self.prev_moves = []
            self._render_debug(work_bgr, verified_gems, rejected_debug, None, f"gems<8 ({len(verified_gems)})")
            return {"moving": False, "moves": [], "gems": [], "rows": 0, "cols": 0}

        dxs, dys = [], []
        for i in range(len(verified_gems)):
            gi = verified_gems[i]
            for j in range(i + 1, len(verified_gems)):
                gj = verified_gems[j]
                dx = abs(gi["cx"] - gj["cx"])
                dy = abs(gi["cy"] - gj["cy"])
                if dy < cell_w * 0.25 and 0.75 * cell_w <= dx <= 1.25 * cell_w:
                    dxs.append(dx)
                if dx < cell_w * 0.25 and 0.75 * cell_w <= dy <= 1.25 * cell_w:
                    dys.append(dy)

        pitch_x = float(np.median(dxs)) if len(dxs) >= 3 else float(cell_w)
        pitch_y = float(np.median(dys)) if len(dys) >= 3 else float(cell_w)

        phase_x = self._circular_phase([g["cx"] for g in verified_gems], pitch_x)
        phase_y = self._circular_phase([g["cy"] for g in verified_gems], pitch_y)

        snapped = []
        for g in verified_gems:
            c_idx = int(round((g["cx"] - phase_x) / pitch_x))
            r_idx = int(round((g["cy"] - phase_y) / pitch_y))
            ex = abs(g["cx"] - (phase_x + c_idx * pitch_x))
            ey = abs(g["cy"] - (phase_y + r_idx * pitch_y))
            if ex <= pitch_x * 0.18 and ey <= pitch_y * 0.18:
                g["r_raw"] = r_idx
                g["c_raw"] = c_idx
                snapped.append(g)

        if len(snapped) < 8:
            with self.lock:
                self.prev_moves = []
            self._render_debug(work_bgr, verified_gems, rejected_debug, None, "snap<8")
            return {"moving": False, "moves": [], "gems": [], "rows": 0, "cols": 0}

        min_r = min(g["r_raw"] for g in snapped)
        max_r = max(g["r_raw"] for g in snapped)
        min_c = min(g["c_raw"] for g in snapped)
        max_c = max(g["c_raw"] for g in snapped)
        rows = max(1, min(15, max_r - min_r + 1))
        cols = max(1, min(15, max_c - min_c + 1))

        color_grid = np.full((rows, cols), -1, dtype=np.int32)
        movable_grid = np.zeros((rows, cols), dtype=bool)
        best_cell_score = np.full((rows, cols), -1.0, dtype=np.float32)

        for g in snapped:
            r = g["r_raw"] - min_r
            c = g["c_raw"] - min_c
            if 0 <= r < rows and 0 <= c < cols and g["score"] > best_cell_score[r, c]:
                color_grid[r, c] = g["color"]
                movable_grid[r, c] = g["movable"]
                best_cell_score[r, c] = g["score"]

        raw_moves = []
        if not self._has_existing_match3(color_grid):
            origin_x = phase_x + min_c * pitch_x
            origin_y = phase_y + min_r * pitch_y

            def cell_to_screen(rr: int, cc: int) -> Tuple[float, float]:
                wx = origin_x + cc * pitch_x
                wy = origin_y + rr * pitch_y
                return (wx / scale, roi_top + wy / scale)

            sim = color_grid.copy()
            for r in range(rows):
                for c in range(cols):
                    c1 = int(sim[r, c])
                    if c1 <= 0 or not movable_grid[r, c]:
                        continue
                    for dr, dc in ((0, 1), (1, 0)):
                        nr, nc = r + dr, c + dc
                        if nr >= rows or nc >= cols:
                            continue
                        c2 = int(sim[nr, nc])
                        if c2 <= 0 or not movable_grid[nr, nc] or c1 == c2:
                            continue
                        sim[r, c], sim[nr, nc] = c2, c1

                        line1 = self._five_line(sim, r, c)
                        if line1:
                            tx, ty = cell_to_screen(r, c)
                            sx, sy = cell_to_screen(nr, nc)
                            raw_moves.append({
                                "targetRow": r, "targetCol": c,
                                "sourceRow": nr, "sourceCol": nc,
                                "targetX": tx, "targetY": ty,
                                "sourceX": sx, "sourceY": sy,
                                "color": c2,
                                "lineCoords": [cell_to_screen(lr, lc) for lr, lc in line1],
                                "lineCells": line1,
                            })
                        line2 = self._five_line(sim, nr, nc)
                        if line2:
                            tx, ty = cell_to_screen(nr, nc)
                            sx, sy = cell_to_screen(r, c)
                            raw_moves.append({
                                "targetRow": nr, "targetCol": nc,
                                "sourceRow": r, "sourceCol": c,
                                "targetX": tx, "targetY": ty,
                                "sourceX": sx, "sourceY": sy,
                                "color": c1,
                                "lineCoords": [cell_to_screen(lr, lc) for lr, lc in line2],
                                "lineCells": line2,
                            })
                        sim[r, c], sim[nr, nc] = c1, c2

        uniq_moves = []
        seen_keys = set()
        for m in raw_moves:
            k = (m["targetRow"], m["targetCol"], m["sourceRow"], m["sourceCol"])
            if k not in seen_keys:
                seen_keys.add(k)
                uniq_moves.append(m)

        tol = (pitch_x / scale) * 0.35
        confirmed = []
        with self.lock:
            for m in uniq_moves:
                matched_prev = any(
                    pm["color"] == m["color"]
                    and math.hypot(pm["targetX"] - m["targetX"], pm["targetY"] - m["targetY"]) < tol
                    and math.hypot(pm["sourceX"] - m["sourceX"], pm["sourceY"] - m["sourceY"]) < tol
                    for pm in self.prev_moves
                )
                if matched_prev:
                    confirmed.append(m)
            self.prev_moves = uniq_moves

        elapsed_ms = (time.time() - t0) * 1000.0
        gems_out = [
            {"x": g["cx"] / scale, "y": roi_top + g["cy"] / scale, "color": g["color"], "movable": g["movable"]}
            for g in snapped
        ]
        grid_info = {
            "rows": rows, "cols": cols,
            "ox": phase_x + min_c * pitch_x,
            "oy": phase_y + min_r * pitch_y,
            "px": pitch_x, "py": pitch_y,
            "scale": scale, "roi_top": roi_top,
        }
        self._render_debug(
            work_bgr, snapped, rejected_debug, grid_info,
            f"{rows}x{cols} gems={len(snapped)} moves={len(confirmed)} {elapsed_ms:.0f}ms",
            confirmed
        )
        return {
            "moving": False,
            "rows": rows, "cols": cols,
            "cellW": pitch_x / scale,
            "cellH": pitch_y / scale,
            "gems": gems_out,
            "moves": [
                {
                    "targetX": m["targetX"], "targetY": m["targetY"],
                    "sourceX": m["sourceX"], "sourceY": m["sourceY"],
                    "color": m["color"],
                    "lineCoords": m["lineCoords"],
                }
                for m in confirmed
            ],
            "ms": round(elapsed_ms, 1),
        }

    def _render_debug(
        self, work_bgr: np.ndarray, gems: list, rejected: list,
        grid_info: Optional[dict], title: str, moves: Optional[list] = None
    ):
        vis = work_bgr.copy()
        if grid_info is not None:
            ox, oy = grid_info["ox"], grid_info["oy"]
            px, py = grid_info["px"], grid_info["py"]
            for r in range(grid_info["rows"]):
                for c in range(grid_info["cols"]):
                    x0 = int(round(ox + (c - 0.5) * px))
                    y0 = int(round(oy + (r - 0.5) * py))
                    x1 = int(round(ox + (c + 0.5) * px))
                    y1 = int(round(oy + (r + 0.5) * py))
                    cv2.rectangle(vis, (x0, y0), (x1, y1), (70, 70, 70), 1)

        for cx, cy, reason in rejected:
            cv2.putText(vis, reason, (int(cx) - 20, int(cy)), cv2.FONT_HERSHEY_SIMPLEX, 0.35, (140, 140, 140), 1)

        for g in gems:
            col = COLOR_BGR.get(g["color"], (255, 255, 255))
            pt = (int(round(g["cx"])), int(round(g["cy"])))
            if g["movable"]:
                cv2.circle(vis, pt, 7, col, -1)
                cv2.circle(vis, pt, 7, (255, 255, 255), 1)
            else:
                cv2.drawMarker(vis, pt, (180, 180, 180), cv2.MARKER_TILTED_CROSS, 12, 2)

        if moves and grid_info is not None:
            sc = grid_info["scale"]
            rt = grid_info["roi_top"]
            for m in moves:
                sx, sy = int(m["sourceX"] * sc), int((m["sourceY"] - rt) * sc)
                tx, ty = int(m["targetX"] * sc), int((m["targetY"] - rt) * sc)
                for lx, ly in m["lineCoords"]:
                    plx, ply = int(lx * sc), int((ly - rt) * sc)
                    cv2.circle(vis, (plx, ply), 14, (0, 215, 255), 2)
                cv2.arrowedLine(vis, (sx, sy), (tx, ty), (0, 255, 130), 4, tipLength=0.35)

        cv2.rectangle(vis, (0, 0), (vis.shape[1], 26), (0, 0, 0), -1)
        cv2.putText(vis, title, (8, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.52, (0, 255, 200), 1)
        ok, buf = cv2.imencode(".jpg", vis, [int(cv2.IMWRITE_JPEG_QUALITY), 78])
        if ok:
            with self.lock:
                self.latest_debug_jpg = buf.tobytes()
                self.latest_stats = {"title": title, "ts": time.strftime("%H:%M:%S")}


engine = ZeroMistakeServerEngine()


@app.get("/health")
def health():
    return {"ok": True, "templates": sorted(list(engine.templates.keys())), "pitch_ratio": engine.pitch_ratio}


@app.post("/upload_gem")
async def upload_gem(
    color: int = Form(...),
    pitch_ratio: float = Form(...),
    file: UploadFile = File(...),
):
    data = await file.read()
    engine.save_template(color, data, pitch_ratio)
    return {"ok": True, "registered": sorted(list(engine.templates.keys()))}


@app.post("/clear_gems")
def clear_gems():
    engine.clear_templates()
    return {"ok": True}


@app.post("/analyze")
async def analyze(
    screen_w: int = Form(...),
    screen_h: int = Form(...),
    roi_top: int = Form(...),
    file: UploadFile = File(...),
):
    data = await file.read()
    return engine.analyze(data, screen_w, screen_h, roi_top)


@app.get("/debug/image")
def debug_image():
    with engine.lock:
        jpg = engine.latest_debug_jpg
    if not jpg:
        return Response(status_code=204)
    return Response(content=jpg, media_type="image/jpeg")


@app.get("/", response_class=HTMLResponse)
def dashboard():
    return """<!doctype html>
<html><head><meta charset="utf-8"><title>Royal2 Debug</title>
<style>body{background:#111;color:#eee;font-family:sans-serif;text-align:center;margin:16px}
img{max-width:95vw;max-height:85vh;border:1px solid #444}</style></head>
<body>
<h3>OOXOO Zero-Mistake Live Debug</h3>
<img id="dbg" src="/debug/image" />
<script>setInterval(()=>{document.getElementById('dbg').src='/debug/image?t='+Date.now();},450);</script>
</body></html>"""
