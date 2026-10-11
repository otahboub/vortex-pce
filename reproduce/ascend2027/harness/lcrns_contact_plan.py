#!/usr/bin/env python3
"""Phase B, Step 1 — real contact plan from NASA's LCRNS Reference Constellation 3.1.

Propagates the five published ELFO satellites (Keplerian elements, study-plan Appendix A) in the
Moon-centered inertial frame with a two-body model and computes, per time slot:
  surf[r][t]  : relay r visible above the horizon of a lunar south-pole surface site
  earth[r][t] : relay r has line-of-sight to Earth (not occulted by the Moon)

These arrays are the R_DET temporal topology consumed by hulc_structural.py, replacing the synthetic
generator. Two stated approximations (fine for contact-window geometry over ~1 day, and flagged in
the write-up): (a) two-body propagation — the ELFO's long-term stability needs Earth perturbations,
but 1-day window geometry is dominated by the Keplerian motion; (b) a fixed inertial Earth direction
(Earth-Moon geometry moves ~13 deg/day). Phase B's honesty note records both.

Source elements (Moon PA frame, J2000, epoch 2027-03-01), NASA GSFC LCRNS Reference Constellation 3.1.
"""
from __future__ import annotations
import math

MU_MOON = 4902.800066     # km^3/s^2
R_MOON = 1737.4           # km
MOON_ROT_RATE = 2 * math.pi / (27.321661 * 86400.0)   # rad/s (sidereal)
SLOT_SECONDS = 60
HORIZON = 24 * 60
MASK_DEG = 5.0            # surface elevation mask
SITE_LAT_DEG = -85.0     # south-pole site
EARTH_DIR = (1.0, 0.0, 0.0)   # fixed inertial Earth direction (approx.)

# SMA(km), ECC, INC(deg), RAAN(deg), AOP(deg), TA(deg)
LCRNS = [
    (11315.936501, 0.691982, 59.373229, 321.019197, 92.494031, 0.000000),
    (11317.948675, 0.691982, 58.951732, 320.997768, 92.505016, 180.000000),
    (11305.413654, 0.691982, 52.733096, 81.148790, 92.062891, 140.049207),
    (11326.302154, 0.691982, 52.513419, 81.138818, 92.068945, 195.992393),
    (11307.882863, 0.691982, 56.310396, 204.889626, 85.444071, 164.007607),
]


def _rot_z(v, a):
    c, s = math.cos(a), math.sin(a)
    x, y, z = v
    return (c * x - s * y, s * x + c * y, z)


def _rot_x(v, a):
    c, s = math.cos(a), math.sin(a)
    x, y, z = v
    return (x, c * y - s * z, s * y + c * z)


def _dot(a, b):
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _norm(a):
    return math.sqrt(_dot(a, a))


def _sub(a, b):
    return (a[0] - b[0], a[1] - b[1], a[2] - b[2])


def _unit(a):
    n = _norm(a)
    return (a[0] / n, a[1] / n, a[2] / n) if n else a


def _elem_to_pos(elem, t):
    a, e, i, raan, aop, ta0 = elem
    i, raan, aop, ta0 = map(math.radians, (i, raan, aop, ta0))
    # Mean anomaly at epoch from true anomaly.
    E0 = 2 * math.atan2(math.sqrt(1 - e) * math.sin(ta0 / 2),
                        math.sqrt(1 + e) * math.cos(ta0 / 2))
    M0 = E0 - e * math.sin(E0)
    n = math.sqrt(MU_MOON / a ** 3)
    M = M0 + n * t
    # Solve Kepler for E.
    E = M
    for _ in range(50):
        dE = (E - e * math.sin(E) - M) / (1 - e * math.cos(E))
        E -= dE
        if abs(dE) < 1e-12:
            break
    nu = 2 * math.atan2(math.sqrt(1 + e) * math.sin(E / 2),
                        math.sqrt(1 - e) * math.cos(E / 2))
    r = a * (1 - e * math.cos(E))
    pf = (r * math.cos(nu), r * math.sin(nu), 0.0)
    v = _rot_z(pf, aop)
    v = _rot_x(v, i)
    v = _rot_z(v, raan)
    return v


def _site_pos(t):
    lat = math.radians(SITE_LAT_DEG)
    lon = MOON_ROT_RATE * t
    return (R_MOON * math.cos(lat) * math.cos(lon),
            R_MOON * math.cos(lat) * math.sin(lon),
            R_MOON * math.sin(lat))


def build_lcrns_visibility():
    nrel = len(LCRNS)
    surf = [[False] * HORIZON for _ in range(nrel)]
    earth = [[False] * HORIZON for _ in range(nrel)]
    mask = math.sin(math.radians(MASK_DEG))
    ed = _unit(EARTH_DIR)
    for tslot in range(HORIZON):
        t = tslot * SLOT_SECONDS
        site = _site_pos(t)
        up = _unit(site)
        for r, elem in enumerate(LCRNS):
            p = _elem_to_pos(elem, t)
            # Surface visibility: relay above the site's local horizon (elevation > mask).
            los = _sub(p, site)
            if _dot(_unit(los), up) >= mask:
                surf[r][tslot] = True
            # Earth visibility: relay on Earth side, or peeking over the Moon's limb.
            d_par = _dot(p, ed)
            perp = _sub(p, (ed[0] * d_par, ed[1] * d_par, ed[2] * d_par))
            if d_par > 0 or _norm(perp) > R_MOON:
                earth[r][tslot] = True
    return surf, earth


def _stats(surf, earth):
    nrel = len(surf)
    slots = HORIZON
    surf_any = sum(1 for s in range(slots) if any(surf[r][s] for r in range(nrel)))
    earth_any = sum(1 for s in range(slots) if any(earth[r][s] for r in range(nrel)))
    both_any = sum(1 for s in range(slots)
                   if any(surf[r][s] for r in range(nrel)) and any(earth[r][s] for r in range(nrel)))
    # Downlink-capable slots: some relay both surface- and Earth-visible in the same slot (direct),
    # OR (store-and-forward) surface-visible earlier + Earth-visible now — we report the direct one.
    per_relay_surf = [round(100 * sum(surf[r]) / slots, 1) for r in range(nrel)]
    per_relay_earth = [round(100 * sum(earth[r]) / slots, 1) for r in range(nrel)]
    # Longest blackout in "any surface relay" coverage.
    longest_gap = cur = 0
    for s in range(slots):
        if any(surf[r][s] for r in range(nrel)):
            cur = 0
        else:
            cur += 1
            longest_gap = max(longest_gap, cur)
    return {
        "surface_coverage_pct": round(100 * surf_any / slots, 1),
        "earth_coverage_pct": round(100 * earth_any / slots, 1),
        "both_coverage_pct": round(100 * both_any / slots, 1),
        "per_relay_surface_pct": per_relay_surf,
        "per_relay_earth_pct": per_relay_earth,
        "longest_surface_blackout_min": longest_gap,
    }


if __name__ == "__main__":
    surf, earth = build_lcrns_visibility()
    st = _stats(surf, earth)
    print("LCRNS Reference Constellation 3.1 — 24h contact plan (60s slots, south-pole site)")
    for k, v in st.items():
        print(f"  {k}: {v}")
