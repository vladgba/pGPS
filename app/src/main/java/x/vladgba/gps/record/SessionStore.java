package x.vladgba.gps.record;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Recorded sessions on disk (app-specific external storage) and the chosen formats. */
public final class SessionStore {

    private static final String PREFS = "recorder";
    private static final String KEY_FORMATS = "formats";

    private SessionStore() {
    }

    /**
     * Android/data/&lt;package&gt;/files/sessions: no storage permission needed, reachable over
     * USB, removed with the app. Falls back to internal storage.
     */
    public static File sessionsRoot(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File root = new File(base, "sessions");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        return root;
    }

    /** Session directories, newest first. */
    public static List<File> list(Context context) {
        File[] dirs = sessionsRoot(context).listFiles(File::isDirectory);
        List<File> result = new ArrayList<>();
        if (dirs != null) result.addAll(Arrays.asList(dirs));
        result.sort((a, b) -> b.getName().compareTo(a.getName()));
        return result;
    }

    /** Visible files of a session (temporary files start with a dot). */
    public static List<File> files(File session) {
        File[] files = session.listFiles(f -> f.isFile() && !f.getName().startsWith("."));
        List<File> result = new ArrayList<>();
        if (files != null) result.addAll(Arrays.asList(files));
        result.sort((a, b) -> a.getName().compareTo(b.getName()));
        return result;
    }

    public static long size(File session) {
        long total = 0;
        File[] files = session.listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    public static boolean delete(File session) {
        File[] files = session.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        return session.delete();
    }

    public static Set<RecordingFormat> formats(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> names = p.getStringSet(KEY_FORMATS, null);
        if (names == null) return EnumSet.allOf(RecordingFormat.class);
        EnumSet<RecordingFormat> set = EnumSet.noneOf(RecordingFormat.class);
        for (String n : names) {
            try {
                set.add(RecordingFormat.valueOf(n));
            } catch (IllegalArgumentException ignored) {
                // format removed in a later version
            }
        }
        return set;
    }

    public static void setFormats(Context context, Set<RecordingFormat> formats) {
        Set<String> names = new java.util.HashSet<>();
        for (RecordingFormat f : formats) names.add(f.name());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY_FORMATS, names).apply();
    }
}
