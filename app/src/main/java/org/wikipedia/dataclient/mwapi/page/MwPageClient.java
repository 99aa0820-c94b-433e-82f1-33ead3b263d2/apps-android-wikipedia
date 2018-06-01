package org.wikipedia.dataclient.mwapi.page;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import org.wikipedia.dataclient.WikiSite;
import org.wikipedia.dataclient.page.PageClient;
import org.wikipedia.dataclient.page.PageLead;
import org.wikipedia.dataclient.page.PageRemaining;
import org.wikipedia.dataclient.page.PageSummary;

import okhttp3.CacheControl;
import retrofit2.Call;

/**
 * Retrofit web service client for MediaWiki PHP API.
 */
public class MwPageClient implements PageClient {
    @NonNull private final MwPageService service;
    @NonNull private final WikiSite wiki;

    public MwPageClient(@NonNull MwPageService service, @NonNull WikiSite wiki) {
        this.service = service;
        this.wiki = wiki;
    }

    @SuppressWarnings("unchecked")
    @NonNull @Override public Call<? extends PageSummary> summary(@NonNull String title) {
        return service.summary(title, wiki.languageCode());
    }

    @SuppressWarnings("unchecked")
    @NonNull @Override public Call<? extends PageLead> lead(@Nullable CacheControl cacheControl,
                                                            @Nullable String saveOfflineHeader,
                                                            @Nullable String previousPageUrl,
                                                            @NonNull String title,
                                                            int leadImageWidth) {
        return service.lead(cacheControl == null ? null : cacheControl.toString(),
                saveOfflineHeader, previousPageUrl, title, leadImageWidth, wiki.languageCode());
    }

    @SuppressWarnings("unchecked")
    @NonNull @Override public Call<? extends PageRemaining> sections(@Nullable CacheControl cacheControl,
                                                                     @Nullable String saveOfflineHeader,
                                                                     @NonNull String title) {
        return service.sections(cacheControl == null ? null : cacheControl.toString(),
                saveOfflineHeader, title, wiki.languageCode());
    }
}
