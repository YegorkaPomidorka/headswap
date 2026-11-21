"""Сравнение кадра с эталоном и поиск картинки (иконки) на кадре. Кадры - numpy (H, W, 3) uint8 RGB.

Точного совпадения не бывает: H.264 шумит на границах и градиентах. Поэтому пиксель «изменился», если после
размытия 3x3 разница по какому-то каналу больше tol, а кадр «тот же», если таких пикселей не больше max_ratio.
Часы, статус и уведомления закрываются масками (Rect).
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

Rect = tuple[int, int, int, int]            # x, y, w, h


@dataclass
class Diff:
    ratio: float                            # доля изменившихся пикселей среди незамаскированных
    mask: np.ndarray                        # (H, W) bool: изменившиеся пиксели
    same_size: bool = True


def load(path: Path) -> np.ndarray:
    """PNG -> RGB. Через imdecode: cv2.imread не открывает пути с кириллицей на Windows."""
    data = np.fromfile(str(path), np.uint8)
    bgr = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if bgr is None:
        raise ValueError(f"не картинка: {path}")
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)


def save(path: Path, rgb: np.ndarray):
    path.parent.mkdir(parents=True, exist_ok=True)
    ok, buf = cv2.imencode(".png", cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
    if not ok:
        raise ValueError(f"не удалось закодировать {path}")
    buf.tofile(str(path))


def diff(actual: np.ndarray, ref: np.ndarray, masks: tuple[Rect, ...] | list[Rect] = (), tol: int = 24) -> Diff:
    if actual.shape != ref.shape:
        return Diff(1.0, np.ones(actual.shape[:2], bool), same_size=False)
    a = cv2.GaussianBlur(actual, (3, 3), 0).astype(np.int16)
    b = cv2.GaussianBlur(ref, (3, 3), 0).astype(np.int16)
    changed = np.abs(a - b).max(axis=2) > tol
    keep = np.ones(changed.shape, bool)
    for x, y, w, h in masks:
        keep[max(0, y):y + h, max(0, x):x + w] = False
    changed &= keep
    total = int(keep.sum())
    return Diff(float(changed.sum()) / total if total else 0.0, changed)


def overlay(actual: np.ndarray, d: Diff, masks: tuple[Rect, ...] | list[Rect] = ()) -> np.ndarray:
    """Картинка для разбора: кадр притушен, изменившиеся пиксели красные, маски обведены синим."""
    out = (actual.astype(np.float32) * 0.45).astype(np.uint8)
    if d.same_size:
        out[d.mask] = (255, 40, 40)
    for x, y, w, h in masks:
        cv2.rectangle(out, (x, y), (x + w - 1, y + h - 1), (60, 120, 255), 2)
    return out


def find_image(frame: np.ndarray, tpl: np.ndarray, threshold: float = 0.9,
               region: Rect | None = None) -> tuple[Rect, float] | None:
    """Где на кадре картинка tpl (иконка, вырезанная из эталона): (рамка, score) или None, если score < threshold."""
    ox = oy = 0
    if region:
        x, y, w, h = region
        frame = frame[y:y + h, x:x + w]
        ox, oy = x, y
    th, tw = tpl.shape[:2]
    if frame.shape[0] < th or frame.shape[1] < tw:
        return None
    res = cv2.matchTemplate(frame, tpl, cv2.TM_CCOEFF_NORMED)
    _, score, _, (mx, my) = cv2.minMaxLoc(res)
    if score < threshold:
        return None
    return (ox + mx, oy + my, tw, th), float(score)


def same_frame(a: np.ndarray, b: np.ndarray, tol: int = 24, max_ratio: float = 0.002) -> bool:
    """Два соседних кадра показывают одно и то же (для «экран успокоился»)."""
    return diff(a, b, (), tol).ratio <= max_ratio
