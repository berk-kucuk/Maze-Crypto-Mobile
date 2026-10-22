package com.mazecrypto.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mazecrypto.app.data.Maze
import com.mazecrypto.app.ui.MazeApp
import com.mazecrypto.app.ui.Nav

class MainActivity : ComponentActivity() {
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Maze.init(this)
        // Tapjacking: ignore touches while another app draws over this window.
        window.decorView.filterTouchesWhenObscured = true
        if (Maze.settings.value.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState == null) nav.deepLink(intent?.data)
        setContent { MazeApp(nav) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        nav.deepLink(intent.data)
    }
}
