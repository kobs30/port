package ai.portscan;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;
import ai.portscan.core.ContainerAssembler;

/** Main-thread state; preferences.apply() schedules disk writes off the main thread. */
final class ScanHistory {
    static final class Entry {
        String number, source, sweep;
        boolean checksum, repeated, assembled;
        long firstSeen;
        Entry(ContainerAssembler.Candidate c, String source, String sweep) {
            number = c.number; checksum = c.checksumValid; repeated = c.repeated;
            assembled = c.assembled; this.source = source; this.sweep = sweep;
            firstSeen = System.currentTimeMillis();
        }
        Entry(JSONObject o) {
            number = o.optString("number"); source = o.optString("source"); sweep = o.optString("sweep");
            checksum = o.optBoolean("checksum"); repeated = o.optBoolean("repeated");
            assembled = o.optBoolean("assembled"); firstSeen = o.optLong("firstSeen");
        }
        JSONObject json() throws Exception {
            return new JSONObject().put("number", number).put("source", source).put("sweep", sweep)
                .put("checksum", checksum).put("repeated", repeated).put("assembled", assembled).put("firstSeen", firstSeen);
        }
    }
    final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private final Context context;
    ScanHistory(Context context) {
        this.context = context;
        try {
            JSONArray array = new JSONArray(context.getSharedPreferences("history", 0).getString("entries", "[]"));
            for (int i = 0; i < array.length(); i++) {
                Entry e = new Entry(array.getJSONObject(i));
                entries.put(e.sweep + ":" + e.number, e);
            }
        } catch (Exception ignored) { /* A corrupt preferences value must not prevent camera startup. */ }
    }
    boolean add(ContainerAssembler.Candidate c, String source, String sweep) {
        String key = sweep + ":" + c.number;
        Entry old = entries.get(key);
        if (old == null) {
            if (entries.size() >= 1000) return false;
            entries.put(key, new Entry(c, source, sweep));
            return true;
        }
        boolean changed = c.repeated && !old.repeated || old.assembled && !c.assembled;
        old.repeated |= c.repeated;
        old.assembled &= c.assembled;
        return changed;
    }
    void save() {
        JSONArray array = new JSONArray();
        try { for (Entry entry : entries.values()) array.put(entry.json()); }
        catch (Exception e) { throw new IllegalStateException(e); }
        context.getSharedPreferences("history", 0).edit().putString("entries", array.toString()).apply();
    }
    List<Entry> visibleEntries(boolean confirmedOnly) {
        List<Entry> visible = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (!confirmedOnly || entry.checksum) visible.add(entry);
        }
        return visible;
    }
    String csv(boolean confirmedOnly) {
        StringBuilder text = new StringBuilder("\uFEFFnumber,checksum_matches,repeated,assembled,sweep,source,first_seen_ms\r\n");
        for (Entry e : visibleEntries(confirmedOnly)) text.append(quote(e.number)).append(',').append(e.checksum).append(',')
            .append(e.repeated).append(',').append(e.assembled).append(',').append(quote(e.sweep)).append(',')
            .append(quote(e.source)).append(',').append(e.firstSeen).append("\r\n");
        return text.toString();
    }
    private static String quote(String s) { return "\"" + s.replace("\"", "\"\"") + "\""; }
}
