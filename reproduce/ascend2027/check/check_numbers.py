#!/usr/bin/env python3
"""check_numbers.py — recompute every number in the paper from this package's result files.

Each check recomputes a value from results/ and compares it with the exact phrase the paper prints,
frozen in paper_numbers.json (exported from the authors' manuscript checker, which runs the same code
against the manuscript text). Exit status is non-zero on any mismatch.

Usage: python3 reproduce/ascend2027/check/check_numbers.py
       (run harness/fetch_rucop_plans.py first: two checks count contacts in the RUCoP plans)

"""
import csv
import json
import os
import re
import statistics as st
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # reproduce/ascend2027
RES = os.path.join(ROOT, "results")
BENCH = RES
RES = os.environ.get("VERIFY_RESULTS", RES)
BENCH = os.environ.get("VERIFY_BENCH", BENCH)
FIX = os.environ.get("VERIFY_FIXTURES", os.path.join(ROOT, "harness/datasets/fixtures"))
EXPORT = sys.argv[sys.argv.index("--export") + 1] if "--export" in sys.argv else None
EXPECTED = json.load(open(sys.argv[sys.argv.index("--expected") + 1] if "--expected" in sys.argv
                          else os.path.join(os.path.dirname(os.path.abspath(__file__)), "paper_numbers.json")))
HERE = os.path.dirname(os.path.abspath(__file__))
TEXT = "" if EXPECTED else open(os.path.join(HERE, "vortexpce_rdet_preprint.md")).read()


def rows(name):
    return list(csv.DictReader(open(os.path.join(RES, name))))


checks, covered, stated, exported = [], set(), {}, []


def expect_in(label, fragments, corpus):
    """Every fragment must appear verbatim in the manuscript (or equal the frozen phrase, with --expected)."""
    fragments = list(fragments)
    if EXPECTED is not None:
        want = EXPECTED.get(label)
        missing = fragments if want is None else [f for f, w in zip(fragments, want) if f != w]
        if want is not None and len(want) != len(fragments):
            missing = fragments
    else:
        missing = [f for f in fragments if f not in corpus]
    checks.append((label, missing))
    assert label not in dict(exported), f"duplicate check label: {label}"
    exported.append((label, fragments))
    return fragments


def expect(label, *fragments):
    fragments = expect_in(label, fragments, TEXT)
    stated[label] = fragments
    for f in fragments:
        covered.update(re.findall(r"\d+(?:[.,]\d+)?", f))


f1 = lambda x: f"{x:.1f}"  # noqa: E731
f3 = lambda x: f"{x:.3f}"  # noqa: E731

# --- A1 (§5.1, abstract, Table 2) ---
# The paper's RRN-A numbers: VortexPCE on the regime-aware rate default, ION through the link emulator
# (rrnA_rdet_final.csv). The submitted extended abstract keeps its own result file (rrnA_rdet_flagship.csv).
a1 = rows("rrnA_rdet_final.csv")
a1abs = rows("rrnA_rdet_flagship.csv")


def a1m(load, arm, key, data=None):
    v = [float(r[key]) for r in (a1 if data is None else data) if r["load"] == load and r["arm"] == arm]
    return st.mean(v), min(v), max(v)


for load, label in (("0.5", "0.5×"), ("1.0", "1×")):
    for arm in ("vortex:1.0", "vortex:0.9", "ion", "odl"):
        m, _, _ = a1m(load, arm, "share_of_ceiling_pct")
        expect(f"A1 {label} {arm} share", f1(m))
        if arm != "vortex:0.9":  # the 0.9 default's critical delivery is shown in Figure 1 only
            m, _, _ = a1m(load, arm, "critical_on_time_pct")
            expect(f"A1 {label} {arm} critical", f1(m))
ion1s = sorted(float(r["share_of_ceiling_pct"]) for r in a1 if r["load"] == "1.0" and r["arm"] == "ion")
expect("A1 ION 1x seed range", f"{f1(ion1s[0])}–{f1(ion1s[-1])}%")
for arm in ("vortex:1.0", "vortex:0.9", "ion", "odl", "vortex-fcfs:1.0", "vortex-isolated:1.0"):
    for ld in ("0.5", "1.0"):
        assert len([r for r in a1 if r["load"] == ld and r["arm"] == arm]) == 5, f"{arm} {ld}: five seeds expected"
