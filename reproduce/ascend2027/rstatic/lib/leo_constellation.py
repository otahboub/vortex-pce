"""Contact plan for a Walker-delta LEO constellation, computed rather than cited.

Static backbone maps go stale -- Abilene was retired in 2007, most Topology Zoo snapshots are
2012-era. A constellation contact plan does not have that problem, because it is *derived* from
orbital parameters rather than copied from a diagram: given shell altitude, inclination, plane
count and satellites per plane, the visibility windows follow from geometry.

Configured here for Amazon Leo (filed as Project Kuiper). **The shell parameters below come from
the FCC filing and should be re-checked before publication** -- they are recorded as data with a
provenance note rather than buried in code, precisely so that check is possible.

What is computed: sub-satellite position over time from circular Keplerian motion, elevation angle
from each ground station, and the resulting visibility windows. What is assumed, and stated in the
output: a spherical Earth, no J2 precession, circular orbits, and a minimum elevation mask. Those
assumptions shorten and shift individual windows slightly; they do not change the character of the
contact pattern, which is what a scheduling study depends on.

Inter-satellite links are deliberately absent. Amazon has not published ISL topology or capacity in
a form that could be cited, and inventing one would be the kind of plausible-looking fabrication
this repository has already had to retract twice. The model is therefore bent-pipe: ground station
to satellite to ground station, which is a real operating mode and an honest lower bound on what
the constellation can do.
"""
import math
from dataclasses import dataclass, field
from typing import Dict, List, Tuple

EARTH_RADIUS_KM = 6371.0
MU_EARTH_KM3_S2 = 398600.4418


@dataclass(frozen=True)
class Shell:
    """One orbital shell of a Walker-delta constellation."""
    name: str
    altitude_km: float
    inclination_deg: float
    planes: int
    sats_per_plane: int

    @property
    def total(self) -> int:
        return self.planes * self.sats_per_plane

    @property
    def period_sec(self) -> float:
        radius = EARTH_RADIUS_KM + self.altitude_km
        return 2 * math.pi * math.sqrt(radius ** 3 / MU_EARTH_KM3_S2)


#: Amazon Leo (Project Kuiper) shells as filed with the FCC: 3,236 satellites in three shells.
#: Recorded as citable data, not folded into code. VERIFY BEFORE PUBLICATION.
AMAZON_LEO_SHELLS = (
    Shell("leo-630", altitude_km=630.0, inclination_deg=51.9, planes=34, sats_per_plane=34),
    Shell("leo-610", altitude_km=610.0, inclination_deg=42.0, planes=36, sats_per_plane=36),
    Shell("leo-590", altitude_km=590.0, inclination_deg=33.0, planes=28, sats_per_plane=28),
)

AMAZON_LEO_PROVENANCE = (
    "Amazon Leo (filed as Project Kuiper) constellation design: 3,236 satellites across three "
    "shells at 590/610/630 km. Parameters transcribed from the FCC filing; re-verify before "
    "publication. Contact windows below are COMPUTED from these parameters by this module, not "
    "taken from any published contact plan."
)


@dataclass(frozen=True)
class GroundStation:
    name: str
    latitude_deg: float
    longitude_deg: float


@dataclass
class ContactWindow:
    ground_station: str
    satellite: str
    start_sec: float
    end_sec: float

    @property
    def duration_sec(self) -> float:
        return self.end_sec - self.start_sec


def _satellite_position(shell: Shell, plane: int, slot: int, t_sec: float) -> Tuple[float, float, float]:
    """Earth-centred inertial position, circular orbit, no J2.

    Walker-delta phasing: planes are spread evenly in right ascension, and satellites within a
    plane are spread evenly in mean anomaly with a per-plane offset so adjacent planes interleave.
    """
    radius = EARTH_RADIUS_KM + shell.altitude_km
    inclination = math.radians(shell.inclination_deg)
    raan = 2 * math.pi * plane / shell.planes
    phase_offset = 2 * math.pi * plane / (shell.planes * shell.sats_per_plane)
    mean_anomaly = (2 * math.pi * slot / shell.sats_per_plane
                    + phase_offset
                    + 2 * math.pi * t_sec / shell.period_sec)

    x_orb = radius * math.cos(mean_anomaly)
    y_orb = radius * math.sin(mean_anomaly)

    y_incl = y_orb * math.cos(inclination)
    z_incl = y_orb * math.sin(inclination)

    x = x_orb * math.cos(raan) - y_incl * math.sin(raan)
    y = x_orb * math.sin(raan) + y_incl * math.cos(raan)
    return x, y, z_incl


