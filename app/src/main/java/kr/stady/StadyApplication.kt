package kr.stady

import android.app.Application
import com.kakao.sdk.common.KakaoSdk

class StadyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Kakao SDK 초기화
        KakaoSdk.init(this, "df2af4aa578aaca7fcd5afca3ac8861b")
    }
}