expect("A1 seeds", "five seeds each (0–4)", "mean of five seeds")
ion1 = sorted(int(r["delivered"]) for r in a1 if r["load"] == "1.0" and r["arm"] == "ion")
expect("A1 ION 1x delivered bundles", f"{ion1[0]}–{ion1[-1]} arrive")
vx1 = sorted(int(r["delivered"]) for r in a1 if r["load"] == "1.0" and r["arm"] == "vortex:1.0")
expect("A1 Vortex 1x admitted", f"admits {vx1[0]}–{vx1[-1]} of 400")
odl = [json.loads(r["refusals"]) for r in a1 if r["arm"] == "odl"]
shares = [100 * x["paths"] / (x["paths"] + x["skipped_no_path"]) for x in odl]
expect("ODL path availability", f"{min(shares):.2f}–{max(shares):.2f}%")
expect("ODL mismatches are zero (stated as never disagreed)", "never disagreed")
assert all(x["mismatches"] == 0 for x in odl), "ODL mismatches in results"
f0 = lambda x: f"{x:.0f}"  # noqa: E731
vx = {ld: a1m(ld, "vortex:1.0", "share_of_ceiling_pct")[0] for ld in ("0.5", "1.0")}
vc = {ld: a1m(ld, "vortex:1.0", "critical_on_time_pct")[0] for ld in ("0.5", "1.0")}
io = {ld: a1m(ld, "ion", "share_of_ceiling_pct")[0] for ld in ("0.5", "1.0")}
ic = {ld: a1m(ld, "ion", "critical_on_time_pct")[0] for ld in ("0.5", "1.0")}
od = {ld: a1m(ld, "odl", "share_of_ceiling_pct")[0] for ld in ("0.5", "1.0")}
headline = (f"{f0(vx['0.5'])}% of a capacity-aware ceiling at half load (ION: {f0(io['0.5'])}%)",
            f"{f0(vx['1.0'])}% at full load (ION: {f0(io['1.0'])}%)",
            f"{f0(vc['0.5'])}% and {f0(vc['1.0'])}% of critical bundles", (f"ION's {f0(ic['0.5'])}% at both loads" if f0(ic['0.5']) == f0(ic['1.0']) else f"ION's {f0(ic['0.5'])}% and {f0(ic['1.0'])}%"),
            f"delivers {f0(od['0.5'])}% and {f0(od['1.0'])}%")
expect("A1 abstract headline", *headline)

# Mechanism ablation inside VortexPCE (§5.1, Table 3, abstract)
abl = {arm: {ld: (a1m(ld, arm, "share_of_ceiling_pct")[0], a1m(ld, arm, "critical_on_time_pct")[0])
             for ld in ("0.5", "1.0")} for arm in ("vortex-isolated:1.0", "vortex-fcfs:1.0", "vortex:1.0")}
for arm, label in (("vortex-isolated:1.0", "| Routes only |"), ("vortex-fcfs:1.0", "| + reservation and admission (arrival order) |"),
                   ("vortex:1.0", "| + least slack first (VortexPCE) |")):
    s, c = abl[arm]["0.5"], abl[arm]["1.0"]
    expect(f"Table 3 {arm}", f"{label} {f1(s[0])} / {f1(c[0])} | {f1(s[1])} / {f1(c[1])} |")
iso = abl["vortex-isolated:1.0"]
expect("ablation text", f"delivery falls to {f1(iso['0.5'][0])}% and {f1(iso['1.0'][0])}%",
       f"against {f1(abl['vortex-fcfs:1.0']['1.0'][1])}% when bundles are admitted in arrival order",
       f"without reserving capacity delivers {f0(iso['0.5'][0])}% and {f0(iso['1.0'][0])}%",
       f"raises total delivery at full load, from {f1(abl['vortex-fcfs:1.0']['1.0'][0])}% to {f1(abl['vortex:1.0']['1.0'][0])}%")
