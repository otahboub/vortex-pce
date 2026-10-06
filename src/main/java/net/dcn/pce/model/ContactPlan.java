package net.dcn.pce.model;

import java.util.List;
import java.util.Optional;

/** Validated finite, non-periodic deterministic contact plan for one link. */
public final class ContactPlan {

    public static final int MAX_WINDOWS = 100_000;
    private static final double EPSILON = 1e-9;

    private final List<ContactWindow> windows;

    public ContactPlan(List<ContactWindow> windows) {
        if (windows == null || windows.isEmpty() || windows.size() > MAX_WINDOWS) {
            throw new IllegalArgumentException("Contact plan must contain 1 to "
                    + MAX_WINDOWS + " windows");
        }
        ContactWindow previous = null;
        for (ContactWindow window : windows) {
            if (window == null || (previous != null
                    && window.startSec() < previous.endSec() - EPSILON)) {
                throw new IllegalArgumentException(
                        "Contact windows must be ordered and non-overlapping");
            }
            previous = window;
        }
        this.windows = List.copyOf(windows);
    }

    public List<ContactWindow> getWindows() {
        return windows;
    }

    public boolean containsInterval(double startSec, double endSec) {
        validateRange(startSec, endSec);
        int index = firstWindowEndingAfter(startSec);
        if (index >= windows.size()) {
            return false;
        }
        ContactWindow window = windows.get(index);
        return startSec + EPSILON >= window.startSec()
                && endSec <= window.endSec() + EPSILON;
    }

    public Optional<ContactWindow> findWindowAtOrAfter(double earliestSec, double latestEndSec) {
        if (!Double.isFinite(earliestSec) || earliestSec < 0
                || !Double.isFinite(latestEndSec) || latestEndSec <= earliestSec) {
            throw new IllegalArgumentException("Invalid contact-plan window query");
        }
        for (int index = firstWindowEndingAfter(earliestSec); index < windows.size(); index++) {
            ContactWindow window = windows.get(index);
            double startSec = Math.max(earliestSec, window.startSec());
            double endSec = Math.min(latestEndSec, window.endSec());
            if (endSec > startSec + EPSILON) {
                return Optional.of(new ContactWindow(startSec, endSec, window.successProb()));
            }
            if (window.startSec() >= latestEndSec) {
                break;
            }
        }
        return Optional.empty();
    }

    /**
     * Success probability of the contact window covering [{@code startSec}, {@code endSec}).
     *
     * <p>Used by R_STOCH admission to fold per-contact failure risk into a route's joint survival
     * probability. Returns {@code 1.0} when no single window covers the interval (deterministic /
     * not risk-annotated), so R_DET plans are unaffected.
     */
    public double successProbForInterval(double startSec, double endSec) {
        return windowForInterval(startSec, endSec).map(ContactWindow::successProb).orElse(1.0);
    }

    /** The single contact window covering [{@code startSec}, {@code endSec}), if one does. */
    public Optional<ContactWindow> windowForInterval(double startSec, double endSec) {
        validateRange(startSec, endSec);
        int index = firstWindowEndingAfter(startSec);
        if (index >= windows.size()) {
            return Optional.empty();
        }
        ContactWindow window = windows.get(index);
        boolean covered = startSec + EPSILON >= window.startSec() && endSec <= window.endSec() + EPSILON;
        return covered ? Optional.of(window) : Optional.empty();
    }

    public double activeDurationBetween(double startSec, double endSec) {
        validateRange(startSec, endSec);
        double activeSec = 0.0;
        for (int index = firstWindowEndingAfter(startSec); index < windows.size(); index++) {
            ContactWindow window = windows.get(index);
            if (window.startSec() >= endSec) {
                break;
            }
            activeSec += Math.max(0.0,
                    Math.min(endSec, window.endSec()) - Math.max(startSec, window.startSec()));
        }
        return activeSec;
    }

    private int firstWindowEndingAfter(double timeSec) {
        int low = 0;
        int high = windows.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (windows.get(middle).endSec() <= timeSec + EPSILON) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static void validateRange(double startSec, double endSec) {
        if (!Double.isFinite(startSec) || startSec < 0
                || !Double.isFinite(endSec) || endSec <= startSec) {
            throw new IllegalArgumentException("Invalid contact-plan interval");
        }
    }
}
