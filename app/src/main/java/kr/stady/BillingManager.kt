package kr.stady

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONObject

/**
 * Google Play Billing 구독 결제 매니저.
 *
 * 웹(useIap)이 window.Android.iapPurchase({planId,productId}) 를 호출하면 여기서
 * 실제 결제 플로우를 띄우고, 결과(purchaseToken 등)를 [onResult] JSON 으로 웹에 돌려준다.
 * 웹은 그 purchaseToken 을 /api/iap/google/verify 로 보내고, **서버가 Google Play
 * Developer API 로 재검증**한 뒤에만 구독권을 지급한다(클라 영수증은 신뢰하지 않음).
 *
 * onResult JSON 계약: { ok, platform:"google", planId?, productId?, purchaseToken?, error? }
 *  - error == "cancelled" 는 사용자가 결제창을 닫은 경우(웹에서 조용히 무시).
 */
class BillingManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
) {
    private var pendingPlanId: String? = null

    private val purchasesListener = PurchasesUpdatedListener { billingResult, purchases ->
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) {
                    deliver(false, error = "결제 결과가 비어 있어요.")
                } else {
                    purchases.forEach { handlePurchase(it) }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED ->
                deliver(false, error = "cancelled")
            else ->
                deliver(false, error = billingResult.debugMessage.ifBlank {
                    "결제에 실패했어요. (code ${billingResult.responseCode})"
                })
        }
    }

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(purchasesListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    private fun ensureConnected(onReady: (Boolean) -> Unit) {
        if (billingClient.isReady) {
            onReady(true)
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                onReady(billingResult.responseCode == BillingClient.BillingResponseCode.OK)
            }
            override fun onBillingServiceDisconnected() { /* reconnect lazily on next use */ }
        })
    }

    /** planId/productId 로 구독 결제창을 띄운다. */
    fun purchase(activity: Activity, planId: String, productId: String) {
        pendingPlanId = planId
        ensureConnected { ok ->
            if (!ok) {
                deliver(false, productId = productId, error = "결제 서비스에 연결하지 못했어요.")
                return@ensureConnected
            }
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()
            billingClient.queryProductDetailsAsync(params) { result, productDetailsList ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK || productDetailsList.isEmpty()) {
                    deliver(false, productId = productId,
                        error = "상품 정보를 불러오지 못했어요. (Play Console 등록 확인)")
                    return@queryProductDetailsAsync
                }
                val details = productDetailsList.first()
                val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
                if (offerToken == null) {
                    deliver(false, productId = productId, error = "구독 상품 구성이 올바르지 않아요.")
                    return@queryProductDetailsAsync
                }
                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(details)
                                .setOfferToken(offerToken)
                                .build()
                        )
                    )
                    .build()
                activity.runOnUiThread {
                    billingClient.launchBillingFlow(activity, flowParams)
                }
            }
        }
    }

    /** 기존 구독 복원 — 현재 계정의 활성 구독을 서버로 재검증하도록 돌려준다. */
    fun restore() {
        ensureConnected { ok ->
            if (!ok) {
                deliver(false, error = "결제 서비스에 연결하지 못했어요.")
                return@ensureConnected
            }
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            billingClient.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases.isNotEmpty()) {
                    handlePurchase(purchases.first())
                } else {
                    deliver(false, error = "복원할 구독이 없어요.")
                }
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            deliver(false, error = "결제가 아직 완료되지 않았어요.")
            return
        }
        // 서버 검증이 최종 지급 기준이지만, 3일 내 acknowledge 하지 않으면 Google 이
        // 자동 환불하므로 여기서 acknowledge 한다.
        if (!purchase.isAcknowledged) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(ackParams) { /* 결과는 서버 검증이 소스오브트루스 */ }
        }
        deliver(
            true,
            productId = purchase.products.firstOrNull() ?: "",
            purchaseToken = purchase.purchaseToken,
        )
    }

    private fun deliver(
        ok: Boolean,
        productId: String? = null,
        purchaseToken: String? = null,
        error: String? = null,
    ) {
        val json = JSONObject().apply {
            put("ok", ok)
            put("platform", "google")
            pendingPlanId?.let { put("planId", it) }
            productId?.let { put("productId", it) }
            purchaseToken?.let { put("purchaseToken", it) }
            error?.let { put("error", it) }
        }
        onResult(json.toString())
    }

    fun endConnection() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}