assert abs(abl["vortex-fcfs:1.0"]["0.5"][0] - abl["vortex:1.0"]["0.5"][0]) < 1.0, "ordering changes half-load total by under a point"

# --- Rate policy and headroom (§5.1, Table 4, abstract) ---
sw = rows("rrnA_rate_policy_sweep.csv")
assert all(int(r["replay_mismatches"]) == 0 for r in sw) and len(sw) == 60, "60 replay-validated solves"
swm = lambda pol, h, ld, k: st.mean(float(r[k]) for r in sw if r["rate_policy"] == pol and r["headroom"] == h and r["load"] == ld)  # noqa: E731
for pol, h, label in (("DATA_FLOW_EQUILIBRIUM_AVERAGED", "1.0", "| DFE, averaged estimate, 1.0 |"),
                      ("DATA_FLOW_EQUILIBRIUM", "1.0", "| DFE, contact-based check, 1.0 |"),
                      ("RESIDUAL_BOTTLENECK_HEADROOM", "1.0", "| Residual rate, 1.0 |"),
                      ("DATA_FLOW_EQUILIBRIUM_AVERAGED", "0.9", "| DFE, averaged estimate, 0.9 |"),
                      ("DATA_FLOW_EQUILIBRIUM", "0.9", "| DFE, contact-based check, 0.9 |"),
                      ("RESIDUAL_BOTTLENECK_HEADROOM", "0.9", "| Residual rate, 0.9 |")):
    d = f"{f1(swm(pol, h, '0.5', 'share_of_ceiling_pct'))} / {f1(swm(pol, h, '1.0', 'share_of_ceiling_pct'))}"
    c = f"{f1(swm(pol, h, '0.5', 'critical_on_time_pct'))} / {f1(swm(pol, h, '1.0', 'critical_on_time_pct'))}"
    expect(f"Table 4 {label}", label, d, c)
gain = sorted(float(a["share_of_ceiling_pct"]) - float(b["share_of_ceiling_pct"]) for a in sw for b in sw
              if a["headroom"] == b["headroom"] == "1.0" and a["load"] == b["load"] == "1.0" and a["seed"] == b["seed"]
              and a["rate_policy"] == "RESIDUAL_BOTTLENECK_HEADROOM" and b["rate_policy"] == "DATA_FLOW_EQUILIBRIUM")
expect("residual rate gain per seed", f"by {f1(gain[0])}–{f1(gain[-1])} points per seed", f"costs {f1(st.mean(gain))} points at full load")
for r in a1:  # the final A1 VortexPCE rows are the residual-rate default
    if r["arm"] == "vortex:1.0":
        twin = next(x for x in sw if x["rate_policy"] == "RESIDUAL_BOTTLENECK_HEADROOM" and x["headroom"] == "1.0"
                    and float(x["load"]) == float(r["load"]) and x["seed"] == r["seed"])
        assert twin["delivered"] == r["delivered"], "A1 default equals the residual-rate sweep cell"

# --- Executing the plan in ION (§5.4, Table 5, abstract) ---
g1 = rows("rrnA_rdet_g1_plan_following.csv")[0]
g1r = rows("rrnA_rdet_g1_vortex_ion_patched.csv")[0]
ion0 = next(r for r in a1 if r["arm"] == "ion" and r["load"] == "1.0" and r["seed"] == "0")
vx0 = next(r for r in a1 if r["arm"] == "vortex:1.0" and r["load"] == "1.0" and r["seed"] == "0")
assert json.loads(g1["refusals"])["admitted"] == int(vx0["delivered"]), "executed exactly the admitted bundles"
for row, label in ((vx0, "| VortexPCE's committed schedule |"), (g1, "| ION following VortexPCE's routes |"),
                   (g1r, "| ION restricted to VortexPCE's reserved contacts |"), (ion0, "| ION alone |")):
    expect(f"Table 5 {label}", f"{label} {row['delivered']} | {row['share_of_ceiling_pct']} | {row['critical_on_time_pct']} |")
