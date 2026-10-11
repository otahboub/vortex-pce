#!/usr/bin/env python3
"""make_figures.py — the preprint's new figures, drawn only from committed result files.

  fig_final_rrnA.png, fig_final_ablation.png  the same two figures for the final paper, from
                    rrnA_rdet_final.csv (VortexPCE regime-aware rate default; ION with the link emulator).
  fig_a1_rdet.png   A1 (§4l): share of the capacity-aware ceiling and critical on-time delivery,
                    Vortex (headroom 1.0 and the 0.9 default) vs real ION, mean of 3 seeds with range.
  fig_c2_sdp.png    C2 (§4c, §4e, §4m): SDP vs contact failure probability on RRN-A and RRN-B.
  fig_c2_oracle_cost.png  C2 (§4c–§4g): share of the oracle against routes per bundle, RRN-A.
  fig_c2_load.png   C2 under load (§4i–§4k): plan once / closed loop / overbooking vs flooding, and re-plan speed.
  fig_c2_priority.png  §4f: critical protection under failures, plan once vs flooding.
  fig_c3_rstatic.png   C3: continuous backbone, single bottleneck and two disjoint paths.

Usage: python3 reproduce/ascend2027/check/make_figures.py   (writes reproduce/ascend2027/figures/)
The paper sets these in Avenir Next; this copy uses DejaVu Sans, so only the typeface differs.
"""
import csv
import json
import os
import sys
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402

SANS = "DejaVu Sans"

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # reproduce/ascend2027
RES = os.path.join(ROOT, "results")
OUT = os.path.join(ROOT, "figures")
os.makedirs(OUT, exist_ok=True)

# Reference categorical palette, fixed order (slots 1-4); every series also has its own marker or
# line style so identity never rests on colour alone.
BLUE, ORANGE, AQUA, YELLOW = "#2a78d6", "#eb6834", "#1baf7a", "#eda100"
INK, MUTED, GRID = "#1f1f1e", "#6b6a63", "#e3e2dc"

plt.rcParams.update({
    "font.size": 9, "font.family": [SANS, "DejaVu Sans"], "pdf.fonttype": 42,
    "axes.edgecolor": MUTED, "axes.labelcolor": INK, "xtick.color": MUTED,
    "ytick.color": MUTED, "axes.spines.top": False, "axes.spines.right": False,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.6, "axes.axisbelow": True,
    "legend.frameon": False,
})


def rows(name):
    with open(os.path.join(RES, name)) as f:
        return list(csv.DictReader(f))


def fig_a1(src="rrnA_rdet_flagship.csv", out="fig_a1_rdet.png", ion_label="ION (real, 48 nodes)"):
    data = defaultdict(list)
    for r in rows(src):
        data[(r["load"], r["arm"])].append(r)
    loads = ["0.5", "1.0"]
    arms = [("vortex:1.0", "Vortex, headroom 1.0", BLUE, ""),
            ("vortex:0.9", "Vortex, 0.9 default", ORANGE, "//"),
            ("ion", ion_label, AQUA, ".."),
            ("odl", "OpenDaylight (real CSPF)", YELLOW, "xx")]
    fig, axes = plt.subplots(1, 2, figsize=(7.0, 3.1))
    for ax, (key, title) in zip(axes, [("share_of_ceiling_pct", "Delivered, % of ceiling"),
                                       ("critical_on_time_pct", "Critical bundles on time, %")]):
        w = 0.2
        for k, (arm, label, colour, hatch) in enumerate(arms):
            xs, ys, lo, hi = [], [], [], []
            for i, load in enumerate(loads):
                v = [float(r[key]) for r in data[(load, arm)]]
                m = sum(v) / len(v)
                xs.append(i + (k - 1.5) * w)
                ys.append(m)
                lo.append(m - min(v))
                hi.append(max(v) - m)
            ax.bar(xs, ys, w * 0.92, color=colour, hatch=hatch, edgecolor="white", linewidth=0.8,
                   label=label)
            ax.errorbar(xs, ys, yerr=[lo, hi], fmt="none", ecolor=INK, elinewidth=0.8, capsize=2)
            for x, y, h in zip(xs, ys, hi):
                ax.text(x, y + h + 2, f"{y:.0f}", ha="center", fontsize=7, color=INK)
        ax.set_xticks(range(len(loads)))
        ax.set_xticklabels(["0.5× load", "1× load"])
        ax.set_ylim(0, 110)
        ax.set_title(title, fontsize=9, color=INK)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, fontsize=7, loc="lower center", ncol=4)
    fig.tight_layout(rect=(0, 0.08, 1, 1))
    fig.savefig(os.path.join(OUT, out), dpi=600)


