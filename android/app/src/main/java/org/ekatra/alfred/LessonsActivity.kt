package org.ekatra.alfred

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.ekatra.alfred.lessons.LessonsApp
import org.ekatra.alfred.ui.PersonaDirection
import org.ekatra.alfred.ui.chat.EkatraChatTheme

/** Micro lessons generated on demand, readable and quizzable offline. Opened from the chat top bar. */
@AndroidEntryPoint
class LessonsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PersonaDirection {
                EkatraChatTheme {
                    LessonsApp(onExit = { finish() })
                }
            }
        }
    }
}
