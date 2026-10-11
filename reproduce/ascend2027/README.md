# Reproduction package: ASCEND 2027

This folder holds everything behind the numbers and plots in:

> O. Y. Tahboub and J. I. Khan, "Admission-Controlled Path Computation for Scheduled-Contact Networks: VortexPCE against Contact Graph Routing," AIAA ASCEND 2027.

It contains:
- the test harness;
- every result file the paper reads;
- a script that recomputes each of the paper's numbers from those files;
- the build files for the three systems under test: VortexPCE, ION, and OpenDaylight.

The VortexPCE engine is this repository, at the tag that contains this folder.

## 1. Check the paper's numbers (2 minutes, no Docker)

```bash
cd reproduce/ascend2027
python3 harness/fetch_rucop_plans.py     # the two RUCoP contact plans, SHA-256 checked
python3 check/check_numbers.py           # 81 checks against the paper's tables and text
python3 check/make_figures.py            # redraws the paper's plots into figures/
```

**What `check_numbers.py` does:**
- It recomputes every number the paper reports from `results/`. That covers means, ranges, ratios, gains, and contact counts.
- It compares each value with the exact phrase the paper prints. These phrases are frozen in `check/paper_numbers.json`.
- It also runs consistency checks. For example, it confirms that each executed plan used exactly the bundles VortexPCE admitted, and that the paired R_STOCH rerun moved no result by more than 0.2 points.
- It exits non-zero on any mismatch.

**Requirements:** Python 3.9 or later, and `matplotlib` for the plots.

## 2. Re-run the experiments

Every result can be re-run with Docker. The ION and OpenDaylight images are `linux/amd64`; we ran them on an Apple-silicon Mac under Docker Desktop's emulation.

### Images

| Image | How to get it |
|---|---|
| `vortex-pce-controller:paper` | `docker build -t vortex-pce-controller:paper .` from the repository root at this tag. The link emulator also runs in this image, which has Python. |
| `ion-dtn:paper` | `docker build -t ion-dtn:paper -f reproduce/ascend2027/ion/Dockerfile reproduce/ascend2027/ion`. This builds ION 4.2.1-a.1 at commit `58eb09f7532a61986a4ad277d43a398cb3e6af94`. |
| `ion-dtn:paper-bpclmfix` | `docker build -t ion-dtn:paper-bpclmfix -f reproduce/ascend2027/ion/Dockerfile.bpclmfix reproduce/ascend2027/ion`. This is the same ION with our `bpclm` fix (`ion/ION_BUG.md`). |
| `odl-crossover-amd64:42ce950` | This is the exact OpenDaylight BGPCEP image the paper used, attached to this tag's GitHub release (see below). |

To load the OpenDaylight image:

```bash
curl -LO https://github.com/otahboub/vortex-pce/releases/download/ascend2027/odl-bgpcep-42ce950-amd64.tar.gz
echo "8a0de3354c2b4f56243ebdb15d488576c194e7281587b92c116dc42d5c3b5530  odl-bgpcep-42ce950-amd64.tar.gz" | shasum -a 256 -c
docker load -i odl-bgpcep-42ce950-amd64.tar.gz       # tags odl-crossover-amd64:42ce950
```

**About the OpenDaylight image:**
- It is built from `opendaylight/bgpcep` at commit `42ce9509a505a2b113c7a572e57a404d616dd45e`. Its Karaf archive SHA-256 is `3ea6f02b…838515`, and the image label records the full value.
- OpenDaylight is licensed under the Eclipse Public License 1.0. The image is unmodified apart from its PCEP topology configuration and entry point.

### Commands

All commands run from `reproduce/ascend2027/harness/`. Each run takes a `--work DIR` for scratch files.

**What one cell costs:**
- **ION runs:** 24 h of contact plan, time-compressed 60×, take about 30 minutes per cell.
- **VortexPCE solves:** up to about 5 minutes per cell. Set `VORTEX_SDP_HTTP_TIMEOUT=3600` for the full-load cells.