def fig_c2():
    sets = [("RRN-A (with inter-satellite links)", "rrnA_sdp_revised.csv", "rrnA_sdp_cut_k8.csv"),
            ("RRN-B (no inter-satellite links)", "rrnB_sdp_revised.csv", "rrnB_sdp_cut_k8.csv")]
    fig, axes = plt.subplots(1, 2, figsize=(7.0, 3.1), sharey=True)
    for ax, (title, rev, cut) in zip(axes, sets):
        r = rows(rev)
        c = {x["pfail"]: x for x in rows(cut)}
        p = [float(x["pfail"]) for x in r]
        series = [
            ([float(c[x["pfail"]]["vortex_sdp"]) if x["pfail"] in c else float("nan") for x in r],
             "Vortex, cut-anchored generator (k=8)", BLUE, "-", "o"),
            ([float(x["vortex_sdp"]) for x in r], "Vortex, default generator (BOUNDED_CGR)", ORANGE, "-", "s"),
            ([float(x["cgr_sdp"]) for x in r], "CGR (nominal route)", AQUA, "-", "^"),
            ([float(x["oracle_sdp"]) for x in r], "Oracle", YELLOW, "--", "D"),
        ]
        for ys, label, colour, ls, mk in series:
            ax.plot(p, ys, ls, color=colour, linewidth=2, marker=mk, markersize=4.5,
                    markeredgecolor="white", markeredgewidth=0.6, label=label)
        ax.set_title(title, fontsize=9, color=INK)
        ax.set_xlabel("contact failure probability p")
        ax.set_ylim(-0.02, 1.05)
    axes[0].set_ylabel("successful delivery probability")
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, fontsize=7, loc="lower center", ncol=2)
    fig.tight_layout(rect=(0, 0.13, 1, 1))
    fig.savefig(os.path.join(OUT, "fig_c2_sdp.png"), dpi=600)


def fig_oracle_cost():
    """C2: share of the oracle's SDP against routes per bundle, across F_generate settings (RRN-A)."""
    settings = [("rrnA_sdp_revised.csv", "BOUNDED_CGR"), ("rrnA_sdp_disjoint.csv", "DISJOINT"),
                ("rrnA_sdp_cut_k2.csv", "CUT k=2"), ("rrnA_sdp_cut_k4.csv", "CUT k=4"),
                ("rrnA_sdp_cut_k8.csv", "CUT k=8"), ("rrnA_mesh_L0.csv", "MESH 0"),
                ("rrnA_mesh_L1.csv", "MESH 1"), ("rrnA_mesh_L2.csv", "MESH 2"),
                ("rrnA_mesh_L3.csv", "MESH 3"), ("rrnA_mesh_LALL.csv", "MESH ALL")]
    fig, ax = plt.subplots(figsize=(7.0, 3.2))
    for pf, colour, mk in (("0.5", BLUE, "o"), ("0.7", ORANGE, "s")):
        xs, ys, labels = [], [], []
        cgr = None
        for name, label in settings:
            row = next(r for r in rows(name) if r["pfail"] == pf)
            xs.append(float(row["routes_per_admit"]))
            ys.append(100 * float(row["vortex_sdp"]) / float(row["oracle_sdp"]))
            labels.append(label)
            cgr = cgr if cgr is not None else 100 * float(row["cgr_sdp"]) / float(row["oracle_sdp"])
        ax.plot([1] + xs, [cgr] + ys, "-", color=colour, linewidth=2, marker=mk, markersize=4.5,
                markeredgecolor="white", markeredgewidth=0.6, label=f"p = {pf}")
        if pf == "0.5":
            ax.annotate("CGR", (1, cgr), textcoords="offset points", xytext=(4, -10), fontsize=7, color=INK)
            offsets = {"BOUNDED_CGR": (-10, 8), "CUT k=8": (-46, 4), "MESH 0": (-14, 8), "MESH ALL": (-40, 8)}
            for x, y, lab in zip(xs, ys, labels):
                if lab in offsets:
                    ax.annotate(lab, (x, y), textcoords="offset points", xytext=offsets[lab], fontsize=7, color=INK)
    ax.set_xscale("log")
    ax.set_xlim(0.7, 3000)
    ax.set_xlabel("routes committed per bundle (log scale)")
    ax.set_ylabel("Vortex SDP, % of oracle")
    ax.set_ylim(0, 105)
    ax.legend(fontsize=7, loc="lower right")
    fig.tight_layout()
    fig.savefig(os.path.join(OUT, "fig_c2_oracle_cost.png"), dpi=600)


