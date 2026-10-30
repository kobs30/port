package ai.portscan.core;

import java.util.*;
import java.util.regex.*;

/** Bounded overlap assembly for ONE container sweep. Never merge across sweep boundaries. */
public final class ContainerAssembler {
    private static final Pattern ANCHOR = Pattern.compile("(?<![A-Z0-9])[A-Z]{3}[UJZ][0-9]{0,7}(?![0-9])");
    private static final Pattern TAIL = Pattern.compile("(?<![A-Z0-9])[A-Z]{1,3}[0-9]{3,7}(?![0-9])");
    private static final Pattern DIGITS = Pattern.compile("(?<![0-9])[0-9]{3,7}(?![0-9])");
    private static final Pattern PREFIX = Pattern.compile("[A-Z]{3}[UJZ][0-9]{0,7}");
    private static final int MAX_FRAGMENTS = 96, MAX_STATES = 128;
    private final LinkedHashMap<String, Evidence> fragments = new LinkedHashMap<>();
    private long frame;
    private boolean limited;
    private Snapshot cached;

    private static final class Evidence {
        int count;
        long lastFrame;
        Evidence(long frame) { count = 1; lastFrame = frame; }
    }
    public static final class Candidate {
        public final String number;
        public final boolean checksumValid, repeated, assembled;
        Candidate(String number, boolean repeated, boolean assembled) {
            this.number = number; this.checksumValid = ContainerCode.valid(number);
            this.repeated = repeated; this.assembled = assembled;
        }
    }
    public static final class Snapshot {
        public final List<Candidate> candidates;
        public final String partial;
        public final List<String> fragments;
        public final boolean limited;
        Snapshot(List<Candidate> c, String p, List<String> f, boolean l) {
            candidates = c; partial = p; fragments = f; limited = l;
        }
    }

    public Snapshot accept(Collection<String> regions) {
        frame++;
        for (String region : regions) {
            String text = ContainerCode.normalize(region);
            for (Pattern pattern : List.of(ANCHOR, TAIL, DIGITS)) {
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) observe(matcher.group());
            }
        }
        return snapshot();
    }

    private void observe(String fragment) {
        Evidence evidence = fragments.get(fragment);
        if (evidence == null) {
            if (fragments.size() >= MAX_FRAGMENTS) {
                if (!limited) cached = null;
                limited = true; return;
            }
            fragments.put(fragment, new Evidence(frame));
            cached = null;
        } else if (evidence.lastFrame != frame) {
            if (evidence.count < 2) { evidence.count++; cached = null; }
            evidence.lastFrame = frame;
        }
    }

    public Snapshot snapshot() {
        if (cached != null) return cached;
        // A state is a prefix anchored by the four letters. Every extension must
        // overlap >= 3 actually observed characters; a checksum is never used to invent text.
        LinkedHashSet<String> states = new LinkedHashSet<>();
        for (String fragment : fragments.keySet())
            if (PREFIX.matcher(fragment).matches()) states.add(fragment);
        for (int pass = 0; pass < 7; pass++) {
            boolean changed = false;
            for (String state : new ArrayList<>(states)) {
                for (String fragment : fragments.keySet()) {
                    for (int overlap = 3; overlap <= Math.min(state.length(), fragment.length()); overlap++) {
                        if (!state.endsWith(fragment.substring(0, overlap))) continue;
                        String joined = state + fragment.substring(overlap);
                        if (joined.length() <= state.length() || !PREFIX.matcher(joined).matches()) continue;
                        if (states.size() >= MAX_STATES) { limited = true; continue; }
                        changed |= states.add(joined);
                    }
                }
            }
            if (!changed) break;
        }
        List<Candidate> candidates = new ArrayList<>();
        String best = "";
        for (String state : states) {
            if (state.length() > best.length()) best = state;
            if (state.length() != 11) continue;
            int[] support = new int[11];
            for (Map.Entry<String, Evidence> entry : fragments.entrySet()) {
                String fragment = entry.getKey();
                for (int start = 0; start + fragment.length() <= 11; start++) {
                    if (state.startsWith(fragment, start)) {
                        for (int j = start; j < start + fragment.length(); j++)
                            support[j] = Math.max(support[j], entry.getValue().count);
                    }
                }
            }
            boolean repeated = Arrays.stream(support).allMatch(count -> count >= 2);
            candidates.add(new Candidate(state, repeated, !fragments.containsKey(state)));
        }
        candidates.sort(Comparator.comparing((Candidate c) -> !c.checksumValid).thenComparing(c -> !c.repeated).thenComparing(c -> c.number));
        if (!candidates.isEmpty()) best = candidates.get(0).number;
        List<String> recent = new ArrayList<>(fragments.keySet());
        Collections.reverse(recent);
        cached = new Snapshot(candidates, best, recent.subList(0, Math.min(10, recent.size())), limited);
        return cached;
    }

    public void clear() { fragments.clear(); frame = 0; limited = false; cached = null; }
}
