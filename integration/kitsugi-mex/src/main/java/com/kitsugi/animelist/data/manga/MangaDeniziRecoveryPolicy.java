package com.kitsugi.animelist.data.manga;

import java.util.ArrayList;
import java.util.List;

/** Temporary recovery selection. No files, source settings or reading data are deleted. */
public final class MangaDeniziRecoveryPolicy {
    private MangaDeniziRecoveryPolicy() { }
    public static MangaSource filterSource(MangaSource source) {
        if (source == null || !"tr".equalsIgnoreCase(source.getLang())) return source;
        return "com.kitsugi.extension.tr.mangadenizi".equals(source.getPkgName()) ? source : null;
    }
    public static List<MangaSource> filterSources(List<MangaSource> sources) {
        List<MangaSource> active = new ArrayList<>();
        boolean selected = false;
        for (MangaSource source : sources) {
            if (!"tr".equalsIgnoreCase(source.getLang())) {
                active.add(source); // Other languages are outside the requested TR recovery profile.
            } else if ("com.kitsugi.extension.tr.mangadenizi".equals(source.getPkgName()) && !selected) {
                active.add(source);
                selected = true; // Don't activate both a manually installed and repository-installed copy.
            }
        }
        return active;
    }
}