def fig_load():
    """C2 under load: plan once, closed loop, overbooking (replay-scored) and re-plan speed (dedicated)."""
    grid = rows("overbooking_grid.csv")
    cells = [("0.3", "200"), ("0.5", "200"), ("0.3", "800"), ("0.5", "800")]
    get = lambda pf, mb, k: next(r for r in grid if r["pfail"] == pf and r["offered_mb"] == mb  # noqa: E731
                                 and float(r["overbook_safety"]) == k)
    share = lambda r, key: 100 * float(r[key]) / float(r["ceiling"])  # noqa: E731
    arms = [("plan once", 0.0, "v0", "#9ec5f4", ""), ("plan once + overbooking", 1.0, "v0", BLUE, ""),
            ("closed loop", 0.0, "v1", "#f7b89c", "//"), ("closed loop + overbooking", 1.0, "v1", ORANGE, "//"),
            ("flooding", 0.0, "flooding", AQUA, "..")]
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(7.0, 3.3), gridspec_kw={"width_ratios": [1.5, 1]})
    w = 0.16
    for k, (label, kappa, key, colour, hatch) in enumerate(arms):
        xs = [i + (k - 2) * w for i in range(len(cells))]
        ys = [share(get(pf, mb, kappa), key) for pf, mb in cells]
        a1.bar(xs, ys, w * 0.92, color=colour, hatch=hatch, edgecolor="white", linewidth=0.8, label=label)
    a1.set_xticks(range(len(cells)))
    a1.set_xticklabels([f"p={pf}\n{mb} MB" for pf, mb in cells], fontsize=7)
    a1.set_ylim(0, 100)
    a1.set_ylabel("% of capacity-aware ceiling")
    a1.set_title("Reports 2 h late, hourly re-plan\n(replay-scored)", fontsize=8, color=INK)
    a1.legend(fontsize=6, loc="upper left", ncol=2)
    sweep = rows("replan_sweep.csv")
    for pf, colour, mk in (("0.3", BLUE, "o"), ("0.5", ORANGE, "s")):
        iv = sorted((float(r["interval_h"]), 100 * float(r["v1"]) / float(r["ceiling"])) for r in sweep
                    if r["pfail"] == pf and float(r["report_delay_h"]) == 0)
        dl = sorted((float(r["report_delay_h"]), 100 * float(r["v1"]) / float(r["ceiling"])) for r in sweep
                    if r["pfail"] == pf and float(r["interval_h"]) == 1.0)
        a2.plot([x for x, _ in iv], [y for _, y in iv], "-", color=colour, linewidth=2, marker=mk, markersize=4.5,
                markeredgecolor="white", markeredgewidth=0.6, label=f"p={pf}, re-plan interval")
        a2.plot([x for x, _ in dl], [y for _, y in dl], "--", color=colour, linewidth=2, marker=mk, markersize=4.5,
                markeredgecolor="white", markeredgewidth=0.6, label=f"p={pf}, report delay (1 h re-plan)")
        fl = next(r for r in sweep if r["pfail"] == pf)
        a2.axhline(100 * float(fl["flooding"]) / float(fl["ceiling"]), color=colour, linewidth=0.8, linestyle=":",
                   label=f"p={pf}, flooding")
    a2.set_xlabel("hours")
    a2.set_ylim(0, 100)
    a2.set_title("Re-plan speed, 200 MB\n(dedicated scoring)", fontsize=8, color=INK)
    a2.legend(fontsize=6, loc="lower left")
    fig.tight_layout()
    fig.savefig(os.path.join(OUT, "fig_c2_load.png"), dpi=600)