Most command lines below were not recorded when the runs were made. They are reconstructed from each script's defaults, the result file's columns and the commit history, and marked *(reconstructed)*.

| Paper | Result file | Command |
|---|---|---|
| Table 2, Fig. 7: the capability ladder (RRN-A) | `rrnA_rdet_final.csv` | Merged by `merge_a1_results.py results/rrnA_rdet_final.csv results/rrnA_rdet_flagship.csv results/rrnA_rdet_vortex_ratedefault.csv results/rrnA_rdet_ion_linkemu_pilot.csv results/rrnA_rdet_ion_linkemu.csv --keep-arms odl` |
| ↳ VortexPCE arms and Table 3 | `rrnA_rdet_vortex_ratedefault.csv` | `a1_flagship.py --loads 0.5,1 --seeds 0,1,2,3,4 --arms vortex:1.0,vortex:0.9,vortex-fcfs:1.0,vortex-isolated:1.0 --image vortex-pce-controller:paper` |
| ↳ ION through the link emulator | `rrnA_rdet_ion_linkemu*.csv` | `a1_flagship.py --loads 0.5,1 --seeds 0,1,2,3,4 --arms ion --ion-link-emu --ion-image ion-dtn:paper` *(reconstructed)* |
| ↳ OpenDaylight | `rrnA_rdet_flagship.csv` (ODL rows) | `a1_flagship.py --loads 0.5,1 --seeds 0,1,2,3,4 --arms odl` *(reconstructed)* |
| Table 4: rate policy × headroom | `rrnA_rate_policy_sweep.csv` | `rate_policy_sweep.py --image vortex-pce-controller:paper --work DIR` |
| Table 5: the plan executed in ION | `rrnA_rdet_g1_plan_following.csv` | `a1_flagship.py --loads 1 --seeds 0,1,2 --arms vortex-ionplan:1.0 --ion-link-emu --ion-attempts 2 --ion-image ion-dtn:paper-bpclmfix` |
| ↳ ION restricted to reserved contacts | `rrnA_rdet_g1_vortex_ion_patched.csv` | `a1_flagship.py --loads 1 --seeds 0 --arms vortex-ion:1.0 --ion-link-emu --ion-image ion-dtn:paper-bpclmfix` *(reconstructed)* |
| §4.2: patched ION on seed 2 | `rrnA_rdet_ion_linkemu_patched.csv` | `a1_flagship.py --loads 1 --seeds 2 --arms ion --ion-link-emu --ion-image ion-dtn:paper-bpclmfix` *(reconstructed)* |
| Table 6: the lunar relay plan | `lcrns_g3_pilot.csv`, `lcrns_g3_ion_patched.csv` | `a1_flagship.py --plan datasets/fixtures/LCRNS_lunar.contactplan.txt --srcs 3-18 --dst 24 --bundle-bytes 4000000 --loads 0.5,1 --seeds 0`, run with `--arms vortex:1.0,vortex-isolated:1.0`, with `--arms odl`, and with `--arms ion --ion-link-emu --ion-image ion-dtn:paper-bpclmfix` |
| Fig. 8: SDP on RRN-A and RRN-B | `rrnA_sdp_revised.csv`, `rrnA_sdp_cut_k8.csv`, `rrnB_*.csv` | `vortex_sdp.py --contact-plan datasets/fixtures/RRN-A_16sats_Walker.contactplan.txt --stochastic --route-policy BOUNDED_CGR --alpha 0.9 --runs 300`; for the cut-anchored curves, `--route-policy CUT_ANCHORED_CGR --cut-upstream 8 --shared-terminal --subgraph-delivery --alpha 0.999 --runs 300` *(reconstructed)* |
| Fig. 9: the cost of reaching the oracle | `rrnA_sdp_cut_k{2,4,8}.csv`, `rrnA_sdp_disjoint.csv`, `rrnA_mesh_L*.csv` | `vortex_sdp.py ... --route-policy MESH_CGR --mesh-level L --mc-survival 256 --subgraph-delivery --alpha 0.999 --runs 200 --pfails 0.3,0.5,0.7` *(reconstructed)* |
| Fig. 10: R_STOCH under load | `closed_loop_replan.csv`, `replan_sweep.csv`, `overbooking_grid.csv` | `closed_loop_replan.py --contact-plan datasets/fixtures/RRN-A_16sats_Walker.contactplan.txt --scn-root DIR --pfails 0.3,0.5 --loads 8 --runs 40`, with `--interval-h`, `--report-delay-h`, `--contention` and `--overbook-safety` as in each file's columns *(reconstructed)* |
| §4.4: paired rerun on this engine | `rstoch_paired_*.csv` | The same, with `--interval-h 1`; with `--interval-h 0.5`; and with `--interval-h 1 --report-delay-h 2 --contention --overbook-safety 1` |
| Fig. 11: critical traffic under failures | `flood_vs_crp_prio_strict_p03.csv` | `flood_vs_crp_load.py --contact-plan ... --scn-root DIR --pfails 0.3 --loads 4,8,16,32 --critical-every 10 --critical-deadline-h 12 --allocations GLOBAL_MARGINAL --runs 40` *(reconstructed)* |
| Fig. 12: continuous backbones | `rstatic_cutthrough_results.json`, `rstatic_multipath_results.json` | In `../rstatic/`: `docker build -t vortex-bench:node -f Dockerfile.bench .`, then `./topo.sh` and `VORTEX_BENCH_REGIMES=R_STATIC VORTEX_BENCH_OUT=rstatic_cutthrough_results.json python3 regime_dataplane_study.py`; then `./twopath_topo.sh` and `python3 rstatic_multipath_study.py` *(reconstructed)*. These use privileged containers and Linux network namespaces. |
| Fig. 3: the worked example | `results/diagrams/*.json` | `d2_worked_example.py vortex`; `d2_worked_example.py ion --link-emu`; `d2_worked_example.py ionplan` |
| §4.2: link emulator check | `ion_volume_check.csv` | `ion_volume_check.py --duration 217 --compress 60 --link-emu` (and 600 s, 10×) *(reconstructed)* |

