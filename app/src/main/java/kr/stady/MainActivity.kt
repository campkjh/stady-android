package kr.stady

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import com.airbnb.lottie.compose.*
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.common.model.ApiError
import com.kakao.sdk.common.model.AuthError
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import com.kakao.sdk.user.UserApiClient

class MainActivity : ComponentActivity() {

    // JavaScript Interface 클래스
    inner class WebAppInterface {

        @JavascriptInterface
        fun loginWithKakao() {
            runOnUiThread {
                this@MainActivity.loginWithKakao()
            }
        }

        @JavascriptInterface
        fun showNativeLogin() {
            runOnUiThread {
                showLoginDialog = true
            }
        }

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var webViewInstance: WebView? = null
    private var showLoginDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // 라이트 모드 강제 설정
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 런타임 진단 로그 (서명/버전/Kakao 앱키)
        printDiagnostics()

        // 시스템 바 흰색 설정
        window.statusBarColor = android.graphics.Color.WHITE
        window.navigationBarColor = android.graphics.Color.WHITE

        // 시스템 바 아이콘 어둡게 설정 (라이트 모드)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        setContent {
            WebViewWithLoading(
                activity = this,
                showLoginDialog = showLoginDialog,
                onDismissLogin = { showLoginDialog = false },
                onLoginSuccess = { email, kakaoId, nickname ->
                    handleKakaoLoginSuccess(email, kakaoId, nickname)
                }
            )
        }
    }

    fun loginWithKakao() {
        val context = this

        Log.d("KakaoLogin", "===== 카카오 로그인 시작 =====")
        Log.d("KakaoLogin", "카카오톡 설치 여부: ${UserApiClient.instance.isKakaoTalkLoginAvailable(context)}")

        // 카카오 로그인 콜백
        val callback: (OAuthToken?, Throwable?) -> Unit = { token, error ->
            if (error != null) {
                logKakaoError("account callback", error)
                Toast.makeText(context, "로그인 실패: ${error.message}", Toast.LENGTH_SHORT).show()
            } else if (token != null) {
                Log.i("KakaoLogin", "카카오 로그인 성공 ${token.accessToken}")

                // 사용자 정보 요청
                UserApiClient.instance.me { user, userError ->
                    if (userError != null) {
                        logKakaoError("UserApiClient.me", userError)
                        Toast.makeText(context, "사용자 정보 요청 실패", Toast.LENGTH_SHORT).show()
                    } else if (user != null) {
                        val kakaoId = user.id.toString()
                        val email = user.kakaoAccount?.email ?: "kakao_${kakaoId}@stady.app"
                        val nickname = user.kakaoAccount?.profile?.nickname ?: "사용자"

                        Log.i("KakaoLogin", "사용자 정보: email=$email, kakaoId=$kakaoId, nickname=$nickname")

                        // 웹뷰로 데이터 전달
                        handleKakaoLoginSuccess(email, kakaoId, nickname)

                        // 다이얼로그 닫기
                        showLoginDialog = false
                    }
                }
            }
        }

        // 카카오톡으로 로그인 시도
        if (UserApiClient.instance.isKakaoTalkLoginAvailable(context)) {
            Log.d("KakaoLogin", "카카오톡 앱으로 로그인 시도")
            UserApiClient.instance.loginWithKakaoTalk(context) { token, error ->
                if (error != null) {
                    logKakaoError("kakaotalk login", error)

                    // 사용자가 카카오톡 설치 후 디바이스 권한 요청 화면에서 로그인을 취소한 경우
                    if (error is ClientError && error.reason == ClientErrorCause.Cancelled) {
                        Log.d("KakaoLogin", "사용자가 로그인을 취소함")
                        return@loginWithKakaoTalk
                    }

                    // 카카오톡에 연결된 카카오계정이 없는 경우, 카카오계정으로 로그인 시도
                    Log.d("KakaoLogin", "카카오계정으로 전환")
                    UserApiClient.instance.loginWithKakaoAccount(context, callback = callback)
                } else if (token != null) {
                    Log.i("KakaoLogin", "카카오톡 로그인 성공 ${token.accessToken}")
                    callback(token, null)
                }
            }
        } else {
            // 카카오톡이 설치되어 있지 않은 경우, 카카오계정으로 로그인
            Log.d("KakaoLogin", "카카오톡이 없어서 카카오계정으로 로그인")
            UserApiClient.instance.loginWithKakaoAccount(context, callback = callback)
        }
    }

    private fun handleKakaoLoginSuccess(email: String, kakaoId: String, nickname: String) {
        val encodedEmail = Uri.encode(email)
        val encodedNickname = Uri.encode(nickname)
        val url = "https://stady.kr/api/auth/kakao/app?email=$encodedEmail&kakao_id=$kakaoId&nickname=$encodedNickname"

        Log.i("KakaoLogin", "이동할 URL: $url")

        runOnUiThread {
            webViewInstance?.loadUrl(url)
        }
    }

    fun setWebView(webView: WebView) {
        this.webViewInstance = webView
    }

    private fun printDiagnostics() {
        Log.e("StadyDiag", "================ STADY DIAGNOSTICS ================")
        try {
            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
            Log.e("StadyDiag", "packageName    = $packageName")
            Log.e("StadyDiag", "versionName    = ${pkgInfo.versionName}")
            @Suppress("DEPRECATION")
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pkgInfo.longVersionCode
            } else {
                pkgInfo.versionCode.toLong()
            }
            Log.e("StadyDiag", "versionCode    = $versionCode")
            Log.e("StadyDiag", "installerPkg   = ${packageManager.getInstallerPackageName(packageName)}")
        } catch (e: Exception) {
            Log.e("StadyDiag", "package info failed", e)
        }

        // Kakao App Key (하드코딩/Manifest 값 확인용)
        try {
            val ai = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val kakaoKey = ai.metaData?.getString("com.kakao.sdk.AppKey")
            Log.e("StadyDiag", "kakao AppKey   = $kakaoKey (manifest)")
        } catch (e: Exception) {
            Log.e("StadyDiag", "kakao AppKey lookup failed", e)
        }

        // 서명 인증서 (실행 중인 APK가 실제로 서명된 키)
        try {
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            }
            val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners ?: arrayOf()
            } else {
                @Suppress("DEPRECATION")
                info.signatures ?: arrayOf()
            }
            Log.e("StadyDiag", "signer count   = ${signatures.size}")
            for ((idx, signature) in signatures.withIndex()) {
                val sha1 = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
                val sha256 = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                val keyHashBase64 = Base64.encodeToString(sha1, Base64.NO_WRAP)
                Log.e("StadyDiag", "signer[$idx] SHA-1   (hex)    = ${sha1.toHexColon()}")
                Log.e("StadyDiag", "signer[$idx] SHA-256 (hex)    = ${sha256.toHexColon()}")
                Log.e("StadyDiag", "signer[$idx] Kakao keyHash(B64) = $keyHashBase64")
            }
        } catch (e: Exception) {
            Log.e("StadyDiag", "signature lookup failed", e)
        }
        Log.e("StadyDiag", "===================================================")
    }

    private fun ByteArray.toHexColon(): String =
        joinToString(":") { String.format("%02X", it) }

    private fun logKakaoError(where: String, error: Throwable) {
        Log.e("KakaoLogin", "────── KAKAO ERROR @ $where ──────")
        Log.e("KakaoLogin", "class   = ${error.javaClass.name}")
        Log.e("KakaoLogin", "message = ${error.message}")
        when (error) {
            is AuthError -> {
                Log.e("KakaoLogin", "AuthError.statusCode = ${error.statusCode}")
                Log.e("KakaoLogin", "AuthError.response   = ${error.response}")
                val desc = error.response.errorDescription ?: ""
                if (desc.contains("hash", ignoreCase = true) ||
                    desc.contains("KOE101", ignoreCase = true) ||
                    desc.contains("KOE006", ignoreCase = true)) {
                    Log.e("KakaoLogin", "⚠️ KEY HASH / APP 설정 문제 의심. errorDescription=\"$desc\"")
                }
            }
            is ApiError -> {
                Log.e("KakaoLogin", "ApiError.statusCode = ${error.statusCode}")
                Log.e("KakaoLogin", "ApiError.response   = ${error.response}")
            }
            is ClientError -> {
                Log.e("KakaoLogin", "ClientError.reason = ${error.reason}")
            }
            else -> {
                Log.e("KakaoLogin", "unknown error type", error)
            }
        }
        Log.e("KakaoLogin", "──────────────────────────────────")
    }
}

