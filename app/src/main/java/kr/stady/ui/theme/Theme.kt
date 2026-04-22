package kr.stady.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun StadyAppTheme(
    darkTheme: Boolean = false, // 항상 라이트 모드 강제
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // 동적 색상 비활성화
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme // 항상 라이트 모드, 동적 색상 사용 안 함

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}