lift = float(g1["share_of_ceiling_pct"]) - float(ion0["share_of_ceiling_pct"])
expect("G1 lift", f"raises ION's delivery by {f1(lift)} points on seed 0",
       f"from {ion0['share_of_ceiling_pct']}% to {g1['share_of_ceiling_pct']}%",
       f"{int(vx0['delivered']) - int(g1['delivered'])} expired in queues")

g1s = rows("rrnA_rdet_g1_plan_following.csv")
assert [r["seed"] for r in g1s] == ["0", "1", "2"]
ions = [next(r for r in a1 if r["arm"] == "ion" and r["load"] == "1.0" and r["seed"] == g["seed"]) for g in g1s]
vxs = [next(r for r in a1 if r["arm"] == "vortex:1.0" and r["load"] == "1.0" and r["seed"] == g["seed"]) for g in g1s]
for g, v in zip(g1s, vxs):
    assert json.loads(g["refusals"])["admitted"] == int(v["delivered"]), f"seed {g['seed']}: executed the admitted bundles"
pf = [float(g["share_of_ceiling_pct"]) for g in g1s]
al = [float(r["share_of_ceiling_pct"]) for r in ions]
lifts = [x - y for x, y in zip(pf, al)]
pat2 = rows("rrnA_rdet_ion_linkemu_patched.csv")[0]
assert pat2["seed"] == "2" and pat2["load"] == "1.0"
expect("G1 seeds", f"delivers {g1s[1]['share_of_ceiling_pct']}% and {g1s[2]['share_of_ceiling_pct']}% of the ceiling",
       f"a mean of {f1(sum(pf) / 3)}%, against {f1(min(al))}–{f1(max(al))}% for ION alone as released",
       f"a gain of {f1(min(lifts))}–{f1(max(lifts))} points",
       f"patched ION alone on seed 2 ({pat2['share_of_ceiling_pct']}%), the gain is {f1(pf[2] - float(pat2['share_of_ceiling_pct']))} points",
       f"{f1(min(float(g['critical_on_time_pct']) for g in g1s))}–{f1(max(float(g['critical_on_time_pct']) for g in g1s))}% of critical bundles arrive on time, "
       f"against {f1(min(float(r['critical_on_time_pct']) for r in ions))}–{f1(max(float(r['critical_on_time_pct']) for r in ions))}% for ION alone")
expect("G1 limitation", f"on three seeds they reach {f1(min(pf))}–{f1(max(pf))}% of the ceiling, not "
       f"{f1(min(float(v['share_of_ceiling_pct']) for v in vxs))}–{f1(max(float(v['share_of_ceiling_pct']) for v in vxs))}%")
share = [(x - y) / (float(v["share_of_ceiling_pct"]) - y) for x, y, v in zip(pf, al, vxs)]
assert all(0.28 <= r <= 0.38 for r in share), share  # "recovers about a third of that gain"
expect("G1 third", "it recovers about a third of that gain (§5.4)")
expect("G1 abstract", f"by {f1(min(lifts))}–{f1(max(lifts))} points across three seeds, to {f1(min(pf))}–{f1(max(pf))}% of the ceiling")

# --- Lunar relay plan (§5.5, Table 6, abstract) ---
g3 = rows("lcrns_g3_pilot.csv") + rows("lcrns_g3_ion_patched.csv")
g = lambda arm, ld: next(r for r in g3 if r["arm"] == arm and r["load"] == ld)  # noqa: E731
for arm, label in (("odl", "| OpenDaylight |"), ("vortex-isolated:1.0", "| VortexPCE, routes only |")):
    expect(f"Table 6 {arm}", f"{label} {g(arm, '0.5')['share_of_ceiling_pct']} / {g(arm, '1.0')['share_of_ceiling_pct']} | "
           f"{g(arm, '0.5')['critical_on_time_pct']} / {g(arm, '1.0')['critical_on_time_pct']} |")
