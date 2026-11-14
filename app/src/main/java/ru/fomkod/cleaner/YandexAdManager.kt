package ru.fomkod.cleaner

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequestConfiguration
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.MobileAds
import com.yandex.mobile.ads.rewarded.Reward
import com.yandex.mobile.ads.rewarded.RewardedAd
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoader
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object YandexAdManager {
    const val YANDEX_AD_UNIT_ID = "demo-rewarded-yandex"

    fun initialize(context: Context) {
        MobileAds.initialize(context) {}
    }

    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun loadAndShowRewarded(context: Context, onRewarded: () -> Unit): Boolean {
        if (!hasInternet(context)) return false
        val activity = context as? Activity ?: return false

        return suspendCancellableCoroutine { cont ->
            val loader = RewardedAdLoader(context)
            var ad: RewardedAd? = null

            loader.setAdLoadListener(object : RewardedAdLoadListener {
                override fun onAdLoaded(rewardedAd: RewardedAd) {
                    ad = rewardedAd
                    rewardedAd.setAdEventListener(object : RewardedAdEventListener {
                        override fun onAdShown() {}
                        override fun onAdFailedToShow(adError: AdError) {
                            if (cont.isActive) cont.resume(false)
                        }
                        override fun onAdDismissed() {
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onRewarded(reward: Reward) {
                            onRewarded()
                        }
                        override fun onAdClicked() {}
                        override fun onAdImpression(impressionData: ImpressionData?) {}
                    })
                    rewardedAd.show(activity)
                }

                override fun onAdFailedToLoad(error: AdRequestError) {
                    if (cont.isActive) cont.resume(false)
                }
            })

            val adRequestConfiguration = AdRequestConfiguration.Builder(YANDEX_AD_UNIT_ID).build()
            loader.loadAd(adRequestConfiguration)

            cont.invokeOnCancellation {
                ad?.setAdEventListener(null)
            }
        }
    }
}
