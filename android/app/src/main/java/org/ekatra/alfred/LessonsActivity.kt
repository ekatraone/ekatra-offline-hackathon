package org.ekatra.alfred

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.ekatra.alfred.lessons.LessonsApp
import org.ekatra.alfred.lessons.LessonsViewModel
import org.ekatra.alfred.ui.PersonaDirection
import org.ekatra.alfred.ui.chat.EkatraChatTheme

/** Micro lessons generated on demand, readable and quizzable offline. Opened from the chat top bar. */
@AndroidEntryPoint
class LessonsActivity : ComponentActivity() {

    private val viewModel: LessonsViewModel by viewModels()

    // "Sign in to get lessons" opens the normal sign-in screen and comes back here when it succeeds.
    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) viewModel.onSignedIn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PersonaDirection {
                EkatraChatTheme {
                    LessonsApp(
                        onExit = { finish() },
                        onSignIn = {
                            signIn.launch(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_FOR_LESSONS, true))
                        },
                        vm = viewModel
                    )
                }
            }
        }
    }
}