expect("Table 6 VortexPCE", f"| VortexPCE | **{g('vortex:1.0', '0.5')['share_of_ceiling_pct']} / {g('vortex:1.0', '1.0')['share_of_ceiling_pct']}** | "
       f"**{g('vortex:1.0', '0.5')['critical_on_time_pct']} / {g('vortex:1.0', '1.0')['critical_on_time_pct']}** |")
expect("Table 6 ION", f"{g('ion', '0.5')['share_of_ceiling_pct']} / {g('ion', '1.0')['share_of_ceiling_pct']} | "
       f"{g('ion', '0.5')['critical_on_time_pct']} / {g('ion', '1.0')['critical_on_time_pct']} |")
expect("lunar ION half load", f"ION delivers {g('ion', '0.5')['delivered']} of {g('ion', '0.5')['offered']} bundles "
       f"({g('ion', '0.5')['share_of_ceiling_pct']}%)")
expect("lunar abstract", f"VortexPCE delivers {g('vortex:1.0', '1.0')['share_of_ceiling_pct']}% at full load against "
       f"{g('ion', '1.0')['share_of_ceiling_pct']}% for ION and {g('odl', '1.0')['share_of_ceiling_pct']}% for OpenDaylight")
expect("lunar offered", f"0.5× and 1× offer {g('vortex:1.0', '0.5')['offered']} and {g('vortex:1.0', '1.0')['offered']}",
       f"saturated ceiling is {g('vortex:1.0', '1.0')['ceiling']} bundles")
assert all(json.loads(g(a, ld)["refusals"])["skipped_no_path"] == 0 for a in ("odl",) for ld in ("0.5", "1.0")), "ODL path every snapshot"

# --- ION fix comparison (§4.2) ---
pat = rows("rrnA_rdet_ion_linkemu_patched.csv")[0]
unp = next(r for r in a1 if r["arm"] == "ion" and r["load"] == "1.0" and r["seed"] == "2")
expect("patched vs unpatched seed 2", f"delivered {pat['delivered']} bundles ({pat['share_of_ceiling_pct']}% of the ceiling)",
       f"delivered {unp['delivered']} ({unp['share_of_ceiling_pct']}%)")

# --- C2 no load (§5.2, abstract) ---
rev = {r["pfail"]: r for r in rows("rrnA_sdp_revised.csv")}
expect("RRN-A CGR SDP", " / ".join(f3(float(rev[p]["cgr_sdp"])) for p in ("0.1", "0.3", "0.5")))
expect("RRN-A BOUNDED_CGR SDP", " / ".join(f3(float(rev[p]["vortex_sdp"])) for p in ("0.1", "0.3", "0.5")))
expect("RRN-A BOUNDED_CGR routes", f"about {float(rev['0.3']['routes_per_admit']):.1f}")
cutA = {r["pfail"]: r for r in rows("rrnA_sdp_cut_k8.csv")}
expect("RRN-A cut k8", f3(float(cutA["0.3"]["vortex_sdp"])), f3(float(cutA["0.5"]["vortex_sdp"])),
       f3(float(cutA["0.3"]["oracle_sdp"])), f3(float(cutA["0.5"]["oracle_sdp"])))
cutB = {r["pfail"]: r for r in rows("rrnB_sdp_cut_k8.csv")}
expect("RRN-B cut k8 p=0.3", f"{f3(float(cutB['0.3']['vortex_sdp']))} against the oracle's {f3(float(cutB['0.3']['oracle_sdp']))}")
expect("RRN-B cut k8 p=0.5", f"{f3(float(cutB['0.5']['vortex_sdp']))} against {f3(float(cutB['0.5']['oracle_sdp']))}")
revB = {r["pfail"]: r for r in rows("rrnB_sdp_revised.csv")}
oldB = {r["pfail"]: r for r in rows("rrnB_sdp_revised_engine_b988b2a.csv")}
assert abs(float(oldB["0.0"]["vortex_admit"]) - 16 / 25) < 1e-9, "RRN-B BOUNDED admission before the fix"
assert abs(float(revB["0.0"]["vortex_admit"]) - 24 / 25) < 1e-9, "RRN-B BOUNDED admission after the fix"
assert revB["0.0"]["vortex_sdp"] == revB["0.0"]["oracle_sdp"], "matches the oracle with no failures"
assert all(float(r["vortex_sdp"]) >= float(r["cgr_sdp"]) for r in revB.values()), "at or above CGR at every p"
expect("RRN-B BOUNDED failure", "8 of the 24 reachable terminals", "routes all 24",
       f"({f3(float(revB['0.3']['vortex_sdp']))} against {f3(float(revB['0.3']['cgr_sdp']))} at p = 0.3)")