## 3. Provenance notes

- **Engine versions.** The R_DET VortexPCE results (Tables 2–5) use the engine in this repository at this tag.
  - The R_STOCH results (Figs. 8–11) were produced on earlier builds of the same engine.
  - The six under-load cells the paper quotes were re-run on this engine. No result moved by more than 0.2 points (`rstoch_paired_*.csv`), and `check_numbers.py` checks this.
  - The 800 MB cells were not re-run.
- **ION.** It runs as released, except where `ion-dtn:paper-bpclmfix` is named. Section 4.2 of the paper and `ion/ION_BUG.md` describe the defect and the fix.
  - A run is scored only if every node started before the plan and no send or storage operation failed. Invalid runs are repeated.
  - `--ion-attempts` sets how many tries a cell gets.
- **Restored row.** The half-load row of `lcrns_g3_ion_patched.csv` was restored from that run's log, which prints the same row. A branch switch replaced the output file while the run was still writing to it.
- **The submitted extended abstract.** Its numbers come from `rrnA_rdet_flagship.csv`; the paper's VortexPCE rows come from `rrnA_rdet_vortex_ratedefault.csv`. Fifteen of the flagship file's VortexPCE rows have no surviving raw output, but the solves are deterministic.

## 4. Third-party data

- **RRN-A and RRN-B:** the RUCoP ring-road contact plans of Fraire et al. `harness/fetch_rucop_plans.py` downloads them from the DtnSim repository (`bitbucket.org/lcd-unc-ar/dtnsim`, commit `8598c5c`) and checks their SHA-256. They are not redistributed here.
- **The lunar plan:** `harness/datasets/fixtures/LCRNS_lunar.*` is our own contact plan. It is propagated from the published elements of NASA's LCRNS Reference Constellation 3.1 by `harness/lcrns_contact_plan.py` and `harness/datasets/lcrns_contactplan.py`.