@Composable
fun WebViewWithLoading(
    activity: MainActivity,
    showLoginDialog: Boolean,
    onDismissLogin: () -> Unit,
    onLoginSuccess: (String, String, String) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current

    // WebView 인스턴스를 Activity에 전달
    LaunchedEffect(webView) {
        webView?.let { activity.setWebView(it) }
    }

    // 상태바 색상을 흰색으로 강제 설정
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        val window = (view.context as? ComponentActivity)?.window
        window?.let {
            it.statusBarColor = android.graphics.Color.WHITE
            it.navigationBarColor = android.graphics.Color.WHITE

            val insetsController = WindowCompat.getInsetsController(it, view)
            insetsController.isAppearanceLightStatusBars = true
            insetsController.isAppearanceLightNavigationBars = true
        }
    }

    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        AndroidView(
            factory = { context ->
                // 쿠키 설정 (WebView 생성 전에 설정)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(WebView(context), true)

                WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            isLoading = true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            isLoading = false
                            // 쿠키 즉시 저장
                            CookieManager.getInstance().flush()
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    // 써드파티 쿠키 허용 (이 WebView에 대해)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    // JavaScript Interface 등록
                    addJavascriptInterface(activity.WebAppInterface(), "Android")

                    loadUrl("https://stady.kr")
                }.also { webView = it }
            },
            update = { view ->
                // WebView가 이미 생성되어 있으면 재사용
                if (webView == null) {
                    webView = view
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            ) {
                val composition by rememberLottieComposition(
                    LottieCompositionSpec.RawRes(R.raw.loading)
                )
                LottieAnimation(
                    composition = composition,
                    iterations = LottieConstants.IterateForever,
                    modifier = Modifier
                        .size(120.dp)
                        .align(Alignment.Center)
                )
            }
        }

        // 네이티브 로그인 다이얼로그
        if (showLoginDialog) {
            NativeLoginDialog(
                onDismiss = onDismissLogin,
                onKakaoLogin = { activity.loginWithKakao() }
            )
        }
    }
}

@Composable
fun NativeLoginDialog(
    onDismiss: () -> Unit,
    onKakaoLogin: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 로고
                Text(
                    text = "🎓",
                    fontSize = 64.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "스타디",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "학습의 시작, 스타디와 함께!",
                    fontSize = 14.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(32.dp))

                // 카카오 로그인 버튼
                Button(
                    onClick = onKakaoLogin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFFC84D)
                    )
                ) {
                    Text(
                        text = "카카오로 시작하기",
                        color = Color(0xFF3E1918),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