expect("abstract RUCoP", "(0.992 vs 0.997 and 0.941 vs 0.946)", "nominal CGR delivers 0.29 and 0.18")
assert f"{float(cutA['0.3']['cgr_sdp']):.2f}" == "0.29" and f"{float(cutB['0.3']['cgr_sdp']):.2f}" == "0.18"

# --- Oracle cost (§5.2, Figure 3) ---
mesh = {lv: {r["pfail"]: r for r in rows(f"rrnA_mesh_L{lv}.csv")} for lv in ("0", "ALL")}
pct = lambda r: 100 * float(r["vortex_sdp"]) / float(r["oracle_sdp"])  # noqa: E731
expect("cut k8 share of oracle p=0.5", f"{pct(cutA['0.5']):.0f}% of the oracle with about {float(cutA['0.5']['routes_per_admit']):.0f} routes")
expect("mesh0 share p=0.5", f"{pct(mesh['0']['0.5']):.0f}% with about {float(mesh['0']['0.5']['routes_per_admit']):.0f}")
expect("p=0.7 shares", f"reach {pct(cutA['0.7']):.0f}% and {pct(mesh['0']['0.7']):.0f}%")
expect("mesh ALL attains", f"{f3(float(mesh['ALL']['0.5']['vortex_sdp']))} against {f3(float(mesh['ALL']['0.5']['oracle_sdp']))}",
       f"{f3(float(mesh['ALL']['0.7']['vortex_sdp']))} against {f3(float(mesh['ALL']['0.7']['oracle_sdp']))}",
       f"{float(mesh['ALL']['0.5']['routes_per_admit']):,.0f} routes per bundle")

# --- Under load (§5.2, Figure 4) ---
cl = rows("closed_loop_replan.csv")
sh = lambda r, k: 100 * float(r[k]) / float(r["ceiling"])  # noqa: E731
v0 = [sh(r, "v0") for r in cl]
v1 = [sh(r, "v1") for r in cl]
fl = [sh(r, "flooding") for r in cl]
expect("plan once vs flooding", f"only {min(v0):.0f}–{max(v0):.0f}% of the capacity-aware ceiling", f"against {min(fl):.0f}–{max(fl):.0f}% for directed flooding")
expect("hourly closed loop", f"reaches {min(v1):.0f}–{max(v1):.0f}%")
sw = rows("replan_sweep.csv")
g = lambda pf, iv, dl, k: sh(next(r for r in sw if r["pfail"] == pf and float(r["interval_h"]) == iv and float(r["report_delay_h"]) == dl), k)  # noqa: E731
expect("30-min re-plan", f"{f1(g('0.3', 0.5, 0, 'v1'))}% / {f1(g('0.5', 0.5, 0, 'v1'))}%", f"({f1(g('0.3', 0.5, 0, 'flooding'))}% / {f1(g('0.5', 0.5, 0, 'flooding'))}%)")
first = [g(pf, 1.0, 0, "v1") - g(pf, 1.0, 1.0, "v1") for pf in ("0.3", "0.5")]
expect("first hour of delay", f"costs {min(first):.0f}–{max(first):.0f} points")
ob = rows("overbooking_grid.csv")
cell = lambda k, key: [sh(r, key) for r in ob if float(r["overbook_safety"]) == k]  # noqa: E731
expect("overbooking ranges", f"from {f1(min(cell(0, 'v1')))}–{f1(max(cell(0, 'v1')))}% to {f1(min(cell(1, 'v1')))}–{f1(max(cell(1, 'v1')))}%",
       f"from {f1(min(cell(0, 'v0')))}–{f1(max(cell(0, 'v0')))}% to {f1(min(cell(1, 'v0')))}–{f1(max(cell(1, 'v0')))}%")
