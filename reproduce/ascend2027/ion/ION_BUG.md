# ION bug: fragmenting an urgent bundle leaves a dangling urgent-queue pointer

**Affects:** ION-OPEN-SOURCE-4.2.1-a.1 (commit 58eb09f), BPv7, `bpv7/daemon/bpclm.c`
**Symptom:** `ipnfw` aborts on an SDR assertion, after which the node stops forwarding.
**Fix:** [`ion-bpclm-fragment-urgent-ordinal.patch`](ion-bpclm-fragment-urgent-ordinal.patch). It changes `bpclm.c` only.

**Status:**
- **Found:** during the VortexPCE R_DET benchmark campaign, October 2026.
- **Fix verification:** the reproduction that crashed in 4 of 4 runs completes with the patch, which fired 8 times (details below).
- **Upstream report:** not yet filed.

## Symptom

```
at line 11900 of bpv7/library/libbpP.c, Assertion failed, SDR transaction canceled. (list)
  libici.so  printStackTrace
  libici.so  _xniEnd
  libici.so  Sdr_list_insert_after
  libbp.so   (insertBundleIntoQueue, static)
  libbp.so   bpEnqueue
  ipnfw      ...
```

Line 11900 is the final `sdr_list_insert_after(sdr, lastElt, bundleAddr)` in `insertBundleIntoQueue()`. It is reached from `enqueueUrgentBundle()`, so the bundle being queued has expedited priority (2).

**When it happens:**
- The crash is tied to the workload: repeated runs of the same contact plan and traffic crash at the same plan time, on the same nodes.
- There is no warning in the node's log beforehand.

## Root cause

**How the cached pointer works.** For expedited bundles, a plan keeps a cached pointer per ordinal to the last urgent bundle of that ordinal: `BpPlan.ordinals[ordinal].lastForOrdinal`. `enqueueUrgentBundle()` inserts each new urgent bundle relative to that pointer. Normal and bulk bundles do not use a cache; they read the queue's real last element with `sdr_list_last()`.

**Who keeps it current.** The cache is maintained by `removeBundleFromQueue()` (`libbpP.c`): when the element it removes is the cached one, it clears the pointer. Every path that takes a bundle off a plan queue goes through it, except one.

**The exception: fragmenting at a contact boundary.** In `bpclm.c`, when the bundle at the head of a plan's queue is larger than the remaining contact volume (`maxPayloadLength`), `bpclm` fragments it:

```c
queue = sdr_list_list(sdr, bundle.planXmitElt);
bpFragment(&bundle, bundleObj, &(bundle.planXmitElt), ...);   /* sdr_list_delete()s the original's element */
secondBundle.planXmitElt = sdr_list_insert_first(sdr, queue, secondBundleObj);
firstBundle.planXmitElt  = sdr_list_insert_first(sdr, queue, firstBundleObj);
```

`bpFragment()` deletes the original bundle's queue element directly. If the original was an expedited bundle and the cached pointer for its ordinal referred to it, the pointer now refers to a freed list element. The two fragments are re-queued at the front, but the cache is not updated. That is the normal case whenever the original is the only, or last, urgent bundle of its ordinal.

**What fails.** The next expedited bundle queued to that plan reaches `insertBundleIntoQueue(..., lastElt = <freed element>, ...)`. `sdr_list_insert_after()` then fails its assertion, the SDR transaction is cancelled, and `ipnfw` aborts.

**Why some runs hit it so often.** Two conditions are needed: expedited traffic, and contacts that end with an expedited bundle at the head of a queue that no longer fits. Plans that fill contacts close to their volume make proactive fragmentation at contact ends routine. In our runs that meant ION executing a contact plan restricted to the contacts a planner had reserved, with contact volume enforced.

## Fix

In `bpclm.c`, after the two fragments are re-queued, check whether the plan's cached pointer for the bundle's ordinal still refers to the original element. If it does, point it at the second fragment, which is now the last bundle of that ordinal.

