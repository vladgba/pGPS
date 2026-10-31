package x.vladgba.gps.gnss;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Per-constellation totals for one satellite status epoch. */
public final class ConstellationCount {

    public final int constellation;
    /** Distinct satellites in view. */
    public int inView;
    /** Distinct satellites used in the fix. */
    public int used;
    /** Signals tracked (one satellite may contribute several bands). */
    public int signals;

    private ConstellationCount(int constellation) {
        this.constellation = constellation;
    }

    /** Totals in {@link Constellations#ORDER}; constellations with no signal are omitted. */
    public static List<ConstellationCount> of(List<SatelliteInfo> satellites) {
        ConstellationCount[] byRank = new ConstellationCount[Constellations.ORDER.length + 1];
        Set<Long> seen = new HashSet<>();
        Set<Long> seenUsed = new HashSet<>();
        for (SatelliteInfo s : satellites) {
            int rank = Constellations.rank(s.constellation);
            ConstellationCount c = byRank[rank];
            if (c == null) {
                c = new ConstellationCount(s.constellation);
                byRank[rank] = c;
            }
            c.signals++;
            if (seen.add(s.key())) c.inView++;
            if (s.usedInFix && seenUsed.add(s.key())) c.used++;
        }
        List<ConstellationCount> result = new ArrayList<>();
        for (ConstellationCount c : byRank) {
            if (c != null) result.add(c);
        }
        return Collections.unmodifiableList(result);
    }
}
