package org.wikipedia.notifications;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.wikipedia.R;
import org.wikipedia.WikipediaApp;
import org.wikipedia.auth.AccountUtil;
import org.wikipedia.csrf.CsrfTokenClient;
import org.wikipedia.dataclient.Service;
import org.wikipedia.dataclient.ServiceFactory;
import org.wikipedia.dataclient.WikiSite;
import org.wikipedia.dataclient.mwapi.MwException;
import org.wikipedia.settings.Prefs;
import org.wikipedia.util.log.L;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Completable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class NotificationPollBroadcastReceiver extends BroadcastReceiver {
    public static final String ACTION_POLL = "action_notification_poll";
    private static final int MAX_LOCALLY_KNOWN_NOTIFICATIONS = 32;

    private Map<String, WikiSite> dbNameWikiSiteMap = new HashMap<>();
    private Map<String, String> dbNameWikiNameMap = new HashMap<>();
    private List<Long> locallyKnownNotifications;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (TextUtils.equals(intent.getAction(), Intent.ACTION_BOOT_COMPLETED)) {
            if (Prefs.notificationPollEnabled()) {
                startPollTask(context);
            } else {
                stopPollTask(context);
            }
        } else if (TextUtils.equals(intent.getAction(), ACTION_POLL)) {
            if (!Prefs.notificationPollEnabled()) {
                stopPollTask(context);
                return;
            }
            if (!AccountUtil.isLoggedIn()) {
                return;
            }

            locallyKnownNotifications = Prefs.getLocallyKnownNotifications();
            pollNotifications(context);
        }
    }

    public static void startPollTask(@NonNull Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime(),
                TimeUnit.MINUTES.toMillis(context.getResources().getInteger(R.integer.notification_poll_interval_minutes)),
                getAlarmPendingIntent(context));
    }

    public static void stopPollTask(@NonNull Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        alarmManager.cancel(getAlarmPendingIntent(context));
    }

    @NonNull private static PendingIntent getAlarmPendingIntent(@NonNull Context context) {
        Intent intent = new Intent(context, NotificationPollBroadcastReceiver.class);
        intent.setAction(ACTION_POLL);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @SuppressLint("CheckResult")
    private void pollNotifications(@NonNull final Context context) {
        ServiceFactory.get(new WikiSite(Service.COMMONS_URL)).getLastUnreadNotification()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(response -> {
                    String lastNotificationTime = "";
                    if (response.query().notifications().list() != null
                            && response.query().notifications().list().size() > 0) {
                        for (Notification n : response.query().notifications().list()) {
                            if (n.getUtcIso8601().compareTo(lastNotificationTime) > 0) {
                                lastNotificationTime = n.getUtcIso8601();
                            }
                        }
                    }
                    if (lastNotificationTime.compareTo(Prefs.getRemoteNotificationsSeenTime()) <= 0) {
                        // we're in sync!
                        return;
                    }
                    Prefs.setRemoteNotificationsSeenTime(lastNotificationTime);
                    retrieveNotifications(context);
                }, t -> {
                    if (t instanceof MwException && ((MwException)t).getError().getTitle().equals("login-required")) {
                        assertLoggedIn();
                    }
                    L.e(t);
                });
    }

    public void assertLoggedIn() {
        // Attempt to get a dummy CSRF token, which should automatically re-log us in explicitly,
        // and should automatically log us out if the credentials are no longer valid.
        Completable.fromAction(() -> new CsrfTokenClient(WikipediaApp.getInstance().getWikiSite(), WikipediaApp.getInstance().getWikiSite())
                .getTokenBlocking()).subscribeOn(Schedulers.io())
                .subscribe();
    }

    @SuppressLint("CheckResult")
    private void retrieveNotifications(@NonNull final Context context) {
        dbNameWikiSiteMap.clear();
        dbNameWikiNameMap.clear();
        ServiceFactory.get(new WikiSite(Service.COMMONS_URL)).getUnreadNotificationWikis()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(response -> {
                    Map<String, Notification.UnreadNotificationWikiItem> wikiMap = response.query().unreadNotificationWikis();
                    List<String> wikis = new ArrayList<>();
                    wikis.addAll(wikiMap.keySet());
                    for (String dbName : wikiMap.keySet()) {
                        if (wikiMap.get(dbName).getSource() != null) {
                            dbNameWikiSiteMap.put(dbName, new WikiSite(wikiMap.get(dbName).getSource().getBase()));
                            dbNameWikiNameMap.put(dbName, wikiMap.get(dbName).getSource().getTitle());
                        }
                    }
                    getFullNotifications(context, wikis);
                }, L::e);
    }

    @SuppressLint("CheckResult")
    private void getFullNotifications(@NonNull final Context context, @NonNull List<String> foreignWikis) {
        ServiceFactory.get(new WikiSite(Service.COMMONS_URL)).getAllNotifications(foreignWikis.isEmpty() ? "*" : TextUtils.join("|", foreignWikis), "!read", null)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(response -> onNotificationsComplete(context, response.query().notifications().list()),
                        L::e);
    }

    private void onNotificationsComplete(@NonNull final Context context, @NonNull List<Notification> notifications) {
        if (notifications.isEmpty()) {
            return;
        }
        boolean locallyKnownModified = false;
        List<Notification> knownNotifications = new ArrayList<>();
        int unreadCount = 0;

        for (final Notification n : notifications) {
            knownNotifications.add(n);
            if (locallyKnownNotifications.contains(n.key())) {
                continue;
            }
            locallyKnownNotifications.add(n.key());
            if (locallyKnownNotifications.size() > MAX_LOCALLY_KNOWN_NOTIFICATIONS) {
                locallyKnownNotifications.remove(0);
            }
            unreadCount++;
            locallyKnownModified = true;
        }

        if (unreadCount > 2) {
            NotificationPresenter.showMultipleUnread(context, unreadCount);
        } else {
            for (final Notification n : notifications) {
                // TODO: remove these conditions when the time is right.
                if ((n.category().startsWith(Notification.CATEGORY_SYSTEM) && Prefs.notificationWelcomeEnabled())
                        || (n.category().equals(Notification.CATEGORY_EDIT_THANK) && Prefs.notificationThanksEnabled())
                        || (n.category().equals(Notification.CATEGORY_THANK_YOU_EDIT) && Prefs.notificationMilestoneEnabled())
                        || (n.category().equals(Notification.CATEGORY_REVERTED) && Prefs.notificationRevertEnabled())
                        || (n.category().equals(Notification.CATEGORY_EDIT_USER_TALK) && Prefs.notificationUserTalkEnabled())
                        || (n.category().equals(Notification.CATEGORY_LOGIN_FAIL) && Prefs.notificationLoginFailEnabled())
                        || (n.category().startsWith(Notification.CATEGORY_MENTION) && Prefs.notificationMentionEnabled())
                        || Prefs.showAllNotifications()) {

                    NotificationPresenter.showNotification(context, n, dbNameWikiNameMap.containsKey(n.wiki()) ? dbNameWikiNameMap.get(n.wiki()) : n.wiki());
                }
            }
        }

        if (locallyKnownModified) {
            Prefs.setLocallyKnownNotifications(locallyKnownNotifications);
        }

        if (knownNotifications.size() > MAX_LOCALLY_KNOWN_NOTIFICATIONS) {
            markItemsAsRead(knownNotifications.subList(0, knownNotifications.size() - MAX_LOCALLY_KNOWN_NOTIFICATIONS));
        }
    }

    private void markItemsAsRead(List<Notification> items) {
        Map<WikiSite, List<Notification>> notificationsPerWiki = new HashMap<>();

        for (Notification item : items) {
            WikiSite wiki = dbNameWikiSiteMap.containsKey(item.wiki())
                    ? dbNameWikiSiteMap.get(item.wiki()) : WikipediaApp.getInstance().getWikiSite();
            if (!notificationsPerWiki.containsKey(wiki)) {
                notificationsPerWiki.put(wiki, new ArrayList<>());
            }
            notificationsPerWiki.get(wiki).add(item);
        }

        for (WikiSite wiki : notificationsPerWiki.keySet()) {
            markRead(wiki, notificationsPerWiki.get(wiki), false);
        }
    }

    public static void markRead(@NonNull WikiSite wiki, @NonNull List<Notification> notifications, boolean unread) {
        final String idListStr = TextUtils.join("|", notifications);
        CsrfTokenClient editTokenClient = new CsrfTokenClient(wiki, WikipediaApp.getInstance().getWikiSite());
        editTokenClient.request(new CsrfTokenClient.DefaultCallback() {
            @SuppressLint("CheckResult")
            @Override
            public void success(@NonNull String token) {
                ServiceFactory.get(wiki).markRead(token, unread ? null : idListStr, unread ? idListStr : null)
                        .subscribeOn(Schedulers.io())
                        .subscribe(response -> { }, L::e);
            }
        });
    }
}
