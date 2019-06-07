package org.wikipedia.feed.suggestededits

import android.content.Context
import android.util.Log
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import org.wikipedia.WikipediaApp
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.feed.FeedCoordinator
import org.wikipedia.feed.dataclient.FeedClient
import org.wikipedia.feed.model.Card
import org.wikipedia.feed.suggestededits.SuggestedEditsFeedClient.SuggestedEditsType.*
import org.wikipedia.page.Namespace
import org.wikipedia.page.PageTitle
import org.wikipedia.suggestededits.SuggestedEditsSummary
import org.wikipedia.suggestededits.provider.MissingDescriptionProvider
import org.wikipedia.util.StringUtil

class SuggestedEditsFeedClient(private var suggestedEditsType: SuggestedEditsType) : FeedClient {
    enum class SuggestedEditsType {
        ADD_DESCRIPTION,
        TRANSLATE_DESCRIPTION,
        ADD_IMAGE_CAPTION,
        TRANSLATE_IMAGE_CAPTION
    }

    interface Callback {
        fun updateCardContent(card: SuggestedEditsCard)
    }

    private val disposables = CompositeDisposable()
    private val app = WikipediaApp.getInstance()
    private var sourceSummary: SuggestedEditsSummary? = null
    private var targetSummary: SuggestedEditsSummary? = null
    var langFromCode: String = app.language().appLanguageCode
    var langToCode: String = if (app.language().appLanguageCodes.size == 1) "" else app.language().appLanguageCodes[1]

    override fun request(context: Context, wiki: WikiSite, age: Int, cb: FeedClient.Callback) {
        cancel()
        fetchSuggestedEditForType(cb, null)
    }

    override fun cancel() {
        disposables.clear()
    }

    private fun toSuggestedEditsCard(suggestedEditsType: SuggestedEditsType, wiki: WikiSite): SuggestedEditsCard {
        return SuggestedEditsCard(wiki, suggestedEditsType, sourceSummary, targetSummary)
    }

    fun fetchSuggestedEditForType(cb: FeedClient.Callback?, callback: Callback?) {
        when (suggestedEditsType) {
            ADD_DESCRIPTION -> getArticleToAddDescription(cb, callback)
            TRANSLATE_DESCRIPTION -> getArticleToTranslateDescription(cb, callback)
            ADD_IMAGE_CAPTION -> getImageToAddCaption(cb, callback)
            TRANSLATE_IMAGE_CAPTION -> getImageToTranslateCaption(cb, callback)

        }
    }