```c
if (bundle.priority > 1)
{
	sdr_stage(sdr, (char *) &plan, planObj, sizeof(BpPlan));
	if (plan.ordinals[bundle.ordinal].lastForOrdinal == originalElt)
	{
		plan.ordinals[bundle.ordinal].lastForOrdinal = secondBundle.planXmitElt;
		sdr_write(sdr, planObj, (char *) &plan, sizeof(BpPlan));
		writeMemo("[i] bpclm: urgent ordinal pointer moved off a fragmented bundle.");
	}
}
```

**Why the second fragment is the right target:**
- Fragmentation only happens to the bundle at the head of the queue.
- If the cache pointed at that bundle, it was the last of its ordinal.
- After re-queueing, the fragments come first, in order, so the second fragment is the last of that ordinal.

The fix does not change ION's behaviour in any other case. It compiles cleanly under ION's `-Wall -Wextra -pedantic -Werror` flags.

A more general alternative would be for `bpFragment()` to take the plan and remove the original through `removeBundleFromQueue()`. That would also keep the plan's backlog accounting consistent, but it is a larger change.

## Reproduction

These are the conditions under which we hit it. Any setup with expedited bundles fragmented at contact boundaries should do.
- **Plan and nodes:** the RRN-A contact plan (48 nodes), all nodes live in one host, UDP convergence layer, time compressed 60×.
- **Traffic:** 1 MB bundles, one in ten sent at expedited priority (`bpsendfile ... 0.2`).
- **Contacts:** volume enforced by a link emulator. ION's contact plan was restricted to the contacts a planner had reserved.
- **Crash point:** four consecutive runs crashed at plan hours 11.8–12.2, on nodes 34 and 43.

The harness is `a1_flagship.py --arms vortex-ion:1.0 --ion-link-emu`. The full-contact-plan runs (`--arms ion`) hit the same assertion less often, in about 25% of runs at half load and 55% at full load.

The patched image is built by `Dockerfile.bpclmfix`: ION's own Makefile rebuilds only the `bpclm` daemon, with the original build's flags.

## Verification

**Unpatched:** the reproduction (RRN-A, full load, seed 0, restricted plan) crashed in 4 of 4 runs, at plan hours 11.8–12.2.

**Patched** (`ion-dtn:bpclmfix`, only `bpclm` rebuilt), the same run on 2026-10-09:
- It ran the full 24-hour plan with **no assertion failure**.
- The fix fired **8 times**, on eight nodes (ground terminals 9, 12, 16, 23 and 29; satellites 41, 43 and 45). Each firing is a case where the unpatched daemon would have left a dangling pointer.
- The harness health check passed: start slack 285 s, 0 send failures, 0 SDR failures.

**Full-contact-plan check** (`--arms ion`, RRN-A, full load, seed 2, link emulator):
- **Patched:** completed first time, 141 bundles delivered (35.4% of the ceiling), 77.5% of critical bundles on time. The fix fired **12 times**.
- **Unpatched:** completed once, overnight, with 125 bundles (31.4%) and 67.5% critical on time. The three later attempts on the same cell, on 2026-10-09, all aborted on this assertion.

**What this suggests:**
- The dangling pointer arises far more often than the crash. An unpatched run can survive it, presumably because the freed element is reused before the next urgent enqueue, and may then queue urgent bundles in the wrong place.
- That could explain why the patched run delivers more critical traffic on time.
- Run-to-run spread on this cell could not be measured: unpatched ION did not complete again in three tries.

## Notes for the upstream report

- Expedited-priority bundles combined with proactive fragmentation in `bpclm` produce a dangling `lastForOrdinal`, and the next urgent enqueue then fails `sdr_list_insert_after()` at `libbpP.c:11900`.
- Proposed fix: the patch file.

**Ruled out during investigation:**
- `bpBlockPlan()`, which also re-forwards from a staged plan copy. It is only reached from `bpadmin` and is never in the crash path.
- All other removals from plan queues (`purgePlanXmitElt`, `reverseEnqueue`, overbooking management in `ipnfw`, the normal pop in `bpclm`). Each goes through `removeBundleFromQueue()`.
