package com.sharkking.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.sharkking.assistant.core.LocalHttpServer
import com.sharkking.assistant.core.RendererCache
import com.sharkking.assistant.data.AppStore
import com.sharkking.assistant.ui.RootScreen

class MainActivity : ComponentActivity() {

    private lateinit var store: AppStore
    private var server: LocalHttpServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不用 enableEdgeToEdge：内容延伸到系统栏后，底栏会被导航键遮住
        store = AppStore(applicationContext)

        val rendererDir = RendererCache.ensure(applicationContext)
        server = LocalHttpServer(rendererDir).also { it.start() }

        setContent {
            val dark = store.darkTheme.value
            // 浅色主题下状态栏底色变亮，图标必须转深色才看得见
            val view = LocalView.current
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, view)
                    .isAppearanceLightStatusBars = !dark
            }
            MaterialTheme(colorScheme = if (dark) XuebiDarkColors else XuebiLightColors) {
                RootScreen(
                    store = store,
                    baseUrl = server?.baseUrl,
                )
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // 交由各窗口自行降频，见 GameWebViewHolder.onLowMemory
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }
}

private val XuebiDarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00252F),
    secondary = Color(0xFF81C784),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2A2A2A),
    onBackground = Color(0xFFE6E6E6),
    onSurface = Color(0xFFE6E6E6),
    // 轻提示胶囊取的是 inverseSurface，深色主题下要给亮底配深字
    inverseSurface = Color(0xFFE6E6E6),
    inverseOnSurface = Color(0xFF1E1E1E),
)

private val XuebiLightColors = lightColorScheme(
    // primary 压深一档：浅底上 4FC3F7 太淡，文字和图标会发飘
    primary = Color(0xFF0277BD),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2E7D32),
    background = Color(0xFFF7F7F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8E8E8),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF5A5A5A),
    error = Color(0xFFC62828),
    inverseSurface = Color(0xFF2A2A2A),
    inverseOnSurface = Color(0xFFF0F0F0),
)