    private fun getArticleToAddDescription(cb: FeedClient.Callback?, callback: Callback?) {
        disposables.add(MissingDescriptionProvider
                .getNextArticleWithMissingDescription(WikiSite.forLanguageCode(app.language().appLanguageCode))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ pageSummary ->
                    sourceSummary = SuggestedEditsSummary(
                            pageSummary.title,
                            pageSummary.lang,
                            pageSummary.getPageTitle(WikiSite.forLanguageCode(pageSummary.lang)),
                            pageSummary.normalizedTitle,
                            pageSummary.displayTitle,
                            pageSummary.description,
                            pageSummary.thumbnailUrl,
                            pageSummary.originalImageUrl,
                            pageSummary.extractHtml,
                            null, null, null
                    )

                    val card: SuggestedEditsCard = toSuggestedEditsCard(ADD_DESCRIPTION, WikiSite.forLanguageCode(app.language().appLanguageCodes[0]))

                    if (callback == null) {
                        FeedCoordinator.postCardsToCallback(cb!!, if (sourceSummary == null) emptyList<Card>() else listOf(card))
                    } else {
                        callback.updateCardContent(card)
                    }

                }, { if (callback == null) cb!!.success(emptyList()) }))
    }

    private fun getArticleToTranslateDescription(cb: FeedClient.Callback?, callback: Callback?) {
        disposables.add(MissingDescriptionProvider
                .getNextArticleWithMissingDescription(WikiSite.forLanguageCode(app.language().appLanguageCodes[0]), app.language().appLanguageCodes[1], true)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ pair ->
                    val source = pair.second
                    val target = pair.first

                    sourceSummary = SuggestedEditsSummary(
                            source.title,
                            source.lang,
                            source.getPageTitle(WikiSite.forLanguageCode(source.lang)),
                            source.normalizedTitle,
                            source.displayTitle,
                            source.description,
                            source.thumbnailUrl,
                            source.originalImageUrl,
                            source.extractHtml,
                            null, null, null
                    )

                    targetSummary = SuggestedEditsSummary(
                            target.title,
                            target.lang,
                            target.getPageTitle(WikiSite.forLanguageCode(target.lang)),
                            target.normalizedTitle,
                            target.displayTitle,
                            target.description,
                            target.thumbnailUrl,
                            target.originalImageUrl,
                            target.extractHtml,
                            null, null, null
                    )

                    val card: SuggestedEditsCard = toSuggestedEditsCard(TRANSLATE_DESCRIPTION, WikiSite.forLanguageCode(app.language().appLanguageCodes[1]))

                    if (callback == null) {
                        FeedCoordinator.postCardsToCallback(cb!!, if (pair == null) emptyList<Card>() else listOf(card))
                    } else {
                        callback.updateCardContent(card)
                    }

                }, { if (callback != null) cb!!.success(emptyList()) }))
    }

    private fun getImageToAddCaption(cb: FeedClient.Callback?, callback: Callback?) {
        disposables.add(MissingDescriptionProvider.getNextImageWithMissingCaption(langFromCode)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap { mwQueryResponse ->
                    ServiceFactory.get(WikiSite.forLanguageCode(langFromCode)).getImageExtMetadata(mwQueryResponse.title())
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                }
                .subscribe({ response ->
                    val page = response.query()!!.pages()!![0]
                    if (page.imageInfo() != null) {
                        val title = page.title()
                        val imageInfo = page.imageInfo()!!

                        sourceSummary = SuggestedEditsSummary(
                                StringUtil.removeNamespace(title),
                                langFromCode,
                                PageTitle(
                                        Namespace.FILE.name,
                                        StringUtil.removeNamespace(title),
                                        null,
                                        imageInfo.thumbUrl,
                                        WikiSite.forLanguageCode(langFromCode)
                                ),
                                StringUtil.removeUnderscores(title),
                                StringUtil.removeHTMLTags(title),
                                imageInfo.metadata!!.imageDescription()!!.value(),
                                imageInfo.thumbUrl,
                                imageInfo.originalUrl,
                                null,
                                imageInfo.timestamp,
                                imageInfo.user,
                                imageInfo.metadata
                        )
                        val card: SuggestedEditsCard = toSuggestedEditsCard(TRANSLATE_IMAGE_CAPTION, WikiSite.forLanguageCode(langFromCode))
                        FeedCoordinator.postCardsToCallback(cb!!, if (sourceSummary == null) emptyList<Card>() else listOf(card))
                    }
                }, { if (callback != null) cb!!.success(emptyList()) }))
    }

    private fun getImageToTranslateCaption(cb: FeedClient.Callback?, callback: Callback?) {
        var fileCaption: String? = null

        disposables.add(MissingDescriptionProvider.getNextImageWithMissingCaption(langFromCode, langToCode)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .flatMap { pair ->
                    fileCaption = pair.first
                    ServiceFactory.get(WikiSite.forLanguageCode(langFromCode)).getImageExtMetadata(pair.second.title())
                            .subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                }
                .subscribe({ response ->
                    val page = response.query()!!.pages()!![0]
                    if (page.imageInfo() != null) {
                        val title = page.title()
                        val imageInfo = page.imageInfo()!!

                        sourceSummary = SuggestedEditsSummary(
                                StringUtil.removeNamespace(title),
                                langFromCode,
                                PageTitle(
                                        Namespace.FILE.name,
                                        StringUtil.removeNamespace(title),
                                        null,
                                        imageInfo.thumbUrl,
                                        WikiSite.forLanguageCode(langFromCode)
                                ),
                                StringUtil.removeUnderscores(title),
                                StringUtil.removeHTMLTags(title),
                                fileCaption,
                                imageInfo.thumbUrl,
                                imageInfo.originalUrl,
                                null,
                                imageInfo.timestamp,
                                imageInfo.user,
                                imageInfo.metadata
                        )

                        targetSummary = sourceSummary!!.copy(
                                description = null,
                                lang = langToCode,
                                pageTitle = PageTitle(
                                        Namespace.FILE.name,
                                        StringUtil.removeNamespace(title),
                                        null,
                                        imageInfo.thumbUrl,
                                        WikiSite.forLanguageCode(langToCode)
                                )
                        )
                        Log.e("####", "fileCap" + fileCaption)

                        val card: SuggestedEditsCard = toSuggestedEditsCard(TRANSLATE_IMAGE_CAPTION, WikiSite.forLanguageCode(app.language().appLanguageCodes[1]))
                        FeedCoordinator.postCardsToCallback(cb!!, if (targetSummary == null) emptyList<Card>() else listOf(card))

                    }
                }, { if (callback == null) cb!!.success(emptyList()) }))
    }

}