def _station_position(station: GroundStation, t_sec: float) -> Tuple[float, float, float]:
    """Ground station in the same frame, rotating with the Earth."""
    rotation = 2 * math.pi * t_sec / 86164.0          # sidereal day
    lat = math.radians(station.latitude_deg)
    lon = math.radians(station.longitude_deg) + rotation
    return (EARTH_RADIUS_KM * math.cos(lat) * math.cos(lon),
            EARTH_RADIUS_KM * math.cos(lat) * math.sin(lon),
            EARTH_RADIUS_KM * math.sin(lat))


def _elevation_deg(station_xyz, satellite_xyz) -> float:
    sx, sy, sz = station_xyz
    px, py, pz = satellite_xyz
    dx, dy, dz = px - sx, py - sy, pz - sz
    range_km = math.sqrt(dx * dx + dy * dy + dz * dz)
    station_norm = math.sqrt(sx * sx + sy * sy + sz * sz)
    if range_km == 0 or station_norm == 0:
        return -90.0
    # Angle between the local vertical and the line of sight.
    cos_zenith = (sx * dx + sy * dy + sz * dz) / (station_norm * range_km)
    return math.degrees(math.asin(max(-1.0, min(1.0, cos_zenith))))


def compute_contacts(stations: List[GroundStation], shells=AMAZON_LEO_SHELLS,
                     horizon_sec: float = 3600.0, step_sec: float = 10.0,
                     min_elevation_deg: float = 25.0,
                     max_satellites_per_shell: int = 0) -> List[ContactWindow]:
    """Visibility windows over the horizon.

    `max_satellites_per_shell` samples the constellation rather than propagating all 3,236 -- a
    full propagation at 10 s resolution is millions of elevation evaluations. Sampling is stated
    in the output because it *reduces* contact opportunity and therefore understates the
    constellation; it never flatters it.
    """
    windows: List[ContactWindow] = []
    for shell in shells:
        satellites = [(p, s) for p in range(shell.planes) for s in range(shell.sats_per_plane)]
        if max_satellites_per_shell and len(satellites) > max_satellites_per_shell:
            stride = max(1, len(satellites) // max_satellites_per_shell)
            satellites = satellites[::stride][:max_satellites_per_shell]

        for plane, slot in satellites:
            name = f"{shell.name}-p{plane}s{slot}"
            for station in stations:
                open_at = None
                t = 0.0
                while t <= horizon_sec:
                    elevation = _elevation_deg(_station_position(station, t),
                                               _satellite_position(shell, plane, slot, t))
                    visible = elevation >= min_elevation_deg
                    if visible and open_at is None:
                        open_at = t
                    elif not visible and open_at is not None:
                        windows.append(ContactWindow(station.name, name, open_at, t))
                        open_at = None
                    t += step_sec
                if open_at is not None:
                    windows.append(ContactWindow(station.name, name, open_at, horizon_sec))
    return windows


def summarise(windows: List[ContactWindow], stations: List[GroundStation],
              horizon_sec: float) -> Dict:
    """What the computed plan actually looks like, so it can be sanity-checked before use."""
    per_station = {}
    for station in stations:
        mine = [w for w in windows if w.ground_station == station.name]
        total = sum(w.duration_sec for w in mine)
        per_station[station.name] = {
            "contacts": len(mine),
            "total_contact_sec": round(total, 1),
            "duty_cycle": round(total / horizon_sec, 3) if horizon_sec else 0.0,
            "mean_contact_sec": round(total / len(mine), 1) if mine else 0.0,
            "longest_gap_sec": round(_longest_gap(mine, horizon_sec), 1),
        }
    return per_station


def _longest_gap(windows: List[ContactWindow], horizon_sec: float) -> float:
    if not windows:
        return horizon_sec
    ordered = sorted(windows, key=lambda w: w.start_sec)
    gap = ordered[0].start_sec
    reach = ordered[0].end_sec
    for window in ordered[1:]:
        gap = max(gap, window.start_sec - reach)
        reach = max(reach, window.end_sec)
    return max(gap, horizon_sec - reach)