hard = next(r for r in ob if r["pfail"] == "0.5" and r["offered_mb"] == "800" and float(r["overbook_safety"]) == 1)
expect("hardest cell", f"({f1(sh(hard, 'flooding'))}% against {f1(sh(hard, 'v1'))}%)")

# --- R_STOCH under load: paired rerun on the current engine (§4.4, §6) ---
pr = {k: rows(f"rstoch_paired_{k}.csv") for k in ("closed_loop", "replan30", "overbook")}
olds = {"closed_loop": [r for r in cl if r["bundles"] == "200"],
        "replan30": [r for r in sw if r["interval_h"] == "0.5" and r["report_delay_h"] == "0.0"],
        "overbook": [r for r in ob if r["bundles"] == "200" and r["overbook_safety"] == "1.0"]}
moves = []
for k, new_rows in pr.items():
    assert len(new_rows) == 2 and len(olds[k]) == 2, k
    for n in new_rows:
        o = next(r for r in olds[k] if r["pfail"] == n["pfail"])
        assert o["ceiling"] == n["ceiling"] and o["flooding"] == n["flooding"], f"{k} same realisations"
        for arm in ("v0", "v1"):
            moves.append(abs(100 * (float(n[arm]) - float(o[arm])) / float(o["ceiling"])))
expect("paired rerun", "We re-ran the six 200 MB cells", f"No result moved by more than {f1(max(moves))} points")
assert max(moves) < 0.25, max(moves)

# --- Priority (§5.2, Figure 5) ---
pr = rows("flood_vs_crp_prio_strict_p03.csv")
crit = lambda s: [100 * float(r["critical_sdp"]) for r in pr if r["scheme"] == s]  # noqa: E731
for s, phrase in (("vortex_GLOBAL_MARGINAL", "delivers {}–{}% of critical"), ("flooding", "against {}–{}% for priority-blind"),
                  ("flooding_priority", "better still ({}–{}%)")):
    v = crit(s)
    expect(f"priority {s}", phrase.format(f"{min(v):.0f}", f"{max(v):.0f}"))

# --- C3 (§5.3, Figure 6) ---
single = json.load(open(os.path.join(BENCH, "rstatic_cutthrough_results.json")))
d = lambda arm, ld: next(r for r in single if r["arm"] == arm and r["load"] == ld)  # noqa: E731
expect("single bottleneck", f"{d('DFE', 0.5)['delivered_mb']} MB against {d('Eager', 0.5)['delivered_mb']} MB",
       f"{d('DFE', 1.0)['delivered_mb']} MB against {d('Eager', 1.0)['delivered_mb']} MB",
       f"deliver {d('Eager', 1.5)['delivered_mb']} MB but finish {d('Eager', 1.5)['wall_sec']:.0f} s")
multi = json.load(open(os.path.join(BENCH, "rstatic_multipath_results.json")))
m = lambda arm, ld: next(r for r in multi if r["arm"] == arm and r["load"] == ld)["delivered_mb"]  # noqa: E731
expect("two paths", f"{m('Optimized', 0.5) / m('ClassicIP', 0.5):.1f}× a single-path",
       f"({m('Optimized', 1.0)} MB against {m('ClassicIP', 1.0)} MB at full load)")

# --- Networks (§4) ---
from_plan = {}
for name in ("RRN-A_16sats_Walker", "RRN-B_16sats_ATrack"):
    n = sum(1 for line in open(os.path.join(FIX, name + ".contactplan.txt"))
            if line.split()[:2] == ["a", "contact"])
    from_plan[name] = n
expect("contact counts", f"{from_plan['RRN-A_16sats_Walker']:,} contacts", f"{from_plan['RRN-B_16sats_ATrack']:,} contacts")