def fig_priority():
    """Critical protection under failures (p = 0.3), plan once."""
    data = rows("flood_vs_crp_prio_strict_p03.csv")
    loads = sorted({int(float(r["offered_mb"])) for r in data})
    arms = [("vortex_GLOBAL_MARGINAL", "Vortex (plan once)", BLUE, "-", "o"),
            ("flooding_priority", "priority-aware flooding", ORANGE, "-", "s"),
            ("flooding", "priority-blind flooding", AQUA, "--", "^")]
    fig, axes = plt.subplots(1, 2, figsize=(7.0, 2.9), sharey=True)
    for ax, key, title in zip(axes, ["critical_sdp", "sdp"], ["Critical bundles on time", "All bundles on time"]):
        for scheme, label, colour, ls, mk in arms:
            ys = [100 * float(next(r for r in data if r["scheme"] == scheme
                                    and int(float(r["offered_mb"])) == mb)[key]) for mb in loads]
            ax.plot(loads, ys, ls, color=colour, linewidth=2, marker=mk, markersize=4.5,
                    markeredgecolor="white", markeredgewidth=0.6, label=label)
        ax.set_xscale("log")
        ax.set_xticks(loads)
        ax.set_xticklabels([f"{mb} MB" for mb in loads], fontsize=7)
        ax.minorticks_off()
        ax.set_title(title, fontsize=9, color=INK)
        ax.set_ylim(0, 105)
    axes[0].set_ylabel("%")
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, fontsize=7, loc="lower center", ncol=3)
    fig.tight_layout(rect=(0, 0.09, 1, 1))
    fig.savefig(os.path.join(OUT, "fig_c2_priority.png"), dpi=600)


def fig_rstatic():
    """C3: a continuous backbone, single bottleneck and two disjoint paths (real TCP testbed)."""
    bench = RES
    single = json.load(open(os.path.join(bench, "rstatic_cutthrough_results.json")))
    multi = json.load(open(os.path.join(bench, "rstatic_multipath_results.json")))
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(7.0, 3.0))
    loads = [0.5, 1.0, 1.5]
    w = 0.36
    for k, (arm, label, colour, hatch) in enumerate([("Eager", "unpaced transport (line rate)", AQUA, ".."),
                                                    ("DFE", "Vortex (paced, admitted)", BLUE, "")]):
        rs = [next(r for r in single if r["arm"] == arm and r["load"] == ld) for ld in loads]
        xs = [i + (k - 0.5) * w for i in range(len(loads))]
        a1.bar(xs, [r["delivered_mb"] for r in rs], w * 0.92, color=colour, hatch=hatch, edgecolor="white",
               linewidth=0.8, label=label)
        for x, r in zip(xs, rs):
            note = f'{r["delivered_mb"]:.1f}' + (f'\n{r["wall_sec"]:.0f} s' if r["wall_sec"] > 30.5 else "")
            a1.text(x, r["delivered_mb"] + 1, note, ha="center", fontsize=6.5, color=INK)
    a1.set_xticks(range(len(loads)))
    a1.set_xticklabels([f"{ld}× load" for ld in loads], fontsize=7)
    a1.set_ylabel("delivered, MB (30 s deadline)")
    a1.set_ylim(0, 62)
    a1.set_title("Single bottleneck", fontsize=9, color=INK)
    a1.legend(fontsize=6, loc="upper left")
    mloads = [0.5, 1.0, 1.5, 2.0]
    for k, (arm, label, colour, hatch) in enumerate([("ClassicIP", "single path: FCFS + shortest hop", AQUA, ".."),
                                                    ("Optimized", "Vortex CRP (two paths)", BLUE, "")]):
        rs = [next(r for r in multi if r["arm"] == arm and r["load"] == ld) for ld in mloads]
        xs = [i + (k - 0.5) * w for i in range(len(mloads))]
        a2.bar(xs, [r["delivered_mb"] for r in rs], w * 0.92, color=colour, hatch=hatch, edgecolor="white",
               linewidth=0.8, label=label)
        for x, r in zip(xs, rs):
            a2.text(x, r["delivered_mb"] + 0.6, f'{r["delivered_mb"]:.1f}', ha="center", fontsize=6.5, color=INK)
    a2.set_xticks(range(len(mloads)))
    a2.set_xticklabels([f"{ld}×" for ld in mloads], fontsize=7)
    a2.set_ylim(0, 30)
    a2.set_title("Two disjoint paths", fontsize=9, color=INK)
    a2.legend(fontsize=6, loc="upper left")
    fig.tight_layout()
    fig.savefig(os.path.join(OUT, "fig_c3_rstatic.png"), dpi=600)


