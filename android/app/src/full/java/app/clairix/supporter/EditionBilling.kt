package app.clairix.supporter

import android.content.Context

/**
 * The full and foss editions (foss builds from src/full too) sell nothing:
 * no billing library is on their classpath, and CI checks that none reaches
 * the foss edition (tools/android/check-no-google-services.sh). Supporter
 * comes to them only through a code. The Play edition's is in src/play.
 */
object EditionBilling {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, exchange: suspend (productId: String, purchaseToken: String) -> Result<Int>): SupporterBilling = NoSupporterBilling
}