# The extended abstract states the same A1 numbers.
ABS = "" if EXPECTED else open(os.path.join(HERE, "ascend2027_extended_abstract.md")).read()
for arm in ("odl", "ion", "vortex:1.0"):
    m = lambda ld, k: f1(a1m(ld, arm, k, a1abs)[0])  # noqa: E731
    frag = f"{m('0.5', 'share_of_ceiling_pct')} / {m('1.0', 'share_of_ceiling_pct')}"
    crit = f"{m('0.5', 'critical_on_time_pct')} / {m('1.0', 'critical_on_time_pct')}"
    expect_in(f"extended abstract Table 1 {arm}", (frag, crit), ABS)
aab = lambda arm, ld, k: a1m(ld, arm, k, a1abs)[0]  # noqa: E731
abs_ablation = (f"delivers {f0(aab('vortex-isolated:1.0', '0.5', 'share_of_ceiling_pct'))}% and "
                f"{f0(aab('vortex-isolated:1.0', '1.0', 'share_of_ceiling_pct'))}% of the ceiling, below ION at half load",
                f"raises delivery to {f1(aab('vortex-fcfs:1.0', '0.5', 'share_of_ceiling_pct'))}% and "
                f"{f1(aab('vortex-fcfs:1.0', '1.0', 'share_of_ceiling_pct'))}%",
                f"from {f1(aab('vortex-fcfs:1.0', '1.0', 'critical_on_time_pct'))}% to {f1(aab('vortex:1.0', '1.0', 'critical_on_time_pct'))}%")
assert aab("vortex-isolated:1.0", "0.5", "share_of_ceiling_pct") < aab("ion", "0.5", "share_of_ceiling_pct"), "below ION at half load"
expect_in("extended abstract ablation (Figure 2)", abs_ablation, ABS)
revA = {r["pfail"]: r for r in rows("rrnA_sdp_revised.csv")}
abs_sdp = (f"about {float(revA['0.3']['routes_per_admit']):.1f} routes per bundle on RRN-A",
           f"({f3(float(revA['0.3']['vortex_sdp']))} and {f3(float(revB['0.3']['vortex_sdp']))} at p = 0.3)")
assert all(float(r["routes_per_admit"]) == 1.0 for r in revB.values()), "one route per bundle on RRN-B"
expect_in("extended abstract default generator (Figure 3)", abs_sdp, ABS)
abs_load = [f for k in ("plan once vs flooding", "hourly closed loop", "30-min re-plan", "hardest cell") for f in stated[k]]
abs_load.append(stated["overbooking ranges"][0])
expect_in("extended abstract under load (Figure 4)", abs_load, ABS)
ah = lambda arm, ld, k: f0(a1m(ld, arm, k, a1abs)[0])  # noqa: E731
abs_headline = (f"{ah('vortex:1.0', '0.5', 'share_of_ceiling_pct')}% of a capacity-aware ceiling at half load (ION: {ah('ion', '0.5', 'share_of_ceiling_pct')}%)",
                f"{ah('vortex:1.0', '1.0', 'share_of_ceiling_pct')}% at full load (ION: {ah('ion', '1.0', 'share_of_ceiling_pct')}%)",
                f"delivers {ah('odl', '0.5', 'share_of_ceiling_pct')}% and {ah('odl', '1.0', 'share_of_ceiling_pct')}%")
expect_in("extended abstract headline", abs_headline, ABS)

bad = [(label, miss) for label, miss in checks if miss]
print(f"{len(checks)} checks, {len(bad)} failed")
for label, miss in bad:
    print(f"  FAIL {label}: " + ("does not match the paper" if EXPECTED is not None else "not found in manuscript") + f": {miss}")

if EXPORT and not bad:
    json.dump({label: list(f) for label, f in exported}, open(EXPORT, "w"), indent=1, ensure_ascii=False)
    print(f"wrote {EXPORT}")
if EXPECTED is not None:
    sys.exit(1 if bad else 0)
body = TEXT.split("# References")[0]
nums = sorted(set(re.findall(r"(?<![\w.])\d+(?:[.,]\d+)*", body)) - covered,
              key=lambda x: float(re.sub(r"[.,](?=.*[.,])", "", x).replace(",", "")))
print("numbers not covered by a check (review by hand):", " ".join(nums))
sys.exit(1 if bad else 0)