def fig_a1_ablation(src="rrnA_rdet_flagship.csv", out="fig_a1_ablation.png"):
    """One mechanism at a time inside VortexPCE (A1 Table 3), with real ION as a reference mark."""
    data = defaultdict(list)
    for r in rows(src):
        data[(r["load"], r["arm"])].append(r)
    loads = ["0.5", "1.0"]
    # Mechanisms accumulate, so one hue light -> dark; Vortex's full planner keeps its Figure 1 blue.
    arms = [("vortex-isolated:1.0", "Routes only", "#a9c8ef", ".."),
            ("vortex-fcfs:1.0", "+ reservation and admission\n(arrival order)", "#6aa1e4", "//"),
            ("vortex:1.0", "+ least slack first (VortexPCE)", BLUE, "")]
    fig, axes = plt.subplots(1, 2, figsize=(7.0, 3.1))
    for ax, (key, title) in zip(axes, [("share_of_ceiling_pct", "Delivered, % of ceiling"),
                                       ("critical_on_time_pct", "Critical bundles on time, %")]):
        w = 0.26
        for k, (arm, label, colour, hatch) in enumerate(arms):
            xs, ys, lo, hi = [], [], [], []
            for i, load in enumerate(loads):
                v = [float(r[key]) for r in data[(load, arm)]]
                m = sum(v) / len(v)
                xs.append(i + (k - 1) * w)
                ys.append(m)
                lo.append(m - min(v))
                hi.append(max(v) - m)
            ax.bar(xs, ys, w * 0.92, color=colour, hatch=hatch, edgecolor="white", linewidth=0.8, label=label)
            ax.errorbar(xs, ys, yerr=[lo, hi], fmt="none", ecolor=INK, elinewidth=0.8, capsize=2)
            for x, y, h in zip(xs, ys, hi):
                ax.text(x, y + h + 2, f"{y:.0f}", ha="center", fontsize=7, color=INK)
        for i, load in enumerate(loads):
            v = [float(r[key]) for r in data[(load, "ion")]]
            ax.hlines(sum(v) / len(v), i - 1.5 * w, i + 1.5 * w, colors=AQUA, linestyles="--", linewidth=1.6,
                      label="ION (real), for reference" if i == 0 else None)
        ax.set_xticks(range(len(loads)))
        ax.set_xticklabels(["0.5× load", "1× load"])
        ax.set_ylim(0, 110)
        ax.set_title(title, fontsize=9, color=INK)
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, fontsize=7, loc="lower center", ncol=4)
    fig.tight_layout(rect=(0, 0.12, 1, 1))
    fig.savefig(os.path.join(OUT, out), dpi=600)


if __name__ == "__main__":
    fig_a1()                      # the submitted extended abstract's figures, from its result file
    fig_a1_ablation()
    # The final paper: VortexPCE on the regime-aware rate default, ION through the link emulator.
    fig_a1("rrnA_rdet_final.csv", "fig_final_rrnA.png", "ION (real, link emulator)")
    fig_a1_ablation("rrnA_rdet_final.csv", "fig_final_ablation.png")
    fig_c2()
    fig_oracle_cost()
    fig_load()
    fig_priority()
    fig_rstatic()
    print("wrote the preprint figures to", OUT)
